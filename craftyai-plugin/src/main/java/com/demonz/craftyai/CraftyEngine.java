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

package com.demonz.craftyai;

import com.demonz.craftyai.common.NeuralResponse;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import org.bukkit.entity.Player;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CraftyEngine V1.2 — Robust HTTP Client (Java 8+ Compatible)
 * =====================================================
 * Uses Gson for safe JSON handling. Maximum compatibility.
 * Supports both CraftyAI Gateway and custom chat-completions-compatible providers.
 */
public class CraftyEngine {

    private static final Gson GSON = new Gson();
    private final CraftyAI plugin;
    private final VersionAdapter adapter;
    private final String serverSecret;
    private final String serverId;
    private final String gatewayUrl;
    private final boolean customProvider;
    private final String customModel;
    private final String serverName;

    private static final int MAX_RETRIES = 3;
    private static final int CONNECT_TIMEOUT = 10000;
    private static final int READ_TIMEOUT = 30000;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile long circuitOpenUntil = 0;

    public interface Callback {
        void onSuccess(String response);
        void onFailure(String error);
    }

    /**
     * Legacy constructor for backward compatibility (no custom provider).
     */
    public CraftyEngine(CraftyAI plugin, VersionAdapter adapter, String serverSecret, String serverId, String gatewayUrl) {
        this(plugin, adapter, serverSecret, serverId, gatewayUrl, false, null, null);
    }

    /**
     * Full constructor with custom provider support.
     * @param customProvider true if using a custom AI provider instead of the gateway
     * @param customModel the model name for the custom provider (can be null)
     */
    public CraftyEngine(CraftyAI plugin, VersionAdapter adapter, String serverSecret, String serverId, String gatewayUrl, boolean customProvider, String customModel, String serverName) {
        this.plugin = plugin;
        this.adapter = adapter;
        this.serverSecret = serverSecret;
        this.serverId = serverId;
        this.customProvider = customProvider;
        this.customModel = customModel;
        this.serverName = serverName;
        String url = gatewayUrl;
        if (url == null || url.trim().isEmpty() || url.equals("YOUR_GATEWAY_URL")) {
            url = com.demonz.craftyai.common.GatewayRequestHeaders.getGatewayUrl();
        }
        if (url != null) {
            url = url.replaceAll("/v1/chat/completions/?$", "");
        }
        this.gatewayUrl = (url != null && url.endsWith("/")) ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * Returns true if this engine is configured to use a custom AI provider.
     */
    public boolean isCustomProvider() {
        return customProvider;
    }

    public void ask(Player player, String question, String context, List<Map<String, String>> history, Callback callback) {
        if (adapter == null) {
            callback.onFailure("Adapter not initialized.");
            return;
        }

        if (serverSecret == null || serverSecret.length() < 1 || serverSecret.equals("YOUR_API_KEY_HERE")) {
            callback.onFailure("API key not configured. Edit config.yml.");
            return;
        }

        if (isCircuitOpen()) {
            callback.onFailure("\u00a7c[!] \u00a77Neural link cooling down. Please wait 30s.");
            return;
        }

        adapter.runAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    if (customProvider) {
                        // Custom provider: use industry-standard chat completions format /v1/chat/completions endpoint
                        sendCustomProviderRequest(player, question, context, history, callback, 0);
                    } else {
                        // Gateway: use existing /v1/chat endpoint
                        Map<String, Object> payload = new HashMap<String, Object>();
                        payload.put("prompt", question);
                        payload.put("player_name", player.getName());
                        payload.put("context", context);
                        payload.put("vision_scan", context != null && (context.contains("block") || context.contains("entity") || context.contains("scan")));
                        payload.put("history", history != null ? history : new ArrayList<Map<String, String>>());
                        payload.put("server_id", serverId);
                        payload.put("client_type", clientType());
                        payload.put("version", pluginVersion());

                        sendWithRetry(gatewayUrl + "/v1/chat", GSON.toJson(payload), callback, 0, serverId);
                    }
                } catch (Exception e) {
                    callback.onFailure("Local error: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Sends a request to a custom chat-completions-compatible provider.
     * Uses the /v1/chat/completions endpoint format.
     */
    private void sendCustomProviderRequest(Player player, String question, String context, List<Map<String, String>> history, Callback callback, int attempt) {
        HttpURLConnection conn = null;
        try {
            // Build chat-completions-compatible messages array
            List<Map<String, String>> messages = new ArrayList<Map<String, String>>();

            // System message with context
            if (context != null && !context.isEmpty()) {
                Map<String, String> systemMsg = new HashMap<String, String>();
                systemMsg.put("role", "system");
                systemMsg.put("content", "You are a helpful Minecraft AI assistant named " + plugin.getAiName() + ". " +
                        "You answer questions about Minecraft and help players. Context:\n" + context);
                messages.add(systemMsg);
            }

            // Add conversation history
            if (history != null) {
                for (Map<String, String> entry : history) {
                    Map<String, String> msg = new HashMap<String, String>();
                    String role = entry.get("role");
                    String content = entry.get("content");
                    if (role != null && content != null) {
                        msg.put("role", role);
                        msg.put("content", content);
                        messages.add(msg);
                    }
                }
            }

            // Add the user's question
            Map<String, String> userMsg = new HashMap<String, String>();
            userMsg.put("role", "user");
            userMsg.put("content", question);
            messages.add(userMsg);

            // Build the chat-completions-compatible request payload
            Map<String, Object> payload = new HashMap<String, Object>();
            payload.put("messages", messages);
            payload.put("player_name", player.getName());
            if (customModel != null && !customModel.isEmpty()) {
                payload.put("model", customModel);
            }
            String jsonBody = GSON.toJson(payload);
            if (jsonBody.length() > 100000) {
                plugin.getLogger().warning("[CraftyAI] Custom provider payload too large (" + jsonBody.length() + " chars) for " + player.getName() + ", truncating");
                String truncatedQuestion = question.length() > 50000 ? question.substring(0, 50000) + "... [truncated]" : question;
                messages.get(messages.size() - 1).put("content", truncatedQuestion);
                payload.put("messages", messages);
                jsonBody = GSON.toJson(payload);
            }
            String endpoint = gatewayUrl + "/v1/chat/completions";

            URL url = URI.create(endpoint).toURL();
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);

            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + serverSecret);
            conn.setRequestProperty("User-Agent", "CraftyAI-Minecraft/" + pluginVersion() + " (custom-provider)");

            byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));

            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
                os.flush();
            }

