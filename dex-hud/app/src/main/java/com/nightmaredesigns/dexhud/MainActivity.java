package com.nightmaredesigns.dexhud;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.media.AudioManager;
import android.media.projection.MediaProjectionManager;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
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
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements HudState.Listener {
    private static final int VOICE_REQUEST = 10;
    private static final int API_VOICE_REQUEST = 11;
    private static final int CAPTURE_REQUEST = 12, AUDIO_PERMISSIONS = 13;
    private static final int LOCATION_PERMISSIONS = 14;
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
    private String transcript = "Optional OpenRouter API chat. Free models still require an OpenRouter key.\n";
    private TextView chatText;
    private Button pollButton;
    private AlertDialog chatDialog;
    private AlertDialog navigationDialog;
    private HudState state;
    private HudDisplay externalDisplay;
    private DisplayManager displays;
    private boolean motion = true, green, resumed;
    private int hudStyle = HudView.STYLE_MINIMAL;
    private String pendingVoice;
    private boolean pendingVoiceForApi;
    private Intent pendingCaptureConsent;
    private boolean capturePending;
    private Button musicButton, navigationButton, captureButton;

    private final Runnable poll = () -> {
        if (active && polling && !busy) sendPrompt(pollingPrompt);
    };
    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        state = HudState.get(this);
        if (!state.settings.contains("style")) {
            state.settings.edit().putInt("style", getPreferences(MODE_PRIVATE)
                    .getInt("hudStyle", HudView.STYLE_MINIMAL))
                    .putBoolean("matrix", getPreferences(MODE_PRIVATE).getBoolean("matrix", false))
                    .putBoolean("motion", getPreferences(MODE_PRIVATE).getBoolean("motion", true)).apply();
        }
        externalDisplay = new HudDisplay(this, state,
                () -> toast("External display disconnected or unavailable. Choose Display to reconnect."));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        model = getPreferences(MODE_PRIVATE).getString("model", "openrouter/free");
        hudStyle = state.settings.getInt("style", HudView.STYLE_MINIMAL);
        if (hudStyle < HudView.STYLE_MINIMAL || hudStyle > HudView.STYLE_TERMINAL) {
            hudStyle = HudView.STYLE_MINIMAL;
        }
        green = state.enabled("matrix", false);
        motion = state.enabled("motion", true);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
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
        state.apply(hud);
        root.addView(hud, new LinearLayout.LayoutParams(-1, 0, 1));
        android.widget.HorizontalScrollView controls = new android.widget.HorizontalScrollView(this);
        LinearLayout row = row();
        button(row, "Jessica", v -> openJessica());
        button(row, "HUD Settings", v -> openHudSettings());
        button(row, "OpenRouter web • no key", v -> openOpenRouterWeb());
        button(row, "Gemini / Live", v -> launchGemini());
        button(row, "HUD voice", v -> recognize());
        button(row, "Display", v -> chooseDisplay());
        button(row, "Fullscreen", v -> { fullscreen = !fullscreen; applyFullscreen(); });
        musicButton = button(row, "Music controls", v -> openMusic());
        navigationButton = button(row, "Navigation", v -> openNavigation());
        controls.addView(row);
        root.addView(controls);
        setContentView(root);
        displays = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        tts = new TextToSpeech(this, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (ttsReady) {
                int result = tts.setLanguage(Locale.getDefault());
                ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED;
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { ttsVisual(true); }
                    @Override public void onDone(String id) { ttsVisual(false); }
                    @Override public void onError(String id) { ttsVisual(false); }
                    @Override public void onStop(String id, boolean interrupted) { ttsVisual(false); }
                });
            }
        });
        applyFullscreen();
    }

    @Override protected void onStart() {
        super.onStart();
        active = true;
        state.add(this);
        hud.setRunning(true);
    }

    @Override protected void onStop() {
        active = false;
        if (navigationDialog != null) navigationDialog.dismiss();
        generation++;
        stopPolling();
        externalDisplay.stop();
        state.remove(this);
        client.cancel();
        if (busy) updateAssistant("JESSICA STANDBY", "Request cancelled when app left the foreground.");
        if (tts != null) tts.stop();
        state.ttsSpeaking = false;
        if (state.capturing) state.changed();
        else state.clearText();
        hud.setRunning(false);
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (!notificationAccess()) {
            state.notifications = "";
            state.changed();
        } else if (state.enabled("notifications", false)) {
            NotificationListenerService.requestRebind(new ComponentName(this, HudNotifications.class));
        }
        if (pendingCaptureConsent != null) beginCapture();
        if (pendingVoice != null) {
            String prompt = pendingVoice;
            pendingVoice = null;
            handleVoice(prompt, pendingVoiceForApi);
        }
    }

    @Override protected void onPause() {
        resumed = false;
        super.onPause();
    }

    @Override protected void onDestroy() {
        externalDisplay.stop();
        if (!isChangingConfigurations()) {
            stopCapture();
            state.navigation.stop();
            state.clearText();
        }
        apiKey = "";
        history.clear();
        transcript = "";
        pollingPrompt = "";
        pendingVoice = null;
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
                    state.displayId = item == 0 ? -1 : available[item - 1].getDisplayId();
                    state.changed();
                }).setNegativeButton("Cancel", null)
                .setMessage(available.length == 0
                        ? "No separate presentation display detected. Your phone may be mirroring, or the display is not connected."
                        : null).show();
    }

    private void updateModes() {
        state.settings.edit().putInt("style", hudStyle)
                .putBoolean("matrix", green).putBoolean("motion", motion).apply();
        state.settingsChanged();
    }

    private void chooseStyle() {
        new AlertDialog.Builder(this).setTitle("Black-background HUD style")
                .setSingleChoiceItems(new String[]{"Minimal / border only", "Reactor / rings",
                        "Terminal / text only"}, hudStyle, (dialog, item) -> {
                    hudStyle = item;
                    updateModes();
                    dialog.dismiss();
                }).setNegativeButton("Cancel", null).show();
    }

    private void updateAssistant(String status, String reply) {
        state.assistant(status, reply);
    }

    @Override public void onHudChanged() {
        hudStyle = state.settings.getInt("style", HudView.STYLE_MINIMAL);
        green = state.enabled("matrix", false);
        motion = state.enabled("motion", true);
        state.apply(hud);
        musicButton.setVisibility(state.enabled("music", false) ? View.VISIBLE : View.GONE);
        navigationButton.setVisibility(state.enabled("offlineNavigation", false) ? View.VISIBLE : View.GONE);
        if (!state.enabled("offlineNavigation", false) && navigationDialog != null) navigationDialog.dismiss();
        if (captureButton != null) {
            String title = state.capturing ? "Stop playback visualization" : "Start playback visualization";
            if (!title.contentEquals(captureButton.getText())) captureButton.setText(title);
        }
        if (!active || state.capturing) externalDisplay.stop();
        else externalDisplay.start();
    }

    private void ttsVisual(boolean speakingNow) {
        handler.post(() -> {
            state.ttsSpeaking = speakingNow && active && state.enabled("dots", true);
            state.changed();
        });
    }

    private void openHudSettings() {
        LinearLayout fields = column();
        fields.setPadding(dp(16), dp(8), dp(16), dp(8));
        fields.addView(label("HUD modules • settings apply to phone and selected external display."));
        TextView brightnessLabel = label("HUD content brightness: " + state.brightness() + "%");
        fields.addView(brightnessLabel);
        SeekBar brightness = new SeekBar(this);
        brightness.setMax(90);
        brightness.setProgress(state.brightness() - 10);
        brightness.setContentDescription("HUD content brightness, 10 to 100 percent");
        brightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int percent = progress + 10;
                brightnessLabel.setText("HUD content brightness: " + percent + "%");
                state.settings.edit().putInt("brightness", percent).apply();
                state.settingsChanged();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        fields.addView(brightness);
        button(fields, "Reset HUD brightness to 100%", v -> {
            brightness.setProgress(90);
            state.settings.edit().putInt("brightness", 100).apply();
            state.settingsChanged();
        });
        fields.addView(label("Dims ALL HUD content over pure black, not phone/glasses hardware brightness. "
                + "Settings and controls remain bright so you can reset it. Glasses brightness, "
                + "electrochromic tint, IPD and 3DoF/head tracking are not exposed by generic Android "
                + "Presentation: use glasses buttons or the vendor app where supported."));
        toggle(fields, "Clock / date", "clock", true);
        toggle(fields, "Battery (listen only while a HUD is active)", "battery", true);
        toggle(fields, "Reactor decoration (Reactor style only)", "reactor", true);
        button(fields, "Choose HUD style", v -> chooseStyle());
        toggle(fields, "Matrix rain", "matrix", false);
        toggle(fields, "Animate decorative FX", "motion", true);
        toggle(fields, "AI reply panel", "assistant", true);
        toggle(fields, "Digital dot matrix", "dots", true);
        captureButton = button(fields, state.capturing ? "Stop playback visualization" : "Start playback visualization",
                v -> { if (state.capturing) stopCapture(); else explainCapture(); });
        fields.addView(label("Dots use eligible playback RMS, not speech detection/transcription. "
                + "App-owned TTS uses begin/end timing only when capture is off."));
        button(fields, "Paste / type an AI reply (memory only)", v -> manualReply());
        button(fields, "Clear AI reply", v -> updateAssistant("JESSICA STANDBY", ""));
        android.widget.CheckBox notificationBox = toggle(fields,
                "Public notifications (optional access)", "notifications", false);
        button(fields, "Grant / revoke notification access", v -> {
            try { startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
            catch (ActivityNotFoundException | SecurityException exception) { toast("Notification access settings unavailable."); }
        });
        button(fields, "Clear notification text", v -> {
            state.notifications = "";
            state.changed();
            // Unbind discards the listener's bounded in-memory cache too.
            setModule("notifications", false);
            notificationBox.setChecked(false);
            toast("Notification module disabled and cleared. Re-enable in HUD Settings if needed.");
        });
        fields.addView(label("Public notifications only; private/secret content and likely codes are excluded. "
                + "Filtering is not a guarantee. Up to 4 items in memory, never saved, logged, "
                + "exported or sent to AI. Text clears on disable/access loss. No SMS permission."));
        toggle(fields, "Music controls / system player launcher", "music", false);
        toggle(fields, "Compass / offline navigation", "offlineNavigation", false);
        fields.addView(label("Left HUD: phone magnetic compass and your supplied waypoint path only. "
                + "No routing provider, street turns, map app, key or payment. Location is foreground-only; "
                + "Navigation explains permission and memory-only coordinate use."));
        toggle(fields, "External button shortcuts (focused HUD only)", "buttonShortcuts", false);
        fields.addView(label("Tap the HUD to focus it. Delivered Android DPAD Left/Right cycles styles; "
                + "Space/Enter/DPAD Center toggles decorative motion. Keys are not intercepted in text "
                + "inputs, dialogs, controls or other apps. Volume/media keys retain Android behavior. "
                + "Glasses buttons may not send app key events. No proprietary button mapping or head tracking."));
        button(fields, "Choose display (Pixel / Samsung / other)", v -> chooseDisplay());
        button(fields, "Samsung AI / Bixby • setup", v -> openSamsungAi());
        button(fields, "OpenRouter web • no API key in HUD", v -> openOpenRouterWeb());
        button(fields, "Optional authenticated API settings", v -> openSettings());
        ScrollView scroll = new ScrollView(this);
        scroll.addView(fields);
        AlertDialog settings = new AlertDialog.Builder(this).setTitle("Central HUD Settings").setView(scroll)
                .setPositiveButton("Close", null).create();
        settings.setOnDismissListener(dialog -> captureButton = null);
        settings.show();
    }

    private android.widget.CheckBox toggle(LinearLayout fields, String title, String key, boolean fallback) {
        android.widget.CheckBox box = new android.widget.CheckBox(this);
        box.setText(title);
        box.setChecked(state.enabled(key, fallback));
        box.setOnCheckedChangeListener((button, checked) -> {
            if ("notifications".equals(key) && checked) {
                if (Build.VERSION.SDK_INT < 27) {
                    box.setChecked(false);
                    toast("Notification module requires Android 8.1 or later.");
                    return;
                }
                new AlertDialog.Builder(this).setTitle("Notification privacy")
                        .setMessage("Android grants broad notification access, although this HUD processes only "
                                + "public notifications while enabled. Private/secret items and likely OTP/secret text "
                                + "are excluded; filtering cannot guarantee redaction. Up to 4 items stay in memory only. "
                                + "Nothing is saved, logged, exported or sent to an AI. Enable, then explicitly grant "
                                + "Jessica notification access in system settings.")
                        .setPositiveButton("Enable module", (dialog, which) -> {
                            setModule(key, true);
                            if (!notificationAccess()) toast("Now use Grant / revoke notification access.");
                        })
                        .setNegativeButton("Cancel", (dialog, which) -> box.setChecked(false))
                        .setOnCancelListener(dialog -> box.setChecked(false)).show();
            } else setModule(key, checked);
        });
        fields.addView(box);
        return box;
    }

    private void setModule(String key, boolean enabled) {
        state.settings.edit().putBoolean(key, enabled).apply();
        green = state.enabled("matrix", false);
        motion = state.enabled("motion", true);
        state.settingsChanged();
        if ("dots".equals(key) && !enabled) stopCapture();
        if ("notifications".equals(key) && enabled && notificationAccess()) {
            NotificationListenerService.requestRebind(new ComponentName(this, HudNotifications.class));
        }
    }

    private boolean notificationAccess() {
        return Build.VERSION.SDK_INT >= 27 && ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .isNotificationListenerAccessGranted(new ComponentName(this, HudNotifications.class));
    }

    private void manualReply() {
        EditText text = new EditText(this);
        text.setHint("Manually paste a reply. Gemini transcripts are not available to this app.");
        text.setMinLines(3);
        text.setMaxLines(8);
        text.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4000)});
        text.setSaveEnabled(false);
        text.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        new AlertDialog.Builder(this).setTitle("Manual AI reply • memory only")
                .setView(text).setMessage("Only text you enter here is shown. Clipboard is not read automatically; "
                        + "use the system Paste menu. This text is not sent to an API.")
                .setPositiveButton("Show in HUD", (dialog, which) ->
                        updateAssistant("MANUAL REPLY / NOT A LIVE TRANSCRIPT", text.getText().toString()))
                .setNegativeButton("Cancel", null).show();
    }

    private void openMusic() {
        if (!state.enabled("music", false)) return;
        LinearLayout actions = column();
        actions.setPadding(dp(16), dp(8), dp(16), dp(8));
        actions.addView(label("System media keys target Android's current player. A player/session must be active; "
                + "no player metadata, media-session access or automatic playback detection."));
        button(actions, "Open system music app", v -> {
            try {
                startActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC));
            } catch (ActivityNotFoundException | SecurityException exception) {
                toast("No system music app resolved. Open your preferred player manually.");
            }
        });
        button(actions, "Previous", v -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS));
        button(actions, "Play / pause", v -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        button(actions, "Next", v -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT));
        new AlertDialog.Builder(this).setTitle("Manual music controls").setView(actions)
                .setPositiveButton("Close", null).show();
    }

    private void mediaKey(int key) {
        if (!state.enabled("music", false)) return;
        AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        try {
            audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
            audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
            toast("System media key sent. The active player decides how to respond.");
        } catch (SecurityException exception) { toast("Your system did not allow this media key."); }
    }

    private void openNavigation() {
        if (!state.enabled("offlineNavigation", false)) return;
        LinearLayout fields = column();
        fields.setPadding(dp(16), dp(8), dp(16), dp(8));
        fields.setSaveEnabled(false);
        fields.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        fields.addView(label("Offline supplied-route following, NOT generated street directions. "
                + "Coordinates only: no address search. Plan a safe path yourself: "
                + "lines latitude,longitude[,label] in travel order, "
                + "maximum 100 waypoints / 20,000 characters / 80-character labels. "
                + "One point means direct bearing / straight line, not a road route. "
                + "Walking/Driving only select GPS following tolerances; they do not optimize roads, "
                + "paths, legality or safety.\n\n"
                + "Start requests foreground location. Jessica uses Android GPS only while this activity "
                + "is visible, never background tracking (including during playback capture). "
                + "Precise permission is needed for GPS guidance; approximate permission can start a path "
                + "but cannot auto-advance or provide GPS guidance. Coordinates/labels stay in process memory: "
                + "never saved, logged or sent to a network, routing provider, chat or AI. "
                + "Stop or disabling this module clears the path. Android/keyboard privacy settings still apply.\n\n"
                + "Phone magnetic compass is NOT glasses pose. GPS travel course and target bearing "
                + "are separate true-north values. No automatic road turns or off-route shortcuts. "
                + "Do not operate controls while driving."));
        RadioGroup modes = new RadioGroup(this);
        RadioButton walking = new RadioButton(this), driving = new RadioButton(this);
        walking.setId(View.generateViewId());
        driving.setId(View.generateViewId());
        walking.setText("Walking following tolerances");
        driving.setText("Driving following tolerances");
        modes.addView(walking);
        modes.addView(driving);
        modes.check(state.navigation.driving() ? driving.getId() : walking.getId());
        fields.addView(modes);
        EditText routeInput = new EditText(this);
        routeInput.setHint("51.5007,-0.1246,Start\n51.5010,-0.1250,Next");
        routeInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        routeInput.setMinLines(3);
        routeInput.setMaxLines(6);
        routeInput.setGravity(Gravity.TOP);
        routeInput.setSaveEnabled(false);
        routeInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        if (state.navigation.route() != null) routeInput.setText(state.navigation.route().editableText());
        fields.addView(routeInput);
        TextView status = label(state.navigation.route() == null ? "No active route" : "Current route in left HUD");
        fields.addView(status);
        LinearLayout actions = row();
        Button previous = button(actions, "Previous", v -> {
            state.navigation.step(-1);
            status.setText("Manual selection • see left HUD. Editor changes require Start / replace.");
        });
        Button next = button(actions, "Next", v -> {
            state.navigation.step(1);
            status.setText("Manual selection • see left HUD. Editor changes require Start / replace.");
        });
        previous.setEnabled(state.navigation.route() != null);
        next.setEnabled(state.navigation.route() != null);
        button(actions, "Stop / clear", v -> {
            state.navigation.stop();
            routeInput.setText("");
            previous.setEnabled(false);
            next.setEnabled(false);
            status.setText("Route cleared • compass only");
        });
        android.widget.HorizontalScrollView actionScroll = new android.widget.HorizontalScrollView(this);
        actionScroll.addView(actions);
        fields.addView(actionScroll);
        ScrollView scroll = new ScrollView(this);
        scroll.setSaveEnabled(false);
        scroll.addView(fields);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Compass / offline navigation")
                .setView(scroll).setPositiveButton("Start / replace", null)
                .setNegativeButton("Close", null).create();
        navigationDialog = dialog;
        dialog.setOnDismissListener(ignored -> {
            routeInput.setText("");
            if (navigationDialog == dialog) navigationDialog = null;
        });
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!active || !state.enabled("offlineNavigation", false)) { dialog.dismiss(); return; }
            OfflineRoute supplied;
            try { supplied = OfflineRoute.parse(routeInput.getText().toString()); }
            catch (IllegalArgumentException exception) {
                routeInput.setError(exception.getMessage());
                return;
            }
            if (!state.navigation.permitted()) {
                status.setText("Permission requested. After granting it, tap Start / replace again.");
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_PERMISSIONS);
                return;
            }
            state.navigation.start(supplied, modes.getCheckedRadioButtonId() == driving.getId());
            previous.setEnabled(state.navigation.route() != null);
            next.setEnabled(state.navigation.route() != null);
            status.setText("Following supplied path in left HUD. Stop clears route; Close keeps following.");
        });
    }

    private void explainCapture() {
        if (Build.VERSION.SDK_INT < 29) { toast("Playback capture requires Android 10+."); return; }
        if (!state.enabled("dots", true)) { toast("Enable Digital dot matrix first."); return; }
        if (capturePending) { toast("A capture permission request is already open."); return; }
        new AlertDialog.Builder(this).setTitle("Visualize playback • explicit consent")
                .setMessage("Uses Android playback capture and RECORD_AUDIO permission, never a microphone source. "
                        + "PCM is reduced to coarse RMS levels in memory: no files, network, speech detection or "
                        + "transcription. Only media/game/unknown usage permitted by the source app and Android "
                        + "can be captured. Gemini/Live and other AI apps may block capture or use an ineligible "
                        + "audio usage. Silent dots mean silence or blocked audio, not detected speech.\n\n"
                        + "Android also asks for screen-sharing consent; no screen frames are read. During capture "
                        + "the foreground service keeps the explicitly selected external HUD visible when opening "
                        + "Gemini; the phone HUD cannot overlay another app. STOP in the notification or HUD Settings "
                        + "ends capture. Closing the HUD task, disconnecting the selected display or revoking "
                        + "consent also ends it.")
                .setPositiveButton("Continue", (dialog, which) -> requestCapture())
                .setNegativeButton("Cancel", null).show();
    }

    private void requestCapture() {
        if (capturePending || state.capturing || !state.enabled("dots", true)) return;
        capturePending = true;
        ArrayList<String> permissions = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!permissions.isEmpty()) requestPermissions(permissions.toArray(new String[0]), AUDIO_PERMISSIONS);
        else projectionConsent();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == LOCATION_PERMISSIONS) {
            toast(state.navigation.permitted() ? "Permission granted. Tap Navigation Start to follow your path."
                    : "Location denied. Compass still works; no route guidance started.");
            return;
        }
        if (request != AUDIO_PERMISSIONS) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                toast("Notification denied: use HUD Settings or Android's active-app controls to stop capture.");
            }
            projectionConsent();
        } else {
            capturePending = false;
            toast("Playback capture not started. Audio permission is required; no microphone fallback.");
        }
    }

    private void projectionConsent() {
        if (Build.VERSION.SDK_INT < 29 || !state.enabled("dots", true)) { capturePending = false; return; }
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        try {
            Intent intent = Build.VERSION.SDK_INT >= 34
                    ? manager.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
                    : manager.createScreenCaptureIntent();
            startActivityForResult(intent, CAPTURE_REQUEST);
        } catch (ActivityNotFoundException | SecurityException exception) {
            capturePending = false;
            toast("Android playback-sharing consent is unavailable.");
        }
    }

    private void beginCapture() {
        Intent consent = pendingCaptureConsent;
        pendingCaptureConsent = null;
        capturePending = false;
        if (Build.VERSION.SDK_INT < 29 || consent == null || !state.enabled("dots", true)) return;
        try {
            startForegroundService(new Intent(this, PlaybackCaptureService.class)
                    .putExtra(PlaybackCaptureService.RESULT, RESULT_OK)
                    .putExtra(PlaybackCaptureService.CONSENT, consent));
        } catch (RuntimeException exception) {
            state.audioStatus = "ANDROID DID NOT ALLOW CAPTURE START";
            state.changed();
            toast("Capture did not start. Try again while Jessica is visible.");
        }
    }

    private void stopCapture() {
        pendingCaptureConsent = null;
        capturePending = false;
        // stopService also handles a foreground start that has not yet reached onStartCommand.
        stopService(new Intent(this, PlaybackCaptureService.class));
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
        LinearLayout routes = column();
        routes.setPadding(dp(16), dp(8), dp(16), dp(8));
        routes.addView(label("Key-free app handoffs: open installed Gemini and start Live there, "
                + "or configure Samsung's Bixby on a supported Samsung phone. These are not embedded "
                + "assistants and Jessica cannot read their replies.\n\n"
                + "OpenRouter web needs no API key in Jessica. Optional API chat remains authenticated."));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(routes);
        AlertDialog chooser = new AlertDialog.Builder(this).setTitle("Jessica / assistant")
                .setView(scroll).setPositiveButton("Close", null).create();
        button(routes, "Open Gemini / Live", v -> { chooser.dismiss(); launchGemini(); });
        button(routes, "Samsung AI / Bixby • setup", v -> { chooser.dismiss(); openSamsungAi(); });
        button(routes, "OpenRouter web • no key in HUD", v -> { chooser.dismiss(); openOpenRouterWeb(); });
        button(routes, "Optional authenticated API chat", v -> { chooser.dismiss(); openOpenRouterChat(); });
        chooser.show();
    }

    private void openSamsungAi() {
        if (!"samsung".equalsIgnoreCase(Build.MANUFACTURER)) {
            new AlertDialog.Builder(this).setTitle("Samsung AI / Bixby • unavailable device")
                    .setMessage("This is not a Samsung device. Jessica does not install Bixby or provide "
                            + "Galaxy AI on Pixel/other phones. You may instead open your configured standard "
                            + "Android assistant. No API key is needed in this HUD and no replies are read.")
                    .setPositiveButton("Standard assistant", (dialog, which) -> launchPhoneAssistant())
                    .setNegativeButton("Cancel", null).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("Samsung AI / Bixby • setup confirmation")
                .setMessage("On supported Samsung phones, enable Bixby and choose it as your default "
                        + "assistant in system settings where available. Device, account and regional support "
                        + "are governed by Samsung.\n\n"
                        + "Continue invokes the configured Android assistant, not a dedicated Bixby API; "
                        + "it may open another assistant if your defaults differ. Galaxy AI features live in "
                        + "supported Samsung apps, not an embedded Jessica assistant. No HUD API key is needed. "
                        + "Jessica cannot send it HUD text, scrape replies or obtain transcripts.")
                .setPositiveButton("Open configured assistant", (dialog, which) -> launchPhoneAssistant())
                .setNegativeButton("Cancel", null).show();
    }

    private void launchGemini() {
        stopPolling();
        if (tts != null) tts.stop();
        Intent intent = getPackageManager().getLaunchIntentForPackage("com.google.android.apps.bard");
        if (intent == null) {
            new AlertDialog.Builder(this).setTitle("Gemini app not available")
                    .setMessage("Install or enable Google's Gemini app on your phone and set it as your "
                            + "mobile assistant. You can also launch your configured assistant with the phone's "
                            + "side button or gesture. Start Live inside Gemini; Jessica has no Live API connection.")
                    .setPositiveButton("Phone assistant", (dialog, which) -> launchPhoneAssistant())
                    .setNegativeButton("Close", null).show();
            return;
        }
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException exception) {
            toast("Unable to open Gemini. Open it from your phone and tap Live.");
        }
    }

    private void launchPhoneAssistant() {
        stopPolling();
        if (tts != null) tts.stop();
        try {
            startActivity(new Intent(Intent.ACTION_VOICE_COMMAND));
        } catch (ActivityNotFoundException | SecurityException exception) {
            toast("Use your phone's assistant gesture or side button. Configure your preferred assistant in Android settings.");
        }
    }

    private void openOpenRouterWeb() {
        stopPolling();
        if (tts != null) tts.stop();
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://openrouter.ai/chat")));
        } catch (ActivityNotFoundException | SecurityException exception) {
            toast("No browser available. Open openrouter.ai/chat on your phone.");
        }
    }

    private void openOpenRouterChat() {
        if (chatDialog != null && chatDialog.isShowing()) return;
        LinearLayout content = column();
        content.setPadding(dp(16), dp(8), dp(16), dp(8));
        content.addView(label("Optional API chat requires an OpenRouter key, even for free models. "
                + "Text goes to OpenRouter/model providers; this is not Gemini Live."));
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
        button(actions, "API voice", v -> recognize(true));
        button(actions, "Optional API settings", v -> openSettings());
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
        chatDialog = new AlertDialog.Builder(this).setTitle("Jessica / optional OpenRouter API")
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
        AlertDialog settings = new AlertDialog.Builder(this).setTitle("Optional OpenRouter API settings")
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
        recognize(false);
    }

    private void recognize(boolean apiChat) {
        if (busy) { toast("Wait for Jessica's current reply before using Voice."); return; }
        stopPolling();
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, apiChat
                ? "Ask Jessica via OpenRouter. Speech may be processed by your speech provider."
                : "Jessica HUD command. Other questions open Gemini; speech may be processed by your speech provider.");
        try {
            startActivityForResult(intent, apiChat ? API_VOICE_REQUEST : VOICE_REQUEST);
        } catch (ActivityNotFoundException exception) {
            toast("No speech recognition app installed. Use Jessica's text input.");
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == CAPTURE_REQUEST) {
            if (result == RESULT_OK && data != null && capturePending) {
                pendingCaptureConsent = data;
                if (resumed) beginCapture();
            } else {
                capturePending = false;
                toast("Playback capture consent declined. Nothing started.");
            }
            return;
        }
        if ((request != VOICE_REQUEST && request != API_VOICE_REQUEST)
                || result != RESULT_OK || data == null) return;
        ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (results == null || results.isEmpty()) return;
        String prompt = results.get(0);
        if (!active) {
            pendingVoice = prompt;
            pendingVoiceForApi = request == API_VOICE_REQUEST;
            return;
        }
        handleVoice(prompt, request == API_VOICE_REQUEST);
    }

    private void handleVoice(String prompt, boolean apiChat) {
        String command = prompt.toLowerCase(Locale.ROOT).replaceFirst("^jessica[, ]*", "").trim();
        switch (command) {
            case "matrix mode": green = true; updateModes(); break;
            case "fullscreen": fullscreen = true; applyFullscreen(); break;
            case "stop polling": stopPolling(); break;
            case "stop speaking": if (tts != null) tts.stop(); break;
            case "gemini":
            case "gemini live": launchGemini(); break;
            default:
                if (apiChat) sendPrompt(prompt);
                else {
                    toast("Ask your question inside Gemini. HUD voice does not send it to Gemini.");
                    launchGemini();
                }
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

    private Button button(LinearLayout row, String title, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextColor(Color.rgb(90, 225, 255));
        GradientDrawable outline = new GradientDrawable();
        outline.setColor(Color.BLACK);
        outline.setStroke(dp(1), Color.rgb(40, 100, 110));
        outline.setCornerRadius(dp(4));
        button.setBackgroundTintList(null);
        button.setBackground(outline);
        button.setOnClickListener(listener);
        row.addView(button);
        return button;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
}
