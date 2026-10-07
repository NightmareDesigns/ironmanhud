package com.nightmaredesigns.dexhud;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.URL;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javax.net.ssl.HttpsURLConnection;

final class OpenRouterClient {
    static final String[] PROVIDERS = {"OpenRouter", "Groq", "Cerebras"};
    private static final int MAX_TEXT = 8000;
    private static final int MAX_LINE = 64 * 1024;
    private static final int MAX_STREAM = 2 * 1024 * 1024;
    private static final int MAX_CATALOG = 8 * 1024 * 1024;
    private static final long MAX_DURATION_NANOS = 120_000_000_000L;
    private final Object connectionLock = new Object();
    private Request activeRequest;

    static boolean isFreeModel(String model) {
        return model != null && model.length() <= 200
                && (model.equals("openrouter/free")
                || model.matches("[A-Za-z0-9._-]+/[A-Za-z0-9._:-]+:free"));
    }

    static boolean isValidModel(String provider, String model) {
        if ("OpenRouter".equals(provider)) return isFreeModel(model);
        return ("Groq".equals(provider) || "Cerebras".equals(provider))
                && model != null && model.length() <= 200
                && model.matches("[A-Za-z0-9][A-Za-z0-9._:-]*(/[A-Za-z0-9][A-Za-z0-9._:-]*)*");
    }

    List<String> models(String provider, String key, BooleanSupplier cancelled) throws IOException {
        Request request = open(provider, key, "models", cancelled);
        HttpsURLConnection current = request.connection;
        try {
            current.setRequestProperty("Accept", "application/json");
            check(request);
            checkStatus(provider, current.getResponseCode());
            String response;
            try (InputStream input = current.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int length;
                while ((length = input.read(buffer)) != -1) {
                    check(request);
                    if (output.size() + length > MAX_CATALOG) {
                        throw failure("Model catalog exceeded the size limit. Try again later.");
                    }
                    output.write(buffer, 0, length);
                }
                response = output.toString(StandardCharsets.UTF_8.name());
            }
            check(request);
            JSONObject catalog = new JSONObject(response);
            if (catalog.has("error")) throw failure(provider + " could not load models. Check your account and try later.");
            JSONArray data = catalog.getJSONArray("data");
            TreeSet<String> ids = new TreeSet<>();
            for (int i = 0; i < data.length(); i++) {
                check(request);
                JSONObject entry = data.optJSONObject(i);
                if (entry == null) continue;
                String id = entry.optString("id", "");
                if (!isValidModel(provider, id)) continue;
                if ("OpenRouter".equals(provider)) {
                    JSONObject pricing = entry.optJSONObject("pricing");
                    if (pricing == null || !isZero(pricing.optString("prompt", ""))
                            || !isZero(pricing.optString("completion", ""))) continue;
                }
                ids.add(id);
            }
            if (ids.isEmpty()) throw failure("No eligible models were found. Check your account or try another provider.");
            return new ArrayList<>(ids);
        } catch (JSONException e) {
            check(request);
            throw failure("The provider returned an invalid model catalog. Try again later.");
        } catch (IOException e) {
            throw safeFailure(request, e);
        } finally {
            close(request);
        }
    }

    String chat(String provider, String key, String model, JSONArray history,
                BooleanSupplier cancelled, Consumer<String> partial) throws IOException {
        if (!isValidModel(provider, model)) {
            throw failure("Choose an eligible model from the selected provider's catalog.");
        }
        if (history == null) throw failure("No conversation was provided.");
        byte[] bytes;
        try {
            bytes = new JSONObject().put("model", model).put("messages", history)
                    .put("max_tokens", 350).put("stream", true)
                    .toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) {
            throw failure("Could not prepare the conversation. Start a new chat.");
        }
        if (bytes.length > 1024 * 1024) throw failure("Conversation is too large. Start a new chat.");
        Request request = open(provider, key, "chat/completions", cancelled);
        HttpsURLConnection current = request.connection;
        try {
            current.setRequestMethod("POST");
            current.setRequestProperty("Content-Type", "application/json");
            current.setRequestProperty("Accept", "text/event-stream");
            current.setDoOutput(true);
            current.setFixedLengthStreamingMode(bytes.length);
            check(request);
            try (OutputStream output = current.getOutputStream()) {
                check(request);
                output.write(bytes);
            }
            check(request);
            checkStatus(provider, current.getResponseCode());
            try (InputStream input = new BufferedInputStream(current.getInputStream())) {
                return readStream(request, input, partial);
            }
        } catch (JSONException e) {
            check(request);
            throw failure("The provider returned an invalid stream. Try again later.");
        } catch (IOException e) {
            throw safeFailure(request, e);
        } finally {
            close(request);
        }
    }

