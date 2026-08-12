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

/**
 * Shared update checker for CraftyAI mods
 * Checks Modrinth for the latest version
 */
public class UpdateChecker {
    private static final Gson GSON = new GsonBuilder().create();
    private static final String MODRINTH_API = "https://api.modrinth.com/v2/project/XOFIR6bb/version";
    private static final String CURRENT_VERSION = UpdateChecker.class.getPackage().getImplementationVersion() != null
            ? UpdateChecker.class.getPackage().getImplementationVersion() : GatewayRequestHeaders.MOD_VERSION;
    private static final String ALLOWED_UPDATE_HOST = "api.modrinth.com";

    /**
     * Check for updates asynchronously
     * @param logger Consumer that receives log messages
     */
    public static void checkForUpdatesAsync(Consumer<String> logger) {
        CompletableFuture.runAsync(() -> {
            try {
                // Validate URL before making request
                URI uri = URI.create(MODRINTH_API);
                if (!ALLOWED_UPDATE_HOST.equals(uri.getHost())) {
                    logger.accept("[CraftyAI] Update host not allowed");
                    return;
                }

                // Use shared HTTP client from LocalBrain for connection pooling
                HttpClient httpClient = LocalBrain.getHttpClient();
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("User-Agent", "CraftyAI-UpdateChecker/1.0.0")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() == 200) {
                    String body = response.body();
                    ModrinthVersion[] versions = GSON.fromJson(body, ModrinthVersion[].class);
                    
                    if (versions != null && versions.length > 0) {
                        String latestVersion = versions[0].version_number;
                        if (!latestVersion.equalsIgnoreCase(CURRENT_VERSION)) {
                            logger.accept("==============================================");
                            logger.accept("  CraftyAI Update Available!                  ");
                            logger.accept("  Current: " + CURRENT_VERSION);
                            logger.accept("  Latest: " + latestVersion);
                            logger.accept("  Download at: https://modrinth.com/plugin/craftyai");
                            logger.accept("==============================================");
                        } else {
                            logger.accept("[CraftyAI] You are running the latest version (" + CURRENT_VERSION + ").");
                        }
                    }
                }
            } catch (Exception e) {
                logger.accept("[CraftyAI] Failed to check for updates: " + e.getMessage());
            }
        });
    }

    /**
     * Modrinth version response class
     */
    private static class ModrinthVersion {
        String version_number;
    }
}
