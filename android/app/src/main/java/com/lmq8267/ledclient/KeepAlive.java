package com.lmq8267.ledclient;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

/**
 * 统一的“设置仍在运行就恢复服务”入口。
 *
 * <p>为什么不在各个组件里各写一份：通知监听和开机广播都可能成为复活点，
 * 它们必须用同一套判断（用户是否启用、是否配置地址、屏幕是否亮），否则很容易出现
 * 一个入口把服务拉起来、另一个入口又把状态改回去的情况。
 *
 * <p>为什么屏幕黑着不立即启动：实测（quizgate 在 MiTV-MFTR0 上取证）前台服务会把
 * 待机中的电视点亮，表现为“电视自己开机”。这里只登记亮屏广播，等屏幕真正亮起再起服务。
 */
final class KeepAlive {
    private static final String TAG = "LedOverlay";
    private static BroadcastReceiver screenOnReceiver;

    private KeepAlive() {}

    static void restoreIfEnabled(Context context, String reason) {
        Context app = context.getApplicationContext();
        if (!Prefs.enabled(app)) {
            Log.i(TAG, "跳过保活 reason=" + reason + " enabled=false");
            return;
        }
        if (Prefs.server(app).trim().length() == 0) {
            Log.i(TAG, "跳过保活 reason=" + reason + " server_empty");
            return;
        }
        if (!isInteractive(app)) {
            Log.i(TAG, "保活延后 reason=" + reason + " screen_off");
            // 进程可能在亮屏前被系统回收；落盘后下次通知监听重绑还能补上。
            Prefs.setPendingRestore(app, true);
            waitForScreenOn(app, reason);
            return;
        }
        if (Prefs.pendingRestore(app)) {
            Log.i(TAG, "补发上次延后的恢复 reason=" + reason);
        }
        start(app, reason);
    }

    static void start(Context context, String reason) {
        // reason 同时作为启动来源写进日志页（boot:…/job/alarm/guard-sync/notification_listener）
        LedService.start(context, Prefs.ACTION_RESTORE, reason);
        Log.i(TAG, "保活启动 reason=" + reason);
    }

    private static boolean isInteractive(Context context) {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm == null) return true;
        if (Build.VERSION.SDK_INT >= 20) return pm.isInteractive();
        return pm.isScreenOn();
    }

    private static synchronized void waitForScreenOn(final Context app, final String reason) {
        if (screenOnReceiver != null) return;
        screenOnReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!Intent.ACTION_SCREEN_ON.equals(intent.getAction())) return;
                Log.i(TAG, "收到亮屏，准备恢复 reason=" + reason);
                synchronized (KeepAlive.class) {
                    if (screenOnReceiver != null) {
                        try { app.unregisterReceiver(screenOnReceiver); } catch (Throwable ignored) {}
                        screenOnReceiver = null;
                    }
                }
                if (Prefs.enabled(app)) start(app, reason + ":screen_on");
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(screenOnReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                app.registerReceiver(screenOnReceiver, filter);
            }
        } catch (Throwable t) {
            screenOnReceiver = null;
            Log.w(TAG, "注册亮屏广播失败: " + t);
        }
    }
}
