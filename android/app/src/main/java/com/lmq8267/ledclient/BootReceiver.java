package com.lmq8267.ledclient;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Prefs.boot(context) && Prefs.server(context).trim().length() > 0) {
            Intent service = new Intent(context, LedService.class);
            service.setAction(Prefs.ACTION_START);
            boolean isTv = context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
            if (Build.VERSION.SDK_INT >= 26 && !isTv) context.startForegroundService(service);
            else context.startService(service);
        }
    }
}