            int status = conn.getResponseCode();

            if (status == 200) {
                String responseBody = readStream(conn.getInputStream());
                String adaptedResponse = adaptOpenAIResponse(responseBody);
                resetCircuit();
                callback.onSuccess(adaptedResponse);
            } else if (status == 401 || status == 403) {
                callback.onFailure("\u00a7c[Auth] Access denied. Check your custom provider API key.");
            } else if (status == 429) {
                callback.onFailure("\u00a7e[Rate Limit] Too many requests. Slow down.");
            } else if (status == 404) {
                callback.onFailure("\u00a7c[Error] Custom AI engine endpoint not found (HTTP 404). Check URL.");
            } else {
                handleCustomProviderRetry(player, question, context, history, callback, attempt, "HTTP " + status);
            }
        } catch (IOException e) {
            handleCustomProviderRetry(player, question, context, history, callback, attempt, e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private String adaptOpenAIResponse(String responseBody) {
        if (responseBody == null || responseBody.trim().isEmpty()) return responseBody;
        try {
            com.google.gson.JsonElement parsed = com.demonz.craftyai.JsonParserAdapter.parse(responseBody);
            if (parsed.isJsonObject()) {
                com.google.gson.JsonObject json = parsed.getAsJsonObject();
                if (json.has("choices")) {
                    com.google.gson.JsonArray choices = json.getAsJsonArray("choices");
                    if (choices.size() > 0) {
                        com.google.gson.JsonObject firstChoice = choices.get(0).getAsJsonObject();
                        if (firstChoice.has("message")) {
                            com.google.gson.JsonObject message = firstChoice.getAsJsonObject("message");
                            if (message.has("content")) {
                                String content = message.get("content").getAsString();
                                Map<String, Object> adapted = new HashMap<String, Object>();
                                adapted.put("response", content);
                                adapted.put("answer", content);
                                adapted.put("source", "custom_provider");
                                return GSON.toJson(adapted);
                            }
                        }
                    }
                }
                if (json.has("response") || json.has("answer")) {
                    return responseBody;
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[CraftyAI] Failed to parse OpenAI response: " + e.getMessage());
        }
        return responseBody + " (note: raw unparsed response)";
    }

    private void handleCustomProviderRetry(final Player player, final String question, final String context, final List<Map<String, String>> history, final Callback callback, final int attempt, String reason) {
        if (attempt < MAX_RETRIES) {
            final int next = attempt + 1;
            plugin.getLogger().warning("[Neural] Custom provider retry " + next + "/" + MAX_RETRIES + " (" + reason + ")");
            long delayTicks = (long) Math.pow(2, next) * 20L;
            adapter.runAsyncLater(new Runnable() {
                @Override
                public void run() {
                    sendCustomProviderRequest(player, question, context, history, callback, next);
                }
            }, delayTicks);
        } else {
            recordFailure();
            callback.onFailure("Custom AI engine offline after " + MAX_RETRIES + " retries.");
        }
    }

    public void learn(String question, String answer, Callback callback) {
        if (adapter == null) {
            callback.onFailure("Adapter not initialized.");
            return;
        }

        if (customProvider) {
            callback.onFailure("Learning is not supported with custom AI providers. Use CraftyAI Cloud for learning features.");
            return;
        }

        adapter.runAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    Map<String, String> payload = new HashMap<String, String>();
                    payload.put("question", question);
                    payload.put("answer", answer);
                    payload.put("server_id", serverId);
                    payload.put("client_type", clientType());
                    payload.put("version", pluginVersion());
                    sendWithRetry(gatewayUrl + "/v1/learn", GSON.toJson(payload), callback, 0, serverId);
                } catch (Exception e) {
                    callback.onFailure("Learn error: " + e.getMessage());
                }
            }
        });
    }

    public void handshake(Callback callback) {
        if (adapter == null) {
            callback.onFailure("Adapter not initialized.");
            return;
        }

        if (customProvider) {
            callback.onSuccess("{\"status\":\"custom_provider\",\"tier\":\"custom\"}");
            return;
        }

        adapter.runAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    Map<String, Object> payload = new HashMap<String, Object>();
                    payload.put("version", pluginVersion());
                    payload.put("client_type", clientType());
                    payload.put("server_id", serverId);
                    payload.put("protocol_version", 2);
                    payload.put("capabilities", Arrays.asList("control_plane_v2", "safe_config", "safe_commands", "command_ack"));
                    payload.put("software_name", plugin.getPlatform().getSoftware().name().toLowerCase(Locale.ROOT));
                    payload.put("minecraft_version", plugin.getServer().getBukkitVersion());
                    payload.put("config_revision", plugin.getConfig().getLong("control.revision", 0L));
                    if (serverName != null && !serverName.isEmpty()) {
                        payload.put("name", serverName);
                    }
                    sendWithRetry(gatewayUrl + "/v1/handshake", GSON.toJson(payload), callback, 0, serverId);
                } catch (Exception e) {
                    callback.onFailure("Handshake error: " + e.getMessage());
                }
            }
        });
    }

    /** Send a v1.3 Control Plane acknowledgement without affecting the chat circuit breaker. */
    public void sendControlAck(final String acknowledgementJson, final Callback callback) {
        if (customProvider || acknowledgementJson == null || acknowledgementJson.trim().isEmpty()) return;
        adapter.runAsync(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) URI.create(gatewayUrl + "/v1/control/ack").toURL().openConnection();
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(8000);
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setRequestProperty("Authorization", "Bearer " + serverSecret);
                    conn.setRequestProperty("X-Server-ID", serverId);
                    conn.setRequestProperty("X-Session-Id", serverId);
                    conn.setRequestProperty("X-Client-Type", clientType());
                    conn.setRequestProperty("X-CraftyAI-Version", pluginVersion());
                    byte[] body = acknowledgementJson.getBytes(StandardCharsets.UTF_8);
                    try (OutputStream output = conn.getOutputStream()) {
                        output.write(body);
                    }
                    int status = conn.getResponseCode();
                    String response = readStream(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
                    if (status >= 200 && status < 300) callback.onSuccess(response);
                    else callback.onFailure("Control acknowledgement rejected (HTTP " + status + ")");
                } catch (Exception error) {
                    callback.onFailure("Control acknowledgement failed: " + error.getMessage());
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        });
    }

    private void sendWithRetry(String urlStr, String jsonBody, Callback callback, int attempt, String serverId) {
        HttpURLConnection conn = null;
        try {
            URL url = URI.create(urlStr).toURL();
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);

            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + serverSecret);
            conn.setRequestProperty("X-Server-ID", serverId);
            conn.setRequestProperty("X-Session-Id", serverId);
            conn.setRequestProperty("X-Client-Type", clientType());
            conn.setRequestProperty("X-CraftyAI-Version", pluginVersion());
            conn.setRequestProperty("X-CraftyAI-Client", clientType());
            conn.setRequestProperty("User-Agent", "CraftyAI-Minecraft/" + pluginVersion() + " (" + clientType() + ")");

            byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));

            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
                os.flush();
            }

            int status = conn.getResponseCode();

            if (status == 200) {
                String responseBody = readStream(conn.getInputStream());
                resetCircuit();
                callback.onSuccess(responseBody);
            } else if (status == 401 || status == 403) {
                try {
                    String errBody = readStream(conn.getErrorStream());
                    if (errBody != null) {
                        if (errBody.contains("suspended") || errBody.contains("revoked")) {
                            callback.onFailure("\u00a7c\u00a7l[!] Account Suspended. \u00a7r\u00a77Create a ticket at \u00a7b\u00a7ndiscord.gg/zCkE44hsBR\u00a7r\u00a77 to appeal.");
                            return;
                        }
                        if (errBody.contains("paused") || errBody.contains("inactive")) {
                            callback.onFailure("\u00a7c\u00a7l[!] Subscription Paused. \u00a7r\u00a77Check your dashboard to resume.");
                            return;
                        }
                    }
                } catch (Exception ignored) {}
                callback.onFailure("\u00a7c[Auth] Access denied. Check API key.");
            } else if (status == 429) {
                String errBody = "";
                try { errBody = readStream(conn.getErrorStream()); } catch (Exception ignored) {}
                if (errBody.contains("Daily request limit exceeded")) {
                    callback.onFailure("\u00a7c\u00a7l[!] Daily Request Limit Reached. \u00a7r\u00a77Create a ticket at \u00a7b\u00a7ndiscord.gg/zCkE44hsBR\u00a7r\u00a77 to upgrade.");
                } else if (errBody.contains("Daily token limit exceeded")) {
                    callback.onFailure("\u00a7c[Limit] Daily Token Limit Reached. \u00a7r\u00a77Create a ticket at \u00a7b\u00a7ndiscord.gg/zCkE44hsBR\u00a7r\u00a77 to upgrade.");
                } else {
                    callback.onFailure("\u00a7e[Rate Limit] Too many requests. Slow down.");
                }
            } else if (status >= 500) {
                handleRetry(urlStr, jsonBody, callback, attempt, "Server error (" + status + ")");
            } else {
                String errorBody = "";
                try { errorBody = readStream(conn.getErrorStream()); } catch (Exception ignored) {}
                callback.onFailure("Error (" + status + "): " + errorBody);
            }
        } catch (java.net.SocketTimeoutException e) {
            handleRetry(urlStr, jsonBody, callback, attempt, "Request timed out");
        } catch (java.net.ConnectException e) {
            handleRetry(urlStr, jsonBody, callback, attempt, "Connection refused");
        } catch (IOException e) {
            handleRetry(urlStr, jsonBody, callback, attempt, "Network error: " + e.getMessage());
        } catch (Exception e) {
            callback.onFailure("System failure: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String readStream(InputStream stream) throws IOException {
        if (stream == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    private String clientType() {
        return "minecraft-" + plugin.getPlatform().getSoftware().name().toLowerCase(Locale.ROOT);
    }

    private String pluginVersion() {
        String version = plugin.getDescription().getVersion();
        return version != null && !version.trim().isEmpty() ? version : com.demonz.craftyai.common.GatewayRequestHeaders.MOD_VERSION;
    }

    private void handleRetry(final String url, final String json, final Callback callback, final int attempt, String reason) {
        if (attempt < MAX_RETRIES) {
            final int next = attempt + 1;
            plugin.getLogger().warning("[Neural] Retry " + next + "/" + MAX_RETRIES + " (" + reason + ")");
            long delayTicks = (long) Math.pow(2, next) * 20L;
            adapter.runAsyncLater(new Runnable() {
                @Override
                public void run() {
                    sendWithRetry(url, json, callback, next, serverId);
                }
            }, delayTicks);
        } else {
            recordFailure();
            callback.onFailure("Neural uplink offline after " + MAX_RETRIES + " retries.");
        }
    }

    private void recordFailure() {
        if (consecutiveFailures.incrementAndGet() >= 5) {
            circuitOpenUntil = System.currentTimeMillis() + 30000;
        }
    }

    private void resetCircuit() {
        consecutiveFailures.set(0);
        circuitOpenUntil = 0;
    }

    private boolean isCircuitOpen() {
        if (circuitOpenUntil > 0 && System.currentTimeMillis() > circuitOpenUntil) {
            resetCircuit();
            return false;
        }
        return circuitOpenUntil > 0;
    }

    /**
     * Parse the answer from a response body. Returns null if parsing fails or no answer found.
     */
    public String parseAnswer(String responseBody) {
        NeuralResponse res = parseResponse(responseBody);
        return res != null ? res.getAnswer() : null;
    }

    /**
     * Parse the action from a response body. Returns null if parsing fails or no action found.
     */
    public String parseAction(String responseBody) {
        NeuralResponse res = parseResponse(responseBody);
        return res != null ? res.getAction() : null;
    }

    /**
     * Parse the response body into a NeuralResponse object.
     * Caches the result to avoid double-parsing when both answer and action are needed.
     * Returns null if the response body is null, empty, or cannot be parsed.
     */
    private NeuralResponse parseResponse(String responseBody) {
        if (responseBody == null || responseBody.trim().isEmpty()) return null;
        try {
            NeuralResponse res = GSON.fromJson(responseBody, NeuralResponse.class);
            return res;
        } catch (JsonSyntaxException e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public void shutdown() {
        // HttpURLConnection doesn't need explicit shutdown
    }

    /**
     * Fetch a player's recent memories from the Gateway.
     * Calls {@code POST /v1/memory-view} on the Gateway.
     */
    public void listMemoriesAsync(String playerName, int limit, final Callback callback) {
        if (serverSecret == null || serverSecret.equals("YOUR_API_KEY_HERE")) {
            callback.onFailure("API key not configured.");
            return;
        }
        adapter.runAsync(new Runnable() {
            @Override public void run() {
                try {
                    Map<String, Object> payload = new HashMap<String, Object>();
                    payload.put("player_name", playerName);
                    payload.put("limit", limit);
                    payload.put("server_id", serverId);
                    payload.put("client_type", clientType());
                    sendWithRetry(gatewayUrl + "/v1/memory-view", GSON.toJson(payload), callback, 0, serverId);
                } catch (Exception e) {
                    callback.onFailure("Memory view error: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Forget a player's most recent N memories.
     * Calls {@code POST /v1/memory-forget} on the Gateway.
     */
    public void forgetMemoriesAsync(String playerName, int count, final Callback callback) {
        if (serverSecret == null || serverSecret.equals("YOUR_API_KEY_HERE")) {
            callback.onFailure("API key not configured.");
            return;
        }
        adapter.runAsync(new Runnable() {
            @Override public void run() {
                try {
                    Map<String, Object> payload = new HashMap<String, Object>();
                    payload.put("player_name", playerName);
                    payload.put("count", count);
                    payload.put("server_id", serverId);
                    payload.put("client_type", clientType());
                    sendWithRetry(gatewayUrl + "/v1/memory-forget", GSON.toJson(payload), callback, 0, serverId);
                } catch (Exception e) {
                    callback.onFailure("Memory forget error: " + e.getMessage());
                }
            }
        });
    }
}
