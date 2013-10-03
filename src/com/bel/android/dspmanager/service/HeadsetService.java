package com.bel.android.dspmanager.service;

import android.app.Notification;
import android.app.Service;
import android.bluetooth.BluetoothA2dp;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.Virtualizer;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import com.bel.android.dspmanager.activity.DSPManager;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * <p>This calls listen to events that affect DSP function and responds to them.</p>
 * <ol>
 * <li>new audio session declarations</li>
 * <li>headset plug / unplug events</li>
 * <li>preference update events.</li>
 * </ol>
 *
 * @author alankila
 */
public class HeadsetService extends Service {
    /**
     * Helper class representing the full complement of effects attached to one
     * audio session.
     *
     * @author alankila
     */
    protected static class EffectSet {
        private static final UUID EFFECT_TYPE_VOLUME =
                UUID.fromString("09e8ede0-ddde-11db-b4f6-0002a5d5c51b");

        /** Session-specific dynamic range compressor */
        public final AudioEffect mCompression;
        /** Session-specific equalizer */
        private final Equalizer mEqualizer;
        /** Session-specific bassboost */
        private final BassBoost mBassBoost;
        /** Session-specific virtualizer */
        private final Virtualizer mVirtualizer;

        protected EffectSet(int sessionId) {
            try {
                mCompression = new AudioEffect(EFFECT_TYPE_VOLUME,
                        AudioEffect.EFFECT_TYPE_NULL, 0, sessionId);
            } catch (IllegalArgumentException e) {
                throw new RuntimeException(e);
            } catch (UnsupportedOperationException e) {
                throw new RuntimeException(e);
            }

            mEqualizer = new Equalizer(0, sessionId);
            mBassBoost = new BassBoost(0, sessionId);
            mVirtualizer = new Virtualizer(0, sessionId);
        }

        protected void release() {
            mCompression.release();
            mEqualizer.release();
            mBassBoost.release();
            mVirtualizer.release();
        }
    }

    protected static final String TAG = HeadsetService.class.getSimpleName();

    public class LocalBinder extends Binder {
        public HeadsetService getService() {
            return HeadsetService.this;
        }
    }

    private final LocalBinder mBinder = new LocalBinder();

    /** Known audio sessions and their associated audioeffect suites. */
    protected final Map<Integer, EffectSet> mAudioSessions = new HashMap<Integer, EffectSet>();

    /** Is a wired headset plugged in? */
    protected boolean mUseHeadset;

    /** Is bluetooth headset plugged in? */
    protected boolean mUseBluetooth;

    /** Is a dock or USB audio device plugged in? */
    protected boolean mUseUSB;

    /** Has DSPManager assumed control of equalizer levels? */
    private float[] mOverriddenEqualizerLevels;

