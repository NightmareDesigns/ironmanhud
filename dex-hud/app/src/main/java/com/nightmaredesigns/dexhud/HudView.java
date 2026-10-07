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
import java.util.ArrayList;
import java.util.List;

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
    private int customAccent = Color.rgb(50, 220, 255);
    private int brightness = 100;
    private boolean showClock = true, showBattery = true;
    private boolean messagesEnabled;
    private final ArrayList<String> messages = new ArrayList<>();
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

    void setAppearance(int accent, int intensity, boolean clockVisible, boolean batteryVisible) {
        customAccent = accent;
        brightness = Math.max(20, Math.min(100, intensity));
        showClock = clockVisible;
        showBattery = batteryVisible;
        invalidate();
    }

    void setMessages(boolean enabled, List<String> feed) {
        messagesEnabled = enabled;
        messages.clear();
        messages.addAll(feed);
        setContentDescription("Animated reactor HUD; clock, battery and Jessica status"
                + (enabled ? ". Live messages: " + (messages.isEmpty() ? "waiting" : String.join(". ", messages)) : ""));
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float fullWidth = getWidth(), h = getHeight();
        float w = messagesEnabled ? fullWidth * .65f : fullWidth;
        if (w <= 0 || h <= 0) return;
        int color = matrix ? Color.rgb(80, 255, 120) : customAccent;
        int accent = Color.rgb(Color.red(color) * brightness / 100,
                Color.green(color) * brightness / 100, Color.blue(color) * brightness / 100);
        float unit = Math.min(w / 720f, h / 440f);
        float pad = 20 * unit;
        float phase = animated ? (SystemClock.uptimeMillis() % 12000) * .03f : 0;
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(w / 2, h / 2, Math.max(w, h),
                new int[]{Color.argb(22, Color.red(accent), Color.green(accent), Color.blue(accent)),
                        Color.BLACK}, null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, fullWidth, h, paint);
        paint.setShader(null);
        paint.setColor(accent);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1, unit));
        paint.setAlpha(45);
        for (float y = pad; y < h; y += 24 * unit) canvas.drawLine(pad, y, w - pad, y, paint);
        paint.setAlpha(18);
        for (float x = pad; x < w; x += 24 * unit) canvas.drawLine(x, pad, x, h - pad, paint);
        if (animated) {
            paint.setAlpha(40);
            float scan = pad + (h - 2 * pad) * phase / 360f;
            canvas.drawLine(pad, scan, w - pad, scan, paint);
        }
        paint.setAlpha(80);
        canvas.drawRect(pad, pad, w - pad, h - pad, paint);
        paint.setAlpha(255);
        paint.setStrokeWidth(3 * unit);
        float corner = 24 * unit;
        for (int i = 0; i < 4; i++) {
            float x = (i & 1) == 0 ? pad : w - pad;
            float y = i < 2 ? pad : h - pad;
            canvas.drawLine(x, y, x + ((i & 1) == 0 ? corner : -corner), y, paint);
            canvas.drawLine(x, y, x, y + (i < 2 ? corner : -corner), paint);
        }

        float radius = Math.min(w * .20f, h * .26f);
        float cx = w / 2, cy = h * .46f;
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(cx, cy, radius * 1.4f,
                new int[]{Color.argb(50, Color.red(accent), Color.green(accent), Color.blue(accent)),
                        Color.TRANSPARENT}, null, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, radius * 1.4f, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(unit);
        paint.setAlpha(120);
        for (int i = 0; i < 72; i++) {
            double angle = Math.toRadians(i * 5 + phase * .15f);
            float inner = radius * (i % 6 == 0 ? 1.08f : 1.14f);
            float outer = radius * 1.2f;
            canvas.drawLine(cx + (float) Math.cos(angle) * inner, cy + (float) Math.sin(angle) * inner,
                    cx + (float) Math.cos(angle) * outer, cy + (float) Math.sin(angle) * outer, paint);
        }
        paint.setAlpha(255);
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
        paint.setStyle(Paint.Style.FILL);
        float pulse = animated ? .85f + .15f * (float) Math.sin(Math.toRadians(phase * 4)) : 1;
        paint.setShader(new RadialGradient(cx, cy, Math.max(1, radius * .3f),
                new int[]{Color.argb((int) (210 * pulse), Color.red(accent), Color.green(accent), Color.blue(accent)),
                        Color.TRANSPARENT}, null, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, radius * .3f, paint);
        paint.setShader(null);
        text(canvas, "J / CORE", cx - radius * .34f, cy + radius * .64f, 11 * unit, accent);
        text(canvas, animated ? "FX ONLINE" : "FX PAUSED", pad * 2, h * .68f, 11 * unit, accent);
        for (int i = 0; i < 12; i++) {
            float bar = (8 + (i * 7 % 19)) * unit;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(accent);
            paint.setAlpha(i < 9 ? 150 : 45);
            canvas.drawRect(w - pad * 2 - (12 - i) * 7 * unit, h * .68f - bar,
                    w - pad * 2 - (12 - i) * 7 * unit + 3 * unit, h * .68f, paint);
        }
        paint.setAlpha(255);

        Date now = new Date();
        if (showClock) {
            text(canvas, clock.format(now), pad * 2, pad * 3, 26 * unit, accent);
            text(canvas, date.format(now).toUpperCase(Locale.getDefault()), pad * 2,
                    pad * 4.3f, 13 * unit, accent);
        }
        String power = battery < 0 ? "POWER --" : "POWER " + battery + "%";
        if (showBattery) {
            text(canvas, power, w - pad * 10, pad * 3, 17 * unit, accent);
            text(canvas, charging ? "CHARGING" : "ON BATTERY", w - pad * 10,
                    pad * 4.3f, 12 * unit, accent);
        }
        text(canvas, "REACTOR / VISUAL SIMULATION", pad * 2, h * .76f, 13 * unit, accent);
        text(canvas, assistantStatus, pad * 2, h * .83f, 16 * unit, accent);
        paint.setTextSize(13 * unit);
        String compact = reply.replace('\n', ' ');
        int count = paint.breakText(compact, true, w - pad * 4, null);
        if (count < compact.length()) compact = compact.substring(0, Math.max(0, count - 3)) + "...";
        text(canvas, compact, pad * 2, h * .9f, 13 * unit, accent);
        if (messagesEnabled) drawMessages(canvas, w, fullWidth, h, accent);
        if (running) postInvalidateDelayed(animated ? 33 : 1000);
    }

    private void drawMessages(Canvas canvas, float left, float right, float height, int accent) {
        float density = getResources().getDisplayMetrics().density;
        float padding = Math.min(12 * density, (right - left) * .08f);
        float size = Math.max(8, Math.min(14 * density, (right - left) / 18));
        canvas.save();
        canvas.clipRect(left, 0, right, height);
        paint.setShader(null);
        paint.setAlpha(255);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(18, Color.red(accent), Color.green(accent), Color.blue(accent)));
        canvas.drawRect(left + padding / 2, padding, right - padding / 2, height - padding, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(accent);
        paint.setStrokeWidth(1);
        canvas.drawLine(left + padding / 2, padding, left + padding / 2, height - padding, paint);
        float y = padding + size;
        text(canvas, "LIVE MESSAGES", left + padding, y, size, accent);
        y += size * 1.7f;
        text(canvas, "LOCAL / NOT SENT TO AI", left + padding, y, size * .68f, accent);
        y += size * 1.8f;
        if (messages.isEmpty()) {
            text(canvas, "Waiting for messages...", left + padding, y, size * .75f, accent);
            y += size * 1.4f;
            text(canvas, "Enable notification access", left + padding, y, size * .65f, accent);
        }
        for (int i = messages.size() - 1; i >= 0 && y < height - padding; i--) {
            paint.setTextSize(size * .8f);
            for (String paragraph : messages.get(i).split("\n")) {
                String remaining = paragraph;
                int lines = 0;
                while (!remaining.isEmpty() && lines++ < 3 && y < height - padding) {
                    paint.setTextSize(size * .8f);
                    int count = paint.breakText(remaining, true, right - left - 2 * padding, null);
                    if (count <= 0) break;
                    String line = remaining.substring(0, count);
                    remaining = remaining.substring(count);
                    if (lines == 3 && !remaining.isEmpty()) line += "…";
                    text(canvas, line, left + padding, y, size * .8f, accent);
                    y += size * 1.15f;
                }
            }
            y += size * .6f;
            paint.setAlpha(70);
            canvas.drawLine(left + padding, y, right - padding, y, paint);
            paint.setAlpha(255);
            y += size * 1.3f;
        }
        canvas.restore();
    }

    private void text(Canvas canvas, String value, float x, float y, float size, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setTextSize(size);
        canvas.drawText(value, x, y, paint);
        paint.setStyle(Paint.Style.STROKE);
    }
}
