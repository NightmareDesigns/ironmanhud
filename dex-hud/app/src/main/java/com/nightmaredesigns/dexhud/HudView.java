package com.nightmaredesigns.dexhud;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class HudView extends View {
    static final int STYLE_MINIMAL = 0;
    static final int STYLE_REACTOR = 1;
    static final int STYLE_TERMINAL = 2;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final HudState hudState;
    private final RectF ring = new RectF();
    private final TextPaint paragraphPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private final SimpleDateFormat date = new SimpleDateFormat("EEE, dd MMM", Locale.getDefault());
    private int battery = -1;
    private int brightness = 100;
    private boolean charging;
    private boolean animated = true;
    private boolean matrix;
    private boolean running;
    private int style = STYLE_MINIMAL;
    private long effectElapsed;
    private long effectStarted = SystemClock.uptimeMillis();
    private String assistantStatus = "JESSICA STANDBY";
    private String reply = "Open Jessica to launch Gemini. Start Live inside the Gemini app.";
    private boolean clockEnabled = true, batteryEnabled = true, reactorEnabled = true;
    private boolean assistantEnabled = true, notificationsEnabled, dotsEnabled = true;
    private boolean capture, speaking;
    private float audioLevel;
    private String audioStatus = "PLAYBACK CAPTURE OFF", notifications = "";

    HudView(Context context) {
        super(context);
        hudState = HudState.get(context);
        setBackgroundColor(Color.BLACK);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setOnClickListener(view -> requestFocus());
        setContentDescription("Black-background HUD; configurable clock, battery, audio dots and reply panels");
        paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
        paragraphPaint.setTypeface(paint.getTypeface());
    }

    private boolean shortcutAllowed(int key, KeyEvent event) {
        return hudState.enabled("buttonShortcuts", false) && isFocused() && hasWindowFocus()
                && event.hasNoModifiers() && (key == KeyEvent.KEYCODE_DPAD_LEFT
                || key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_SPACE
                || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_DPAD_CENTER);
    }

    @Override public boolean onKeyDown(int key, KeyEvent event) {
        if (!shortcutAllowed(key, event)) return super.onKeyDown(key, event);
        if (event.getRepeatCount() == 0) {
            if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                int selected = hudState.settings.getInt("style", STYLE_MINIMAL);
                if (selected < STYLE_MINIMAL || selected > STYLE_TERMINAL) selected = STYLE_MINIMAL;
                selected = (selected + (key == KeyEvent.KEYCODE_DPAD_LEFT ? 2 : 1)) % 3;
                hudState.settings.edit().putInt("style", selected).apply();
            } else {
                hudState.settings.edit().putBoolean("motion", !hudState.enabled("motion", true)).apply();
            }
            hudState.settingsChanged();
        }
        return true;
    }

    @Override public boolean onKeyUp(int key, KeyEvent event) {
        if (shortcutAllowed(key, event)) return true;
        return super.onKeyUp(key, event);
    }

    void setBattery(int percent, boolean plugged) {
        battery = percent;
        charging = plugged;
        invalidate();
    }

    void setBrightness(int percent) {
        brightness = Math.max(10, Math.min(100, percent));
        invalidate();
    }

    void setAssistant(String status, String message) {
        assistantStatus = status;
        reply = message;
        invalidate();
    }

    void setModules(boolean clockModule, boolean batteryModule, boolean reactorModule,
                    boolean assistantModule, boolean notificationModule, boolean dotModule) {
        clockEnabled = clockModule;
        batteryEnabled = batteryModule;
        reactorEnabled = reactorModule;
        assistantEnabled = assistantModule;
        notificationsEnabled = notificationModule;
        dotsEnabled = dotModule;
        invalidate();
    }

    void setNotifications(String value) {
        notifications = value;
        invalidate();
    }

    void setAudio(float level, boolean capturing, boolean ttsSpeaking, String status) {
        audioLevel = level;
        capture = capturing;
        speaking = ttsSpeaking;
        audioStatus = status;
        invalidate();
    }

    void setRunning(boolean value) {
        running = value;
        invalidate();
    }

    void setModes(boolean motion, boolean green, int selectedStyle) {
        long now = SystemClock.uptimeMillis();
        if (animated) effectElapsed += now - effectStarted;
        effectStarted = now;
        animated = motion;
        matrix = green;
        style = selectedStyle;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        canvas.drawColor(Color.BLACK);
        int contentLayer = brightness < 100
                ? canvas.saveLayerAlpha(0, 0, w, h, Math.round(brightness * 255 / 100f))
                : canvas.save();
        int accent = matrix ? Color.rgb(80, 255, 120) : Color.rgb(50, 220, 255);
        float unit = Math.min(w / 720f, h / 440f);
        float readableText = 14 * getResources().getDisplayMetrics().scaledDensity;
        float pad = 20 * unit;
        boolean wide = w > h * 1.15f;
        boolean hasPanel = assistantEnabled || notificationsEnabled;
        float visualWidth = hasPanel && wide ? w * .61f : w;
        float visualHeight = hasPanel && !wide ? h * .48f : h * .8f;
        paint.setColor(accent);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1, unit));
        paint.setAlpha(45);
        if (style != STYLE_TERMINAL) canvas.drawRect(pad, pad, w - pad, h - pad, paint);
        paint.setAlpha(255);

        long effectTime = effectElapsed + (animated ? SystemClock.uptimeMillis() - effectStarted : 0);
        if (matrix) drawMatrix(canvas, visualWidth, visualHeight, pad, unit, effectTime);
        if (style == STYLE_REACTOR && reactorEnabled) {
            float radius = Math.min(visualWidth * .20f, visualHeight * .26f);
            float cx = visualWidth / 2, cy = visualHeight * .52f;
            paint.setColor(accent);
            paint.setStyle(Paint.Style.STROKE);
            float phase = (effectTime % 12000) * .03f;
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
        } else if (style == STYLE_TERMINAL) {
            text(canvas, "> JESSICA HUD", pad * 2, visualHeight * .4f, 24 * unit, accent);
        }

        Date now = new Date();
        if (clockEnabled) {
            text(canvas, clock.format(now), pad * 2, pad * 3, 26 * unit, accent);
            text(canvas, date.format(now).toUpperCase(Locale.getDefault()), pad * 2,
                    pad * 4.3f, Math.max(13 * unit, readableText * .8f), accent);
        }
        if (batteryEnabled) {
            String power = battery < 0 ? "POWER --" : "POWER " + battery + "%";
            text(canvas, power, w - pad * 10, pad * 3, 17 * unit, accent);
            text(canvas, charging ? "CHARGING" : "ON BATTERY", w - pad * 10,
                    pad * 4.3f, Math.max(12 * unit, readableText * .8f), accent);
        }
        if (dotsEnabled) drawDots(canvas, visualWidth, visualHeight, pad, unit, accent, effectTime);
        if (hasPanel) {
            float left = wide ? w * .63f : pad * 2;
            float top = wide ? pad * 5.5f : h * .52f;
            float panelWidth = w - left - pad * 2;
            float panelHeight = h - top - pad * 2;
            if (assistantEnabled) {
                float height = notificationsEnabled ? panelHeight * .53f : panelHeight;
                panel(canvas, left, top, panelWidth, height, "AI / " + assistantStatus,
                        reply, Math.max(readableText, Math.min(16 * unit, panelWidth / 26)), accent);
                top += height + pad * .5f;
                panelHeight -= height + pad * .5f;
            }
            if (notificationsEnabled) panel(canvas, left, top, panelWidth, panelHeight,
                    "PUBLIC NOTIFICATIONS", notifications.isEmpty()
                            ? "No eligible public notifications. Private/secret messages and likely codes are hidden."
                            : notifications, Math.max(readableText, Math.min(16 * unit, panelWidth / 26)), accent);
        }
        canvas.restoreToCount(contentLayer);
        if (running) postInvalidateDelayed(capture || (speaking && dotsEnabled)
                || animated && (matrix || style == STYLE_REACTOR && reactorEnabled) ? 50 : 1000);
    }

    private void drawDots(Canvas canvas, float w, float h, float pad, float unit, int accent, long time) {
        float top = h * .67f, bottom = h * .9f;
        float spacingX = (w - pad * 4) / 28f, spacingY = (bottom - top) / 10f;
        float level = capture ? audioLevel : speaking ? .35f + .15f * (float) Math.sin(time / 170.0) : 0;
        paint.setStyle(Paint.Style.FILL);
        for (int column = 0; column < 28; column++) {
            for (int row = 0; row < 10; row++) {
                boolean lit = Math.abs(row - 4.5f) < level * 5;
                paint.setColor(accent);
                paint.setAlpha(lit ? 220 : 30);
                canvas.drawCircle(pad * 2 + (column + .5f) * spacingX,
                        top + (row + .5f) * spacingY, Math.max(.7f, Math.min(spacingX, spacingY) * .24f), paint);
            }
        }
        paint.setAlpha(255);
        String status = !capture && speaking ? "APP TTS / TIMING ONLY (NOT RMS)" : audioStatus;
        float textSize = Math.max(11 * unit, 12 * getResources().getDisplayMetrics().scaledDensity);
        paragraph(canvas, status, pad * 2, bottom + 8 * unit, w - pad * 4,
                Math.max(textSize * 2.6f, h * .09f), textSize, accent);
    }

    private void panel(Canvas canvas, float x, float y, float w, float h,
                       String title, String body, float size, int accent) {
        if (w <= 0 || h <= size * 2) return;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.BLACK);
        canvas.drawRect(x, y, x + w, y + h, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(accent);
        paint.setAlpha(80);
        canvas.drawRect(x, y, x + w, y + h, paint);
        paint.setAlpha(255);
        paragraph(canvas, title, x + size * .5f, y + size * .4f, w - size, size * 2, size, accent);
        paragraph(canvas, body, x + size * .5f, y + size * 2.7f, w - size,
                h - size * 3, size, accent);
    }

    private void paragraph(Canvas canvas, String body, float x, float y, float w, float h, float size, int color) {
        if (w < 1 || h < size || size < 1) return;
        paragraphPaint.setColor(color);
        paragraphPaint.setTextSize(size);
        StaticLayout layout = StaticLayout.Builder.obtain(body, 0, body.length(), paragraphPaint, (int) w)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
                .setMaxLines(Math.max(1, (int) (h / (size * 1.25f))))
                .setEllipsize(TextUtils.TruncateAt.END).build();
        canvas.save();
        canvas.clipRect(x, y, x + w, y + h);
        canvas.translate(x, y);
        layout.draw(canvas);
        canvas.restore();
    }

    private void drawMatrix(Canvas canvas, float w, float h, float pad, float unit, long time) {
        float size = Math.max(8, 14 * unit);
        float spacing = Math.max(size * 2, (w - pad * 4) / 40);
        float top = h * .24f, bottom = h * .7f;
        float span = bottom - top + size * 8;
        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(size);
        for (int column = 0; pad * 2 + column * spacing < w - pad * 2; column++) {
            float head = (time * .025f + column * 73) % span;
            for (int trail = 0; trail < 7; trail++) {
                float y = top + head - trail * size * 1.3f;
                if (y < top || y > bottom) continue;
                paint.setColor(Color.argb(55 - trail * 6, 80, 255, 120));
                int code = (int) ((time / 300 + column * 13 + trail * 7) % 36);
                String glyph = String.valueOf((char) (code < 10 ? '0' + code : 'A' + code - 10));
                canvas.drawText(glyph, pad * 2 + column * spacing, y, paint);
            }
        }
        paint.setAlpha(255);
    }

    private void text(Canvas canvas, String value, float x, float y, float size, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setTextSize(size);
        canvas.drawText(value, x, y, paint);
        paint.setStyle(Paint.Style.STROKE);
    }
}
