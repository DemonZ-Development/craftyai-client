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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ControlPlaneProcessor {
    private static final int MAX_SEEN_COMMANDS = 1000;
    private static final Map<String, Long> SEEN_COMMANDS = new LinkedHashMap<String, Long>();

    private ControlPlaneProcessor() {
    }

    public interface Handler {
        void showMessage(String title, String message, String severity);
        boolean persistConfiguration(CraftyAIConfig config);
        void reloadConfiguration();
        void log(String message);
    }

    public static final class Result {
        private long configRevision;
        private boolean configChanged;
        private final JsonArray commandAcks = new JsonArray();
        private final JsonArray warningIds = new JsonArray();
        private final List<JsonObject> pendingConfigurationAcks = new ArrayList<JsonObject>();
        private final List<String> pendingConfigurationCommandIds = new ArrayList<String>();

        public long getConfigRevision() {
            return configRevision;
        }

        public boolean hasAcknowledgements() {
            return configChanged || commandAcks.size() > 0 || warningIds.size() > 0;
        }

        public String toAckJson(String serverId) {
            JsonObject body = new JsonObject();
            body.addProperty("server_id", serverId);
            body.addProperty("config_revision", configRevision);
            body.add("command_acks", commandAcks);
            body.add("warning_ids", warningIds);
            return body.toString();
        }
    }

    public static Result process(String responseBody, CraftyAIConfig config, Handler handler) {
        Result result = new Result();
        result.configRevision = config == null ? 0 : config.control_revision;
        if (responseBody == null || config == null || handler == null) return result;
        CraftyAIConfig original = CraftyAIConfig.fromJson(config.toJson());
        original.tier = config.tier;

        try {
            JsonElement parsed = JsonParserAdapter.parse(responseBody);
            if (parsed == null || !parsed.isJsonObject()) return result;
            JsonObject root = parsed.getAsJsonObject();
            collectWarningIds(root, result);
            if (!root.has("control_plane") || !root.get("control_plane").isJsonObject()) return result;

            JsonObject control = root.getAsJsonObject("control_plane");
            long revision = getLong(control, "config_revision", config.control_revision);
            if (revision > config.control_revision && control.has("config") && control.get("config").isJsonObject()) {
                CraftyAIConfig candidate = CraftyAIConfig.fromJson(config.toJson());
                applySettingsObject(candidate, control.getAsJsonObject("config"));
                candidate.control_revision = revision;
                config.copyFrom(candidate);
                result.configRevision = revision;
                result.configChanged = true;
            }

            if (control.has("commands") && control.get("commands").isJsonArray()) {
                JsonArray commands = control.getAsJsonArray("commands");
                int count = Math.min(commands.size(), 20);
                for (int i = 0; i < count; i++) {
                    JsonElement commandElement = commands.get(i);
                    if (commandElement.isJsonObject()) processCommand(commandElement.getAsJsonObject(), config, handler, result);
                }
            }

            if (result.configChanged) {
                if (handler.persistConfiguration(config)) {
                    handler.reloadConfiguration();
                    for (String commandId : result.pendingConfigurationCommandIds) markSeen(commandId);
                } else {
                    config.copyFrom(original);
                    result.configRevision = original.control_revision;
                    result.configChanged = false;
                    for (JsonObject acknowledgement : result.pendingConfigurationAcks) {
                        acknowledgement.addProperty("status", "failed");
                        acknowledgement.remove("result");
                        acknowledgement.addProperty("error", "Configuration could not be persisted");
                    }
                    handler.log("[Control] Configuration update was not acknowledged because the atomic save failed");
                }
            }
        } catch (Throwable error) {
            handler.log("[Control] Ignored invalid control-plane response: " + safeMessage(error));
        }
        return result;
    }

    private static void collectWarningIds(JsonObject root, Result result) {
        if (!root.has("warnings") || !root.get("warnings").isJsonArray()) return;
        JsonArray warnings = root.getAsJsonArray("warnings");
        int count = Math.min(warnings.size(), 20);
        for (int i = 0; i < count; i++) {
            JsonElement item = warnings.get(i);
            if (item.isJsonObject()) {
                String id = getString(item.getAsJsonObject(), "id", "");
                if (isUuid(id)) result.warningIds.add(id);
            }
        }
    }

    private static void processCommand(JsonObject command, CraftyAIConfig config, Handler handler, Result result) {
        String id = getString(command, "id", "");
        String type = getString(command, "command_type", "");
        if (!isUuid(id) || type.length() == 0) return;
        if (alreadySeen(id)) {
            JsonObject duplicateAck = new JsonObject();
            duplicateAck.addProperty("id", id);
            duplicateAck.addProperty("status", "acknowledged");
            JsonObject duplicateResult = new JsonObject();
            duplicateResult.addProperty("applied", false);
            duplicateResult.addProperty("duplicate", true);
            duplicateAck.add("result", duplicateResult);
            result.commandAcks.add(duplicateAck);
            return;
        }

        JsonObject acknowledgement = new JsonObject();
        acknowledgement.addProperty("id", id);
        try {
            JsonObject payload = command.has("payload") && command.get("payload").isJsonObject()
                    ? command.getAsJsonObject("payload") : new JsonObject();
            if ("show_message".equals(type)) {
                JsonElement titleElement = payload.has("title") ? payload.get("title") : stringElement("CraftyAI");
                JsonElement messageElement = payload.has("message") ? payload.get("message") : stringElement("");
                String title = boundedString(titleElement, 1, 80);
                String message = boundedString(messageElement, 1, 500);
                String severity = getString(payload, "severity", "info");
                if (!"info".equals(severity) && !"warning".equals(severity) && !"critical".equals(severity)) {
                    throw new IllegalArgumentException("Invalid message severity");
                }
                handler.showMessage(title, message, severity);
            } else if ("reload_config".equals(type)) {
                handler.reloadConfiguration();
            } else if ("set_runtime_setting".equals(type)) {
                String key = getString(payload, "key", "");
                if (!payload.has("value")) throw new IllegalArgumentException("Setting value is missing");
                applySetting(config, key, payload.get("value"));
                result.configChanged = true;
                result.pendingConfigurationAcks.add(acknowledgement);
                result.pendingConfigurationCommandIds.add(id);
            } else {
                throw new IllegalArgumentException("Unsupported command type");
            }
            if (!"set_runtime_setting".equals(type)) markSeen(id);
            acknowledgement.addProperty("status", "acknowledged");
            JsonObject commandResult = new JsonObject();
            commandResult.addProperty("applied", true);
            acknowledgement.add("result", commandResult);
        } catch (Throwable error) {
            acknowledgement.addProperty("status", "failed");
            acknowledgement.addProperty("error", safeMessage(error));
        }
        result.commandAcks.add(acknowledgement);
    }

    private static void applySettingsObject(CraftyAIConfig config, JsonObject values) {
        for (Map.Entry<String, JsonElement> entry : values.entrySet()) {
            applySetting(config, entry.getKey(), entry.getValue());
        }
    }

    private static void applySetting(CraftyAIConfig config, String key, JsonElement value) {
        if ("ai.name".equals(key)) {
            config.ai_name = boundedString(value, 2, 24);
        } else if ("ai.aliases".equals(key)) {
            if (!value.isJsonArray() || value.getAsJsonArray().size() == 0 || value.getAsJsonArray().size() > 12) throw new IllegalArgumentException("Invalid aliases");
            String[] aliases = new String[value.getAsJsonArray().size()];
            for (int i = 0; i < aliases.length; i++) aliases[i] = boundedString(value.getAsJsonArray().get(i), 1, 24);
            config.aliases = aliases;
        } else if ("ai.prefix".equals(key)) {
            config.prefix = boundedString(value, 1, 4);
        } else if ("ai.require_prefix".equals(key)) {
            config.require_prefix = requiredBoolean(value);
        } else if ("ai.cooldown_seconds".equals(key)) {
            config.cooldown_seconds = boundedInt(value, 0, 300);
        } else if ("ai.response_visibility".equals(key)) {
            String visibility = boundedString(value, 7, 14);
            if (!"default".equals(visibility) && !"always-private".equals(visibility) && !"always-public".equals(visibility)) throw new IllegalArgumentException("Invalid response visibility");
            config.response_visibility = visibility;
        } else if ("ai.actions_enabled".equals(key)) {
            boolean enabled = requiredBoolean(value);
            config.ai_enable_actions = enabled;
            config.agentic_tasks_enabled = enabled;
        } else if ("ai.force_local_mode".equals(key)) {
            config.force_local_mode = requiredBoolean(value);
        } else if ("ai.require_confirmation".equals(key)) {
            config.require_confirmation = requiredBoolean(value);
        } else if ("vision.enabled".equals(key)) {
            config.allow_block_scanning = requiredBoolean(value);
        } else if ("vision.activation".equals(key)) {
            config.vision_activation = boundedString(value, 1, 32);
        } else if ("vision.activation_item".equals(key)) {
            config.vision_activation_item = boundedString(value, 1, 32);
        } else if ("vision.cooldown".equals(key)) {
            config.vision_cooldown = boundedInt(value, 0, 300);
        } else if ("vision.shift_scan_enabled".equals(key)) {
            config.vision_shift_scan_enabled = requiredBoolean(value);
        } else if ("experience.op_welcome".equals(key)) {
            config.op_welcome_message = requiredBoolean(value);
        } else {
            throw new IllegalArgumentException("Unsupported setting: " + key);
        }
    }

    private static boolean requiredBoolean(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("Expected boolean");
        return value.getAsBoolean();
    }

    private static int boundedInt(JsonElement value, int minimum, int maximum) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Expected number");
        int number = value.getAsInt();
        if (number < minimum || number > maximum) throw new IllegalArgumentException("Number out of range");
        return number;
    }

    private static String boundedString(JsonElement value, int minimum, int maximum) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Expected string");
        String text = value.getAsString().trim();
        if (text.length() < minimum || text.length() > maximum || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) throw new IllegalArgumentException("String out of range");
        return text;
    }

    private static String getString(JsonObject object, String key, String fallback) {
        try {
            return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static JsonElement stringElement(String value) {
        com.google.gson.JsonPrimitive primitive = new com.google.gson.JsonPrimitive(value);
        return primitive;
    }

    private static long getLong(JsonObject object, String key, long fallback) {
        try {
            return object.has(key) ? object.get(key).getAsLong() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static boolean isUuid(String value) {
        return value != null && value.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$");
    }

    private static synchronized boolean alreadySeen(String id) {
        return SEEN_COMMANDS.containsKey(id);
    }

    private static synchronized void markSeen(String id) {
        SEEN_COMMANDS.put(id, System.currentTimeMillis());
        while (SEEN_COMMANDS.size() > MAX_SEEN_COMMANDS) {
            String oldest = SEEN_COMMANDS.keySet().iterator().next();
            SEEN_COMMANDS.remove(oldest);
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? "Unknown error" : error.getMessage();
        if (message == null || message.length() == 0) message = error.getClass().getSimpleName();
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
