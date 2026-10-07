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
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.SeekBar;
import android.text.TextWatcher;
import android.text.Editable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int VOICE_REQUEST = 10;
    private static final int EXPORT_REQUEST = 11;
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
    private String provider = "OpenRouter", failedPrompt, pendingExport;
    private boolean saveChats;
    private int accentColor = Color.rgb(50, 220, 255), hudBrightness = 100;
    private boolean showClock = true, showBattery = true;
    private boolean liveMessages;
    private int messageGeneration;
    private final java.util.LinkedHashMap<String, String> messageFeed = new java.util.LinkedHashMap<>();
    private String transcript = "Jessica ready. Configure OpenRouter to chat.\n";
    private TextView chatText;
    private TextView chatDisclosure;
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
        provider = getPreferences(MODE_PRIVATE).getString("provider", "OpenRouter");
        if (!java.util.Arrays.asList(OpenRouterClient.PROVIDERS).contains(provider)) provider = "OpenRouter";
        saveChats = getPreferences(MODE_PRIVATE).getBoolean("saveChats", false);
        accentColor = getPreferences(MODE_PRIVATE).getInt("accent", accentColor);
        hudBrightness = getPreferences(MODE_PRIVATE).getInt("brightness", 100);
        showClock = getPreferences(MODE_PRIVATE).getBoolean("clock", true);
        showBattery = getPreferences(MODE_PRIVATE).getBoolean("battery", true);
        motion = getPreferences(MODE_PRIVATE).getBoolean("motion", true);
        liveMessages = getPreferences(MODE_PRIVATE).getBoolean("liveMessages", false);
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
        button(row, "Gemini Live", v -> startActivity(new Intent(this, GeminiLiveActivity.class)));
        button(row, "Voice", v -> recognize());
        button(row, "Display", v -> chooseDisplay());
        button(row, "Customize", v -> customizeHud());
        button(row, "Messages", v -> configureMessages());
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
        updateModes();
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
        subscribeMessages();
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        hud.setRunning(true);
    }

    @Override protected void onStop() {
        active = false;
        messageGeneration++;
        LiveMessageService.setListener(null);
        messageFeed.clear();
        updateMessagePanel();
        generation++;
        stopPolling();
        closeExternalDisplay();
        client.cancel();
        if (busy) updateAssistant("JESSICA STANDBY", "Request cancelled when app left the foreground.");
        busy = false;
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
                        view.setAppearance(accentColor, hudBrightness, showClock, showBattery);
                        view.setMessages(liveMessages, new ArrayList<>(messageFeed.values()));
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
        hud.setAppearance(accentColor, hudBrightness, showClock, showBattery);
        if (externalHud != null) {
            externalHud.setModes(motion, green);
            externalHud.setAppearance(accentColor, hudBrightness, showClock, showBattery);
        }
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
        chatDisclosure = label("Text is sent to " + provider + "/model providers. Voice uses your installed speech service.");
        content.addView(chatDisclosure);
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
        button(actions, "Cancel", v -> cancelRequest());
        button(actions, "Retry", v -> retryPrompt());
        button(actions, "New chat", v -> confirmNewChat());
        button(actions, "Save chat", v -> saveConversation());
        button(actions, "Saved / search", v -> browseConversations());
        button(actions, "Export", v -> exportConversation());
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
                    .setMessage("Send this prompt and recent chat to " + provider + " every 60 seconds while this app is visible? Account quotas and possible billing apply. This does not read messages, notifications or live sensors.")
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
        chatDialog.setOnDismissListener(dialog -> { chatText = null; chatDisclosure = null; pollButton = null; });
        chatDialog.show();
    }

    private void openSettings() {
        if (busy) { toast("Cancel the current request before changing providers."); return; }
        LinearLayout fields = column();
        fields.setPadding(dp(20), dp(8), dp(20), 0);
        fields.addView(label("API key stays in memory only; re-enter after closing/recreating the app. Never add it to GitHub."));
        fields.addView(label("OpenRouter accepts only free models. Groq and Cerebras offer account-based free tiers, not guaranteed free model IDs. Check your account limits and billing before sending."));
        Spinner providerInput = new Spinner(this);
        providerInput.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                OpenRouterClient.PROVIDERS));
        providerInput.setSelection(java.util.Arrays.asList(OpenRouterClient.PROVIDERS).indexOf(provider));
        fields.addView(providerInput);
        EditText keyInput = new EditText(this);
        keyInput.setHint("Selected provider's API key");
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyInput.setText(apiKey);
        keyInput.setSaveEnabled(false);
        keyInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        fields.addView(keyInput);
        EditText modelInput = new EditText(this);
        modelInput.setHint("Browse models or enter a model ID");
        modelInput.setSingleLine();
        modelInput.setText(model);
        fields.addView(modelInput);
        providerInput.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (busy) cancelRequest();
                String selectedProvider = OpenRouterClient.PROVIDERS[position];
                if (!selectedProvider.equals(provider)) {
                    keyInput.setText("");
                    modelInput.setText(selectedProvider.equals("OpenRouter") ? "openrouter/free" : "");
                } else {
                    keyInput.setText(apiKey);
                    modelInput.setText(model);
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        button(fields, "Browse available models", v -> browseModels(
                providerInput.getSelectedItem().toString(), keyInput.getText().toString().trim(), modelInput));
        android.widget.CheckBox spoken = new android.widget.CheckBox(this);
        spoken.setText("Speak Jessica's replies (Android text-to-speech)");
        spoken.setChecked(speak);
        fields.addView(spoken);
        CheckBox storage = new CheckBox(this);
        storage.setText("Enable local saved chats (not encrypted; turning off deletes saved chats)");
        storage.setChecked(saveChats);
        fields.addView(storage);
        ScrollView settingsScroll = new ScrollView(this);
        settingsScroll.addView(fields);
        AlertDialog settings = new AlertDialog.Builder(this).setTitle("Providers / Jessica")
                .setView(settingsScroll).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        settings.show();
        settings.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        settings.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (busy) { toast("Wait for model browsing or cancel the request first."); return; }
            String selected = modelInput.getText().toString().trim();
            String selectedProvider = providerInput.getSelectedItem().toString();
            String key = keyInput.getText().toString().trim();
            if (!OpenRouterClient.isValidModel(selectedProvider, selected)) {
                modelInput.setError("Choose a valid model; OpenRouter requires a free model ID");
                return;
            }
            if (key.contains("\n") || key.contains("\r") || key.length() > 512) {
                keyInput.setError("Invalid API key");
                return;
            }
            stopPolling();
            if (!provider.equals(selectedProvider)) {
                history.clear();
                transcript = "Provider changed. New chat ready.\n";
                if (chatText != null) chatText.setText(transcript);
                failedPrompt = null;
            }
            provider = selectedProvider;
            if (chatDisclosure != null) chatDisclosure.setText("Text is sent to " + provider
                    + "/model providers. Voice uses your installed speech service.");
            apiKey = key;
            model = selected;
            speak = spoken.isChecked();
            if (!speak && tts != null) tts.stop();
            saveChats = storage.isChecked();
            android.content.SharedPreferences.Editor prefs = getPreferences(MODE_PRIVATE).edit()
                    .putString("model", model).putString("provider", provider).putBoolean("saveChats", saveChats);
            if (!saveChats) prefs.remove("conversations");
            prefs.apply();
            settings.dismiss();
            toast(key.isEmpty() ? "Key cleared. Offline HUD remains available." : "Jessica configured.");
        });
    }

    private void recognize() {
        if (busy) cancelRequest();
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
        if (request == EXPORT_REQUEST) {
            String text = pendingExport;
            pendingExport = null;
            if (result != RESULT_OK || data == null || data.getData() == null || text == null) return;
            android.net.Uri destination = data.getData();
            if (!android.content.ContentResolver.SCHEME_CONTENT.equals(destination.getScheme())
                    || !android.provider.DocumentsContract.isDocumentUri(this, destination)
                    || checkUriPermission(destination, android.os.Process.myPid(), android.os.Process.myUid(),
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                toast("Choose a writable document using Android's file picker.");
                return;
            }
            try (java.io.OutputStream output = getContentResolver().openOutputStream(destination, "wt")) {
                if (output == null) throw new java.io.IOException("No destination");
                output.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                toast("Conversation exported.");
            } catch (Exception exception) { toast("Unable to export to that destination."); }
            return;
        }
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
            case "cyan mode": green = false; accentColor = Color.rgb(50, 220, 255); updateModes(); break;
            case "pause effects": motion = false; updateModes(); break;
            case "resume effects": motion = true; updateModes(); break;
            case "fullscreen": fullscreen = true; applyFullscreen(); break;
            case "exit fullscreen": fullscreen = false; applyFullscreen(); break;
            case "cancel request": cancelRequest(); break;
            case "retry": retryPrompt(); break;
            case "new chat": confirmNewChat(); break;
            case "save chat": saveConversation(); break;
            case "open chat": openJessica(); break;
            case "customize hud": customizeHud(); break;
            case "gemini live": startActivity(new Intent(this, GeminiLiveActivity.class)); break;
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
        String requestKey = apiKey, requestModel = model, requestProvider = provider;
        failedPrompt = prompt;
        append("You: " + prompt);
        String requestTranscript = transcript;
        updateAssistant("JESSICA THINKING", "Contacting " + provider + "...");
        executor.execute(() -> {
            String answer;
            boolean success;
            try {
                answer = client.chat(requestProvider, requestKey, requestModel, messages,
                        () -> !active || requestGeneration != generation, partial -> handler.post(() -> {
                            if (!active || isDestroyed() || requestGeneration != generation) return;
                            updateAssistant("JESSICA STREAMING", partial);
                            if (chatText != null) chatText.setText(requestTranscript + "\nJessica: " + partial);
                        }));
                success = true;
            } catch (Exception exception) {
                answer = exception instanceof java.io.IOException && exception.getMessage() != null
                        ? exception.getMessage() : "Unable to reach Jessica. Check your connection, key and model availability.";
                success = false;
            }
            final String reply = answer;
            final boolean ok = success;
            handler.post(() -> {
                if (!active || isDestroyed() || requestGeneration != generation) return;
                busy = false;
                append((ok ? "Jessica: " : "Connection: ") + reply);
                updateAssistant(ok ? "JESSICA ONLINE" : "JESSICA OFFLINE", reply);
                if (ok) {
                    failedPrompt = null;
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

    private void cancelRequest() {
        stopPolling();
        generation++;
        client.cancel();
        busy = false;
        if (chatText != null) chatText.setText(transcript);
        updateAssistant("JESSICA STANDBY", "Cancelled. Already submitted data cannot be recalled.");
    }

    private void retryPrompt() {
        if (failedPrompt == null) { toast("No failed or cancelled message to retry."); return; }
        stopPolling();
        sendPrompt(failedPrompt);
    }

    private void browseModels(String selectedProvider, String key, EditText target) {
        if (busy) { toast("A request is already running."); return; }
        if (key.length() > 512 || key.contains("\n") || key.contains("\r")) {
            toast("Invalid API key."); return;
        }
        busy = true;
        int token = generation;
        toast("Fetching " + selectedProvider + " model catalog...");
        executor.execute(() -> {
            java.util.List<String> models = null;
            String error = null;
            try { models = client.models(selectedProvider, key, () -> !active || generation != token); }
            catch (Exception exception) { error = "Could not load models. Check your key, connection and provider availability."; }
            final java.util.List<String> found = models;
            final String failure = error;
            handler.post(() -> {
                if (!active || isDestroyed() || generation != token) return;
                busy = false;
                if (!target.isAttachedToWindow()) return;
                if (failure != null) { toast(failure); return; }
                if (found == null || found.isEmpty()) { toast("No eligible models available."); return; }
                new AlertDialog.Builder(this).setTitle(selectedProvider + " models")
                        .setItems(found.toArray(new String[0]), (dialog, index) -> {
                            if (target.isAttachedToWindow()) target.setText(found.get(index));
                        }).setNegativeButton("Cancel", null).show();
            });
        });
    }

    private void confirmNewChat() {
        new AlertDialog.Builder(this).setTitle("Start a new chat?")
                .setMessage("Clears the current in-memory conversation and cancels requests. Saved chats are kept.")
                .setNegativeButton("Cancel", null).setPositiveButton("Clear", (dialog, which) -> {
                    cancelRequest();
                    history.clear();
                    failedPrompt = null;
                    transcript = "Jessica ready. New chat.\n";
                    if (chatText != null) chatText.setText(transcript);
                }).show();
    }

    private JSONArray savedConversations() {
        String data = getPreferences(MODE_PRIVATE).getString("conversations", "[]");
        if (data.length() > 512000) return new JSONArray();
        try { return new JSONArray(data); }
        catch (org.json.JSONException exception) { return new JSONArray(); }
    }

    private void saveConversation() {
        if (!saveChats) { toast("Enable local saved chats in Settings first."); return; }
        if (busy) { toast("Finish or cancel the current reply before saving."); return; }
        if (history.isEmpty()) { toast("No completed conversation to save."); return; }
        try {
            JSONArray chats = savedConversations();
            String title = history.get(0).optString("content", "Chat");
            title = title.substring(0, Math.min(64, title.length()));
            JSONArray messages = new JSONArray();
            for (JSONObject message : history) messages.put(message);
            JSONObject snapshot = new JSONObject().put("title", title)
                    .put("text", transcript).put("messages", messages)
                    .put("time", System.currentTimeMillis());
            JSONArray bounded = new JSONArray().put(snapshot);
            int size = snapshot.toString().length() + 2;
            for (int i = 0; i < Math.min(chats.length(), 9); i++) {
                Object previous = chats.get(i);
                int addition = previous.toString().length() + 1;
                if (size + addition > 500000) break;
                bounded.put(previous);
                size += addition;
            }
            getPreferences(MODE_PRIVATE).edit().putString("conversations", bounded.toString()).apply();
            toast("Saved on this device (up to 10 snapshots).");
        } catch (org.json.JSONException exception) { toast("Could not save this conversation."); }
    }

    private void browseConversations() {
        LinearLayout content = column();
        EditText search = new EditText(this);
        search.setHint("Search saved conversations");
        content.addView(search);
        android.widget.ListView list = new android.widget.ListView(this);
        content.addView(list, new LinearLayout.LayoutParams(-1, dp(240)));
        ArrayList<JSONObject> visible = new ArrayList<>();
        Runnable refresh = () -> {
            visible.clear();
            ArrayList<String> titles = new ArrayList<>();
            JSONArray chats = savedConversations();
            String query = search.getText().toString().toLowerCase(Locale.ROOT);
            for (int i = 0; i < Math.min(chats.length(), 10); i++) {
                JSONObject item = chats.optJSONObject(i);
                if (item == null || !(item.optString("title") + "\n" + item.optString("text"))
                        .toLowerCase(Locale.ROOT).contains(query)) continue;
                visible.add(item);
                titles.add(item.optString("title", "Chat") + "\n"
                        + new java.util.Date(item.optLong("time")));
            }
            list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, titles));
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { refresh.run(); }
            @Override public void afterTextChanged(Editable text) { }
        });
        AlertDialog browser = new AlertDialog.Builder(this).setTitle("Saved chats (local only)")
                .setView(content).setNegativeButton("Close", null)
                .setNeutralButton("Delete all", (dialog, which) ->
                    new AlertDialog.Builder(this).setTitle("Delete all saved chats?")
                            .setNegativeButton("Cancel", null).setPositiveButton("Delete", (d, w) ->
                                getPreferences(MODE_PRIVATE).edit().remove("conversations").apply()).show())
                .create();
        list.setOnItemClickListener((parent, view, index, id) -> {
            JSONObject snapshot = visible.get(index);
            new AlertDialog.Builder(this).setTitle("Load saved chat?")
                    .setMessage("Replaces the current chat. Future sends include this history to " + provider + ".")
                    .setNegativeButton("Cancel", null).setPositiveButton("Load", (d, w) -> {
                        cancelRequest();
                        history.clear();
                        JSONArray messages = snapshot.optJSONArray("messages");
                        if (messages != null) {
                            for (int i = 0; i < Math.min(messages.length(), 12); i++) {
                                JSONObject message = messages.optJSONObject(i);
                                if (message != null && ("user".equals(message.optString("role"))
                                        || "assistant".equals(message.optString("role")))) history.add(message);
                            }
                        }
                        transcript = snapshot.optString("text", "");
                        if (transcript.length() > 16000) transcript = transcript.substring(transcript.length() - 16000);
                        failedPrompt = null;
                        if (chatText != null) chatText.setText(transcript);
                        browser.dismiss();
                        openJessica();
                    }).show();
        });
        refresh.run();
        browser.show();
    }

    private void exportConversation() {
        if (busy) { toast("Finish or cancel the reply before exporting."); return; }
        new AlertDialog.Builder(this).setTitle("Export chat text?")
                .setMessage("The exported file contains your conversation, not API keys. Anyone with access to the chosen destination may read it.")
                .setNegativeButton("Cancel", null).setPositiveButton("Choose file", (dialog, which) -> {
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("text/plain");
                    intent.putExtra(Intent.EXTRA_TITLE, "jessica-chat.txt");
                    pendingExport = transcript;
                    try { startActivityForResult(intent, EXPORT_REQUEST); }
                    catch (ActivityNotFoundException exception) {
                        pendingExport = null;
                        toast("No document picker installed.");
                    }
                }).show();
    }

    private void customizeHud() {
        LinearLayout fields = column();
        Spinner colors = new Spinner(this);
        colors.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Cyan", "Green", "Amber", "Violet"}));
        int[] values = {Color.rgb(50, 220, 255), Color.rgb(80, 255, 120),
                Color.rgb(255, 185, 50), Color.rgb(190, 125, 255)};
        for (int i = 0; i < values.length; i++) if (values[i] == accentColor) colors.setSelection(i);
        fields.addView(colors);
        fields.addView(label("HUD brightness (20–100%, not screen brightness)"));
        SeekBar brightness = new SeekBar(this);
        brightness.setMax(80);
        brightness.setProgress(Math.max(0, Math.min(80, hudBrightness - 20)));
        fields.addView(brightness);
        CheckBox clock = new CheckBox(this), battery = new CheckBox(this), effects = new CheckBox(this);
        clock.setText("Show clock"); clock.setChecked(showClock); fields.addView(clock);
        battery.setText("Show battery"); battery.setChecked(showBattery); fields.addView(battery);
        effects.setText("Animate reactor"); effects.setChecked(motion); fields.addView(effects);
        new AlertDialog.Builder(this).setTitle("HUD customization").setView(fields)
                .setNegativeButton("Cancel", null).setPositiveButton("Apply", (dialog, which) -> {
                    accentColor = values[colors.getSelectedItemPosition()];
                    green = false;
                    hudBrightness = brightness.getProgress() + 20;
                    showClock = clock.isChecked(); showBattery = battery.isChecked(); motion = effects.isChecked();
                    updateModes();
                    getPreferences(MODE_PRIVATE).edit().putInt("accent", accentColor)
                            .putInt("brightness", hudBrightness).putBoolean("clock", showClock)
                            .putBoolean("battery", showBattery).putBoolean("motion", motion).apply();
                }).show();
    }

    private void configureMessages() {
        new AlertDialog.Builder(this).setTitle("Right-side live messages")
                .setMessage("Opt in to Android notification access to show incoming message notifications on the HUD and external display while this app is visible. Android grants access to all notifications, but Jessica only displays message-category notifications. Content stays in memory, is cleared when you leave, and is never added to AI prompts, saved chats or exports. Anyone viewing your display can read it.")
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Disable / clear", (dialog, which) -> {
                    liveMessages = false;
                    messageGeneration++;
                    LiveMessageService.setListener(null);
                    messageFeed.clear();
                    getPreferences(MODE_PRIVATE).edit().putBoolean("liveMessages", false).apply();
                    updateMessagePanel();
                    toast("Panel disabled. You can also revoke notification access in Android Settings.");
                })
                .setPositiveButton("Enable / access settings", (dialog, which) -> {
                    liveMessages = true;
                    getPreferences(MODE_PRIVATE).edit().putBoolean("liveMessages", true).apply();
                    updateMessagePanel();
                    Intent intent = new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                    try { startActivity(intent); }
                    catch (ActivityNotFoundException exception) { toast("Notification access settings are unavailable."); }
                    subscribeMessages();
                }).show();
    }

    private void subscribeMessages() {
        if (!liveMessages || !active) { LiveMessageService.setListener(null); return; }
        int token = ++messageGeneration;
        LiveMessageService.setListener((key, text) -> handler.post(() -> {
            if (!active || !liveMessages || isDestroyed() || token != messageGeneration) return;
            messageFeed.remove(key);
            messageFeed.put(key, text);
            while (messageFeed.size() > 4) messageFeed.remove(messageFeed.keySet().iterator().next());
            updateMessagePanel();
        }));
        updateMessagePanel();
    }

    private void updateMessagePanel() {
        ArrayList<String> messages = new ArrayList<>(messageFeed.values());
        if (hud != null) hud.setMessages(liveMessages, messages);
        if (externalHud != null) externalHud.setMessages(liveMessages, messages);
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
