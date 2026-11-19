package com.lmq8267.ledclient;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public class LedService extends Service {
    private static final String TAG = "LedOverlay";
    /** 启动来源，写进日志页，用来回答"这次是谁把我拉起来的"。 */
    static final String EXTRA_VIA = "via";
    private static final long HEARTBEAT_INTERVAL_MS = 5_000L;
    private WsClient ws;
    private Thread thread;
    private String currentServer = "";
    /** 服务端在 hello-ok 里下发的 ACK 密钥；HTTP 兜底 ACK 必须带上。 */
    private volatile String ackSecret = "";
    /** onDestroy 之后网络回调仍可能并发进入 startClient；这个标记保证不会创建停不掉的连接。 */
    private volatile boolean destroyed;
    /**
     * 进程内的存活标记：只有本进程真的创建过服务才为 true。
     *
     * <p>为什么不能用 Prefs.running / 心跳判断：强停不会走 onDestroy，running 会永久停在 true；
     * 心跳还有 15 秒的新鲜窗口，强停后通知监听约 150ms 就重绑，此时心跳看起来仍然"新鲜"，
     * 会把复活逻辑误判成"服务还在"。静态字段随进程消失，是唯一同进程内准确的判断。
     */
    private static volatile boolean alive;
    private volatile int connectionGeneration = 0;
    private WindowManager windowManager;
    private volatile OverlayView overlay;
    private Handler mainHandler;
    private String lastOverlayError = "";
    private ConnectivityManager.NetworkCallback networkCallback;
    private final ArrayList<LedMessage> displayQueue = new ArrayList<LedMessage>();
    private final ArrayList<String> completedAckRetries = new ArrayList<String>();
    private final ArrayList<String> ackInFlight = new ArrayList<String>();
    /**
     * ACK 用固定两个线程：一个跑 WebSocket 发送，一个跑 HTTP 兜底。
     * 旧实现每条 ACK 每次重试都 new Thread，HTTP 不通时会瞬间堆出几百个线程。
     */
    private final ExecutorService ackExecutor = Executors.newFixedThreadPool(2, new ThreadFactory() {
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "led-ack");
            t.setDaemon(true);
            return t;
        }
    });
    /** 消息历史是本地小文件读写，不能放在主线程或 ACK 线程上。 */
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "led-io");
            t.setDaemon(true);
            return t;
        }
    });
    private String displayingTaskId = "";
    private String lastHttpAckError = "";
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            Prefs.touchHeartbeat(LedService.this);
            if (mainHandler != null) mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS);
        }
    };
    private final Runnable pendingAckFlusher = new Runnable() {
        @Override public void run() {
            flushPendingCompletedAcks();
            if (Prefs.running(LedService.this) && mainHandler != null) {
                mainHandler.postDelayed(this, 5000);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        // 必须无条件先 startForeground：Android 8+ 的 5 秒规则不会因为厂商拦截而放宽。
        // 前台服务类型改成 specialUse 后，MiTV 上不再走 dataSync 那套限制（quizgate 已实测）。
        startAsForeground();
        alive = true;
        Prefs.setRunning(this, true);
        // 只有服务真的起来了才清“待恢复”标记；startForegroundService 被系统拒绝时保留，等下次锚点重试。
        Prefs.setPendingRestore(this, false);
        Prefs.touchHeartbeat(this);
        mainHandler.post(heartbeat);
        registerNetworkCallback();
        mainHandler.postDelayed(pendingAckFlusher, 5000);
        Log.i(TAG, "服务创建 enabled=" + Prefs.enabled(this));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        String via = intent == null ? null : intent.getStringExtra(EXTRA_VIA);
        if (via == null || via.length() == 0) via = "unknown";
        // 走到这里说明服务已经在运行（可能是复用实例），待恢复标记可以清掉。
        Prefs.setPendingRestore(this, false);
        if (Prefs.ACTION_CLEAN_IDLE_OVERLAY.equals(action)) {
            if (!Prefs.enabled(this)) {
                stopSelf();
                return START_NOT_STICKY;
            }
            removeIdleOverlay();
            // 该 action 用的是 startForegroundService：如果服务实例刚好被系统回收，
            // 系统会新建一个只有心跳、没有 WebSocket 的空壳。这里补一次连接，
            // 确保 CLEAN 不会把“运行中但没连接”的状态留在界面上。
            // 排程放在这里而不是 onCreate：enabled=false 时这条路径会先 stopSelf，
            // 不能在 onCreate 里无条件排程，否则用户已停止也会留下每 15 分钟的作业。
            Watchdog.schedule(this);
            logStart(via, action);
            startClient();
            return START_STICKY;
        }

        if (Prefs.ACTION_START.equals(action)) {
            Prefs.setEnabled(this, true);
        } else if (!Prefs.enabled(this)) {
            // 系统用 null intent 重启、或者旧版本遗留的 RESTORE，都不能绕过用户按下的“停止”。
            Log.i(TAG, "服务停止 reason=disabled action=" + action);
            stopSelf();
            return START_NOT_STICKY;
        }

        Prefs.touchHeartbeat(this);
        // 到这里才确定服务要运行（enabled 已置 true 或本来就是 true）：
        // 排两条定时兜底，再注册同步自愈。
        Watchdog.schedule(this);
        GuardSync.ensure(this);
        Log.i(TAG, "同步自愈状态 " + GuardSync.status(this));
        logStart(via, action);
        startClient();
        return START_STICKY;
    }

    /** 把启动来源写进用户可见的日志页，出问题时能回答“这次是谁拉起的”。 */
    private void logStart(String via, String action) {
        Log.i(TAG, "服务启动 via=" + via + " action=" + action);
        LogStore.append(this, getString(R.string.log_service_start, via, action));
    }

    /**
     * 从最近任务划掉时，部分系统会连带停止服务（手机/部分电视固件）。
     * 2 秒后用不精确闹钟把自己拉回来；用户已停止时不排，避免"停了又活"。
     *
     * <p>强停（stopped）和断电不会回调这里——那两种场景由通知监听、同步框架和开机广播处理。
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (Prefs.enabled(this)) scheduleRestart(this, 2000L, "task");
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        alive = false;
        if (mainHandler != null) {
            mainHandler.removeCallbacks(heartbeat);
            mainHandler.removeCallbacks(pendingAckFlusher);
        }
        ackExecutor.shutdownNow();
        ioExecutor.shutdownNow();
        unregisterNetworkCallback();
        connectionGeneration++;
        if (ws != null) ws.stop();
        if (overlay != null) {
            // 先停掉 16ms 刷新和 onFinished 回调，再移除窗口：
            // 否则服务销毁后 OverlayView 仍会在主线程自循环。
            overlay.stop();
            try { windowManager.removeView(overlay); } catch (Exception ignored) {}
        }
        // 这里只清运行态。enabled 是用户意图，系统杀进程时 onDestroy 往往根本不会执行，
        // 即使执行了也不能把用户“开着”的意图抹掉，否则通知监听锚点将无事可做。
        Prefs.setRunning(this, false);
        Prefs.setConnectionState(this,
                Prefs.enabled(this) ? Prefs.CONNECTION_CONNECTING : Prefs.CONNECTION_DISCONNECTED);
        Log.i(TAG, "服务销毁 enabled=" + Prefs.enabled(this));
        // 有序停止（非用户点“停止”）后 5 秒拉回来。用户点停止时 enabled 已经是 false，
        // 这里不会排程；强停/断电不会走 onDestroy，由通知监听、同步框架和开机广播接住。
        if (Prefs.enabled(this)) scheduleRestart(this, 5000L, "destroy");
        super.onDestroy();
    }

    /** 所有复活入口统一走这里，避免每个调用点各写一遍 startForegroundService 的兼容分支。 */
    static void start(Context context, String action) {
        start(context, action, "");
    }

    /** @param via 启动来源（boot/job/alarm/sync/listener/task/destroy…），只用于日志归因 */
    static void start(Context context, String action, String via) {
        Intent intent = new Intent(context, LedService.class).setAction(action);
        if (via != null && via.length() > 0) intent.putExtra(EXTRA_VIA, via);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (Throwable first) {
            // Android 12+ 的后台启动限制可能抛 ForegroundServiceStartNotAllowedException。
            // 在部分电视固件上普通 startService 仍可用，作为最后兜底；失败时只记录，不让锚点崩掉。
            try {
                context.startService(intent);
            } catch (Throwable second) {
                Log.w(TAG, "服务启动失败 action=" + action, first);
            }
        }
    }

    /** 同进程内是否真的创建过服务；进程被杀后静态值随进程消失，比心跳更准确。 */
    static boolean isRunning() {
        return alive;
    }

    /**
     * 被划掉 / 有序停止后的自恢复。
     *
     * <p>用 {@code AlarmManager.set} 而不是 setExact：不精确闹钟不需要 SCHEDULE_EXACT_ALARM
     * 权限（Android 12+ 精确闹钟要用户授权），代价只是 Doze 下可能延迟。
     */
    static void scheduleRestart(Context context, long delayMs, String via) {
        try {
            Intent intent = new Intent(context, LedService.class)
                    .setAction(Prefs.ACTION_RESTORE)
                    .putExtra(EXTRA_VIA, via);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pending;
            if (Build.VERSION.SDK_INT >= 26) {
                pending = PendingIntent.getForegroundService(context, 7410, intent, flags);
            } else {
                pending = PendingIntent.getService(context, 7410, intent, flags);
            }
            AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarm == null) return;
            alarm.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs, pending);
        } catch (Throwable t) {
            Log.w(TAG, "无法排程任务移除后的重启", t);
        }
    }

    static void stop(Context context) {
        Prefs.setEnabled(context, false);
        Prefs.setPendingRestore(context, false);
        Prefs.clearRuntimeState(context);
        // 用户已经点了停止：不能留两条每 15 分钟叫醒进程的定时器，也不能让同步框架继续 bind 进程
        Watchdog.cancel(context);
        GuardSync.pause(context);
        try { context.stopService(new Intent(context, LedService.class)); } catch (Throwable ignored) {}
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private synchronized void startClient() {
        if (destroyed || !Prefs.enabled(this)) return;
        final String server = Prefs.server(this);
        if (server.trim().length() == 0) return;
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            // 悬浮窗缺失不应该把整个连接停掉：进程和 WebSocket 仍要活着，
            // 任务到达时会走 ensureOverlay 失败并把 failed ACK 回报给服务端。
            LogStore.append(this, getString(R.string.log_overlay_permission_missing));
            Log.w(TAG, "缺少悬浮窗权限，继续保持连接");
        }
        final String target = WsClient.normalize(server);
        if (thread != null && thread.isAlive()) {
            if (server.equals(currentServer)) {
                Prefs.setConnectionState(this,
                        ws != null && ws.isOpen() ? Prefs.CONNECTION_CONNECTED : Prefs.CONNECTION_CONNECTING);
                return;
            }
            if (ws != null) ws.stop();
            thread = null;
        }
        currentServer = server;
        final int generation = ++connectionGeneration;
        Prefs.setConnectionState(this, Prefs.CONNECTION_CONNECTING);
        LogStore.append(this, getString(R.string.log_connecting, target));
        final WsClient[] clientRef = new WsClient[1];
        final WsClient client = new WsClient(server, new WsClient.Listener() {
            @Override public void onOpen() {
                if (!isActiveConnection(generation)) return;
                ackSecret = "";
                Prefs.setConnectionState(LedService.this, Prefs.CONNECTION_CONNECTED);
                LogStore.append(LedService.this, getString(R.string.log_connected, target));
                JSONObject hello = new JSONObject();
                try {
                    hello.put("type", "hello");
                    hello.put("clientId", Prefs.clientId(LedService.this));
                    hello.put("name", Build.MANUFACTURER + " " + Build.MODEL);
                } catch (Exception ignored) {}
                clientRef[0].send(hello);
            }

            @Override public void onText(String text) {
                if (!isActiveConnection(generation)) return;
                Prefs.setConnectionState(LedService.this, Prefs.CONNECTION_CONNECTED);
                try {
                    JSONObject json = new JSONObject(text);
                    String type = json.optString("type");
                    if ("hello-ok".equals(type)) {
                        ackSecret = json.optString("secret", "");
                        // 不能把整帧写进日志：里面有本次连接的 ACK 密钥。
                        LogStore.append(LedService.this, "连接握手完成，已更新 ACK 密钥");
                        flushPendingCompletedAcks();
                        return;
                    }
                    LogStore.append(LedService.this, getString(R.string.log_message, text));
                    if ("message".equals(type)) {
                        String taskId = json.optString("taskId", "");
                        try {
                            receiveMessage(LedMessage.fromJson(json));
                        } catch (Exception e) {
                            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                            LogStore.append(LedService.this, getString(R.string.log_invalid_message, reason));
                            if (taskId.length() > 0) sendAckReliable(taskId, "failed", "客户端解析消息失败：" + reason);
                        }
                    }
                    else if ("cancel".equals(type)) cancel(json.optString("taskId"));
                } catch (Exception e) {
                    LogStore.append(LedService.this, getString(R.string.log_invalid_message, e.getMessage()));
                }
            }

            @Override public void onClosed(Exception error) {
                if (!isActiveConnection(generation)) return;
                ackSecret = "";
                Prefs.setConnectionState(LedService.this,
                        Prefs.running(LedService.this) ? Prefs.CONNECTION_CONNECTING : Prefs.CONNECTION_DISCONNECTED);
                String reason = error == null || error.getMessage() == null
                        ? "connection closed"
                        : error.getMessage();
                LogStore.append(LedService.this, getString(R.string.log_disconnected, reason));
            }
        });
        clientRef[0] = client;
        ws = client;
        thread = new Thread(new Runnable() {
            @Override public void run() {
                client.connectLoop();
            }
        }, "led-ws");
        thread.start();
    }

    private boolean isActiveConnection(int generation) {
        return generation == connectionGeneration;
    }

    private boolean ensureOverlay() {
        // 队列可能在被取消的瞬间变空；主线程真正加窗前再确认一次，
        // 避免留下一个没有任务的空闲系统窗口（MiTV 会因此强停进程）。
        if (!hasPendingDisplay()) return false;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            final boolean[] ok = new boolean[] {false};
            final CountDownLatch latch = new CountDownLatch(1);
            mainHandler.post(new Runnable() {
                @Override public void run() {
                    ok[0] = ensureOverlay();
                    latch.countDown();
                }
            });
            try {
                if (!latch.await(3000, TimeUnit.MILLISECONDS)) {
                    lastOverlayError = "等待主线程创建悬浮窗超时";
                    return false;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                lastOverlayError = "等待主线程创建悬浮窗被中断";
            }
            return ok[0];
        }
        if (overlay != null) return true;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        overlay = new OverlayView(this);
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayHeight(),
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.y = statusBarHeight();
        try {
            overlay.setVisibility(android.view.View.INVISIBLE);
            windowManager.addView(overlay, lp);
            lastOverlayError = "";
            return true;
        } catch (Exception e) {
            lastOverlayError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            LogStore.append(this, getString(R.string.log_overlay_add_failed, lastOverlayError));
            overlay = null;
            return false;
        }
    }

    private boolean hasPendingDisplay() {
        synchronized (displayQueue) {
            return displayingTaskId.length() > 0 || !displayQueue.isEmpty();
        }
    }

    private void receiveMessage(final LedMessage message) {
        if (Prefs.isTaskDone(this, message.taskId)) {
            if (Prefs.hasPendingCompletedAck(this, message.taskId)) return;
            sendAckReliable(message.taskId, "completed", "客户端记录显示已完成，跳过重复显示");
            return;
        }
        if (isDisplayingOrQueued(message.taskId)) {
            sendAckReliable(message.taskId, "received", "客户端已收到任务，当前已在本地显示队列中");
            return;
        }
        synchronized (displayQueue) {
            displayQueue.add(message);
        }
        sendAckReliable(message.taskId, "received", "客户端已收到任务，已加入本地显示队列");
        processDisplayQueue();
    }

    private boolean isDisplayingOrQueued(String taskId) {
        synchronized (displayQueue) {
            if (displayingTaskId.equals(taskId)) return true;
            for (int i = 0; i < displayQueue.size(); i++) {
                if (displayQueue.get(i).taskId.equals(taskId)) return true;
            }
        }
        return false;
    }

    private void processDisplayQueue() {
        boolean idle;
        synchronized (displayQueue) {
            // 正在显示的任务没结束前绝对不能再出队：OverlayView.show() 会替换当前
            // message 并移除旧的 tick，被顶掉的任务永远拿不到 onFinished，也就不会
            // 上报 completed。队列只在 onFinished/cancel/failed 清空 displayingTaskId 后推进。
            if (displayingTaskId.length() > 0) return;
            idle = displayQueue.isEmpty();
        }
        if (idle) {
            // 队列空了必须把窗口真正 removeView，不能只 setVisibility(INVISIBLE)：
            // MiTV 的 MitvWindowManager 只要发现应用还有系统窗口，就会在切后台时强停
            // （2026-11-18 对照原实现确认，隐藏窗口同样会触发）。
            removeIdleOverlay();
            return;
        }
        if (!ensureOverlay()) {
            String reason = lastOverlayError.length() == 0
                    ? "悬浮窗创建失败，请检查悬浮窗权限或系统限制"
                    : "悬浮窗创建失败：" + lastOverlayError;
            failQueuedMessages(reason);
            return;
        }
        LedMessage next;
        synchronized (displayQueue) {
            if (displayQueue.isEmpty()) return;
            next = displayQueue.remove(0);
            displayingTaskId = next.taskId;
        }
        showWhenOverlayReady(next, 0);
    }

    private void failQueuedMessages(String reason) {
        ArrayList<LedMessage> failed = new ArrayList<LedMessage>();
        synchronized (displayQueue) {
            failed.addAll(displayQueue);
            displayQueue.clear();
            displayingTaskId = "";
        }
        for (int i = 0; i < failed.size(); i++) {
            sendAckReliable(failed.get(i).taskId, "failed", reason);
        }
        removeIdleOverlay();
    }

    private void showWhenOverlayReady(final LedMessage message, final int attempts) {
        final OverlayView view = overlay;
        if (view == null) {
            sendAckReliable(message.taskId, "failed", "悬浮窗对象不存在，无法显示");
            synchronized (displayQueue) {
                if (displayingTaskId.equals(message.taskId)) displayingTaskId = "";
            }
            processDisplayQueue();
            return;
        }
        view.post(new Runnable() {
            @Override public void run() {
                if (view.getWidth() <= 0 || view.getHeight() <= 0) {
                    if (attempts < 10) {
                        showWhenOverlayReady(message, attempts + 1);
                    } else {
                        LogStore.append(LedService.this, getString(R.string.log_overlay_add_failed, "overlay not ready"));
                        sendAckReliable(message.taskId, "failed", "悬浮窗布局未就绪，等待 10 次后仍无法获取尺寸");
                        synchronized (displayQueue) {
                            if (displayingTaskId.equals(message.taskId)) displayingTaskId = "";
                        }
                        processDisplayQueue();
                    }
                    return;
                }
                final LedMessage historyMessage = message;
                submitIo(new Runnable() {
                    @Override public void run() {
                        MessageHistory.append(LedService.this, historyMessage);
                    }
                });
                sendAckReliable(message.taskId, "displaying", "客户端正在按本地队列显示该任务");
                view.show(message, new OverlayView.Listener() {
                    @Override public void onFinished(String taskId) {
                        Prefs.markTaskDone(LedService.this, taskId);
                        LogStore.append(LedService.this, "任务显示完成，正在上报服务端：" + taskId);
                        sendAckReliable(taskId, "completed", "客户端显示完成");
                        synchronized (displayQueue) {
                            if (displayingTaskId.equals(taskId)) displayingTaskId = "";
                        }
                        processDisplayQueue();
                    }
                });
            }
        });
    }

    private boolean sendAck(String taskId, String status, String detail) {
        JSONObject ack = new JSONObject();
        try {
            ack.put("type", "ack");
            ack.put("taskId", taskId);
            ack.put("status", status);
            ack.put("detail", detail == null ? "" : detail);
        } catch (Exception ignored) {}
        boolean sent = ws != null && ws.send(ack);
        return sent;
    }

    private void sendAckReliable(String taskId, String status) {
        sendAckReliable(taskId, status, "");
    }

    private void sendAckReliable(String taskId, String status, String detail) {
        if (taskId == null || taskId.length() == 0) return;
        if ("completed".equals(status)) {
            Prefs.addPendingCompletedAck(this, taskId);
            submitAckSend(taskId, status, detail);
            sendCompletedAckHttp(taskId, detail);
            scheduleCompletedAckRetry(taskId, detail);
            return;
        }
        Prefs.addPendingAck(this, taskId, status);
        submitAckSend(taskId, status, detail);
        sendAckHttpAsync(taskId, status, detail);
        scheduleAckRetry(taskId, status, detail);
    }

    /** WS ACK 也放到 ACK 线程池：主线程（onFinished / 定时补发）不能因为 socket 写阻塞而 ANR。 */
    private void submitAckSend(final String taskId, final String status, final String detail) {
        final String key = "ws:" + status + ":" + taskId;
        synchronized (ackInFlight) {
            if (ackInFlight.contains(key)) return;
            ackInFlight.add(key);
        }
        try {
            ackExecutor.execute(new Runnable() {
                @Override public void run() {
                    try {
                        sendAck(taskId, status, detail);
                    } finally {
                        synchronized (ackInFlight) {
                            ackInFlight.remove(key);
                        }
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            synchronized (ackInFlight) {
                ackInFlight.remove(key);
            }
        }
    }

    private void scheduleCompletedAckRetry(final String taskId, final String detail) {
        if (taskId == null || taskId.length() == 0 || mainHandler == null) return;
        final String retryKey = "completed:" + taskId;
        synchronized (completedAckRetries) {
            if (completedAckRetries.contains(retryKey)) return;
            completedAckRetries.add(retryKey);
        }
        mainHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (shouldRetryCompletedAck(taskId)) {
                    sendCompletedAckHttp(taskId, detail);
                }
            }
        }, 1000);
        mainHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (shouldRetryCompletedAck(taskId)) {
                    sendCompletedAckHttp(taskId, detail);
                }
                synchronized (completedAckRetries) {
                    completedAckRetries.remove(retryKey);
                }
            }
        }, 3000);
    }

    private boolean shouldRetryCompletedAck(String taskId) {
        return Prefs.isTaskDone(this, taskId) && Prefs.hasPendingCompletedAck(this, taskId);
    }

    /** 通用 ACK HTTP 重试（received/displaying/failed 等非 completed 状态） */
    private void scheduleAckRetry(final String taskId, final String status, final String detail) {
        if (taskId == null || taskId.length() == 0 || mainHandler == null) return;
        final String retryKey = status + ":" + taskId;
        synchronized (completedAckRetries) {
            if (completedAckRetries.contains(retryKey)) return;
            completedAckRetries.add(retryKey);
        }
        mainHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (shouldRetryAck(taskId, status)) sendAckHttpAsync(taskId, status, detail);
            }
        }, 1000);
        mainHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (shouldRetryAck(taskId, status)) sendAckHttpAsync(taskId, status, detail);
                synchronized (completedAckRetries) {
                    completedAckRetries.remove(retryKey);
                }
            }
        }, 3000);
    }

    private boolean shouldRetryAck(String taskId, String status) {
        String pending = Prefs.pendingAcks(this);
        return pending.contains("|" + status + ":" + taskId + "|");
    }

    private void sendAckHttpAsync(final String taskId, final String status, final String detail) {
        if (taskId == null || taskId.length() == 0) return;
        final String key = "http:" + status + ":" + taskId;
        submitAckHttp(key, new Runnable() {
            @Override public void run() {
                boolean ok = sendAckHttp(taskId, status, detail == null ? "" : detail);
                if (ok) {
                    Prefs.removePendingAck(LedService.this, taskId, status);
                    LogStore.append(LedService.this, status + " ACK HTTP 状态已上报：" + taskId);
                } else {
                    LogStore.append(LedService.this, status + " ACK HTTP 失败，等待补发：" + taskId
                            + (lastHttpAckError.length() == 0 ? "" : "，" + lastHttpAckError));
                }
            }
        });
    }

    private void sendCompletedAckHttp(final String taskId, final String detail) {
        if (taskId == null || taskId.length() == 0) return;
        final String key = "http:completed:" + taskId;
        submitAckHttp(key, new Runnable() {
            @Override public void run() {
                boolean ok = sendAckHttp(taskId, "completed", detail == null ? "客户端显示完成，HTTP 兜底确认" : detail);
                if (ok) {
                    Prefs.removePendingCompletedAck(LedService.this, taskId);
                    Prefs.removePendingAck(LedService.this, taskId, "received");
                    Prefs.removePendingAck(LedService.this, taskId, "displaying");
                    LogStore.append(LedService.this, "已完成 completed ACK HTTP 状态已上报：" + taskId);
                } else {
                    LogStore.append(LedService.this, "已完成 completed ACK HTTP 失败，等待补发：" + taskId
                            + (lastHttpAckError.length() == 0 ? "" : "，" + lastHttpAckError));
                }
            }
        });
    }

    private void submitIo(final Runnable task) {
        try {
            ioExecutor.execute(task);
        } catch (RejectedExecutionException ignored) {
        }
    }

    private void submitAckHttp(final String key, final Runnable task) {
        synchronized (ackInFlight) {
            if (ackInFlight.contains(key)) return;
            ackInFlight.add(key);
        }
        try {
            ackExecutor.execute(new Runnable() {
                @Override public void run() {
                    try {
                        task.run();
                    } finally {
                        synchronized (ackInFlight) {
                            ackInFlight.remove(key);
                        }
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            synchronized (ackInFlight) {
                ackInFlight.remove(key);
            }
        }
    }

    private boolean sendAckHttp(String taskId, String status, String detail) {
        lastHttpAckError = "";
        String[] urls = WsClient.normalizeHttpAckCandidates(Prefs.server(this));
        JSONObject ack = new JSONObject();
        try {
            ack.put("type", "ack");
            ack.put("clientId", Prefs.clientId(this));
            ack.put("taskId", taskId);
            ack.put("status", status);
            ack.put("detail", detail == null ? "" : detail);
            ack.put("secret", ackSecret);
        } catch (Exception ignored) {}
        byte[] body;
        try {
            body = ack.toString().getBytes("UTF-8");
        } catch (Exception e) {
            return false;
        }
        for (int i = 0; i < urls.length; i++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(urls[i]).openConnection();
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setFixedLengthStreamingMode(body.length);
                OutputStream out = conn.getOutputStream();
                out.write(body);
                out.close();
                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) return true;
                lastHttpAckError = urls[i] + " HTTP " + code;
            } catch (Exception e) {
                lastHttpAckError = urls[i] + " " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return false;
    }

    private void flushPendingCompletedAcks() {
        String all = Prefs.pendingAcks(this);
        if (all.length() > 0) {
            String[] entries = all.split("\\|");
            for (int i = 0; i < entries.length; i++) {
                int sep = entries[i].indexOf(':');
                if (sep > 0 && sep + 1 < entries[i].length()) {
                    String status = entries[i].substring(0, sep);
                    String taskId = entries[i].substring(sep + 1);
                    sendAckReliable(taskId, status);
                }
            }
        }
        String pending = Prefs.pendingCompletedAcks(this);
        if (pending.length() == 0) return;
        String[] ids = pending.split("\\|");
        for (int i = 0; i < ids.length; i++) {
            if (ids[i].length() > 0) sendAckReliable(ids[i], "completed");
        }
    }

    private void cancel(final String taskId) {
        synchronized (displayQueue) {
            for (int i = displayQueue.size() - 1; i >= 0; i--) {
                if (displayQueue.get(i).taskId.equals(taskId)) displayQueue.remove(i);
            }
        }
        final OverlayView view = overlay;
        if (view == null) return;
        view.post(new Runnable() {
            @Override public void run() {
                view.cancel(taskId);
                synchronized (displayQueue) {
                    if (displayingTaskId.equals(taskId)) displayingTaskId = "";
                }
                processDisplayQueue();
            }
        });
    }

    private void removeIdleOverlay() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            if (mainHandler != null) {
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        removeIdleOverlay();
                    }
                });
            }
            return;
        }
        synchronized (displayQueue) {
            if (displayingTaskId.length() > 0 || !displayQueue.isEmpty()) return;
        }
        if (overlay != null && overlay.isDisplaying()) return;
        if (overlay == null) return;
        try { windowManager.removeView(overlay); } catch (Exception ignored) {}
        overlay = null;
        LogStore.append(this, "已清理空闲悬浮窗");
    }

    private void startAsForeground() {
        Notification notification = notification();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(7, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(7, notification);
            }
        } catch (Throwable first) {
            // 个别固件仍可能拒绝带类型的调用：退回不带类型的写法再试一次。
            // 两次都失败时只记录，不让服务在 onCreate 里直接崩成重启循环。
            try {
                startForeground(7, notification);
            } catch (Throwable second) {
                Log.w(TAG, "前台服务启动失败", second);
                LogStore.append(this, "前台服务启动失败：" + second.getClass().getSimpleName());
            }
        }
    }

    private void registerNetworkCallback() {
        if (Build.VERSION.SDK_INT < 24) return;
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                // 网络恢复时不要等下一次 5 秒重连循环；这里只打断旧 socket，交给 connectLoop 立刻重连。
                if (ws == null) {
                    startClient();
                } else if (!ws.isOpen()) {
                    ws.reconnect();
                }
            }

            @Override public void onLost(Network network) {
                // Wi-Fi/有线切换时旧 socket 往往仍然“看起来打开”，必须主动关掉，
                // 否则 send() 会一直写进一个已经失效的链路。
                if (ws != null) ws.reconnect();
            }
        };
        try {
            cm.registerDefaultNetworkCallback(networkCallback);
        } catch (Throwable t) {
            networkCallback = null;
            Log.w(TAG, "网络回调注册失败", t);
        }
    }

    private void unregisterNetworkCallback() {
        if (networkCallback == null) return;
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            try { cm.unregisterNetworkCallback(networkCallback); } catch (Throwable ignored) {}
        }
        networkCallback = null;
    }

    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel("led_service", getString(R.string.app_name),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            channel.setSound(null, null);
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            manager.createNotificationChannel(channel);
            return new Notification.Builder(this, "led_service")
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText(getString(R.string.client_running))
                    .setSmallIcon(R.drawable.ic_stat_led)
                    .setOngoing(true)
                    .setContentIntent(pi)
                    .build();
        }
        return new Notification.Builder(this)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.client_running))
                .setSmallIcon(R.drawable.ic_stat_led)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) return getResources().getDimensionPixelSize(id);
        return dp(24);
    }

    private int overlayHeight() {
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        int shortSide = Math.min(metrics.widthPixels, metrics.heightPixels);
        int height = (int) (shortSide * 0.12f);
        return Math.max(dp(72), Math.min(height, dp(180)));
    }
}
