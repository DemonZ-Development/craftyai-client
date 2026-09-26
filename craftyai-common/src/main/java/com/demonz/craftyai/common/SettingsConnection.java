/*
 * Copyright 2026 DemonZ Development
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.demonz.craftyai.common;

import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public final class SettingsConnection {
    private static final ExecutorService IO = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(8), r -> {
                Thread t = new Thread(r, "CraftyAI-Settings"); t.setDaemon(true); return t;
            });
    private SettingsConnection() {}
    public static CompletableFuture<String> run(Callable<String> operation) {
        CompletableFuture<String> result = new CompletableFuture<>();
        try {
            IO.execute(() -> {
                try { result.complete(operation.call()); }
                catch (Exception e) { result.completeExceptionally(e); }
            });
        } catch (RejectedExecutionException e) { result.completeExceptionally(e); }
        return result;
    }

    public static String gateway(String key, String clientType, String session, boolean mint) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("version", GatewayRequestHeaders.MOD_VERSION);
        body.addProperty("client_type", clientType); body.addProperty("server_id", session);
        body.addProperty("name", "CraftyAI Client");
        String path = mint ? "/v1/handshake-no-key" : "/v1/handshake";
        HttpURLConnection c = (HttpURLConnection) URI.create(GatewayRequestHeaders.getGatewayUrl() + path).toURL().openConnection();
        try {
            setup(c); c.setRequestMethod("POST"); c.setDoOutput(true);
            GatewayRequestHeaders.apply(c, clientType, session);
            c.setRequestProperty("Content-Type", "application/json");
            if (!mint) c.setRequestProperty("Authorization", "Bearer " + key);
            try (java.io.OutputStream out = c.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            if (c.getResponseCode() < 200 || c.getResponseCode() >= 300) throw new java.io.IOException("Request failed");
            JsonObject json = JsonParserAdapter.parse(read(c)).getAsJsonObject();
            if (mint) {
                String minted = json.has("api_key") ? json.get("api_key").getAsString() : "";
                if (!minted.matches("^cai_[a-zA-Z0-9_-]{16,128}$")) throw new java.io.IOException("Invalid key response");
                return minted;
            }
            return "Connection verified.";
        } finally { c.disconnect(); }
    }

    public static String provider(String url, String key) throws Exception {
        HttpURLConnection c = (HttpURLConnection) URI.create(SettingsDraft.providerBaseUrl(url) + "/v1/models").toURL().openConnection();
        try {
            setup(c); c.setRequestMethod("GET");
            if (!key.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + key);
            int code = c.getResponseCode();
            return code >= 200 && code < 300 ? "Provider connected." : "Provider returned HTTP " + code + ".";
        } finally { c.disconnect(); }
    }

    private static void setup(HttpURLConnection c) {
        c.setConnectTimeout(5000); c.setReadTimeout(8000); c.setInstanceFollowRedirects(false);
    }
    private static String read(HttpURLConnection c) throws Exception {
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048]; int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > 65536) throw new java.io.IOException("Response too large");
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
