package com.nightmaredesigns.dexhud;

import android.annotation.SuppressLint;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/** Foreground-only PCM transport. Callbacks are delivered off the UI thread. */
final class GeminiLiveSession implements AutoCloseable {
    interface Listener {
        void onReady();
        void onLevel(boolean microphone, float level);
        void onTranscript(String speaker, String text);
        void onEnd(String message);
    }

    private final Listener listener;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS).retryOnConnectionFailure(false).build();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean ready = new AtomicBoolean();
    private final ArrayBlockingQueue<byte[]> playback = new ArrayBlockingQueue<>(32);
    private final Object audioLock = new Object();
    private volatile WebSocket socket;
    private AudioRecord recorder;
    private AudioTrack track;
    private Thread inputThread;
    private Thread outputThread;
    private int playbackEpoch;

    GeminiLiveSession(Listener listener) {
        this.listener = listener;
    }

    void connect(String key, String model) {
        HttpUrl url = new HttpUrl.Builder().scheme("https")
                .host("generativelanguage.googleapis.com")
                .addPathSegments("ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent")
                .addQueryParameter("key", key).build();
        WebSocket connection = client.newWebSocket(new Request.Builder().url(url).build(),
                new WebSocketListener() {
                    @Override public void onOpen(WebSocket ws, Response response) {
                        socket = ws;
                        if (closed.get()) { ws.cancel(); return; }
                        try {
                            JSONObject setup = new JSONObject()
                                    .put("model", "models/" + model)
                                    .put("generationConfig", new JSONObject()
                                            .put("responseModalities", new JSONArray().put("AUDIO")))
                                    .put("inputAudioTranscription", new JSONObject())
                                    .put("outputAudioTranscription", new JSONObject())
                                    .put("systemInstruction", new JSONObject().put("parts",
                                            new JSONArray().put(new JSONObject().put("text",
                                                    "You are Jessica, a helpful concise voice assistant."))));
                            if (!ws.send(new JSONObject().put("setup", setup).toString())) {
                                fail("Unable to send session setup. Try again.");
                            }
                        } catch (JSONException e) {
                            fail("Unable to configure Live session.");
                        }
                    }

                    @Override public void onMessage(WebSocket ws, String text) {
                        receive(text);
                    }

                    @Override public void onMessage(WebSocket ws, ByteString bytes) {
                        if (bytes.size() > 1_000_000) {
                            fail("Live response exceeded the safety limit.");
                        } else {
                            receive(bytes.utf8());
                        }
                    }

                    @Override public void onFailure(WebSocket ws, Throwable error, Response response) {
                        int code = response == null ? 0 : response.code();
                        fail(code == 401 || code == 403
                                ? "Access denied. Check your Gemini key, project and model access."
                                : code == 429 ? "Quota exceeded. Check Google AI Studio quotas and billing."
                                : "Live connection failed. Check internet, key, model access and quota.");
                    }

                    @Override public void onClosing(WebSocket ws, int code, String reason) {
                        // Server reasons can contain sensitive request data; never display or log them.
                        ws.close(code, null);
                        fail("Live session ended (code " + code + "). Start again when ready.");
                    }

                    @Override public void onClosed(WebSocket ws, int code, String reason) {
                        fail("Live session closed. Start again when ready.");
                    }
                });
        socket = connection;
        if (closed.get()) connection.cancel();
    }

    private void receive(String text) {
        if (closed.get()) return;
        if (text.length() > 1_000_000) {
            fail("Live response exceeded the safety limit.");
            return;
        }
        try {
            JSONObject message = new JSONObject(text);
            if (message.has("error")) {
                int code = message.optJSONObject("error") == null ? 0
                        : message.getJSONObject("error").optInt("code");
                fail(code == 429 ? "Quota exceeded. Check AI Studio quotas and billing."
                        : "Gemini rejected the session. Check key, model availability and billing.");
                return;
            }
            if (message.has("goAway")) {
                fail("Google is ending this connection. Start a new session.");
                return;
            }
            if (message.has("setupComplete") && ready.compareAndSet(false, true)) {
                inputThread = new Thread(this::capture, "gemini-live-microphone");
                inputThread.start();
            }
            JSONObject content = message.optJSONObject("serverContent");
            if (content == null) return;
            if (content.optBoolean("interrupted")) {
                synchronized (audioLock) {
                    playbackEpoch++;
                    playback.clear();
                    if (track != null) {
                        track.pause();
                        track.flush();
                        track.play();
                    }
                }
                listener.onLevel(false, 0);
            }
            transcript(content.optJSONObject("inputTranscription"), "You");
            transcript(content.optJSONObject("outputTranscription"), "Jessica");
            JSONObject turn = content.optJSONObject("modelTurn");
            JSONArray parts = turn == null ? null : turn.optJSONArray("parts");
            if (parts != null) {
                for (int i = 0; i < parts.length(); i++) {
                    JSONObject part = parts.optJSONObject(i);
                    JSONObject data = part == null ? null : part.optJSONObject("inlineData");
                    if (data == null) continue;
                    String mime = data.optString("mimeType");
                    if (!mime.startsWith("audio/pcm")) continue;
                    if (mime.contains("rate=") && !mime.contains("rate=24000")) {
                        fail("Unsupported output audio sample rate.");
                        return;
                    }
                    String encoded = data.optString("data");
                    if (encoded.length() > 128_000) {
                        fail("Audio chunk exceeded the safety limit.");
                        return;
                    }
                    byte[] pcm = Base64.decode(encoded, Base64.DEFAULT);
                    if ((pcm.length & 1) != 0) {
                        fail("Invalid PCM audio received.");
                        return;
                    }
                    for (int offset = 0; offset < pcm.length; offset += 4800) {
                        byte[] block = Arrays.copyOfRange(pcm, offset, Math.min(offset + 4800, pcm.length));
                        if (!playback.offer(block)) {
                            fail("Audio playback fell behind. Use headphones and start again.");
                            return;
                        }
                    }
                }
            }
            if (content.optBoolean("turnComplete")) listener.onTranscript("", "\n");
        } catch (JSONException | IllegalArgumentException | IllegalStateException e) {
            fail("Invalid Live response or audio state. Start again.");
        }
    }

    private void transcript(JSONObject transcription, String speaker) {
        if (transcription != null) {
            String text = transcription.optString("text");
            if (!text.isEmpty()) listener.onTranscript(speaker,
                    text.substring(0, Math.min(text.length(), 4000)));
        }
    }

    @SuppressLint("MissingPermission")
    private void capture() {
        AudioRecord input = null;
        AcousticEchoCanceler echo = null;
        try {
            int inputSize = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            int outputSize = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (inputSize <= 0 || outputSize <= 0) {
                fail("This device does not support the required PCM audio formats.");
                return;
            }
            input = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(inputSize, 6400));
            if (input.getState() != AudioRecord.STATE_INITIALIZED) {
                fail("Microphone unavailable. Close other recording apps.");
                return;
            }
            synchronized (audioLock) {
                if (closed.get()) return;
                recorder = input;
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(24000)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setBufferSizeInBytes(Math.max(outputSize, 9600))
                        .setTransferMode(AudioTrack.MODE_STREAM).build();
                if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                    fail("Audio output unavailable.");
                    return;
                }
                if (AcousticEchoCanceler.isAvailable()) {
                    echo = AcousticEchoCanceler.create(input.getAudioSessionId());
                    if (echo != null) echo.setEnabled(true);
                }
                track.play();
                input.startRecording();
                outputThread = new Thread(this::play, "gemini-live-speaker");
                outputThread.start();
            }
            if (closed.get()) return;
            listener.onReady();
            byte[] buffer = new byte[640];
            int meterTick = 0;
            while (!closed.get()) {
                int length = input.read(buffer, 0, buffer.length);
                if (closed.get()) break;
                if (length <= 0) { fail("Microphone recording stopped."); break; }
                length &= ~1;
                WebSocket ws = socket;
                if (ws == null || ws.queueSize() > 64_000) {
                    fail("Network cannot keep up with Live audio. Start again.");
                    break;
                }
                JSONObject audio = new JSONObject().put("mimeType", "audio/pcm;rate=16000")
                        .put("data", Base64.encodeToString(buffer, 0, length, Base64.NO_WRAP));
                if (!ws.send(new JSONObject().put("realtimeInput",
                        new JSONObject().put("audio", audio)).toString())) {
                    fail("Live audio connection stopped.");
                    break;
                }
                if (++meterTick % 4 == 0) listener.onLevel(true, level(buffer, length));
            }
        } catch (RuntimeException | JSONException e) {
            fail("Unable to start audio. Check microphone permission and audio device.");
        } finally {
            if (echo != null) echo.release();
            synchronized (audioLock) {
                if (recorder == input) recorder = null;
                if (input != null) {
                    try { input.stop(); } catch (IllegalStateException ignored) { }
                    input.release();
                }
            }
        }
    }

    private void play() {
        try {
            while (!closed.get()) {
                int epoch;
                synchronized (audioLock) { epoch = playbackEpoch; }
                byte[] block = playback.poll(100, TimeUnit.MILLISECONDS);
                if (block == null) { listener.onLevel(false, 0); continue; }
                int offset = 0;
                listener.onLevel(false, level(block, block.length));
                while (offset < block.length && !closed.get()) {
                    int written;
                    synchronized (audioLock) {
                        if (track == null || epoch != playbackEpoch) break;
                        written = track.write(block, offset, block.length - offset,
                                AudioTrack.WRITE_NON_BLOCKING);
                    }
                    if (written < 0) { fail("Audio playback stopped."); return; }
                    offset += written;
                    if (written == 0) Thread.sleep(10);
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            fail("Audio playback unavailable.");
        }
    }

    private static float level(byte[] pcm, int length) {
        double sum = 0;
        for (int i = 0; i + 1 < length; i += 2) {
            short sample = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
            sum += (double) sample * sample;
        }
        return length < 2 ? 0 : (float) Math.min(1, Math.sqrt(sum / (length / 2)) / 7000);
    }

    private void fail(String message) {
        if (closeOnce()) listener.onEnd(message);
    }

    @Override public void close() { closeOnce(); }

    private boolean closeOnce() {
        if (!closed.compareAndSet(false, true)) return false;
        WebSocket ws = socket;
        socket = null;
        if (ws != null) ws.cancel();
        playback.clear();
        synchronized (audioLock) {
            if (recorder != null) {
                try { recorder.stop(); } catch (IllegalStateException ignored) { }
            }
            if (track != null) {
                try { track.pause(); track.flush(); } catch (IllegalStateException ignored) { }
                track.release();
                track = null;
            }
        }
        if (inputThread != null) inputThread.interrupt();
        if (outputThread != null) outputThread.interrupt();
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdown();
        return true;
    }
}
