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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class AnnouncementPoller {

    private final CraftyAI plugin;
    private final VersionAdapter adapter;
    private final Set<String> seenAnnouncementIds = ConcurrentHashMap.newKeySet();
    private static final int MAX_SEEN_IDS = 500;
    private static final int MIN_POLL_SECONDS = 300;
    private static final int MAX_POLL_SECONDS = 3600;
    private static final Gson GSON = new Gson();
    private volatile String lastEtag;

    public AnnouncementPoller(CraftyAI plugin, VersionAdapter adapter) {
        this.plugin = plugin;
        this.adapter = adapter;
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("announcements.enabled", true)) {
            plugin.getLogger().info("[CraftyAI] Announcement checks are disabled by configuration.");
            return;
        }
        final int configuredInterval = plugin.getConfig().getInt("announcements.poll_interval_seconds", MIN_POLL_SECONDS);
        final int pollSeconds = Math.max(MIN_POLL_SECONDS, Math.min(configuredInterval, MAX_POLL_SECONDS));
        final Runnable[] ref = new Runnable[1];
        ref[0] = new Runnable() {
            @Override
            public void run() {
                try {
                    String targetUrl = com.demonz.craftyai.common.GatewayRequestHeaders.getGatewayUrl();
                    String vaultUrl = plugin.getConfig().getString("vault_url", null);
                    if (vaultUrl == null || vaultUrl.isEmpty()) {
                        vaultUrl = targetUrl;
                        if (vaultUrl.contains("-gateway")) {
                            vaultUrl = vaultUrl.replace("-gateway", "-vault");
                        }
                    }
                    HttpURLConnection conn = null;
                    try {
                        URL url = new URL(vaultUrl + "/api/announcements");
                        conn = (HttpURLConnection) url.openConnection();
                        conn.setInstanceFollowRedirects(false);
                        conn.setRequestMethod("GET");
                        conn.setRequestProperty("X-Client-Type", "minecraft-spigot");
                        conn.setRequestProperty("X-CraftyAI-Version", plugin.getDescription().getVersion());
                        if (lastEtag != null && !lastEtag.isEmpty()) {
                            conn.setRequestProperty("If-None-Match", lastEtag);
                        }
                        conn.setConnectTimeout(5000);
                        conn.setReadTimeout(5000);
                        int code = conn.getResponseCode();
                        if (code == HttpURLConnection.HTTP_OK) {
                            String responseEtag = conn.getHeaderField("ETag");
                            if (responseEtag != null && !responseEtag.isEmpty()) lastEtag = responseEtag;
                            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                                StringBuilder sb = new StringBuilder();
                                String line;
                                while ((line = br.readLine()) != null) sb.append(line);
                                parseAndDeliver(sb.toString());
                            }
                        }
                    } finally {
                        if (conn != null) conn.disconnect();
                    }
                } catch (Throwable error) {
                    plugin.getLogger().fine("[CraftyAI] Announcement check failed: " + error.getMessage());
                } finally {
                    adapter.runAsyncLater(ref[0], 20L * pollSeconds);
                }
            }
        };
        adapter.runAsyncLater(ref[0], 20 * 10);
    }

    private void parseAndDeliver(String respBody) {
        if (seenAnnouncementIds.size() > MAX_SEEN_IDS) {
            seenAnnouncementIds.clear();
        }
        try {
            JsonArray arr = GSON.fromJson(respBody, JsonArray.class);
            if (arr == null) return;
            for (int i = 0; i < arr.size(); i++) {
                JsonObject obj = arr.get(i).getAsJsonObject();
                String id = obj.get("id").getAsString();
                if (!seenAnnouncementIds.add(id)) continue;
                final String title = obj.has("title") ? obj.get("title").getAsString() : "Announcement";
                final String body = obj.has("body") ? obj.get("body").getAsString() : "";
                final String sev = obj.has("severity") ? obj.get("severity").getAsString() : "low";
                final String color = sev.equals("critical") || sev.equals("high") ? "&c&l" : sev.equals("medium") ? "&e&l" : "&7&l";
                adapter.runSync(new Runnable() {
                    public void run() {
                        for (org.bukkit.entity.Player p : plugin.getServer().getOnlinePlayers()) {
                            if (p.hasPermission("crafty.admin")) {
                                adapter.sendMessage(p, color + "[" + plugin.getAiName() + "] " + title);
                                if (!body.isEmpty()) adapter.sendMessage(p, "&7" + body);
                            }
                        }
                    }
                });
            }
        } catch (Throwable t) {
            plugin.getLogger().fine("[" + plugin.getAiName() + "] Announcement parse failed: " + t.getMessage());
        }
    }
}