    private String readStream(Request request, InputStream input, Consumer<String> partial)
            throws IOException, JSONException {
        StringBuilder text = new StringBuilder();
        StringBuilder data = new StringBuilder();
        String event = "";
        int total = 0;
        boolean firstLine = true;
        while (true) {
            ByteArrayOutputStream lineBytes = new ByteArrayOutputStream();
            int next;
            while (true) {
                check(request);
                next = input.read();
                if (next == -1) break;
                if (++total > MAX_STREAM) throw failure("Response exceeded the size limit. Try a shorter request.");
                if (next == '\n') break;
                if (lineBytes.size() >= MAX_LINE) throw failure("The provider sent an oversized stream line.");
                lineBytes.write(next);
            }
            String line = lineBytes.toString(StandardCharsets.UTF_8.name());
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            if (firstLine) {
                firstLine = false;
                if (line.startsWith("\uFEFF")) line = line.substring(1);
            }
            if (line.isEmpty() || next == -1) {
                if ("error".equals(event)) {
                    throw failure("The provider stopped the response. Check your model, account quota, and try later.");
                }
                // Only complete, blank-line-delimited SSE events are trusted.
                if (next != -1 && data.length() > 0) {
                    if (processEvent(request, data.toString(), text, partial)) return completed(request, text);
                    data.setLength(0);
                }
                event = "";
                if (next == -1) throw failure("The response ended before completion. Try again.");
            } else if (line.startsWith("data:")) {
                String value = line.substring(5);
                if (value.startsWith(" ")) value = value.substring(1);
                if (data.length() + value.length() + 1 > MAX_LINE) {
                    throw failure("The provider sent an oversized stream event.");
                }
                if (data.length() > 0) data.append('\n');
                data.append(value);
            } else if (line.startsWith("event:")) {
                event = line.substring(6).trim();
            }
        }
    }

    private boolean processEvent(Request request, String data, StringBuilder text,
                                 Consumer<String> partial) throws IOException, JSONException {
        check(request);
        if ("[DONE]".equals(data.trim())) return true;
        JSONObject chunk = new JSONObject(data);
        if (chunk.has("error")) {
            throw failure("The provider stopped the response. Check your model, account quota, and try later.");
        }
        JSONArray choices = chunk.optJSONArray("choices");
        if (choices == null) throw failure("The provider returned an invalid stream. Try again later.");
        for (int i = 0; i < choices.length(); i++) {
            JSONObject choice = choices.getJSONObject(i);
            if (choice.optInt("index", 0) != 0) continue;
            String finish = choice.isNull("finish_reason") ? "" : choice.optString("finish_reason", "");
            if ("error".equals(finish) || "content_filter".equals(finish)) {
                throw failure("The provider could not complete this response. Try a different request or model.");
            }
            JSONObject delta = choice.optJSONObject("delta");
            if (delta != null && !delta.isNull("content")) {
                Object content = delta.get("content");
                if (!(content instanceof String)) throw failure("The provider returned unsupported response content.");
                String addition = (String) content;
                int available = MAX_TEXT - text.length();
                if (available > 0 && !addition.isEmpty()) {
                    int end = Math.min(available, addition.length());
                    if (end < addition.length() && end > 0 && Character.isHighSurrogate(addition.charAt(end - 1))) end--;
                    text.append(addition, 0, end);
                    check(request);
                    if (partial != null && end > 0) partial.accept(text.toString());
                }
            }
            if (!finish.isEmpty()) {
                if ("stop".equals(finish) || "length".equals(finish)
                        || "tool_calls".equals(finish) || "function_call".equals(finish)) return true;
                throw failure("The provider did not complete the response normally. Try another request or model.");
            }
        }
        return false;
    }

