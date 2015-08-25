// Copyright (c) Microsoft. All Rights Reserved. Licensed under the MIT License. See license.txt in the project root for further information.
package org.thaliproject.p2p.btconnectorlib;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pManager;
import android.util.Log;

/**
 * Created by juksilve on 6.3.2015.
 */
class WifiBase{

    public interface  WifiStatusCallBack{
        void WifiStateChanged(int state);
    }

    private WifiP2pManager p2p = null;
    private WifiP2pManager.Channel channel = null;
    private final Context context ;

    private final WifiStatusCallBack callback;
    private MainBCReceiver mBRReceiver = null;

    public WifiBase(Context Context, WifiStatusCallBack handler){
        this.context = Context;
        this.callback = handler;
    }

    public boolean Start(){

        Stop();
        MainBCReceiver tmpBRReceiver = new MainBCReceiver();
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
            this.context.registerReceiver(tmpBRReceiver, filter);
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
            return false;
        }
        mBRReceiver = tmpBRReceiver;

        p2p = (WifiP2pManager) this.context.getSystemService(Context.WIFI_P2P_SERVICE);
        if (p2p == null) {
            print_debug("", "This device does not support Wi-Fi Direct");
            return false;
        }

        channel = p2p.initialize(this.context, this.context.getMainLooper(),null);
        return true;
    }

    public void Stop(){
        BroadcastReceiver tmpRec = mBRReceiver;
        mBRReceiver = null;
        if(tmpRec != null) {
            try {
                this.context.unregisterReceiver(tmpRec);
            } catch (IllegalArgumentException e) {e.printStackTrace();}
        }
    }

    public WifiP2pManager.Channel GetWifiChannel(){
        return channel;
    }
    public WifiP2pManager  GetWifiP2pManager(){
        return p2p;
    }

    public boolean isWifiEnabled() {
        WifiManager wifiManager = (WifiManager) this.context.getSystemService(Context.WIFI_SERVICE);
        return wifiManager != null && wifiManager.isWifiEnabled();
    }

    public boolean setWifiEnabled(boolean enabled) {
        WifiManager wifiManager = (WifiManager) this.context.getSystemService(Context.WIFI_SERVICE);
        return wifiManager != null && wifiManager.setWifiEnabled(enabled);
    }

    private class MainBCReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                if(callback != null) {
                    int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                    callback.WifiStateChanged(state);
                }
            }
        }
    }

    private void print_debug(String who, String message){
        Log.d("WifiBase",  "BTListerThread: " + message);
    }
}
