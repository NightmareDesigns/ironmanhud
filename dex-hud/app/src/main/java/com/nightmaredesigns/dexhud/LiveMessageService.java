package com.nightmaredesigns.dexhud;

import android.app.Notification;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

final public class LiveMessageService extends NotificationListenerService {
    interface Listener {
        void onMessage(String key, String text);
    }

    private static volatile Listener listener;

    static void setListener(Listener value) {
        listener = value;
    }

    @Override public void onNotificationPosted(StatusBarNotification status) {
        Listener target = listener;
        Notification notification = status.getNotification();
        if (target == null || !Notification.CATEGORY_MESSAGE.equals(notification.category)
                || (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        CharSequence title = notification.extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence body = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
        if (body == null || body.length() == 0) return;
        String text = status.getPackageName() + "\n" + clipped(title, 100) + "\n" + clipped(body, 400);
        target.onMessage(status.getKey(), text);
    }

    private static String clipped(CharSequence value, int limit) {
        if (value == null) return "";
        return value.subSequence(0, Math.min(value.length(), limit)).toString();
    }
}
