package com.lmq8267.ledclient;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;

public class LedService extends Service {
    private WsClient ws;
    private Thread thread;
    private String currentServer = "";
    private volatile int connectionGeneration = 0;
    private WindowManager windowManager;
    private OverlayView overlay;
    private Handler mainHandler;
    private String lastOverlayError = "";
    private final ArrayList<LedMessage> displayQueue = new ArrayList<LedMessage>();
    private final ArrayList<String> completedAckRetries = new ArrayList<String>();
    private String displayingTaskId = "";
    private String lastHttpAckError = "";
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
        Prefs.store(this).edit().putBoolean(Prefs.KEY_RUNNING, true).apply();
        try {
            startForeground(7, notification());
        } catch (Exception ignored) {
            // 小米电视 AppsRelaunchController 会拦截 setServiceForeground
            // 忽略异常，作为一个普通后台服务运行
        }
        scheduleWatchdog();
        mainHandler.postDelayed(pendingAckFlusher, 5000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && Prefs.ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startClient();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (mainHandler != null) mainHandler.removeCallbacks(pendingAckFlusher);
        cancelWatchdog();
        connectionGeneration++;
        if (ws != null) ws.stop();
        if (overlay != null) {
            try { windowManager.removeView(overlay); } catch (Exception ignored) {}
        }
        Prefs.store(this).edit()
                .putBoolean(Prefs.KEY_RUNNING, false)
                .putString(Prefs.KEY_CONNECTION_STATE, Prefs.CONNECTION_DISCONNECTED)
                .apply();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startClient() {
        final String server = Prefs.server(this);
        if (server.trim().length() == 0) return;
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)
                && !getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
            LogStore.append(this, getString(R.string.log_overlay_permission_missing));
            Prefs.store(this).edit().putBoolean(Prefs.KEY_RUNNING, false).apply();
            stopSelf();
            return;
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
                Prefs.setConnectionState(LedService.this, Prefs.CONNECTION_CONNECTED);
                LogStore.append(LedService.this, getString(R.string.log_connected, target));
                JSONObject hello = new JSONObject();
                try {
                    hello.put("type", "hello");
                    hello.put("clientId", Prefs.clientId(LedService.this));
                    hello.put("name", Build.MANUFACTURER + " " + Build.MODEL);
                } catch (Exception ignored) {}
                clientRef[0].send(hello);
                flushPendingCompletedAcks();
            }

            @Override public void onText(String text) {
                if (!isActiveConnection(generation)) return;
                Prefs.setConnectionState(LedService.this, Prefs.CONNECTION_CONNECTED);
                LogStore.append(LedService.this, getString(R.string.log_message, text));
                try {
                    JSONObject json = new JSONObject(text);
                    String type = json.optString("type");
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
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
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
        synchronized (displayQueue) {
            if (displayingTaskId.length() > 0 || displayQueue.isEmpty()) return;
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
    }

    private void showWhenOverlayReady(final LedMessage message, final int attempts) {
        if (overlay == null) {
            sendAckReliable(message.taskId, "failed", "悬浮窗对象不存在，无法显示");
            synchronized (displayQueue) {
                if (displayingTaskId.equals(message.taskId)) displayingTaskId = "";
            }
            processDisplayQueue();
            return;
        }
        overlay.post(new Runnable() {
            @Override public void run() {
                if (overlay.getWidth() <= 0 || overlay.getHeight() <= 0) {
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
                Prefs.markTaskSeen(LedService.this, message.taskId);
                MessageHistory.append(LedService.this, message);
                sendAckReliable(message.taskId, "displaying", "客户端正在按本地队列显示该任务");
                overlay.show(message, new OverlayView.Listener() {
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
        if ("completed".equals(status)) {
            Prefs.addPendingCompletedAck(this, taskId);
            sendCompletedAckHttp(taskId, detail);
            scheduleCompletedAckRetry(taskId, detail);
            return;
        }
        boolean sent = sendAck(taskId, status, detail);
        if (sent) {
            Prefs.removePendingAck(this, taskId, status);
        } else {
            // WS 发送失败 → HTTP 兜底 + 1s/3s 重试（与 completed 同级）
            sendAckHttp(taskId, status, detail);
            scheduleAckRetry(taskId, status, detail);
            Prefs.addPendingAck(this, taskId, status);
            LogStore.append(this, status + " ACK 发送失败，等待补发：" + taskId);
        }
    }

    private void scheduleCompletedAckRetry(final String taskId, final String detail) {
        if (taskId == null || taskId.length() == 0 || mainHandler == null) return;
        synchronized (completedAckRetries) {
            if (completedAckRetries.contains(taskId)) return;
            completedAckRetries.add(taskId);
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
                    completedAckRetries.remove(taskId);
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
        synchronized (completedAckRetries) {
            if (completedAckRetries.contains(taskId)) return;
            completedAckRetries.add(taskId);
        }
        mainHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (shouldRetryAck(taskId, status)) {
                    sendAckHttp(taskId, status, detail);
                }
            }
        }, 1000);
        mainHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (shouldRetryAck(taskId, status)) {
                    sendAckHttp(taskId, status, detail);
                }
                synchronized (completedAckRetries) {
                    completedAckRetries.remove(taskId);
                }
            }
        }, 3000);
    }

    private boolean shouldRetryAck(String taskId, String status) {
        String pending = Prefs.pendingAcks(this);
        return pending.contains("|" + status + ":" + taskId + "|");
    }

    private void sendCompletedAckHttp(final String taskId, final String detail) {
        if (taskId == null || taskId.length() == 0) return;
        new Thread(new Runnable() {
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
        }, "led-ack-http").start();
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
                    if ("displaying".equals(status)) {
                        Prefs.removePendingAck(this, taskId, status);
                    } else {
                        sendAckReliable(taskId, status);
                    }
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
        if (overlay == null) return;
        overlay.post(new Runnable() {
            @Override public void run() {
                overlay.cancel(taskId);
                synchronized (displayQueue) {
                    if (displayingTaskId.equals(taskId)) displayingTaskId = "";
                }
                processDisplayQueue();
            }
        });
    }

    // ── 看门狗：MIUI 等系统会忽略 START_STICKY 杀死后台服务 ──────
    private PendingIntent watchdogPendingIntent() {
        Intent intent = new Intent(this, WatchdogReceiver.class);
        return PendingIntent.getBroadcast(this, 0, intent,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT : 0);
    }

    private void scheduleWatchdog() {
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (am == null) return;
        cancelWatchdog(); // 先取消已有闹钟，防止重复
        PendingIntent pi = watchdogPendingIntent();
        long interval = 3 * 60 * 1000; // 每 3 分钟检查一次
        long trigger = System.currentTimeMillis() + interval;
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, trigger, interval, pi);
    }

    private void cancelWatchdog() {
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (am == null) return;
        am.cancel(watchdogPendingIntent());
    }

    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel("led", getString(R.string.app_name),
                    NotificationManager.IMPORTANCE_MIN);
            channel.setShowBadge(false);
            channel.setSound(null, null);
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            manager.createNotificationChannel(channel);
            return new Notification.Builder(this, "led")
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
