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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

public final class ActionAuditLog {
    private final Path logDir;
    private final Consumer<String> logger;
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ISO_INSTANT;
    private static final Object WRITE_LOCK = new Object();

    public ActionAuditLog(String configDir, Consumer<String> logger) {
        this.logDir = Paths.get(configDir);
        this.logger = logger == null ? s -> {} : logger;
    }

    public void log(String playerName, String playerUuid, String serverId, String action, boolean success, String detail) {
        String ts = java.time.Instant.now().toString();
        String safePlayer = sanitize(playerName);
        String safeUuid = sanitize(playerUuid);
        String safeServer = sanitize(serverId);
        String safeAction = sanitize(action);
        String safeDetail = sanitize(detail);
        String result = success ? "OK" : "FAIL";
        String line = ts + " | player=" + safePlayer + " | uuid=" + safeUuid
                + " | server=" + safeServer + " | action=" + safeAction
                + " | result=" + result + " | detail=" + safeDetail;

        logger.accept("[CraftyAI-audit] " + line);

        try {
            if (!Files.exists(logDir)) Files.createDirectories(logDir);
            String fileName = "craftyai-audit-" + LocalDate.now() + ".log";
            Path file = logDir.resolve(fileName);
            byte[] bytes = (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
            synchronized (WRITE_LOCK) {
                Files.write(file, bytes,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            logger.accept("[CraftyAI-audit] WARN: failed to write audit log: " + e.getMessage());
        }
    }

    private static String sanitize(String s) {
        if (s == null) return "-";

        return s.replace('|', '_').replace('\n', '_').replace('\r', '_');
    }
}
