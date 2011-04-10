package com.bel.android.dspmanager;

import java.lang.reflect.Method;
import java.util.UUID;

import android.app.Notification;
import android.app.Service;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.Virtualizer;
import android.os.IBinder;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.bel.android.dspmanager.activity.DSPManager;

/**
 * <p>
 * This calls listen to two kinds of events:
 * <ol>
 * <li>headset plug / unplug events. The android framework only delivers
 *     these events to a running process, so there must be a service listening
 *     to them.
 * <li>preference update events.
 * </ol>
 * <p>
 * When new events arrive, they are pushed into the audio stack
 * using AudioManager.setParameters().
 * 
 * @author alankila
 */
public class HeadsetService extends Service {
	protected static final String TAG = HeadsetService.class.getSimpleName();

	public static final UUID EFFECT_TYPE_VOLUME = UUID.fromString("09e8ede0-ddde-11db-b4f6-0002a5d5c51b");
	
    public static final UUID EFFECT_TYPE_NULL = UUID.fromString("ec7178ec-e5e1-4432-a3f4-4657e6795210");

    private AudioEffect compression;
	private Equalizer equalizer;
	private Virtualizer virtualizer;
	private BassBoost bassBoost;
	
	protected boolean useHeadphone;

	protected boolean inCall;

	protected boolean bluetoothAudio;

	/**
	 * Update audio parameters when preferences have been updated.
	 */
    private final BroadcastReceiver preferenceUpdateReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			Log.i(TAG, "Preferences updated.");
			updateDsp();
		}
	};

	/**
	 * Update audio parameters when headset is plugged/unplugged.
	 */
    private final BroadcastReceiver headsetReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			useHeadphone = intent.getIntExtra("state", 0) != 0;
			Log.i(TAG, "Headset plugged: " + useHeadphone);
			updateDsp();
		}
	};

	private final BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			/* Work out what device was connected. Must be something that handles audio. */
			BluetoothDevice bd = (BluetoothDevice) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
			BluetoothClass bdc = bd.getBluetoothClass();
			if (bdc == null) {
				Log.i(TAG, intent.getAction() + ": missing class information on device: " + bd);
				return;
			}
			
			Log.i(TAG, intent.getAction() + ": bluetooth class is " + bdc);
			if (bdc.getMajorDeviceClass() != BluetoothClass.Device.Major.AUDIO_VIDEO) {
				return;
			}
			
			/* I don't actually know at this point if user will actually enable this device,
			 * or whether it just remains "connected". Knowing this would be useful.
			 */
			bluetoothAudio = intent.getAction().equals(BluetoothDevice.ACTION_ACL_CONNECTED);
			Log.i(TAG, "Bluetooth plugged: " + bluetoothAudio);
		}
	};

	private final PhoneStateListener mPhoneListener = new PhoneStateListener() {
		@Override
		public void onCallStateChanged(int state, String incomingNumber) {
			switch (state) {
			default:
				Log.i(TAG, "Disabling DSP during call.");
				inCall = true;
				break;
			case TelephonyManager.CALL_STATE_IDLE:
				Log.i(TAG, "Enabling DSP after call has ended.");
				inCall = false;
				break;
			}

			updateDsp();
		}
	};

	@Override
	public void onCreate() {
		super.onCreate();
		Log.i(TAG, "Starting service.");

		try {
			/* The AudioEffect with UUID constructor is not visible in SDK, but
			 * it is available via reflection. Here's a kind request to Google:
			 * please expose this method, and the parameter APIs, to make it
			 * possible to create audio effects that aren't part of the official
			 * platform without having to patch the platform itself. */
			compression = AudioEffect.class
			.getConstructor(UUID.class, UUID.class, Integer.TYPE, Integer.TYPE)
			.newInstance(EFFECT_TYPE_VOLUME, EFFECT_TYPE_NULL, 0, 0);
			/* Also, setParameter and getParameter() are hidden,
			 * but both their signatures are byte[], byte[]. */
		} catch (Exception e) {
			throw new RuntimeException("Failed to construct EFFECT_TYPE_VOLUME", e);
		}

		equalizer = new Equalizer(0, 0);
		virtualizer = new Virtualizer(0, 0);
		bassBoost = new BassBoost(0, 0);
		
		startForeground(DSPManager.NOTIFY_FOREGROUND_ID, new Notification());
		
		TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
		tm.listen(mPhoneListener, PhoneStateListener.LISTEN_CALL_STATE);
		
        registerReceiver(headsetReceiver, new IntentFilter(Intent.ACTION_HEADSET_PLUG));
        registerReceiver(preferenceUpdateReceiver, new IntentFilter("com.bel.android.dspmanager.UPDATE"));

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        registerReceiver(bluetoothReceiver, filter);
	}
	
	@Override
	public void onDestroy() {
		super.onDestroy();
		Log.i(TAG, "Stopping service.");

		stopForeground(true);
		
		unregisterReceiver(headsetReceiver);
		unregisterReceiver(preferenceUpdateReceiver);
		unregisterReceiver(bluetoothReceiver);
		TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
		tm.listen(mPhoneListener, 0);
	}
	
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	/**
	 * Push new configuration to audio stack.
	 */
	protected void updateDsp() {
		final String mode;
		
		if (inCall) {
			/* During calls, everything gets disabled; there is no configuration called 'disable' */
			mode = "disable";
		} else if (bluetoothAudio) {
			/* Bluetooth takes precedence over everything else */
			mode = "bluetooth";
		} else {
			/* Wired headset or internal speaker */
			mode = useHeadphone ? "headset" : "speaker";
		}
		SharedPreferences preferences = getSharedPreferences(DSPManager.SHARED_PREFERENCES_BASENAME + "." + mode, 0);

		{
			compression.setEnabled(preferences.getBoolean("dsp.compression.enable", false));
			int strength = preferences.getInt("dsp.compression.strength", 0);
			try {
				Method setParameter = AudioEffect.class.getMethod("setParameter", byte[].class, byte[].class);
				setParameter.invoke(compression, new byte[] { 0, 0, 0, 0, (byte) (strength >> 8), (byte) (strength & 0xff) }, new byte[4]);
				/* Return array ignored, anyway... */
			}
			catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
		
		{
			bassBoost.setEnabled(preferences.getBoolean("dsp.bass.enable", false));
			if (bassBoost.getStrengthSupported()) {
				String strength = preferences.getString("dsp.bass.mode", "0");
				bassBoost.setStrength(Short.valueOf(strength));
			}
		}

		{
			/* Equalizer state is in a single string preference with all values separated by ; */
			equalizer.setEnabled(preferences.getBoolean("dsp.tone.enable", false));
			String levels[] = preferences.getString("dsp.tone.eq", "0;0;0;0;0").split(";");

			if (levels[0].equals("-1337")) {
				levels = preferences.getString("dsp.tone.eq.custom", "0;0;0;0;0").split(";");
			}

			for (short i = 0; i < levels.length; i ++) {
				equalizer.setBandLevel(i, (short) (Float.valueOf(levels[i]) * 100));
			}
		}

		{
			virtualizer.setEnabled(preferences.getBoolean("dsp.headphone.enable", false));
			if (virtualizer.getStrengthSupported()) {
				String strength = preferences.getString("dsp.headphone.mode", "0");
				virtualizer.setStrength(Short.valueOf(strength));
			}
		}
	}	
}
