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

/**
 * Shared configuration class for CraftyAI mods
 * Used by both Fabric and Forge implementations
 */
public class CraftyAIConfig {
    public int config_version = 4;
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
    public String response_visibility = "default"; // Options: "default", "always-private", "always-public"
    public boolean ai_enable_actions = true;
    public String server_id = ""; // Saved to config as recovery key — also persisted in .craftyai_session
    public transient volatile String tier = "free"; // Fetched from Gateway during handshake

    // Custom provider settings
    public boolean custom_provider_enabled = false;
    public String custom_provider_url = "";
    public String custom_provider_key = "";
    public String custom_provider_model = "";

    // Agentic tasks - only pro users should have agentic tasks
    public boolean agentic_tasks_enabled = false;

    // Block scanning — universally available (tier caps radius only)
    public boolean allow_block_scanning = true;

    // When false, destructive actions execute immediately without /crafty confirm
    public boolean require_confirmation = true;

    // Vision scanner settings
    public String vision_activation = "V";  // Changed from Shift to V key
    public String vision_activation_item = "COMPASS";  // Item to hold for Bukkit vision scanning
    public boolean vision_shift_scan_enabled = false;  // Disabled by default since it conflicts with block placing

    private static final Gson GSON = new GsonBuilder().create();

    /**
     * Serialize config to JSON
     */
    public String toJson() {
        return GSON.toJson(this);
    }

    /**
     * Deserialize config from JSON
     */
    public static CraftyAIConfig fromJson(String json) {
        return GSON.fromJson(json, CraftyAIConfig.class);
    }

    /** Replace this instance atomically after a candidate configuration validates. */
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
        this.vision_shift_scan_enabled = other.vision_shift_scan_enabled;
    }

    /**
     * Get cooldown in milliseconds
     */
    public int getCooldownMs() {
        return cooldown_seconds * 1000;
    }

    /**
     * Returns the effective API URL based on config.
     * If custom provider is enabled with a non-empty URL, returns the custom provider URL.
     * Otherwise, returns the CraftyAI gateway URL.
     */
    public String getEffectiveApiUrl() {
        if (custom_provider_enabled && custom_provider_url != null && !custom_provider_url.isEmpty()) {
            return custom_provider_url;
        }
        return GatewayRequestHeaders.getGatewayUrl();
    }

    /**
     * Returns the effective API key based on config.
     * If custom provider is enabled with a non-empty key, returns the custom provider key.
     * Otherwise, returns the standard API key.
     */
    public String getEffectiveApiKey() {
        if (custom_provider_enabled && custom_provider_key != null && !custom_provider_key.isEmpty()) {
            return custom_provider_key;
        }
        return api_key;
    }

    /**
     * Checks if a key matches valid CraftyAI API key format (cai_...).
     */
    public static boolean isValidApiKeyFormat(String key) {
        if (key == null) return false;
        String trimmed = key.trim();
        return trimmed.startsWith("cai_") && trimmed.length() >= 20 && trimmed.length() <= 132;
    }

    /**
     * Checks if an API key needs auto-minting (missing, unconfigured, or legacy format).
     * Custom provider keys are never auto-minted.
     */
    public static boolean needsAutoMint(String key, boolean customProviderEnabled) {
        if (customProviderEnabled) return false;
        if (key == null || key.trim().isEmpty()) return true;
        String trimmed = key.trim();
        if ("YOUR_API_KEY_HERE".equalsIgnoreCase(trimmed)) return true;
        return !isValidApiKeyFormat(trimmed);
    }
}
