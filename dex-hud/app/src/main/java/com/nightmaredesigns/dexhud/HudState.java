package com.nightmaredesigns.dexhud;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;

import java.util.ArrayList;

/** Process-local display data. Notification text and assistant replies never enter preferences. */
final class HudState {
    interface Listener { void onHudChanged(); }
    private static HudState instance;
    final SharedPreferences settings;
    private final Context context;
    private final ArrayList<Listener> listeners = new ArrayList<>();
    private final ArrayList<Runnable> textCleanups = new ArrayList<>();
    private boolean batteryRegistered;
    int battery = -1;
    boolean plugged;
    int displayId = -1;
    boolean capturing, ttsSpeaking;
    float audioLevel;
    String audioStatus = "PLAYBACK CAPTURE OFF";
    String assistantStatus = "JESSICA STANDBY";
    String reply = "Open Gemini / Live or OpenRouter web. External apps do not expose replies to this HUD.";
    String notifications = "";

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            battery = level >= 0 && scale > 0 ? level * 100 / scale : -1;
            plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            changed();
        }
    };

    static HudState get(Context context) {
        if (instance == null) instance = new HudState(context.getApplicationContext());
        return instance;
    }

    private HudState(Context context) {
        this.context = context;
        settings = context.getSharedPreferences("hud_settings", Context.MODE_PRIVATE);
    }

    boolean enabled(String key, boolean fallback) { return settings.getBoolean(key, fallback); }
    int brightness() { return Math.max(10, Math.min(100, settings.getInt("brightness", 100))); }

    void add(Listener listener) {
        if (!listeners.contains(listener)) listeners.add(listener);
        updateBattery();
        listener.onHudChanged();
    }

    void remove(Listener listener) {
        listeners.remove(listener);
        updateBattery();
    }

    void settingsChanged() {
        if (!enabled("notifications", false)) notifications = "";
        if (!enabled("dots", true)) ttsSpeaking = false;
        updateBattery();
        changed();
    }

    void changed() {
        for (Listener listener : new ArrayList<>(listeners)) listener.onHudChanged();
    }

    void addTextCleanup(Runnable cleanup) { textCleanups.add(cleanup); }
    void removeTextCleanup(Runnable cleanup) { textCleanups.remove(cleanup); }

    void clearText() {
        assistantStatus = "JESSICA STANDBY";
        reply = "";
        notifications = "";
        ttsSpeaking = false;
        for (Runnable cleanup : new ArrayList<>(textCleanups)) cleanup.run();
        changed();
    }

    void assistant(String status, String message) {
        assistantStatus = bounded(status, 80);
        reply = bounded(message, 4000);
        changed();
    }

    static String bounded(String text, int limit) {
        if (text == null) return "";
        return text.length() > limit ? text.substring(0, limit) : text;
    }

    void apply(HudView view) {
        view.setBrightness(brightness());
        view.setBattery(battery, plugged);
        view.setModes(enabled("motion", true), enabled("matrix", false),
                settings.getInt("style", HudView.STYLE_MINIMAL));
        view.setModules(enabled("clock", true), enabled("battery", true),
                enabled("reactor", true), enabled("assistant", true),
                enabled("notifications", false), enabled("dots", true));
        view.setAssistant(assistantStatus, reply);
        view.setNotifications(notifications);
        view.setAudio(audioLevel, capturing, ttsSpeaking, audioStatus);
    }

    private void updateBattery() {
        boolean needed = !listeners.isEmpty() && enabled("battery", true);
        if (needed && !batteryRegistered) {
            batteryRegistered = true;
            context.registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        } else if (!needed && batteryRegistered) {
            context.unregisterReceiver(batteryReceiver);
            batteryRegistered = false;
            battery = -1;
        }
    }
}
