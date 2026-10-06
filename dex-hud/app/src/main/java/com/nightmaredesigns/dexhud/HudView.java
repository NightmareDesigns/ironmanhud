package com.nightmaredesigns.dexhud;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class HudView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ring = new RectF();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private final SimpleDateFormat date = new SimpleDateFormat("EEE, dd MMM", Locale.getDefault());
    private int battery = -1;
    private boolean charging;
    private boolean animated = true;
    private boolean matrix;
    private boolean running;
    private String assistantStatus = "JESSICA STANDBY";
    private String reply = "Open Jessica to configure free-model chat.";

    HudView(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);
        setContentDescription("Animated reactor HUD; clock, battery and Jessica status");
        paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
    }

    void setBattery(int percent, boolean plugged) {
        battery = percent;
        charging = plugged;
        invalidate();
    }

    void setAssistant(String status, String message) {
        assistantStatus = status;
        reply = message;
        invalidate();
    }

    void setRunning(boolean value) {
        running = value;
        invalidate();
    }

    void setModes(boolean motion, boolean green) {
        animated = motion;
        matrix = green;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        int accent = matrix ? Color.rgb(80, 255, 120) : Color.rgb(50, 220, 255);
        float unit = Math.min(w / 720f, h / 440f);
        float pad = 20 * unit;
        paint.setColor(accent);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1, unit));
        paint.setAlpha(45);
        for (float y = pad; y < h; y += 24 * unit) canvas.drawLine(pad, y, w - pad, y, paint);
        canvas.drawRect(pad, pad, w - pad, h - pad, paint);
        paint.setAlpha(255);

        float radius = Math.min(w * .20f, h * .26f);
        float cx = w / 2, cy = h * .46f;
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(cx, cy, radius * 1.4f,
                new int[]{Color.argb(50, Color.red(accent), Color.green(accent), Color.blue(accent)),
                        Color.TRANSPARENT}, null, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, radius * 1.4f, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        float phase = animated ? (SystemClock.uptimeMillis() % 12000) * .03f : 0;
        for (int i = 0; i < 3; i++) {
            float r = radius * (1 - i * .19f);
            ring.set(cx - r, cy - r, cx + r, cy + r);
            paint.setStrokeWidth((i == 1 ? 5 : 2) * unit);
            canvas.drawArc(ring, phase * (i == 1 ? -1 : 1) + i * 90, 255, false, paint);
        }
        paint.setStrokeWidth(2 * unit);
        canvas.drawCircle(cx, cy, radius * .36f, paint);
        canvas.drawLine(cx - radius * .25f, cy, cx + radius * .25f, cy, paint);
        canvas.drawLine(cx, cy - radius * .25f, cx, cy + radius * .25f, paint);

        Date now = new Date();
        text(canvas, clock.format(now), pad * 2, pad * 3, 26 * unit, accent);
        text(canvas, date.format(now).toUpperCase(Locale.getDefault()), pad * 2,
                pad * 4.3f, 13 * unit, accent);
        String power = battery < 0 ? "POWER --" : "POWER " + battery + "%";
        text(canvas, power, w - pad * 10, pad * 3, 17 * unit, accent);
        text(canvas, charging ? "CHARGING" : "ON BATTERY", w - pad * 10,
                pad * 4.3f, 12 * unit, accent);
        text(canvas, "REACTOR / VISUAL SIMULATION", pad * 2, h * .76f, 13 * unit, accent);
        text(canvas, assistantStatus, pad * 2, h * .83f, 16 * unit, accent);
        paint.setTextSize(13 * unit);
        String compact = reply.replace('\n', ' ');
        int count = paint.breakText(compact, true, w - pad * 4, null);
        if (count < compact.length()) compact = compact.substring(0, Math.max(0, count - 3)) + "...";
        text(canvas, compact, pad * 2, h * .9f, 13 * unit, accent);
        if (running) postInvalidateDelayed(animated ? 33 : 1000);
    }

    private void text(Canvas canvas, String value, float x, float y, float size, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setTextSize(size);
        canvas.drawText(value, x, y, paint);
        paint.setStyle(Paint.Style.STROKE);
    }
}
