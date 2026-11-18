package com.lmq8267.ledclient;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机/覆盖升级后的兜底恢复入口。
 *
 * <p>注意：小米电视在电源键关机时会执行 killAllThirdApp，把第三方包置为 stopped，
 * 被强停的包收不到 BOOT_COMPLETED（quizgate 已在 MiTV-MFTR0 实测，见其 ADR-4）。
 * 所以这里只是兜底，真正可靠的复活点是 LedNotifProbe 的通知监听重绑。
 * 但兜底也不能再“只写状态不启动服务”——那会让界面显示运行中、实际什么都没起。
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "LedOverlay";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action);
        boolean replaced = Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        if (!boot && !replaced) return;
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
        // 先判断屏幕：开机瞬间屏幕可能还没亮，直接起前台服务会把待机电视点亮。
        KeepAlive.restoreIfEnabled(context, "boot:" + action);
    }
}
