package com.nightmaredesigns.dexhud;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

import javax.net.ssl.HttpsURLConnection;

final class OpenRouterClient {
    private volatile HttpsURLConnection connection;

    static boolean isFreeModel(String model) {
        return model.equals("openrouter/free")
                || model.matches("[A-Za-z0-9._-]+/[A-Za-z0-9._:-]+:free");
    }

    String chat(String key, String model, JSONArray history, BooleanSupplier cancelled) throws Exception {
        if (!isFreeModel(model)) throw new IOException("Choose openrouter/free or a model ID ending in :free.");
        JSONObject body = new JSONObject()
                .put("model", model)
                .put("messages", history)
                .put("max_tokens", 350);
        HttpsURLConnection current = (HttpsURLConnection)
                new URL("https://openrouter.ai/api/v1/chat/completions").openConnection();
        connection = current;
        try {
            if (cancelled.getAsBoolean()) throw new IOException("Request cancelled.");
            current.setConnectTimeout(15000);
            current.setReadTimeout(45000);
            current.setRequestMethod("POST");
            current.setRequestProperty("Authorization", "Bearer " + key);
            current.setRequestProperty("Content-Type", "application/json");
            current.setDoOutput(true);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            current.setFixedLengthStreamingMode(bytes.length);
            try (java.io.OutputStream output = current.getOutputStream()) {
                if (cancelled.getAsBoolean()) throw new IOException("Request cancelled.");
                output.write(bytes);
            }
            int code = current.getResponseCode();
            if (code != 200) {
                if (code == 401 || code == 403) throw new IOException("OpenRouter rejected access. Check your API key and account.");
                if (code == 429) throw new IOException("Free-model rate limit reached. Polling stopped; try later.");
                throw new IOException("OpenRouter HTTP " + code + ". The free model may be unavailable.");
            }
            String response;
            try (InputStream input = current.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int length;
                while ((length = input.read(buffer)) != -1) {
                    if (cancelled.getAsBoolean()) throw new IOException("Request cancelled.");
                    if (output.size() + length > 1024 * 1024) throw new IOException("Response exceeded size limit.");
                    output.write(buffer, 0, length);
                }
                response = output.toString(StandardCharsets.UTF_8.name());
            }
            String content = new JSONObject(response).getJSONArray("choices")
                    .getJSONObject(0).getJSONObject("message").getString("content").trim();
            if (content.isEmpty()) throw new IOException("The model returned no text.");
            return content.substring(0, Math.min(content.length(), 8000));
        } finally {
            current.disconnect();
            connection = null;
        }
    }

    void cancel() {
        HttpsURLConnection current = connection;
        if (current != null) current.disconnect();
    }
}
