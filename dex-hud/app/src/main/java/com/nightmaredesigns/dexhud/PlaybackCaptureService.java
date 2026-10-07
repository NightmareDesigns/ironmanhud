package com.nightmaredesigns.dexhud;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

public final class PlaybackCaptureService extends Service implements HudState.Listener {
    static final String STOP = "com.nightmaredesigns.dexhud.STOP_CAPTURE";
    static final String CONSENT = "consent";
    static final String RESULT = "result";
    private static final int NOTIFICATION_ID = 42;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean reading;
    private AudioRecord recorder;
    private Thread worker;
    private MediaProjection projection;
    private HudState state;
    private HudDisplay display;
    private AppOpsManager appOps;
    private boolean started, stopping;
    private final AppOpsManager.OnOpChangedListener permission = (op, packageName) -> main.post(() -> {
        int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_RECORD_AUDIO,
                android.os.Process.myUid(), getPackageName());
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                || mode == AppOpsManager.MODE_IGNORED || mode == AppOpsManager.MODE_ERRORED) {
            finishCapture("AUDIO PERMISSION REVOKED");
        }
    });
    private final SharedPreferences.OnSharedPreferenceChangeListener preferences = (prefs, key) -> {
        if ("dots".equals(key) && !state.enabled("dots", true)) {
            main.post(() -> finishCapture("DOT MODULE DISABLED"));
        }
    };
    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override public void onStop() { finishCapture("PLAYBACK CONSENT ENDED"); }
    };

    @Override public void onCreate() {
        super.onCreate();
        state = HudState.get(this);
        display = new HudDisplay(this, state, () -> finishCapture("EXTERNAL DISPLAY DISCONNECTED"));
        state.settings.registerOnSharedPreferenceChangeListener(preferences);
        appOps = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
        appOps.startWatchingMode(AppOpsManager.OPSTR_RECORD_AUDIO, getPackageName(), permission);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction())) {
            finishCapture("PLAYBACK CAPTURE OFF");
            return START_NOT_STICKY;
        }
        if (started || stopping) return START_NOT_STICKY;
        if (Build.VERSION.SDK_INT < 29 || !state.enabled("dots", true)
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            finishCapture("PLAYBACK CAPTURE UNAVAILABLE");
            return START_NOT_STICKY;
        }
        Intent consent = intent.getParcelableExtra(CONSENT);
        int result = intent.getIntExtra(RESULT, 0);
        if (consent == null || result != android.app.Activity.RESULT_OK) {
            finishCapture("PLAYBACK CONSENT REQUIRED");
            return START_NOT_STICKY;
        }
        started = true;
        try {
            NotificationManager notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            notifications.createNotificationChannel(new NotificationChannel("playback_capture",
                    "HUD playback visualization", NotificationManager.IMPORTANCE_LOW));
            PendingIntent stop = PendingIntent.getService(this, 0,
                    new Intent(this, PlaybackCaptureService.class).setAction(STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(this, "playback_capture")
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle("HUD playback levels active")
                    .setContentText("Eligible playback only • no microphone or recording")
                    .setContentIntent(open).setOngoing(true)
                    .addAction(new Notification.Action.Builder(null, "STOP", stop).build()).build();
            // Android 14+: consent, then foreground type, then projection, then callback, then audio.
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = manager.getMediaProjection(result, consent);
            if (projection == null) throw new IllegalStateException("Projection unavailable");
            projection.registerCallback(projectionCallback, main);
            AudioPlaybackCaptureConfiguration configuration = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build();
            int minimum = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IllegalStateException("Audio format unavailable");
            recorder = new AudioRecord.Builder()
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(44100).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                    .setBufferSizeInBytes(Math.max(minimum, 8192))
                    .setAudioPlaybackCaptureConfig(configuration).build();
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("Audio unavailable");
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("Audio did not start");
            }
            reading = true;
            state.capturing = true;
            state.audioLevel = 0;
            state.audioStatus = "WAITING FOR ELIGIBLE PLAYBACK";
            state.changed(); // The activity relinquishes its Presentation before the service acquires one.
            if (stopping) return START_NOT_STICKY;
            state.add(this);
            display.start();
            if (!stopping) {
                AudioRecord source = recorder;
                worker = new Thread(() -> readLevels(source), "hud-playback-levels");
                worker.start();
            }
        } catch (RuntimeException exception) {
            finishCapture("CAPTURE UNAVAILABLE / CONSENT OR SOURCE POLICY");
        }
        return START_NOT_STICKY;
    }

    private void readLevels(AudioRecord source) {
        short[] samples = new short[2048];
        long lastUpdate = 0, lastSignal = SystemClock.elapsedRealtime();
        try {
            while (reading) {
                int count = source.read(samples, 0, samples.length, AudioRecord.READ_BLOCKING);
                if (count <= 0) break;
                double sum = 0;
                for (int i = 0; i < count; i++) {
                    double value = samples[i] / 32768.0;
                    sum += value * value;
                }
                float level = Math.min(1f, (float) Math.sqrt(sum / count) * 4f);
                long now = SystemClock.elapsedRealtime();
                if (level > .008f) lastSignal = now;
                if (now - lastUpdate >= 100) {
                    lastUpdate = now;
                    boolean silent = now - lastSignal > 1500;
                    main.post(() -> {
                        if (!reading || stopping) return;
                        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            finishCapture("AUDIO PERMISSION REVOKED");
                            return;
                        }
                        state.audioLevel = level;
                        state.audioStatus = silent ? "NO CAPTURABLE AUDIO / SILENT OR BLOCKED" : "ELIGIBLE PLAYBACK RMS";
                        state.changed();
                    });
                }
            }
        } catch (RuntimeException ignored) {
            // Revocation/route changes can invalidate AudioRecord while a blocking read is in progress.
        } finally {
            try { source.release(); } catch (RuntimeException ignored) { }
            main.post(() -> {
                if (reading) finishCapture("PLAYBACK AUDIO ENDED");
            });
        }
    }

    private void finishCapture(String status) {
        if (stopping) return;
        stopping = true;
        cleanup(status);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void cleanup(String status) {
        boolean workerOwnsRecord = worker != null;
        reading = false;
        if (recorder != null) {
            try { recorder.stop(); } catch (IllegalStateException ignored) { }
            if (!workerOwnsRecord) {
                try { recorder.release(); } catch (RuntimeException ignored) { }
            }
            recorder = null;
        }
        if (projection != null) {
            projection.unregisterCallback(projectionCallback);
            try { projection.stop(); } catch (RuntimeException ignored) { }
            projection = null;
        }
        display.stop();
        state.remove(this);
        state.capturing = false;
        state.audioLevel = 0;
        state.audioStatus = status;
        state.clearText();
    }

    @Override public void onHudChanged() {
        if (!stopping) display.refresh();
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        finishCapture("HUD TASK CLOSED");
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        if (!stopping) {
            stopping = true;
            cleanup("PLAYBACK CAPTURE OFF");
        }
        main.removeCallbacksAndMessages(null);
        state.settings.unregisterOnSharedPreferenceChangeListener(preferences);
        appOps.stopWatchingMode(permission);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