    private String completed(Request request, StringBuilder text) throws IOException {
        check(request);
        String result = text.toString().trim();
        if (result.isEmpty()) throw failure("The model returned no text. Try a different request or model.");
        return result;
    }

    private Request open(String provider, String key, String path, BooleanSupplier cancelled)
            throws IOException {
        String base;
        switch (provider == null ? "" : provider) {
            case "OpenRouter": base = "https://openrouter.ai/api/v1/"; break;
            case "Groq": base = "https://api.groq.com/openai/v1/"; break;
            case "Cerebras": base = "https://api.cerebras.ai/v1/"; break;
            default: throw failure("Choose OpenRouter, Groq, or Cerebras.");
        }
        boolean publicCatalog = "OpenRouter".equals(provider) && "models".equals(path)
                && (key == null || key.trim().isEmpty());
        if (!publicCatalog && (key == null || key.isEmpty() || key.length() > 4096
                || !key.matches("[A-Za-z0-9._~+/-]+=*"))) {
            throw failure("Enter a valid API key for the selected provider (without spaces).");
        }
        if (cancelled == null) throw failure("Request cancellation is unavailable.");
        HttpsURLConnection current;
        try {
            current = (HttpsURLConnection) new URL(base + path).openConnection();
        } catch (IOException e) {
            throw failure("Could not connect to the provider. Check your internet connection.");
        }
        Request request = new Request(current, cancelled);
        current.setInstanceFollowRedirects(false);
        current.setUseCaches(false);
        current.setConnectTimeout(15000);
        current.setReadTimeout(45000);
        if (!publicCatalog) current.setRequestProperty("Authorization", "Bearer " + key);
        synchronized (connectionLock) {
            try {
                check(request);
                if (activeRequest != null) throw failure("Another provider request is still running. Try again shortly.");
                activeRequest = request;
            } catch (IOException e) {
                current.disconnect();
                throw e;
            }
        }
        return request;
    }

    private static boolean isZero(String price) {
        if (price.isEmpty() || price.length() > 64) return false;
        try {
            return new BigDecimal(price).signum() == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static void checkStatus(String provider, int code) throws IOException {
        if (code == 200) return;
        if (code == 401 || code == 403) throw failure(provider + " rejected access. Check your API key and account.");
        if (code == 402) throw failure(provider + " requires account credit. Choose an eligible model or check your plan.");
        if (code == 429) throw failure(provider + " rate or account quota limit reached. Try later; check your plan.");
        if (code == 400 || code == 404) throw failure(provider + " rejected the model or request. Refresh models and try again.");
        if (code >= 300 && code < 400) throw failure("Provider redirects are not allowed. Try again later.");
        throw failure(provider + " HTTP " + code + ". The service or model may be unavailable; try later.");
    }

    private static void check(Request request) throws IOException {
        if (request.cancelled || request.cancellation.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw failure("Request cancelled.");
        }
        if (System.nanoTime() - request.started > MAX_DURATION_NANOS) {
            throw failure("Request timed out. Try again with a shorter request.");
        }
    }

    private static IOException safeFailure(Request request, IOException cause) {
        if (request.cancelled || request.cancellation.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            return failure("Request cancelled.");
        }
        if (cause instanceof ClientException) return cause;
        if (cause instanceof SocketTimeoutException) return failure("Provider timed out. Check your connection and try later.");
        return failure("Provider connection failed. Check your internet connection and try again.");
    }

    private void close(Request request) {
        request.connection.disconnect();
        synchronized (connectionLock) {
            if (activeRequest == request) activeRequest = null;
        }
    }

    void cancel() {
        Request request;
        synchronized (connectionLock) {
            request = activeRequest;
            if (request != null) request.cancelled = true;
        }
        if (request != null) request.connection.disconnect();
    }

    private static ClientException failure(String message) {
        return new ClientException(message);
    }

    private static final class ClientException extends IOException {
        private static final long serialVersionUID = 1L;

        ClientException(String message) {
            super(message);
        }
    }

    private static final class Request {
        final HttpsURLConnection connection;
        final BooleanSupplier cancellation;
        final long started = System.nanoTime();
        volatile boolean cancelled;

        Request(HttpsURLConnection connection, BooleanSupplier cancellation) {
            this.connection = connection;
            this.cancellation = cancellation;
        }
    }
}
