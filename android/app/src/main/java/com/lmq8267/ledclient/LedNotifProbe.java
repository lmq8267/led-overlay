package com.lmq8267.ledclient;

import android.os.Handler;
import android.os.Looper;
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
 * <p>为什么要分档重试：重绑发生的瞬间进程可能还没准备好（用户未解锁、FGS 被拒、
 * 服务实例刚被回收）。只试一次的话，失败就要等下一次重绑，可能几分钟。这里按
 * 2s/5s/10s/20s/40s/60s 重试，约 2 分钟收口，与 StudyOnTV 的复活窗口同量级。
 *
 * <p>必须和 LedService 同进程（清单里不写 android:process），否则重绑只会拉起一个
 * 没有 WebSocket 的空壳进程，对保活没有意义。
 */
public class LedNotifProbe extends NotificationListenerService {
    private static final String TAG = "LedOverlay";

    /** 首次立即检查，之后逐档拉长。 */
    private static final long[] RETRY_DELAYS = {2_000L, 5_000L, 10_000L, 20_000L, 40_000L, 60_000L};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int attempt;
    private final Runnable retry = new Runnable() {
        @Override public void run() {
            check();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        // 部分固件 bind 了服务，onListenerConnected 却要等一会儿；在 onCreate 先起一轮，
        // 等 connected 回调到达时会重置窗口，等于多一次拉起机会（与 StudyOnTV 一致）。
        startRecovery();
    }

    @Override public void onListenerConnected() {
        Log.i(TAG, "通知监听已连接");
        startRecovery();
    }

    @Override public void onListenerDisconnected() {
        Log.i(TAG, "通知监听已断开");
        handler.removeCallbacks(retry);
    }

    private void startRecovery() {
        handler.removeCallbacks(retry);
        attempt = 0;
        handler.post(retry);
    }

    private void check() {
        if (!Prefs.enabled(this)) {
            finish("disabled");
            return;
        }
        // 用同进程静态标记而不是心跳：强停后心跳仍可能"新鲜"，会误判服务还活着
        if (LedService.isRunning()) {
            finish("service running");
            return;
        }
        // 屏幕黑时 KeepAlive 只登记亮屏广播、不启动 FGS（否则会把待机电视点亮）。
        // 这里继续按窗口重试也没关系：它只会重复做一次 isInteractive 判断，
        // 不会重复注册 receiver，也不会点亮屏幕。
        KeepAlive.restoreIfEnabled(this, "notification_listener");
        if (attempt >= RETRY_DELAYS.length) {
            finish("retry window exhausted");
            return;
        }
        handler.postDelayed(retry, RETRY_DELAYS[attempt++]);
    }

    private void finish(String result) {
        handler.removeCallbacks(retry);
        Log.i(TAG, "复活收口: " + result + " (running=" + LedService.isRunning() + ")");
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(retry);
        super.onDestroy();
    }
}
