package com.example.floatingcandlescanner;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class SignalDismissReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent){
        if(intent!=null && "scanner.DISMISS_SIGNAL".equals(intent.getAction()))
            AutoSymbolAccessibilityService.dismissSignalOverlay();
    }
}
