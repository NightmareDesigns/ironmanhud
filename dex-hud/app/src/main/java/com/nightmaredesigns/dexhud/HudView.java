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
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

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
    private boolean navigationEnabled;
    private NavigationController.Snapshot navigation;
    private float contentHeight, contentScroll, touchY, touchStart;
    private boolean scrolling;

    HudView(Context context) {
        super(context);
        hudState = HudState.get(context);
        setBackgroundColor(Color.BLACK);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setOnClickListener(view -> requestFocus());
        setContentDescription("Black HUD with optional left compass and offline supplied path; swipe vertically for longer panels");
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
        if (running == value) return;
        running = value;
        hudState.hudRunning(this, value);
        if (value) hudState.apply(this);
        invalidate();
    }

    @Override protected void onDetachedFromWindow() {
        setRunning(false);
        super.onDetachedFromWindow();
    }

    void setNavigation(boolean enabled, NavigationController.Snapshot snapshot) {
        navigationEnabled = enabled;
        navigation = snapshot;
        if (!enabled) contentScroll = 0;
        invalidate();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!navigationEnabled) return super.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                requestFocus();
                touchStart = touchY = event.getY();
                scrolling = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(event.getY() - touchStart) > ViewConfiguration.get(getContext()).getScaledTouchSlop()) scrolling = true;
                if (scrolling) {
                    contentScroll = Math.max(0, Math.min(Math.max(0, contentHeight - getHeight()),
                            contentScroll + touchY - event.getY()));
                    invalidate();
                }
                touchY = event.getY();
                return true;
            case MotionEvent.ACTION_UP:
                if (!scrolling) performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        if (navigationEnabled && event.getActionMasked() == MotionEvent.ACTION_SCROLL) {
            contentScroll = Math.max(0, Math.min(Math.max(0, contentHeight - getHeight()),
                    contentScroll - event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                            * 48 * getResources().getDisplayMetrics().density));
            invalidate();
            return true;
        }
        return super.onGenericMotionEvent(event);
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
        if (navigationEnabled && navigation != null) {
            drawNavigationLayout(canvas, w, h, accent);
            canvas.restoreToCount(contentLayer);
            scheduleFrame();
            return;
        }
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
        scheduleFrame();
    }

    private void scheduleFrame() {
        if (running) postInvalidateDelayed(capture || (speaking && dotsEnabled)
                || animated && (matrix || style == STYLE_REACTOR && reactorEnabled) ? 50 : 1000);
    }

    private void drawNavigationLayout(Canvas canvas, float w, float h, int accent) {
        float density = getResources().getDisplayMetrics().density;
        float size = 14 * getResources().getDisplayMetrics().scaledDensity;
        float gap = 12 * density;
        boolean wide = w >= 640 * density && w > h * 1.15f;
        boolean hasRightPanel = assistantEnabled || notificationsEnabled;
        float availableWidth = Math.max(1, w - gap * 2);
        float navWidth = wide ? (availableWidth - gap * 2) * .36f : availableWidth;
        float centerWidth = availableWidth, rightWidth = availableWidth;
        if (wide) {
            centerWidth = hasRightPanel ? (availableWidth - gap * 2) * .27f : availableWidth - navWidth - gap;
            rightWidth = hasRightPanel ? availableWidth - navWidth - centerWidth - gap * 2 : 0;
        }
        String compass = navigation.phoneHeading();
        String navBody = compass + "\n\n" + navigation.guidanceText();
        String sketchTitle = "SUPPLIED PATH SKETCH • not streets";
        float sketchTitleHeight = measuredHeight(sketchTitle, navWidth - size, size * .85f);
        float sketchHeight = navigation.route != null && navigation.route.points.size() >= 2
                ? sketchTitleHeight + 120 * density : 0;
        float navHeight = fullPanelHeight("COMPASS / OFFLINE NAVIGATION", navBody, navWidth, size) + sketchHeight;
        float dotStatusHeight = measuredHeight(!capture && speaking ? "APP TTS / TIMING ONLY (NOT RMS)" : audioStatus,
                centerWidth - gap * 2, Math.max(11, 12 * getResources().getDisplayMetrics().scaledDensity));
        boolean hasGraphics = dotsEnabled || matrix || style == STYLE_TERMINAL
                || reactorEnabled && style == STYLE_REACTOR;
        float centerHeight = hasGraphics
                ? Math.max(250 * density, 220 * density + (dotsEnabled ? dotStatusHeight : 0)) : 0;
        String notificationBody = notifications.isEmpty()
                ? "No eligible public notifications. Private/secret messages and likely codes are hidden." : notifications;
        float assistantHeight = assistantEnabled ? fullPanelHeight("AI / " + assistantStatus, reply, rightWidth, size) : 0;
        float notificationHeight = notificationsEnabled
                ? fullPanelHeight("PUBLIC NOTIFICATIONS", notificationBody, rightWidth, size) : 0;
        float rightHeight = assistantHeight + notificationHeight + (assistantEnabled && notificationsEnabled ? gap : 0);
        Date now = new Date();
        String clockBody = clock.format(now) + "\n" + date.format(now);
        String batteryBody = (battery < 0 ? "POWER --" : "POWER " + battery + "%")
                + "\n" + (charging ? "CHARGING" : "ON BATTERY");
        float headerHeight = Math.max(gap,
                Math.max(clockEnabled ? measuredHeight(clockBody, availableWidth * .53f, size) : 0,
                        batteryEnabled ? measuredHeight(batteryBody, availableWidth * .44f, size) : 0) + gap);
        float bodyHeight = wide ? Math.max(navHeight, Math.max(centerHeight, rightHeight))
                : navHeight + (centerHeight > 0 ? gap + centerHeight : 0) + (rightHeight > 0 ? gap + rightHeight : 0);
        contentHeight = headerHeight + bodyHeight + gap * 2;
        contentScroll = Math.max(0, Math.min(contentScroll, Math.max(0, contentHeight - h)));
        canvas.save();
        canvas.clipRect(0, 0, w, h);
        canvas.translate(0, -contentScroll);
        if (clockEnabled) fullParagraph(canvas, clockBody, gap, gap * .5f, availableWidth * .53f, size, accent);
        if (batteryEnabled) fullParagraph(canvas, batteryBody,
                gap + availableWidth * .56f, gap * .5f, availableWidth * .44f, size, accent);
        float top = headerHeight + gap;
        fullPanel(canvas, gap, top, navWidth, navHeight, "COMPASS / OFFLINE NAVIGATION", navBody, size, accent);
        if (sketchHeight > 0) {
            float sketchTop = top + navHeight - sketchHeight;
            fullParagraph(canvas, sketchTitle, gap + size * .5f, sketchTop, navWidth - size, size * .85f, accent);
            drawRouteSketch(canvas, new RectF(gap + size, sketchTop + sketchTitleHeight + size * .5f,
                    gap + navWidth - size, top + navHeight - size), accent);
        }
        float centerLeft = wide ? gap * 2 + navWidth : gap;
        float centerTop = wide ? top : top + navHeight + gap;
        if (centerHeight > 0) {
            canvas.save();
            canvas.translate(centerLeft, centerTop);
            canvas.clipRect(0, 0, centerWidth, centerHeight);
            drawCenter(canvas, centerWidth, centerHeight, gap * .5f, accent);
            canvas.restore();
        }
        float rightLeft = wide ? gap * 3 + navWidth + centerWidth : gap;
        float rightTop = wide ? top : centerTop + centerHeight + (centerHeight > 0 ? gap : 0);
        if (assistantEnabled) {
            fullPanel(canvas, rightLeft, rightTop, rightWidth, assistantHeight, "AI / " + assistantStatus, reply, size, accent);
            rightTop += assistantHeight + gap;
        }
        if (notificationsEnabled) fullPanel(canvas, rightLeft, rightTop, rightWidth, notificationHeight,
                "PUBLIC NOTIFICATIONS", notificationBody, size, accent);
        canvas.restore();
        if (contentHeight > h) {
            paint.setColor(accent);
            paint.setStyle(Paint.Style.FILL);
            paint.setAlpha(150);
            float barHeight = Math.max(12 * density, h * h / contentHeight);
            float barTop = contentScroll / Math.max(1, contentHeight - h) * (h - barHeight);
            canvas.drawRect(w - 3 * density, barTop, w, barTop + barHeight, paint);
            paint.setAlpha(255);
        }
    }

    private float measuredHeight(String body, float width, float size) {
        paragraphPaint.setTextSize(size);
        return StaticLayout.Builder.obtain(body, 0, body.length(), paragraphPaint, Math.max(1, (int) width))
                .setIncludePad(false).build().getHeight();
    }

    private float fullPanelHeight(String title, String body, float width, float size) {
        return measuredHeight(title, width - size, size) + measuredHeight(body, width - size, size) + size * 2;
    }

    private void fullParagraph(Canvas canvas, String body, float x, float y, float width, float size, int accent) {
        paragraphPaint.setTextSize(size);
        paragraphPaint.setColor(accent);
        StaticLayout layout = StaticLayout.Builder.obtain(body, 0, body.length(), paragraphPaint, Math.max(1, (int) width))
                .setIncludePad(false).build();
        canvas.save();
        canvas.translate(x, y);
        layout.draw(canvas);
        canvas.restore();
    }

    private void fullPanel(Canvas canvas, float x, float y, float w, float h,
                           String title, String body, float size, int accent) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1);
        paint.setColor(accent);
        paint.setAlpha(80);
        canvas.drawRect(x, y, x + w, y + h, paint);
        paint.setAlpha(255);
        fullParagraph(canvas, title, x + size * .5f, y + size * .5f, w - size, size, accent);
        float bodyTop = y + size + measuredHeight(title, w - size, size);
        fullParagraph(canvas, body, x + size * .5f, bodyTop, w - size, size, accent);
    }

    private void drawCenter(Canvas canvas, float w, float h, float pad, int accent) {
        float unit = Math.min(w / 320f, h / 300f);
        long time = effectElapsed + (animated ? SystemClock.uptimeMillis() - effectStarted : 0);
        if (matrix) drawMatrix(canvas, w, h, pad, unit, time);
        if (reactorEnabled && style == STYLE_REACTOR) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(accent);
            paint.setStrokeWidth(Math.max(1, 2 * unit));
            float radius = Math.min(w * .24f, h * .20f), cx = w / 2, cy = h * .31f;
            for (int i = 0; i < 3; i++) {
                float r = radius * (1 - i * .23f);
                ring.set(cx - r, cy - r, cx + r, cy + r);
                canvas.drawArc(ring, (time % 12000) * .03f * (i == 1 ? -1 : 1), 255, false, paint);
            }
        } else if (style == STYLE_TERMINAL) {
            paragraph(canvas, "> JESSICA HUD", pad * 2, h * .3f, w - pad * 4, h * .2f,
                    14 * getResources().getDisplayMetrics().scaledDensity, accent);
        }
        if (dotsEnabled) drawDots(canvas, w, h, pad, unit, accent, time);
    }

    private void drawRouteSketch(Canvas canvas, RectF bounds, int accent) {
        if (!Float.isFinite(bounds.left) || !Float.isFinite(bounds.top)
                || !Float.isFinite(bounds.right) || !Float.isFinite(bounds.bottom)
                || bounds.width() <= 0 || bounds.height() <= 0 || navigation.route == null) return;
        double[][] positions = navigation.route.sketch();
        if (positions.length == 0) return;
        for (double[] point : positions) {
            if (!Double.isFinite(point[0]) || !Double.isFinite(point[1])) return;
        }
        double minX = positions[0][0], maxX = minX, minY = positions[0][1], maxY = minY;
        for (double[] point : positions) {
            minX = Math.min(minX, point[0]); maxX = Math.max(maxX, point[0]);
            minY = Math.min(minY, point[1]); maxY = Math.max(maxY, point[1]);
        }
        double scale = Math.min(bounds.width() / Math.max(.00001, maxX - minX),
                bounds.height() / Math.max(.00001, maxY - minY)) * .9;
        canvas.save();
        canvas.clipRect(bounds);
        paint.setColor(accent);
        paint.setStrokeWidth(2);
        float previousX = 0, previousY = 0;
        for (int i = 0; i < positions.length; i++) {
            float x = bounds.centerX() + (float) ((positions[i][0] - (minX + maxX) / 2) * scale);
            float y = bounds.centerY() + (float) ((positions[i][1] - (minY + maxY) / 2) * scale);
            paint.setStyle(Paint.Style.STROKE);
            if (i > 0) canvas.drawLine(previousX, previousY, x, y, paint);
            paint.setStyle(i == navigation.target ? Paint.Style.FILL : Paint.Style.STROKE);
            canvas.drawCircle(x, y, i == navigation.target ? 5 : 3, paint);
            previousX = x; previousY = y;
        }
        canvas.restore();
    }

    private void drawDots(Canvas canvas, float w, float h, float pad, float unit, int accent, long time) {
        String status = !capture && speaking ? "APP TTS / TIMING ONLY (NOT RMS)" : audioStatus;
        float textSize = Math.max(11 * unit, 12 * getResources().getDisplayMetrics().scaledDensity);
        float top = h * .67f, bottom = h * .9f;
        if (navigationEnabled) {
            bottom = Math.min(bottom, h - measuredHeight(status, w - pad * 4, textSize) - 12 * unit);
            top = Math.min(top, bottom * .7f);
        }
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
        if (navigationEnabled) fullParagraph(canvas, status, pad * 2, bottom + 8 * unit, w - pad * 4, textSize, accent);
        else paragraph(canvas, status, pad * 2, bottom + 8 * unit, w - pad * 4,
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
