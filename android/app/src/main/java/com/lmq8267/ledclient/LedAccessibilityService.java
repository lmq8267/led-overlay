package com.lmq8267.ledclient;

import android.accessibilityservice.AccessibilityService;
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
        startClient();
        mainHandler.postDelayed(pendingAckFlusher, 5000);
    }

    @Override
    public void onDestroy() {
        if (mainHandler != null) mainHandler.removeCallbacks(pendingAckFlusher);
        if (ws != null) ws.stop();
        if (overlay != null) {
            try { windowManager.removeView(overlay); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    private void startClient() {
        final String server = Prefs.server(this);
        if (server.trim().length() == 0) return;

        final String target = WsClient.normalize(server);
        if (thread != null && thread.isAlive()) {
            if (server.equals(currentServer)) return;
            if (ws != null) ws.stop();
            thread = null;
        }
        currentServer = server;
        LogStore.append(this, getString(R.string.log_connecting, target));

        final WsClient[] clientRef = new WsClient[1];
        final WsClient client = new WsClient(server, new WsClient.Listener() {
            @Override public void onOpen() {
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

    private void receiveMessage(final LedMessage message) {
        // 收到消息立即回复"received"，服务器显示"待接收"
        sendAckReliable(message.taskId, "received", "客户端已收到消息");

        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (overlay == null) {
                    overlay = new OverlayView(LedAccessibilityService.this);
                    int windowType = Build.VERSION.SDK_INT >= 30
                            ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                            : WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
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
                    }
                });
            }
        });
    }

    private void cancel(final String taskId) {
        if (overlay == null) return;
        overlay.post(new Runnable() {
            @Override public void run() {
                overlay.cancel(taskId);
            }
        });
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
            sendCompletedAckHttp(taskId, detail);
            scheduleCompletedAckRetry(taskId, detail);
            return;
        }
        boolean sent = sendAck(taskId, status, detail);
        if (sent) {
            Prefs.removePendingAck(this, taskId, status);
        } else if ("displaying".equals(status)) {
            Prefs.removePendingAck(this, taskId, status);
        } else {
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
}
