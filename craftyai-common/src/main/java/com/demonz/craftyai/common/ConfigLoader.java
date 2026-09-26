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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;

public class ConfigLoader {

    public static CraftyAIConfig loadOrCreateConfig(String configDir, String configFileName, Consumer<String> logger) {
        Path configPath = Paths.get(configDir);
        Path configFile = configPath.resolve(configFileName);

        try {
            if (!Files.exists(configPath)) {
                Files.createDirectories(configPath);
            }

            if (!Files.exists(configFile)) {
                CraftyAIConfig defaultConfig = new CraftyAIConfig();
                saveConfig(configDir, configFileName, defaultConfig, logger);
                logger.accept("[CraftyAI] Config created at " + configFileName + " \u2014 set your API key!");
                return defaultConfig;
            }

            String content = new String(Files.readAllBytes(configFile), StandardCharsets.UTF_8);
            CraftyAIConfig config = CraftyAIConfig.fromJson(content);

            boolean migrated = false;
            if (config.config_version < 2) {
                logger.accept("[CraftyAI] Migrating craftyai.json from version " + config.config_version + " to 2...");
                config.config_version = 2;
                if (config.response_visibility == null) {
                    config.response_visibility = "default";
                }
                migrated = true;
            }

            if (config.config_version < 3) {
                logger.accept("[CraftyAI] Migrating craftyai.json from version " + config.config_version + " to 3...");
                config.config_version = 3;
                config.ai_enable_actions = true;
                config.allow_block_scanning = true;
                if (!config.agentic_tasks_enabled) {
                    config.agentic_tasks_enabled = true;
                }
                migrated = true;
            }

            if (config.config_version < 4) {
                logger.accept("[CraftyAI] Migrating craftyai.json from version " + config.config_version + " to 4...");
                config.config_version = 4;
                if (config.control_revision < 0) config.control_revision = 0;
                migrated = true;
            }

            if (config.config_version < 5) {
                logger.accept("[CraftyAI] Migrating craftyai.json from version " + config.config_version + " to 5...");
                config.config_version = 5;
                migrated = true;
            }

            if (config.config_version < 6) {
                logger.accept("[CraftyAI] Migrating craftyai.json from version " + config.config_version + " to 6...");
                config.config_version = 6;
                if (config.vision_cooldown <= 0) config.vision_cooldown = 5;
                if ("V".equals(config.vision_activation)) config.vision_activation = "item_right_click";
                migrated = true;
            }

            if (migrated) {
                saveConfig(configDir, configFileName, config, logger);
            }

            String migrationError = validateConfig(config);
            if (migrationError != null) {
                logger.accept("[CraftyAI] Config validation note after migration: " + migrationError);
            }

            return config;
        } catch (Exception e) {
            logger.accept("[CraftyAI] Failed to load config: " + e.getMessage());
            return new CraftyAIConfig();
        }
    }

    private static String validateConfig(CraftyAIConfig config) {
        if (config == null) return "Config is null";

        if (config.api_key != null && !config.api_key.isEmpty() && !"YOUR_API_KEY_HERE".equals(config.api_key)) {
            if (!config.api_key.matches("^cai_[a-zA-Z0-9_-]{16,128}$")) {
                return "Invalid API key format (must start with cai_)";
            }
        }

        if (config.custom_provider_enabled) {
            if (config.custom_provider_url == null || config.custom_provider_url.isEmpty()) {
                return "Custom provider enabled but URL is empty";
            }
            if (!config.custom_provider_url.matches("^https?://.+$")) {
                return "Custom provider URL must start with http:// or https://";
            }
        }

        return null;
    }

    public static boolean saveConfig(String configDir, String configFileName, CraftyAIConfig config, Consumer<String> logger) {
        Path configPath = Paths.get(configDir);
        Path configFile = configPath.resolve(configFileName);
        Path tempFile = null;

        String validationError = validateConfig(config);
        if (validationError != null) {
            logger.accept("[CraftyAI] Config validation warning (saving anyway): " + validationError);
        }

        try {
            if (!Files.exists(configPath)) {
                Files.createDirectories(configPath);
            }

            String json = config.toJson();
            tempFile = Files.createTempFile(configPath, configFileName + ".", ".tmp");
            Files.write(tempFile, json.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tempFile, configFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tempFile, configFile, StandardCopyOption.REPLACE_EXISTING);
            }
            logger.accept("[CraftyAI] Config saved successfully");
            return true;
        } catch (Exception e) {
            logger.accept("[CraftyAI] Failed to save config: " + e.getMessage());
            try { if (tempFile != null) Files.deleteIfExists(tempFile); } catch (Exception ignored) { }
            return false;
        }
    }
}
