package com.qwe7002.telegram_rc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class wap_receiver extends BroadcastReceiver {
    @Override
    public void onReceive(final Context context, Intent intent) {
        Log.d("wap_receiver", "Receive action: " + intent.getAction());
    }
}
