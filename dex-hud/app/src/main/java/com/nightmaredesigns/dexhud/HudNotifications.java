package com.nightmaredesigns.dexhud;

import android.app.Notification;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.LinkedHashMap;
import java.util.regex.Pattern;

public final class HudNotifications extends NotificationListenerService {
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)(\\b\\d{4,8}\\b|otp|one.?time|verif|authentic|password|passcode|security code|"
                    + "sign.?in|log.?in|secret|token|bank|payment|credit|debit|account|pin\\b)");
    private final LinkedHashMap<String, String> entries = new LinkedHashMap<>();
    private final Runnable textCleanup = entries::clear;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HudState state;
    private boolean connected;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferences = (prefs, key) -> {
        if ("notifications".equals(key)) main.post(() -> {
            clear();
            if (!state.enabled("notifications", false) && connected) requestUnbind();
        });
    };

    @Override public void onCreate() {
        super.onCreate();
        state = HudState.get(this);
        state.addTextCleanup(textCleanup);
        state.settings.registerOnSharedPreferenceChangeListener(preferences);
    }

    @Override public void onListenerConnected() {
        connected = true;
        clear();
        if (!state.enabled("notifications", false)) { requestUnbind(); return; }
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) for (StatusBarNotification notification : active) collect(notification);
            publish();
        } catch (SecurityException exception) { clear(); }
    }

    @Override public void onNotificationPosted(StatusBarNotification notification) {
        if (!connected || !state.enabled("notifications", false)) return;
        collect(notification);
        publish();
    }

    @Override public void onNotificationRemoved(StatusBarNotification notification) {
        if (!state.enabled("notifications", false)) return;
        entries.remove(notification.getKey());
        publish();
    }

    private void collect(StatusBarNotification item) {
        entries.remove(item.getKey());
        Notification notification = item.getNotification();
        // Only explicitly public, ordinary notifications; never expand private/secret lock-screen text.
        if (notification.visibility != Notification.VISIBILITY_PUBLIC
                || (notification.flags & (Notification.FLAG_GROUP_SUMMARY | Notification.FLAG_ONGOING_EVENT)) != 0
                || getPackageName().equals(item.getPackageName())) return;
        CharSequence title = notification.extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence body = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
        String text = HudState.bounded(title == null ? "" : title.toString(), 100) + "\n"
                + HudState.bounded(body == null ? "" : body.toString(), 256);
        if (text.trim().isEmpty() || SENSITIVE.matcher(text).find()) return;
        entries.put(item.getKey(), item.getPackageName() + "\n" + text.replaceAll("[\\p{Cntrl}&&[^\\n]]", " "));
        while (entries.size() > 4) entries.remove(entries.keySet().iterator().next());
    }

    private void publish() {
        StringBuilder text = new StringBuilder();
        for (String entry : entries.values()) {
            if (text.length() > 0) text.append("\n\n");
            text.append(entry);
        }
        state.notifications = HudState.bounded(text.toString(), 1600);
        state.changed();
    }

    private void clear() {
        entries.clear();
        state.notifications = "";
        state.changed();
    }

    @Override public void onListenerDisconnected() {
        connected = false;
        clear();
    }

    @Override public void onDestroy() {
        connected = false;
        state.removeTextCleanup(textCleanup);
        state.settings.unregisterOnSharedPreferenceChangeListener(preferences);
        clear();
        super.onDestroy();
    }
}
