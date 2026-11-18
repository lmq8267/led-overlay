package com.lmq8267.ledclient;

import android.service.notification.NotificationListenerService;
import android.util.Log;

/**
 * 存活锚点：只等系统重新绑定通知监听，不读取通知内容。
 *
 * <p>为什么不用无障碍当锚点：quizgate 在 MiTV-MFTR0（Android 11 / 14）实测，强停后
 * 无障碍授权会被系统清空，服务不会重新绑定；而通知监听被系统重绑时会把整个进程拉回来，
 * 实测强停后约 150ms 就能恢复。本类因此只做一件事：收到系统重绑信号后，
 * 让 KeepAlive 去恢复 LED 服务。
 *
 * <p>必须和 LedService 同进程（清单里不写 android:process），否则重绑只会拉起一个
 * 没有 WebSocket 的空壳进程，对保活没有意义。
 */
public class LedNotifProbe extends NotificationListenerService {
    private static final String TAG = "LedOverlay";

    @Override public void onListenerConnected() {
        Log.i(TAG, "通知监听已连接");
        KeepAlive.restoreIfEnabled(this, "notification_listener");
    }

    @Override public void onListenerDisconnected() {
        Log.i(TAG, "通知监听已断开");
    }
}
