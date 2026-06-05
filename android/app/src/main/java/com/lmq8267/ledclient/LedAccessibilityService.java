package com.lmq8267.ledclient;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;

/**
 * 无障碍服务：绕过小米电视 AppsRelaunchController 的前台服务拦截
 *
 * 小米电视的 AppsRelaunchController 会阻止非白名单应用创建前台服务，
 * 但无障碍服务属于系统级组件，不会被拦截。
 * - 使用 getOverlayWindowManager() 创建悬浮窗（无需 SYSTEM_ALERT_WINDOW）
 * - 后台保活能力比普通 Service 更强
 * - 通过 ADB 开启：adb shell settings put secure enabled_accessibility_services
 *   com.lmq8267.ledclient/.LedAccessibilityService
 */
public class LedAccessibilityService extends AccessibilityService {
    private WindowManager windowManager;
    private OverlayView overlay;
    private WsClient ws;
    private Thread thread;
    private String currentServer = "";
    private Handler mainHandler;
    private String lastHttpAckError = "";
    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
        @Override public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
            if (Prefs.KEY_RUNNING.equals(key) || Prefs.KEY_SERVER.equals(key)) {
                if (Prefs.running(LedAccessibilityService.this)) {
                    startClient();
                    if (mainHandler != null) {
                        mainHandler.removeCallbacks(pendingAckFlusher);
                        mainHandler.postDelayed(pendingAckFlusher, 5000);
                    }
                } else {
                    if (mainHandler != null) mainHandler.removeCallbacks(pendingAckFlusher);
                    stopClient();
                }
            }
        }
    };
    private final ArrayList<String> completedAckRetries = new ArrayList<String>();
    private final Runnable pendingAckFlusher = new Runnable() {
        @Override public void run() {
            flushPendingCompletedAcks();
            if (Prefs.running(LedAccessibilityService.this) && mainHandler != null) {
                mainHandler.postDelayed(this, 5000);
            }
        }
    };

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 不需要处理具体事件，只用于保活和悬浮窗
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        mainHandler = new Handler(Looper.getMainLooper());
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                java.lang.reflect.Method m = AccessibilityService.class.getMethod("getOverlayWindowManager");
                windowManager = (WindowManager) m.invoke(this);
            } catch (Exception e) {
                windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            }
        } else {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        }
        Prefs.store(this).registerOnSharedPreferenceChangeListener(prefsListener);
        if (Prefs.running(this)) {
            startClient();
            mainHandler.postDelayed(pendingAckFlusher, 5000);
        }
    }

    @Override
    public void onDestroy() {
        Prefs.store(this).unregisterOnSharedPreferenceChangeListener(prefsListener);
        if (mainHandler != null) mainHandler.removeCallbacks(pendingAckFlusher);
        stopClient();
        super.onDestroy();
    }

    private void startClient() {
        final String server = Prefs.server(this);
        if (server.trim().length() == 0) {
            Prefs.setConnectionState(this, Prefs.CONNECTION_DISCONNECTED);
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
        Prefs.setConnectionState(this, Prefs.CONNECTION_CONNECTING);
        LogStore.append(this, getString(R.string.log_connecting, target));

        final WsClient[] clientRef = new WsClient[1];
        final WsClient client = new WsClient(server, new WsClient.Listener() {
            @Override public void onOpen() {
                Prefs.setConnectionState(LedAccessibilityService.this, Prefs.CONNECTION_CONNECTED);
                LogStore.append(LedAccessibilityService.this, getString(R.string.log_connected, target));
                JSONObject hello = new JSONObject();
                try {
                    hello.put("type", "hello");
                    hello.put("clientId", Prefs.clientId(LedAccessibilityService.this));
                    hello.put("name", Build.MANUFACTURER + " " + Build.MODEL);
                } catch (Exception ignored) {}
                clientRef[0].send(hello);
                flushPendingCompletedAcks();
            }

            @Override public void onText(String text) {
                Prefs.setConnectionState(LedAccessibilityService.this, Prefs.CONNECTION_CONNECTED);
                LogStore.append(LedAccessibilityService.this, getString(R.string.log_message, text));
                try {
                    JSONObject json = new JSONObject(text);
                    String type = json.optString("type");
                    if ("message".equals(type)) {
                        String taskId = json.optString("taskId", "");
                        try {
                            receiveMessage(LedMessage.fromJson(json));
                        } catch (Exception e) {
                            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                            LogStore.append(LedAccessibilityService.this,
                                    getString(R.string.log_invalid_message, reason));
                            if (taskId.length() > 0) sendAckReliable(taskId, "failed", "客户端解析消息失败：" + reason);
                        }
                    }
                    else if ("cancel".equals(type)) cancel(json.optString("taskId"));
                } catch (Exception e) {
                    LogStore.append(LedAccessibilityService.this,
                            getString(R.string.log_invalid_message, e.getMessage()));
                }
            }

            @Override public void onClosed(Exception error) {
                Prefs.setConnectionState(LedAccessibilityService.this,
                        Prefs.running(LedAccessibilityService.this)
                                ? Prefs.CONNECTION_CONNECTING
                                : Prefs.CONNECTION_DISCONNECTED);
                String reason = error == null || error.getMessage() == null
                        ? "connection closed" : error.getMessage();
                LogStore.append(LedAccessibilityService.this,
                        getString(R.string.log_disconnected, reason));
            }
        });
        clientRef[0] = client;
        ws = client;
        thread = new Thread(new Runnable() {
            @Override public void run() {
                client.connectLoop();
            }
        }, "led-ws-a11y");
        thread.start();
    }

    private void stopClient() {
        currentServer = "";
        if (ws != null) {
            ws.stop();
            ws = null;
        }
        thread = null;
        if (overlay != null) {
            try { windowManager.removeView(overlay); } catch (Exception ignored) {}
            overlay = null;
        }
        Prefs.setConnectionState(this,
                Prefs.running(this) ? Prefs.CONNECTION_CONNECTING : Prefs.CONNECTION_DISCONNECTED);
    }

    private void receiveMessage(final LedMessage message) {
        // 收到消息立即回复"received"，服务器显示"待接收"
        sendAckReliable(message.taskId, "received", "客户端已收到消息");
        Prefs.markTaskSeen(this, message.taskId);
        MessageHistory.append(this, message);

        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (overlay == null) {
                    overlay = new OverlayView(LedAccessibilityService.this);
                    int windowType = Build.VERSION.SDK_INT >= 22
                            ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE;
                    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                            WindowManager.LayoutParams.MATCH_PARENT,
                            (int) (getResources().getDisplayMetrics().heightPixels * 0.12f),
                            windowType,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                            android.graphics.PixelFormat.TRANSLUCENT);
                    lp.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
                    try {
                        windowManager.addView(overlay, lp);
                    } catch (Exception e) {
                        overlay = null;
                        LogStore.append(LedAccessibilityService.this,
                                "Accessibility overlay failed: " + e.getMessage());
                        sendAckReliable(message.taskId, "failed", "悬浮窗创建失败：" + e.getMessage());
                        return;
                    }
                }
                // 开始显示 → 回复"displaying"，服务器显示"待显示"
                sendAckReliable(message.taskId, "displaying", "正在通过无障碍服务显示");
                overlay.show(message, new OverlayView.Listener() {
                    @Override public void onFinished(String taskId) {
                        Prefs.markTaskDone(LedAccessibilityService.this, taskId);
                        // 显示完成 → 回复"completed"，服务器显示"已完成"
                        sendAckReliable(taskId, "completed", "无障碍显示完成");
                        removeOverlay();
                    }
                });
            }
        });
    }

    private void cancel(final String taskId) {
        if (overlay == null) return;
        overlay.post(new Runnable() {
            @Override public void run() {
                if (overlay != null && overlay.cancel(taskId)) removeOverlay();
            }
        });
    }

    private void removeOverlay() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            if (mainHandler != null) {
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        removeOverlay();
                    }
                });
            }
            return;
        }
        if (overlay == null) return;
        try { windowManager.removeView(overlay); } catch (Exception ignored) {}
        overlay = null;
    }

    // ═══════════════════════════════════════════════════════
    //  ACK 逻辑（与 LedService 完全一致）
    // ═══════════════════════════════════════════════════════

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
            sendAck(taskId, status, detail);
            sendCompletedAckHttp(taskId, detail);
            scheduleCompletedAckRetry(taskId, detail);
            return;
        }
        Prefs.addPendingAck(this, taskId, status);
        sendAck(taskId, status, detail);
        sendAckHttpAsync(taskId, status, detail);
        scheduleAckRetry(taskId, status, detail);
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
        new Thread(new Runnable() {
            @Override public void run() {
                boolean ok = sendAckHttp(taskId, status, detail == null ? "" : detail);
                if (ok) {
                    Prefs.removePendingAck(LedAccessibilityService.this, taskId, status);
                    LogStore.append(LedAccessibilityService.this,
                            status + " ACK HTTP 状态已上报：" + taskId);
                } else {
                    LogStore.append(LedAccessibilityService.this,
                            status + " ACK HTTP 失败，等待补发：" + taskId
                            + (lastHttpAckError.length() == 0 ? "" : "，" + lastHttpAckError));
                }
            }
        }, "led-a11y-ack-http-" + status).start();
    }

    private void sendCompletedAckHttp(final String taskId, final String detail) {
        if (taskId == null || taskId.length() == 0) return;
        new Thread(new Runnable() {
            @Override public void run() {
                boolean ok = sendAckHttp(taskId, "completed",
                        detail == null ? "客户端显示完成，HTTP 兜底确认" : detail);
                if (ok) {
                    Prefs.removePendingCompletedAck(LedAccessibilityService.this, taskId);
                    Prefs.removePendingAck(LedAccessibilityService.this, taskId, "received");
                    Prefs.removePendingAck(LedAccessibilityService.this, taskId, "displaying");
                    LogStore.append(LedAccessibilityService.this,
                            "已完成 completed ACK HTTP 状态已上报：" + taskId);
                } else {
                    LogStore.append(LedAccessibilityService.this,
                            "已完成 completed ACK HTTP 失败，等待补发：" + taskId
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
                lastHttpAckError = urls[i] + " "
                        + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
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
}
