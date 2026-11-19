package com.lmq8267.ledclient;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.UserManager;
import android.util.Log;

/**
 * 开机 / 覆盖升级 / 复活闹钟的统一入口。
 *
 * <p>注意：小米电视在电源键关机时会执行 killAllThirdApp，把第三方包置为 stopped，
 * 被强停的包收不到 BOOT_COMPLETED（quizgate 已在 MiTV-MFTR0 实测，见其 ADR-4）。
 * 所以这里只是兜底，真正可靠的复活点是 LedNotifProbe 的通知监听重绑，
 * 强停后的二次兜底是 Watchdog 的作业/闹钟与 GuardSync 的同步框架。
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "LedOverlay";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();

        // 复活闹钟：只发恢复意图，屏幕状态由 KeepAlive 判断
        if (Watchdog.ACTION_REVIVE.equals(action)) {
            KeepAlive.restoreIfEnabled(context, "alarm");
            return;
        }

        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.tcl.android.intent.action.BOOT_COMPLETED".equals(action)
                || "com.hisense.intent.action.BOOT_COMPLETED".equals(action);
        boolean replaced = Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        if (!boot && !replaced) return;

        // 个别厂商的快速开机广播可能在用户解锁前到达，此时 SharedPreferences（CE 存储）不可读，
        // 强行读取会抛 IllegalStateException。等 BOOT_COMPLETED 或通知监听重绑再处理。
        if (!isUserUnlocked(context)) {
            Log.i(TAG, "开机恢复延后 action=" + action + " user_locked");
            return;
        }
        if (Prefs.server(context).trim().length() == 0) return;

        if (boot) {
            // 开机自启开关由用户控制；打开即代表这次开机希望客户端运行。
            if (!Prefs.boot(context)) return;
            Prefs.setEnabled(context, true);
        } else if (!Prefs.enabled(context)) {
            // 覆盖升级只有在用户本来就启用时才恢复，不能把“已停止”的设备重新拉起来。
            return;
        }

        Log.i(TAG, "开机恢复 action=" + action);
        // 排程与自愈注册都在 LedService.onCreate/onStartCommand 里做，这里只需要拉起服务
        KeepAlive.restoreIfEnabled(context, "boot:" + action);
    }

    private static boolean isUserUnlocked(Context context) {
        if (Build.VERSION.SDK_INT < 24) return true;
        UserManager manager = (UserManager) context.getSystemService(Context.USER_SERVICE);
        return manager == null || manager.isUserUnlocked();
    }
}
