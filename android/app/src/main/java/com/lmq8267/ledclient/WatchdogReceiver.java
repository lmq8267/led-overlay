package com.lmq8267.ledclient;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 闹钟接收器：MIUI 等系统会忽略 START_STICKY 杀死后台服务，
 * 通过 AlarmManager 定时心跳检查，自动恢复服务。
 */
public class WatchdogReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Prefs.running(context)) return;
        if (Prefs.server(context).trim().length() == 0) return;
        Intent service = new Intent(context, LedService.class);
        service.setAction(Prefs.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(service);
        } else {
            context.startService(service);
        }
    }
}
