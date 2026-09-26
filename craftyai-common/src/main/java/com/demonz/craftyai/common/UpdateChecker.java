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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class UpdateChecker {
    private static final Gson GSON = new GsonBuilder().create();
    private static final String MODRINTH_API = "https://api.modrinth.com/v2/project/XOFIR6bb/version";
    private static final String CURRENT_VERSION = UpdateChecker.class.getPackage().getImplementationVersion() != null
            ? UpdateChecker.class.getPackage().getImplementationVersion() : GatewayRequestHeaders.MOD_VERSION;
    private static final String ALLOWED_UPDATE_HOST = "api.modrinth.com";
    private static final long CACHE_DURATION_MS = 3600000;
    private static long lastCheckTime = 0;
    private static volatile String latestVersion;

    public static String getLatestVersion() {
        return latestVersion;
    }

    public static void checkForUpdatesAsync(Consumer<String> logger) {
        checkForUpdatesAsync(logger, null);
    }

    public static void checkForUpdatesAsync(Consumer<String> logger, String requiredLoader) {
        long now = System.currentTimeMillis();
        if (now - lastCheckTime < CACHE_DURATION_MS) return;
        lastCheckTime = now;
        CompletableFuture.runAsync(() -> {
            try {
                URI uri = URI.create(MODRINTH_API);
                if (!ALLOWED_UPDATE_HOST.equals(uri.getHost())) {
                    logger.accept("[CraftyAI] Update host not allowed");
                    return;
                }
                HttpClient httpClient = LocalBrain.getHttpClient();
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("User-Agent", "CraftyAI-UpdateChecker/" + GatewayRequestHeaders.MOD_VERSION)
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    String body = response.body();
                    ModrinthVersion[] versions = GSON.fromJson(body, ModrinthVersion[].class);
                    if (versions != null && versions.length > 0) {
                        ModrinthVersion match = null;
                        if (requiredLoader != null && !requiredLoader.isEmpty()) {
                            for (ModrinthVersion v : versions) {
                                if (v.loaders != null && v.loaders.contains(requiredLoader)) {
                                    match = v;
                                    break;
                                }
                            }
                        }
                        if (match == null) match = versions[0];
                        String foundVersion = match.version_number;
                        latestVersion = foundVersion;
                        String currentVersion = CURRENT_VERSION;
                        int cmp = compareVersions(foundVersion, currentVersion);
                        if (cmp > 0) {
                            logger.accept("==============================================");
                            logger.accept("  CraftyAI Update Available!                  ");
                            logger.accept("  Current: " + currentVersion);
                            logger.accept("  Latest: " + foundVersion);
                            logger.accept("  Download at: https://modrinth.com/mod/craftyai");
                            logger.accept("==============================================");
                        } else if (cmp == 0) {
                            logger.accept("[CraftyAI] You are running the latest version (" + currentVersion + ").");
                        }
                    }
                }
            } catch (Exception e) {
                logger.accept("[CraftyAI] Failed to check for updates: " + e.getMessage());
            }
        });
    }

    private static int compareVersions(String v1, String v2) {
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        int len = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < len; i++) {
            int a = 0;
            int b = 0;
            if (i < parts1.length) {
                String p = parts1[i].replaceAll("[^0-9]", "");
                if (!p.isEmpty()) {
                    try {
                        a = Integer.parseInt(p);
                    } catch (NumberFormatException ex) {
                        a = 0;
                    }
                }
            }
            if (i < parts2.length) {
                String p = parts2[i].replaceAll("[^0-9]", "");
                if (!p.isEmpty()) {
                    try {
                        b = Integer.parseInt(p);
                    } catch (NumberFormatException ex) {
                        b = 0;
                    }
                }
            }
            if (a != b) return Integer.compare(a, b);
        }
        return 0;
    }

    private static class ModrinthVersion {
        String version_number;
        java.util.List<String> loaders;
        java.util.List<String> game_versions;
    }
}
