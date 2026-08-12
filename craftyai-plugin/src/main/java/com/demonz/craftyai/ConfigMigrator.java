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

/*
 * v1.2.0-beta: ConfigMigrator — safely migrates plugin/config versions.
 * Idempotent. Additive only. Never deletes unknown fields (forward compat).
 *
 * Migration map:
 *   v1 -> v2: add ai.enable_actions (default true), ai.custom_prompt (default ""),
 *             ai.max_scheduled_tasks (default 2), bump config_version to 2
 *   v2 -> v3: rename ai.agentic_tasks_enabled -> ai.enable_actions (read both),
 *             add ai.allow_block_scanning (default true), bump config_version to 3
 *
 * On failure: log error, keep old config in config.yml.bak, fall back to defaults.
 * Never crash server startup.
 */
package com.demonz.craftyai;

import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.logging.Logger;

public final class ConfigMigrator {
    public static final int CURRENT_VERSION = 4;
    private final Logger logger;
    private final File configFile;
    private final File backupFile;

    public ConfigMigrator(File dataFolder, Logger logger) {
        this.logger = logger;
        this.configFile = new File(dataFolder, "config.yml");
        this.backupFile = new File(dataFolder, "config.yml.bak");
    }

    /**
     * Run migrations in order. Returns true if any change was made.
     * Safe to call on every startup; idempotent.
     */
    public boolean migrate(FileConfiguration config) {
        if (config == null) return false;
        int version = config.getInt("config_version", config.getInt("config-version", 1));
        if (version >= CURRENT_VERSION) {
            return false;
        }
        try {
            backupConfig();
            boolean changed = false;
            if (version < 2) {
                changed |= migrateV1ToV2(config);
            }
            if (version < 3) {
                changed |= migrateV2ToV3(config);
            }
            if (version < 4) {
                changed |= migrateV3ToV4(config);
            }
            config.set("config_version", CURRENT_VERSION);
            config.set("config-version", CURRENT_VERSION);
            logger.info("[CraftyAI] Config migrated to v" + CURRENT_VERSION);
            // The version markers themselves changed even when an admin had
            // already added every new key manually, so the file must be saved.
            return true;
        } catch (Throwable t) {
            logger.warning("[CraftyAI] Config migration failed: " + t.getClass().getSimpleName() + " — " + t.getMessage());
            logger.warning("[CraftyAI] Keeping old config in " + backupFile.getName() + ". Server will use defaults for missing fields.");
            return false;
        }
    }

    private boolean migrateV1ToV2(FileConfiguration config) {
        boolean changed = false;
        if (!config.contains("ai.enable_actions")) {
            config.set("ai.enable_actions", true);
            changed = true;
        }
        if (!config.contains("ai.custom_prompt")) {
            config.set("ai.custom_prompt", "");
            changed = true;
        }
        if (!config.contains("ai.max_scheduled_tasks")) {
            config.set("ai.max_scheduled_tasks", 2);
            changed = true;
        }
        return changed;
    }

    private boolean migrateV2ToV3(FileConfiguration config) {
        boolean changed = false;
        // Rename ai.agentic_tasks_enabled -> ai.enable_actions (read both, prefer new)
        if (config.contains("ai.agentic_tasks_enabled") && !config.contains("ai.enable_actions")) {
            config.set("ai.enable_actions", config.getBoolean("ai.agentic_tasks_enabled", true));
            changed = true;
        } else if (!config.contains("ai.enable_actions")) {
            config.set("ai.enable_actions", true);
            changed = true;
        }
        if (!config.contains("ai.allow_block_scanning")) {
            config.set("ai.allow_block_scanning", true);
            changed = true;
        }
        return changed;
    }

    private boolean migrateV3ToV4(FileConfiguration config) {
        boolean changed = false;
        if (!config.contains("announcements.enabled")) {
            config.set("announcements.enabled", true);
            changed = true;
        }
        if (!config.contains("announcements.poll_interval_seconds")) {
            config.set("announcements.poll_interval_seconds", 300);
            changed = true;
        }
        if (!config.contains("ai.privacy_opt_out")) {
            config.set("ai.privacy_opt_out", false);
            changed = true;
        }
        if (!config.contains("control.revision")) {
            config.set("control.revision", 0L);
            changed = true;
        }
        return changed;
    }

    private void backupConfig() {
        try {
            if (configFile.exists()) {
                Files.copy(configFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            logger.warning("[CraftyAI] Config backup failed: " + e.getMessage());
        }
    }
}
