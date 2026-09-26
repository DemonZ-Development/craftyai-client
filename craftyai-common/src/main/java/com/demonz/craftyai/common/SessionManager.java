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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

public class SessionManager {
    private static volatile String currentSessionId = null;
    private static final Object LOCK = new Object();
    private static final Logger LOGGER = Logger.getLogger(SessionManager.class.getName());

    public static String getSessionId(Path configDir) {
        String id = currentSessionId;
        if (id != null && !id.isEmpty()) {
            return id;
        }

        File sessionFile = new File(configDir.toFile(), ".craftyai_session");
        boolean needsWrite = false;

        synchronized (LOCK) {

            if (currentSessionId != null && !currentSessionId.isEmpty()) {
                return currentSessionId;
            }

            try {
                if (sessionFile.exists()) {
                    String content = new String(Files.readAllBytes(sessionFile.toPath()), java.nio.charset.StandardCharsets.UTF_8).trim();
                    if (content.matches("^srv-[a-fA-F0-9]{8}$")) {
                        currentSessionId = content;
                        return currentSessionId;
                    }
                }

                currentSessionId = "srv-" + UUID.randomUUID().toString().substring(0, 8);
                LOGGER.info("[CraftyAI] Generated new session ID: " + currentSessionId);
                needsWrite = true;

            } catch (IOException e) {
                LOGGER.warning("[CraftyAI] CRITICAL: Session file access error: " + e.getMessage());
                if (currentSessionId == null) {
                    currentSessionId = "srv-" + UUID.randomUUID().toString().substring(0, 8);
                    LOGGER.info("[CraftyAI] Using temporary session ID: " + currentSessionId);
                }
            }
        }

        if (needsWrite) {
            File parent = sessionFile.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            writeSessionFile(sessionFile, currentSessionId);
        }

        return currentSessionId;
    }

    private static void writeSessionFile(File sessionFile, String id) {
        try {
            Files.write(sessionFile.toPath(), id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return;
        } catch (IOException e) {

        }
        try {
            Thread.sleep(100);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        try {
            Files.write(sessionFile.toPath(), id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOGGER.warning("[CraftyAI] CRITICAL: Failed to persist session ID to " + sessionFile.getAbsolutePath());
            LOGGER.warning("[CraftyAI] Session ID will not survive server restart: " + e.getMessage());
        }
    }

    public static void resetSessionId(Path configDir) {
        synchronized (LOCK) {
            currentSessionId = null;
            File sessionFile = new File(configDir.toFile(), ".craftyai_session");
            if (sessionFile.exists()) {
                try {
                    Files.delete(sessionFile.toPath());
                    LOGGER.info("[CraftyAI] Session ID reset \u2014 old session file deleted");
                } catch (IOException e) {
                    LOGGER.info("[CraftyAI] Failed to delete session file during reset: " + e.getMessage());
                }
            }
        }
    }

    public static void setSessionId(Path configDir, String newId) {
        if (newId == null || newId.isEmpty() || !newId.matches("^srv-[a-fA-F0-9]{8}$")) {
            return;
        }
        synchronized (LOCK) {
            currentSessionId = newId;
            File sessionFile = new File(configDir.toFile(), ".craftyai_session");
            File parent = sessionFile.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            writeSessionFile(sessionFile, newId);
        }
    }

    public static void reload() {
        synchronized (LOCK) {
            currentSessionId = null;
        }
    }
}
