package com.lmq8267.ledclient;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Build;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

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
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        startClient();
    }

    @Override
    public void onDestroy() {
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
                org.json.JSONObject hello = new org.json.JSONObject();
                try {
                    hello.put("type", "hello");
                    hello.put("clientId", Prefs.clientId(LedAccessibilityService.this));
                    hello.put("name", Build.MANUFACTURER + " " + Build.MODEL);
                } catch (Exception ignored) {}
                clientRef[0].send(hello);
            }

            @Override public void onText(String text) {
                LogStore.append(LedAccessibilityService.this, getString(R.string.log_message, text));
                try {
                    org.json.JSONObject json = new org.json.JSONObject(text);
                    String type = json.optString("type");
                    if ("message".equals(type)) {
                        receiveMessage(LedMessage.fromJson(json));
                    }
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
        if (overlay == null) {
            overlay = new OverlayView(this);
            int type = Build.VERSION.SDK_INT >= 26
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    (int) (getResources().getDisplayMetrics().heightPixels * 0.12f),
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    android.graphics.PixelFormat.TRANSLUCENT);
            lp.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
            try {
                windowManager.addView(overlay, lp);
            } catch (Exception e) {
                LogStore.append(this, "Accessibility overlay failed: " + e.getMessage());
                return;
            }
        }
        overlay.show(message, new OverlayView.Listener() {
            @Override public void onFinished(String taskId) {
                // 显示完成
            }
        });
    }
}
