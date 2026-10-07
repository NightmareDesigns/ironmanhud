package com.nightmaredesigns.dexhud;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Explicitly started, foreground-only Gemini voice session with audio-reactive visuals. */
public final class GeminiLiveActivity extends Activity {
    private static final int MICROPHONE_PERMISSION = 40;
    private final Handler main = new Handler(Looper.getMainLooper());
    private EditText keyInput;
    private EditText modelInput;
    private Button startButton;
    private Button stopButton;
    private TextView status;
    private TextView transcript;
    private AudioPanel panel;
    private GeminiLiveSession session;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private boolean foreground;
    private boolean audioReady;
    private int generation;
    private String lastSpeaker = "";
    private final StringBuilder history = new StringBuilder();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(20), dp(24), dp(20));
        root.setBackgroundColor(Color.rgb(6, 15, 24));
        scroll.addView(root);
        root.addView(label("JESSICA • GEMINI LIVE", 24));
        root.addView(label("Real-time voice • visual audio activity • foreground only", 14));
        root.addView(label("Microphone audio is sent to Google only after Start. Use headphones "
                + "to avoid speaker echo. No camera or screen capture is used.", 14));
        keyInput = field("Gemini API key (not OpenRouter)");
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyInput.setSaveEnabled(false);
        keyInput.setFreezesText(false);
        keyInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        keyInput.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        root.addView(keyInput);
        modelInput = field("Live model");
        modelInput.setText("gemini-3.8-live");
        root.addView(modelInput);
        root.addView(label("Use a Google AI Studio Gemini Developer API key. Model availability, "
                + "quotas and charges depend on your Google project, region and billing tier. "
                + "A Gemini app subscription or OpenRouter credit does not fund this API. "
                + "Check aistudio.google.com and ai.google.dev/gemini-api/docs/pricing. "
                + "Keys are memory-only and cleared on start or leaving this screen. "
                + "For distributed production apps, use a backend with ephemeral tokens.", 13));
        LinearLayout buttons = new LinearLayout(this);
        startButton = new Button(this);
        startButton.setText("START LIVE");
        stopButton = new Button(this);
        stopButton.setText("STOP");
        stopButton.setEnabled(false);
        buttons.addView(startButton, new LinearLayout.LayoutParams(0, dp(56), 1));
        buttons.addView(stopButton, new LinearLayout.LayoutParams(0, dp(56), 1));
        root.addView(buttons);
        status = label("READY • enter a key, then explicitly start", 16);
        root.addView(status);
        panel = new AudioPanel(this);
        root.addView(panel, new LinearLayout.LayoutParams(-1, dp(160)));
        root.addView(label("SESSION TRANSCRIPT (when supplied by Gemini)", 14));
        transcript = label("No conversation yet.", 16);
        transcript.setSaveEnabled(false);
        transcript.setTextIsSelectable(false);
        root.addView(transcript);
        Button back = new Button(this);
        back.setText("BACK TO HUD");
        back.setOnClickListener(v -> finish());
        root.addView(back);
        startButton.setOnClickListener(v -> requestStart());
        stopButton.setOnClickListener(v -> stop("STOPPED • microphone and connection closed"));
        setContentView(scroll);
    }

    private TextView label(String text, int size) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(Color.rgb(110, 235, 245));
        view.setPadding(0, dp(8), 0, dp(8));
        return view;
    }

    private EditText field(String hint) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setHint(hint);
        field.setTextColor(Color.WHITE);
        field.setHintTextColor(Color.LTGRAY);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        return field;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void requestStart() {
        if (!foreground || session != null) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.setText("Microphone permission is required. After granting it, press Start again.");
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE_PERMISSION);
            return;
        }
        String key = keyInput.getText().toString().trim();
        String model = modelInput.getText().toString().trim();
        if (key.isEmpty() || key.length() > 200) {
            status.setText("Enter a valid Gemini Developer API key.");
            return;
        }
        if (model.startsWith("models/")) model = model.substring(7);
        if (!model.matches("[A-Za-z0-9._-]{1,100}")) {
            status.setText("Enter a Live model ID, for example gemini-3.8-live.");
            return;
        }
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener(change -> {
                    if (change < 0) stop("STOPPED • audio focus lost; press Start to reconnect");
                }, main).build();
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            audioManager.abandonAudioFocusRequest(focusRequest);
            focusRequest = null;
            status.setText("Audio is busy. Finish the other audio session and try again.");
            return;
        }
        keyInput.setText("");
        keyInput.setEnabled(false);
        modelInput.setEnabled(false);
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
        history.setLength(0);
        lastSpeaker = "";
        transcript.setText("Waiting for speech…");
        status.setText("CONNECTING • waiting for Gemini setup");
        panel.active = true;
        audioReady = false;
        panel.invalidate();
        int token = ++generation;
        session = new GeminiLiveSession(new GeminiLiveSession.Listener() {
            private void ui(Runnable action) {
                main.post(() -> {
                    if (foreground && token == generation && session != null) action.run();
                });
            }
            @Override public void onReady() {
                ui(() -> {
                    audioReady = true;
                    status.setText("LIVE • microphone streaming to Google • speak naturally");
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                });
            }
            @Override public void onLevel(boolean mic, float level) {
                ui(() -> {
                    if (mic) panel.microphone = level; else panel.speaker = level;
                    panel.invalidate();
                });
            }
            @Override public void onTranscript(String speaker, String text) {
                ui(() -> appendTranscript(speaker, text));
            }
            @Override public void onEnd(String message) { ui(() -> stop(message)); }
        });
        try {
            session.connect(key, model);
        } catch (RuntimeException e) {
            stop("Unable to connect. Check key, model and internet access.");
            return;
        }
        main.postDelayed(() -> {
            if (token == generation && session != null && !audioReady) {
                stop("Setup timed out. Check internet, key, model availability and billing.");
            }
        }, 20_000);
        main.postDelayed(() -> {
            if (token == generation && session != null) {
                stop("STOPPED • 10-minute safety limit. Start a new session.");
            }
        }, 10 * 60_000L);
    }

    private void appendTranscript(String speaker, String text) {
        if (!speaker.isEmpty() && !speaker.equals(lastSpeaker)) {
            if (history.length() > 0) history.append('\n');
            history.append(speaker).append(": ");
            lastSpeaker = speaker;
        }
        history.append(text);
        if (speaker.isEmpty()) lastSpeaker = "";
        if (history.length() > 12_000) history.delete(0, history.length() - 12_000);
        transcript.setText(history.toString());
    }

    private void stop(String message) {
        generation++;
        main.removeCallbacksAndMessages(null);
        GeminiLiveSession old = session;
        session = null;
        if (old != null) old.close();
        if (focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
            focusRequest = null;
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        keyInput.setText("");
        keyInput.setEnabled(true);
        modelInput.setEnabled(true);
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        panel.active = false;
        audioReady = false;
        panel.microphone = panel.speaker = 0;
        panel.invalidate();
        status.setText(message);
    }

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
    }

    @Override protected void onPause() {
        foreground = false;
        stop("STOPPED • left foreground; enter key and press Start to reconnect");
        history.setLength(0);
        transcript.setText("Transcript cleared on leaving the screen.");
        super.onPause();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == MICROPHONE_PERMISSION) {
            status.setText(results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED
                    ? "Microphone allowed. Enter key if needed and press Start."
                    : "Microphone denied. Enable it in Android app permissions to use Live.");
        }
    }

    private static final class AudioPanel extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float microphone;
        float speaker;
        boolean active;

        AudioPanel(Context context) { super(context); setContentDescription("Microphone and Gemini audio levels"); }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float density = getResources().getDisplayMetrics().density;
            paint.setTextSize(13 * density);
            paint.setColor(Color.rgb(100, 210, 225));
            canvas.drawText(active ? "MIC INPUT" : "MIC OFF", 8 * density, 22 * density, paint);
            canvas.drawText("GEMINI AUDIO", 8 * density, 98 * density, paint);
            bars(canvas, microphone, 32 * density, Color.rgb(0, 235, 200), density);
            bars(canvas, speaker, 108 * density, Color.rgb(255, 175, 70), density);
        }

        private void bars(Canvas canvas, float level, float top, int color, float density) {
            float width = (getWidth() - 16 * density) / 24;
            for (int i = 0; i < 24; i++) {
                paint.setColor(active && (i + 1) / 24f <= level ? color : Color.rgb(24, 47, 57));
                float left = 8 * density + i * width;
                canvas.drawRoundRect(left, top, left + width - 2 * density,
                        top + 35 * density, 2 * density, 2 * density, paint);
            }
        }
    }
}
