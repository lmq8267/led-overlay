package com.lmq8267.ledclient;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

final class OverlayView extends View {
    interface Listener {
        void onFinished(String taskId);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LedMessage message;
    private Listener listener;
    private float x;
    private long lastFrameAt;
    private int completedLoops;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (message == null) return;
            long now = System.currentTimeMillis();
            float elapsedSeconds = Math.max(0.001f, (now - lastFrameAt) / 1000f);
            lastFrameAt = now;
            x -= message.speed * getResources().getDisplayMetrics().density * elapsedSeconds;
            float textWidth = paint.measureText(message.text);
            if (x <= -textWidth) {
                completedLoops++;
                if (completedLoops >= message.loops) {
                    String id = message.taskId;
                    message = null;
                    setVisibility(INVISIBLE);
                    invalidate();
                    if (listener != null) listener.onFinished(id);
                    return;
                }
                x = getWidth();
            }
            invalidate();
            handler.postDelayed(this, 16);
        }
    };

    OverlayView(Context context) {
        super(context);
        // CLEAR 模式在硬件加速画布上不受支持，需使用软件渲染层
        if (Build.VERSION.SDK_INT >= 28) {
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }
    }

    void show(LedMessage message, Listener listener) {
        handler.removeCallbacks(tick);
        this.message = message;
        this.listener = listener;
        paint.setColor(message.foreground);
        int h = getHeight() > 0 ? getHeight() : (int) (72 * getResources().getDisplayMetrics().density);
        int w = getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels;
        paint.setTextSize(Math.max(30, h * 0.62f));
        x = w;
        completedLoops = 0;
        lastFrameAt = System.currentTimeMillis();
        setVisibility(VISIBLE);
        bringToFront();
        handler.post(tick);
        invalidate();
    }

    boolean cancel(String taskId) {
        if (message == null || !message.taskId.equals(taskId)) return false;
        message = null;
        handler.removeCallbacks(tick);
        setVisibility(INVISIBLE);
        invalidate();
        return true;
    }

    boolean isDisplaying() {
        return message != null;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (message == null) {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            return;
        }
        canvas.drawColor(message.background);
        Paint.FontMetrics fm = paint.getFontMetrics();
        float y = getHeight() / 2f - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(message.text, x, y, paint);
    }
}
