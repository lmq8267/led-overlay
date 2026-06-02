package com.lmq8267.ledclient;

import org.json.JSONObject;

final class LedMessage {
    final String taskId;
    final String text;
    final int background;
    final int foreground;
    final int speed;
    final int loops;

    private LedMessage(String taskId, String text, int background, int foreground, int speed, int loops) {
        this.taskId = taskId;
        this.text = text;
        this.background = background;
        this.foreground = foreground;
        this.speed = speed;
        this.loops = loops;
    }

    static LedMessage fromJson(JSONObject object) {
        String bg = object.optString("background", "#000000");
        String fg = object.optString("foreground", "#ff0000");
        return new LedMessage(
                object.optString("taskId", String.valueOf(System.currentTimeMillis())),
                object.optString("text", ""),
                parseColor("background", bg),
                parseColor("foreground", fg),
                Math.max(20, object.optInt("speed", 90)),
                Math.max(1, object.optInt("loops", object.optInt("duration", 1)))
        );
    }

    private static int parseColor(String field, String value) {
        try {
            return android.graphics.Color.parseColor(value);
        } catch (Exception e) {
            throw new IllegalArgumentException(field + " 颜色无效：" + value);
        }
    }
}
