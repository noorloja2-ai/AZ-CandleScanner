package com.example.floatingcandlescanner;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class SignalDismissReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent){
        if(intent==null)return;
        String action=intent.getAction();
        if("scanner.SHOW_SIGNAL".equals(action))
            AutoSymbolAccessibilityService.showLastSignalOverlay();
        else if("scanner.DISMISS_SIGNAL".equals(action))
            AutoSymbolAccessibilityService.dismissSignalOverlay();
    }
}
