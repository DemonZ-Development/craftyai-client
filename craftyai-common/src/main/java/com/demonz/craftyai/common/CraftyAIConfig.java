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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class CraftyAIConfig {
    public int config_version = 6;
    public long control_revision = 0;
    public String api_key = "YOUR_API_KEY_HERE";
    public String ai_name = "Crafty";
    public String[] aliases = new String[]{"crafty", "craftyai", "ai", "helper"};
    public String prefix = "@";
    public boolean require_prefix = false;
    public int cooldown_seconds = 0;

    public boolean op_welcome_message = true;
    public boolean force_local_mode = false;
    public boolean telemetry_enabled = true;
    public String response_visibility = "default";
    public boolean ai_enable_actions = true;
    public String server_id = "";
    public transient volatile String tier = "free";

    public boolean custom_provider_enabled = false;
    public String custom_provider_url = "";
    public String custom_provider_key = "";
    public String custom_provider_model = "";

    public boolean agentic_tasks_enabled = true;

    public boolean allow_block_scanning = true;

    public boolean require_confirmation = true;

    public String vision_activation = "item_right_click";
    public String vision_activation_item = "COMPASS";
    public int vision_cooldown = 5;
    public boolean vision_shift_scan_enabled = false;

    private static final Gson GSON = new GsonBuilder().create();

    public String toJson() {
        return GSON.toJson(this);
    }

    public static CraftyAIConfig fromJson(String json) {
        return GSON.fromJson(json, CraftyAIConfig.class);
    }

    public void copyFrom(CraftyAIConfig other) {
        if (other == null) return;
        this.config_version = other.config_version;
        this.control_revision = other.control_revision;
        this.api_key = other.api_key;
        this.ai_name = other.ai_name;
        this.aliases = other.aliases == null ? null : other.aliases.clone();
        this.prefix = other.prefix;
        this.require_prefix = other.require_prefix;
        this.cooldown_seconds = other.cooldown_seconds;
        this.op_welcome_message = other.op_welcome_message;
        this.force_local_mode = other.force_local_mode;
        this.telemetry_enabled = other.telemetry_enabled;
        this.response_visibility = other.response_visibility;
        this.ai_enable_actions = other.ai_enable_actions;
        this.server_id = other.server_id;
        this.tier = other.tier;
        this.custom_provider_enabled = other.custom_provider_enabled;
        this.custom_provider_url = other.custom_provider_url;
        this.custom_provider_key = other.custom_provider_key;
        this.custom_provider_model = other.custom_provider_model;
        this.agentic_tasks_enabled = other.agentic_tasks_enabled;
        this.allow_block_scanning = other.allow_block_scanning;
        this.require_confirmation = other.require_confirmation;
        this.vision_activation = other.vision_activation;
        this.vision_activation_item = other.vision_activation_item;
        this.vision_cooldown = other.vision_cooldown;
        this.vision_shift_scan_enabled = other.vision_shift_scan_enabled;
    }

    public int getCooldownMs() {
        return cooldown_seconds * 1000;
    }

    public String getEffectiveApiUrl() {
        if (custom_provider_enabled && custom_provider_url != null && !custom_provider_url.isEmpty()) {
            return custom_provider_url;
        }
        return GatewayRequestHeaders.getGatewayUrl();
    }

    public String getEffectiveApiKey() {
        if (custom_provider_enabled && custom_provider_key != null && !custom_provider_key.isEmpty()) {
            return custom_provider_key;
        }
        return api_key;
    }

    public static boolean isValidApiKeyFormat(String key) {
        if (key == null) return false;
        String trimmed = key.trim();
        return trimmed.startsWith("cai_") && trimmed.length() >= 20 && trimmed.length() <= 132;
    }

    public static boolean needsAutoMint(String key, boolean customProviderEnabled) {
        if (customProviderEnabled) return false;
        if (key == null || key.trim().isEmpty()) return true;
        String trimmed = key.trim();
        if ("YOUR_API_KEY_HERE".equalsIgnoreCase(trimmed)) return true;
        return !isValidApiKeyFormat(trimmed);
    }
}
