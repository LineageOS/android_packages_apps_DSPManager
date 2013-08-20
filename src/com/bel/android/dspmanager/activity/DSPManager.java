
package com.bel.android.dspmanager.activity;

import android.app.ActionBar;
import android.app.DialogFragment;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v13.app.FragmentPagerAdapter;
import android.support.v4.app.FragmentActivity;
import android.support.v4.view.PagerTabStrip;
import android.support.v4.view.ViewPager;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.bel.android.dspmanager.R;
import com.bel.android.dspmanager.service.HeadsetService;

import java.util.ArrayList;

/**
 * Setting utility for CyanogenMod's DSP capabilities. This page is displays the
 * top-level configurations menu.
 *
 * @author alankila@gmail.com
 */
public final class DSPManager extends FragmentActivity {
    public static final String SHARED_PREFERENCES_BASENAME = "com.bel.android.dspmanager";
    public static final String ACTION_UPDATE_PREFERENCES = "com.bel.android.dspmanager.UPDATE";

    protected MyAdapter pagerAdapter;
    protected ActionBar actionBar;
    protected ViewPager viewPager;
    protected PagerTabStrip pagerTabStrip;

    public static class HelpFragment extends DialogFragment {
        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
            View v = inflater.inflate(R.layout.help, null);
            TextView tv = (TextView) v.findViewById(R.id.help);
            tv.setText(R.string.help_text);
            return v;
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.top);

        pagerAdapter = new MyAdapter(getFragmentManager(), this);
        actionBar = getActionBar();
        viewPager = (ViewPager) findViewById(R.id.viewPager);
        pagerTabStrip = (PagerTabStrip) findViewById(R.id.pagerTabStrip);

        Intent serviceIntent = new Intent(this, HeadsetService.class);
        startService(serviceIntent);

        actionBar.setDisplayShowTitleEnabled(true);

        viewPager.setAdapter(pagerAdapter);

        pagerTabStrip.setDrawFullUnderline(true);
        pagerTabStrip.setTabIndicatorColor(getResources().getColor(android.R.color.holo_blue_light));
    }

    @Override
    public void onResume() {
        super.onResume();
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                HeadsetService service = ((HeadsetService.LocalBinder) binder).getService();
                String routing = service.getAudioOutputRouting();
                String[] entries = pagerAdapter.getEntries();
                for (int i = 0; i < entries.length; i++) {
                    if (routing.equals(entries[i])) {
                        viewPager.setCurrentItem(i);
                        break;
                    }
                }
                unbindService(this);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
            }
        };
        Intent serviceIntent = new Intent(this, HeadsetService.class);
        bindService(serviceIntent, connection, 0);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int choice = item.getItemId();
        switch (choice) {
            case R.id.help:
                DialogFragment df = new HelpFragment();
                df.setStyle(DialogFragment.STYLE_NO_TITLE, 0);
                df.show(getFragmentManager(), "help");
                return true;
            default:
                return false;
        }
    }
}

class MyAdapter extends FragmentPagerAdapter {
    private final ArrayList<String> tmpEntries;
    private final ArrayList<String> tmpTitles;
    private final String[] entries;
    private final String[] titles;

    public MyAdapter(FragmentManager fm, Context context) {
        super(fm);
        Resources res = context.getResources();
        tmpEntries = new ArrayList<String>();
        tmpEntries.add("headset");
        tmpEntries.add("speaker");
        tmpEntries.add("bluetooth");
        tmpEntries.add("usb");

        tmpTitles = new ArrayList<String>();
        tmpTitles.add(res.getString(R.string.headset_title).toUpperCase());
        tmpTitles.add(res.getString(R.string.speaker_title).toUpperCase());
        tmpTitles.add(res.getString(R.string.bluetooth_title).toUpperCase());
        tmpTitles.add(res.getString(R.string.usb_title).toUpperCase());

        // Determine if WM8994 is supported
        if (WM8994.isSupported(context)) {
            tmpEntries.add(WM8994.NAME);
            tmpTitles.add(res.getString(R.string.wm8994_title).toUpperCase());
        }

        entries = (String[]) tmpEntries.toArray(new String[tmpEntries.size()]);
        titles = (String[]) tmpTitles.toArray(new String[tmpTitles.size()]);
    }

    @Override
    public CharSequence getPageTitle(int position) {
        return titles[position];
    }

    public String[] getEntries() {
        return entries;
    }

    @Override
    public int getCount() {
        return entries.length;
    }

    @Override
    public Fragment getItem(int position) {

        // Determine if fragment is WM8994
        if (entries[position].equals(WM8994.NAME)) {
            return new WM8994();
        } else {
            final DSPScreen dspFragment = new DSPScreen();
            Bundle b = new Bundle();
            b.putString("config", entries[position]);
            dspFragment.setArguments(b);
            return dspFragment;
        }
    }
}
