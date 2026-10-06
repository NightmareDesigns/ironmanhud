package com.nightmaredesigns.dexhud;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Presentation;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.Display;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int VOICE_REQUEST = 10;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final OpenRouterClient client = new OpenRouterClient();
    private final ArrayList<JSONObject> history = new ArrayList<>();
    private HudView hud;
    private TextToSpeech tts;
    private boolean ttsReady, speak, busy, polling, fullscreen = true;
    private volatile boolean active;
    private volatile int generation;
    private String apiKey = "", model = "openrouter/free", pollingPrompt = "";
    private String transcript = "Jessica ready. Configure OpenRouter to chat.\n";
    private TextView chatText;
    private Button pollButton;
    private AlertDialog chatDialog;
    private Presentation externalPresentation;
    private HudView externalHud;
    private DisplayManager displays;
    private int batteryPercent = -1;
    private boolean plugged, motion = true, green;
    private String assistantStatus = "JESSICA STANDBY";
    private String assistantReply = "Open Jessica to configure free-model chat.";
    private String pendingVoice;

    private final Runnable poll = () -> {
        if (active && polling && !busy) sendPrompt(pollingPrompt);
    };
    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            batteryPercent = level >= 0 && scale > 0 ? level * 100 / scale : -1;
            plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            hud.setBattery(batteryPercent, plugged);
            if (externalHud != null) externalHud.setBattery(batteryPercent, plugged);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        model = getPreferences(MODE_PRIVATE).getString("model", "openrouter/free");
        LinearLayout root = column();
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(dp(12), 0, dp(12), 0);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets edges = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(dp(12) + edges.left, edges.top, dp(12) + edges.right, edges.bottom);
                return insets;
            });
        }
        TextView title = label("JESSICA  /  HUD SYSTEMS");
        title.setTextSize(18);
        title.setPadding(dp(8), dp(8), dp(8), dp(8));
        root.addView(title);
        hud = new HudView(this);
        root.addView(hud, new LinearLayout.LayoutParams(-1, 0, 1));
        android.widget.HorizontalScrollView controls = new android.widget.HorizontalScrollView(this);
        LinearLayout row = row();
        button(row, "Jessica", v -> openJessica());
        button(row, "Voice", v -> recognize());
        button(row, "Display", v -> chooseDisplay());
        button(row, "Fullscreen", v -> { fullscreen = !fullscreen; applyFullscreen(); });
        button(row, "Pause FX", v -> {
            motion = !motion;
            updateModes();
            ((Button) v).setText(motion ? "Pause FX" : "Resume FX");
        });
        button(row, "Matrix", v -> {
            green = !green;
            updateModes();
            ((Button) v).setText(green ? "Cyan HUD" : "Matrix");
        });
        controls.addView(row);
        root.addView(controls);
        setContentView(root);
        displays = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        tts = new TextToSpeech(this, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (ttsReady) {
                int result = tts.setLanguage(Locale.getDefault());
                ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED;
            }
        });
        applyFullscreen();
    }

    @Override protected void onStart() {
        super.onStart();
        active = true;
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        hud.setRunning(true);
    }

    @Override protected void onStop() {
        active = false;
        generation++;
        stopPolling();
        closeExternalDisplay();
        client.cancel();
        if (busy) updateAssistant("JESSICA STANDBY", "Request cancelled when app left the foreground.");
        if (tts != null) tts.stop();
        hud.setRunning(false);
        unregisterReceiver(batteryReceiver);
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        if (pendingVoice != null) {
            String prompt = pendingVoice;
            pendingVoice = null;
            handleVoice(prompt);
        }
    }

    @Override protected void onDestroy() {
        client.cancel();
        executor.shutdownNow();
        handler.removeCallbacksAndMessages(null);
        if (tts != null) tts.shutdown();
        if (chatDialog != null) chatDialog.dismiss();
        super.onDestroy();
    }

    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) applyFullscreen();
    }

    private void applyFullscreen() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                if (fullscreen) controller.hide(WindowInsets.Type.systemBars());
                else controller.show(WindowInsets.Type.systemBars());
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(fullscreen
                    ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY : View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    private void chooseDisplay() {
        Display[] available = displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        String[] names = new String[available.length + 1];
        names[0] = "Phone / current app window";
        for (int i = 0; i < available.length; i++) names[i + 1] = available[i].getName();
        new AlertDialog.Builder(this).setTitle("HUD display")
                .setItems(names, (dialog, item) -> {
                    closeExternalDisplay();
                    if (item == 0) return;
                    try {
                        Presentation presentation = new Presentation(this, available[item - 1],
                                android.R.style.Theme_Material_NoActionBar_Fullscreen);
                        HudView view = new HudView(presentation.getContext());
                        view.setBattery(batteryPercent, plugged);
                        view.setAssistant(assistantStatus, assistantReply);
                        view.setModes(motion, green);
                        presentation.setContentView(view);
                        presentation.setOnDismissListener(closed -> {
                            view.setRunning(false);
                            if (externalPresentation == presentation) {
                                externalPresentation = null;
                                externalHud = null;
                            }
                        });
                        presentation.show();
                        view.setRunning(true);
                        externalPresentation = presentation;
                        externalHud = view;
                    } catch (WindowManager.InvalidDisplayException | SecurityException exception) {
                        toast("Android cannot use this display. Try opening the app on the external desktop.");
                    }
                }).setNegativeButton("Cancel", null)
                .setMessage(available.length == 0
                        ? "No separate presentation display detected. Your phone may be mirroring, or the display is not connected."
                        : null).show();
    }

    private void closeExternalDisplay() {
        if (externalPresentation != null) externalPresentation.dismiss();
        externalPresentation = null;
        externalHud = null;
    }

    private void updateModes() {
        hud.setModes(motion, green);
        if (externalHud != null) externalHud.setModes(motion, green);
    }

    private void updateAssistant(String status, String reply) {
        assistantStatus = status;
        assistantReply = reply;
        hud.setAssistant(status, reply);
        if (externalHud != null) externalHud.setAssistant(status, reply);
    }

    @Override public boolean onKeyDown(int key, KeyEvent event) {
        if (key == KeyEvent.KEYCODE_ESCAPE && fullscreen) {
            fullscreen = false;
            applyFullscreen();
            return true;
        }
        return super.onKeyDown(key, event);
    }

    private void openJessica() {
        if (chatDialog != null && chatDialog.isShowing()) return;
        LinearLayout content = column();
        content.setPadding(dp(16), dp(8), dp(16), dp(8));
        content.addView(label("Text is sent to OpenRouter/model providers. Voice uses your installed speech service."));
        chatText = label(transcript);
        chatText.setTextIsSelectable(true);
        ScrollView messages = new ScrollView(this);
        messages.addView(chatText);
        content.addView(messages, new LinearLayout.LayoutParams(-1, dp(160)));
        EditText input = new EditText(this);
        input.setHint("Ask Jessica, or enter a prompt to poll every 60 seconds");
        input.setMaxLines(3);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4000)});
        content.addView(input);
        LinearLayout actions = row();
        button(actions, "Send", v -> {
            if (!input.getText().toString().trim().isEmpty()) {
                stopPolling();
                sendPrompt(input.getText().toString());
            }
        });
        button(actions, "Voice", v -> recognize());
        button(actions, "Settings", v -> openSettings());
        android.widget.HorizontalScrollView actionScroll = new android.widget.HorizontalScrollView(this);
        actionScroll.addView(actions);
        content.addView(actionScroll);
        pollButton = new Button(this);
        pollButton.setText(polling ? "Stop polling" : "Start polling (60s)");
        pollButton.setOnClickListener(v -> {
            if (polling) { stopPolling(); return; }
            String prompt = input.getText().toString().trim();
            if (prompt.isEmpty()) { toast("Enter a polling prompt first."); return; }
            if (busy) { toast("Wait for Jessica's current reply."); return; }
            if (apiKey.isEmpty()) { openSettings(); return; }
            new AlertDialog.Builder(this).setTitle("Start text polling?")
                    .setMessage("Send this prompt and recent chat to OpenRouter every 60 seconds while this app is visible? Free-model limits apply. This does not read messages, notifications or live sensors.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Start", (dialog, which) -> {
                        pollingPrompt = prompt;
                        polling = true;
                        if (pollButton != null) pollButton.setText("Stop polling");
                        sendPrompt(prompt);
                    }).show();
        });
        content.addView(pollButton);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        chatDialog = new AlertDialog.Builder(this).setTitle("Jessica")
                .setView(scroll).setPositiveButton("Close", null).create();
        chatDialog.setOnDismissListener(dialog -> { chatText = null; pollButton = null; });
        chatDialog.show();
    }

    private void openSettings() {
        LinearLayout fields = column();
        fields.setPadding(dp(20), dp(8), dp(20), 0);
        fields.addView(label("API key stays in memory only; re-enter after closing/recreating the app. Never add it to GitHub."));
        EditText keyInput = new EditText(this);
        keyInput.setHint("OpenRouter API key");
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyInput.setText(apiKey);
        keyInput.setSaveEnabled(false);
        keyInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        fields.addView(keyInput);
        EditText modelInput = new EditText(this);
        modelInput.setHint("openrouter/free or provider/model:free");
        modelInput.setSingleLine();
        modelInput.setText(model);
        fields.addView(modelInput);
        android.widget.CheckBox spoken = new android.widget.CheckBox(this);
        spoken.setText("Speak Jessica's replies (Android text-to-speech)");
        spoken.setChecked(speak);
        fields.addView(spoken);
        AlertDialog settings = new AlertDialog.Builder(this).setTitle("OpenRouter / Jessica")
                .setView(fields).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        settings.show();
        settings.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        settings.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String selected = modelInput.getText().toString().trim();
            String key = keyInput.getText().toString().trim();
            if (!OpenRouterClient.isFreeModel(selected)) {
                modelInput.setError("Use openrouter/free or an ID ending in :free");
                return;
            }
            if (key.contains("\n") || key.contains("\r") || key.length() > 512) {
                keyInput.setError("Invalid API key");
                return;
            }
            stopPolling();
            apiKey = key;
            model = selected;
            speak = spoken.isChecked();
            if (!speak && tts != null) tts.stop();
            getPreferences(MODE_PRIVATE).edit().putString("model", model).apply();
            settings.dismiss();
            toast(key.isEmpty() ? "Key cleared. Offline HUD remains available." : "Jessica configured.");
        });
    }

    private void recognize() {
        if (busy) { toast("Wait for Jessica's current reply before using Voice."); return; }
        stopPolling();
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask Jessica. Speech may be processed by your speech provider.");
        try {
            startActivityForResult(intent, VOICE_REQUEST);
        } catch (ActivityNotFoundException exception) {
            toast("No speech recognition app installed. Use Jessica's text input.");
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != VOICE_REQUEST || result != RESULT_OK || data == null) return;
        ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (results == null || results.isEmpty()) return;
        String prompt = results.get(0);
        if (!active) { pendingVoice = prompt; return; }
        handleVoice(prompt);
    }

    private void handleVoice(String prompt) {
        String command = prompt.toLowerCase(Locale.ROOT).replaceFirst("^jessica[, ]*", "").trim();
        switch (command) {
            case "matrix mode": green = true; updateModes(); break;
            case "fullscreen": fullscreen = true; applyFullscreen(); break;
            case "stop polling": stopPolling(); break;
            case "stop speaking": if (tts != null) tts.stop(); break;
            default: sendPrompt(prompt);
        }
    }

    private void sendPrompt(String raw) {
        if (!active || busy) { toast("Wait for Jessica's current reply."); return; }
        String prompt = raw.trim();
        if (prompt.isEmpty() || prompt.length() > 4000) { toast("Use 1–4000 characters."); stopPolling(); return; }
        if (apiKey.isEmpty()) { stopPolling(); openSettings(); return; }
        final JSONArray messages = new JSONArray();
        try {
            messages.put(new JSONObject().put("role", "system").put("content",
                    "You are Jessica, a concise, friendly AI assistant in a futuristic armored-suit HUD. "
                    + "Never call yourself Jarvis. Reactor graphics are fictional. You have no sensor, "
                    + "weather, notification or device-control tools, and no live data unless the user supplies it. "
                    + "Do not pretend to perform actions or observe the surroundings."));
            for (JSONObject message : history) messages.put(message);
            messages.put(new JSONObject().put("role", "user").put("content", prompt));
        } catch (org.json.JSONException exception) {
            stopPolling();
            toast("Unable to prepare chat.");
            return;
        }
        busy = true;
        int requestGeneration = generation;
        String requestKey = apiKey, requestModel = model;
        append("You: " + prompt);
        updateAssistant("JESSICA THINKING", "Contacting OpenRouter free model...");
        executor.execute(() -> {
            String answer;
            boolean success;
            try {
                answer = client.chat(requestKey, requestModel, messages,
                        () -> !active || requestGeneration != generation);
                success = true;
            } catch (Exception exception) {
                answer = exception instanceof java.io.IOException && exception.getMessage() != null
                        && exception.getMessage().startsWith("OpenRouter")
                        ? exception.getMessage() : "Unable to reach Jessica. Check your connection, key and free-model availability.";
                success = false;
            }
            final String reply = answer;
            final boolean ok = success;
            handler.post(() -> {
                busy = false;
                if (!active || isDestroyed() || requestGeneration != generation) return;
                append((ok ? "Jessica: " : "Connection: ") + reply);
                updateAssistant(ok ? "JESSICA ONLINE" : "JESSICA OFFLINE", reply);
                if (ok) {
                    try {
                        history.add(new JSONObject().put("role", "user").put("content", prompt));
                        history.add(new JSONObject().put("role", "assistant").put("content", reply));
                        while (history.size() > 12) history.remove(0);
                    } catch (org.json.JSONException ignored) { history.clear(); }
                    if (speak && ttsReady) tts.speak(reply.substring(0,
                            Math.min(reply.length(), TextToSpeech.getMaxSpeechInputLength())),
                            TextToSpeech.QUEUE_FLUSH, null, "jessica");
                    if (polling) handler.postDelayed(poll, 60000);
                } else stopPolling();
            });
        });
    }

    private void stopPolling() {
        polling = false;
        handler.removeCallbacks(poll);
        if (pollButton != null) pollButton.setText("Start polling (60s)");
    }

    private void append(String message) {
        transcript += "\n" + message + "\n";
        if (transcript.length() > 16000) transcript = transcript.substring(transcript.length() - 16000);
        if (chatText != null) chatText.setText(transcript);
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.rgb(90, 225, 255));
        return view;
    }

    private void button(LinearLayout row, String title, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(title);
        button.setOnClickListener(listener);
        row.addView(button);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
}
