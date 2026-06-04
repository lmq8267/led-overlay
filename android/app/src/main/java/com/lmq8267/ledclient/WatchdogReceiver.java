package com.lmq8267.ledclient;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.List;

/**
 * 闹钟接收器：MIUI 等系统会忽略 START_STICKY 杀死后台服务，
 * 通过 AlarmManager 定时心跳检查，自动恢复服务。
 *
 * 小米电视的 AppsRelaunchController 会拦截 startForegroundService()，
 * 因此使用普通 startService() 绕过。
 */
public class WatchdogReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Prefs.running(context)) return;
        if (Prefs.server(context).trim().length() == 0) return;

        // 检查 LedService 和 LedAccessibilityService 是否已在运行
        if (isServiceRunning(context, LedService.class.getName())
                || isServiceRunning(context, LedAccessibilityService.class.getName())) {
            return; // 至少有一个服务存活，无需拉起
        }

        // 优先拉起无障碍服务（后台驻留能力更强）
        Intent service = new Intent(context, LedAccessibilityService.class);
        service.setAction(Prefs.ACTION_START);
        context.startService(service);
    }

    private boolean isServiceRunning(Context context, String serviceName) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        List<ActivityManager.RunningServiceInfo> services = am.getRunningServices(Integer.MAX_VALUE);
        if (services == null) return false;
        for (ActivityManager.RunningServiceInfo info : services) {
            if (serviceName.equals(info.service.getClassName())) {
                return true;
            }
        }
        return false;
    }
}