    /**
     * Receive new broadcast intents for adding DSP to session
     */
    private final BroadcastReceiver mAudioSessionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            int sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0);
            if (action.equals(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)) {
                Log.i(TAG, String.format("New audio session: %d", sessionId));
                if (!mAudioSessions.containsKey(sessionId)) {
                    mAudioSessions.put(sessionId, new EffectSet(sessionId));
                }
            }
            if (action.equals(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)) {
                Log.i(TAG, String.format("Audio session removed: %d", sessionId));
                EffectSet gone = mAudioSessions.remove(sessionId);
                if (gone != null) {
                    gone.release();
                }
            }
            updateDsp();
        }
    };

    /**
     * Update audio parameters when preferences have been updated.
     */
    private final BroadcastReceiver mPreferenceUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "Preferences updated.");
            updateDsp();
        }
    };

    /**
     * This code listens for changes in bluetooth and headset events. It is
     * adapted from google's own MusicFX application, so it's presumably the
     * most correct design there is for this problem.
     */
    private final BroadcastReceiver mRoutingReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            final String action = intent.getAction();
            final boolean prevUseHeadset = mUseHeadset;
            final boolean prevUseBluetooth = mUseBluetooth;
            final boolean prevUseUSB = mUseUSB;

            if (action.equals(Intent.ACTION_HEADSET_PLUG)) {
                mUseHeadset = intent.getIntExtra("state", 0) == 1;
            } else if (action.equals(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)) {
                int state = intent.getIntExtra(BluetoothA2dp.EXTRA_STATE,
                        BluetoothA2dp.STATE_DISCONNECTED);
                mUseBluetooth = state == BluetoothA2dp.STATE_CONNECTED;
            } else if (action.equals(Intent.ACTION_ANALOG_AUDIO_DOCK_PLUG)) {
                mUseUSB = intent.getIntExtra("state", 0) == 1;
            }

            Log.i(TAG, "Headset=" + mUseHeadset + "; Bluetooth="
                    + mUseBluetooth + " ; USB=" + mUseUSB);
            if (prevUseHeadset != mUseHeadset
                    || prevUseBluetooth != mUseBluetooth
                    || prevUseUSB != mUseUSB) {
                updateDsp();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Starting service.");

        IntentFilter audioFilter = new IntentFilter();
        audioFilter.addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
        audioFilter.addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
        registerReceiver(mAudioSessionReceiver, audioFilter);

        final IntentFilter intentFilter = new IntentFilter(Intent.ACTION_HEADSET_PLUG);
        intentFilter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        intentFilter.addAction(Intent.ACTION_ANALOG_AUDIO_DOCK_PLUG);
        intentFilter.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        registerReceiver(mRoutingReceiver, intentFilter);

        registerReceiver(mPreferenceUpdateReceiver,
                new IntentFilter(DSPManager.ACTION_UPDATE_PREFERENCES));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "Stopping service.");

        unregisterReceiver(mAudioSessionReceiver);
        unregisterReceiver(mRoutingReceiver);
        unregisterReceiver(mPreferenceUpdateReceiver);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    /**
     * Gain temporary control over the global equalizer.
     * Used by DSPManager when testing a new equalizer setting.
     *
     * @param levels
     */
    public void setEqualizerLevels(float[] levels) {
        mOverriddenEqualizerLevels = levels;
        updateDsp();
    }

    /**
     * There appears to be no way to find out what the current actual audio routing is.
     * For instance, if a wired headset is plugged in, the following objects/classes are involved:
     * </p>
     * <ol>
     * <li>wiredaccessoryobserver</li>
     * <li>audioservice</li>
     * <li>audiosystem</li>
     * <li>audiopolicyservice</li>
     * <li>audiopolicymanager</li>
     * </ol>
     * <p>Once the decision of new routing has been made by the policy manager, it is relayed to
     * audiopolicyservice, which waits for some time to let application buffers drain, and then
     * informs it to hardware. The full chain is:</p>
     * <ol>
     * <li>audiopolicymanager</li>
     * <li>audiopolicyservice</li>
     * <li>audiosystem</li>
     * <li>audioflinger</li>
     * <li>audioeffect (if any)</li>
     * </ol>
     * <p>However, the decision does not appear to be relayed to java layer, so we must
     * make a guess about what the audio output routing is.</p>
     *
     * @return string token that identifies configuration to use
     */
    public String getAudioOutputRouting() {
        if (mUseHeadset) {
            return "headset";
        }
        if (mUseBluetooth) {
            return "bluetooth";
        }
        if (mUseUSB) {
            return "usb";
        }
        return "speaker";
    }

    /**
     * Push new configuration to audio stack.
     */
    protected void updateDsp() {
        final String mode = getAudioOutputRouting();
        SharedPreferences preferences = getSharedPreferences(
                DSPManager.SHARED_PREFERENCES_BASENAME + "." + mode, 0);

        Log.i(TAG, "Selected configuration: " + mode);

        for (Integer sessionId : mAudioSessions.keySet()) {
            updateDsp(preferences, mAudioSessions.get(sessionId));
        }
    }

    private void updateDsp(SharedPreferences prefs, EffectSet session) {
        session.mCompression.setEnabled(prefs.getBoolean("dsp.compression.enable", false));
        session.mCompression.setParameter(intToByteArray(0),
                shortToByteArray(Short.valueOf(prefs.getString("dsp.compression.mode", "0"))));

        session.mBassBoost.setEnabled(prefs.getBoolean("dsp.bass.enable", false));
        session.mBassBoost.setStrength(Short.valueOf(prefs.getString("dsp.bass.mode", "0")));

        session.mEqualizer.setEnabled(prefs.getBoolean("dsp.tone.enable", false));
        float[] equalizerLevels;
        if (mOverriddenEqualizerLevels != null) {
            equalizerLevels = mOverriddenEqualizerLevels;
        } else {
            /* Equalizer state is in a single string preference with all values separated by ; */
            String[] levels = prefs.getString("dsp.tone.eq.custom", "0;0;0;0;0").split(";");
            equalizerLevels = new float[levels.length];
            for (int i = 0; i < levels.length; i++) {
                equalizerLevels[i] = Float.valueOf(levels[i]);
            }
        }

        for (short i = 0; i < equalizerLevels.length; i ++) {
            session.mEqualizer.setBandLevel(i, (short) Math.round(equalizerLevels[i] * 100));
        }
        session.mEqualizer.setParameter(intToByteArray(1000),
                shortToByteArray(Short.valueOf(prefs.getString("dsp.tone.loudness", "10000"))));

        session.mVirtualizer.setEnabled(prefs.getBoolean("dsp.headphone.enable", false));
        session.mVirtualizer.setStrength(
                Short.valueOf(prefs.getString("dsp.headphone.mode", "0")));
    }

    private static byte[] intToByteArray(int value) {
        return new byte[] {
            (byte) (value),
            (byte) (value >> 8),
            (byte) (value >> 16),
            (byte) (value >> 24)
        };
    }

    private static byte[] shortToByteArray(short value) {
        return new byte[] {
            (byte) (value),
            (byte) (value >> 8)
        };
    }
}
