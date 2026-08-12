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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;

public class UpdateChecker {

    private final CraftyAI plugin;
    private final String projectId = "XOFIR6bb";
    private final String currentVersion;
    private static long lastCheckTime = 0;
    private static final long CACHE_DURATION_MS = 3600_000; // 1 hour

    public UpdateChecker(CraftyAI plugin) {
        this.plugin = plugin;
        this.currentVersion = plugin.getDescription().getVersion();
    }

    public void checkForUpdates() {
        long now = System.currentTimeMillis();
        if (now - lastCheckTime < CACHE_DURATION_MS) return;
        lastCheckTime = now;
        plugin.getAdapter().runAsync(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection connection = null;
                try {
                    URL url = URI.create("https://api.modrinth.com/v2/project/" + projectId + "/version").toURL();
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");
                    connection.setRequestProperty("User-Agent", "CraftyAI-UpdateChecker/" + currentVersion);
                    connection.setConnectTimeout(5000);
                    connection.setReadTimeout(5000);

                    if (connection.getResponseCode() == 200) {
                        StringBuilder responseStr = new StringBuilder();
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                responseStr.append(line);
                            }
                        }

                        // Parse using Gson — find the first version_number from the array
                        String response = responseStr.toString();
                        String latestVersion = parseLatestVersion(response);
                        
                        if (latestVersion != null && compareVersions(latestVersion, currentVersion) > 0) {
                            plugin.getLogger().warning("==============================================");
                            plugin.getLogger().warning("  CraftyAI Update Available!                  ");
                            plugin.getLogger().warning("  Current: " + currentVersion);
                            plugin.getLogger().warning("  Latest: " + latestVersion);
                            plugin.getLogger().warning("  Download at: https://modrinth.com/plugin/craftyai");
                            plugin.getLogger().warning("==============================================");
                        } else if (latestVersion != null) {
                            plugin.getLogger().info("[CraftyAI] You are running the latest version (" + currentVersion + ").");
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to check for CraftyAI updates on Modrinth: " + e.getMessage());
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            }
        });
    }

    /**
     * Parse the Modrinth version API response using Gson.
     * The response is a JSON array of version objects, each with a "version_number" field.
     * Returns the version_number of the first (latest) entry, or null on failure.
     */
    private String parseLatestVersion(String responseBody) {
        try {
            if (responseBody == null || responseBody.trim().isEmpty()) return null;
            JsonElement root = JsonParserAdapter.parse(responseBody);
            if (root == null || !root.isJsonArray()) return null;
            JsonArray versions = root.getAsJsonArray();
            if (versions.size() == 0) return null;
            JsonElement first = versions.get(0);
            if (!first.isJsonObject()) return null;
            JsonObject firstObj = first.getAsJsonObject();
            if (firstObj.has("version_number")) {
                return firstObj.get("version_number").getAsString();
            }
        } catch (Exception e) {
            plugin.getLogger().fine("[CraftyAI] Failed to parse Modrinth version response: " + e.getMessage());
        }
        return null;
    }

    /**
     * LOW-NEW-12: Semantic version comparison instead of string comparison.
     * Returns positive if v1 > v2, negative if v1 < v2, zero if equal.
     * Handles versions like "1.2.0-beta" by stripping non-numeric suffixes.
     */
    private static int compareVersions(String v1, String v2) {
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        int len = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < len; i++) {
            int a = i < parts1.length ? Integer.parseInt(parts1[i].replaceAll("[^0-9]", "")) : 0;
            int b = i < parts2.length ? Integer.parseInt(parts2[i].replaceAll("[^0-9]", "")) : 0;
            if (a != b) return Integer.compare(a, b);
        }
        return 0;
    }
}
