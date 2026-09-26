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

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import com.demonz.craftyai.common.ActionAuditLog;
import com.demonz.craftyai.common.ActionRateLimiter;
import com.demonz.craftyai.common.ActionConfirmation;
import com.demonz.craftyai.common.AgenticActions;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

public class ActionHandler {

    private static final int MAX_ACTIONS_PER_REQUEST = 5;
    private static final java.util.logging.Logger LOGGER = java.util.logging.Logger.getLogger("CraftyAI");
    private final Set<Material> giveBlacklist;
    private final VersionAdapter adapter;

    private final ActionAuditLog auditLog;
    private final ActionRateLimiter rateLimiter;
    private final ActionConfirmation confirmations;
    private final ConcurrentHashMap<String, Long> lastBlockScanTime = new ConcurrentHashMap<String, Long>();
    private static final int MAX_BLOCK_SCAN_TRACKED = 1000;
    private static final long BLOCK_SCAN_TTL_MS = 360_000L;

    public ActionHandler(VersionAdapter adapter, ActionAuditLog auditLog,
                         ActionRateLimiter rateLimiter, ActionConfirmation confirmations) {
        this.adapter = adapter;
        this.auditLog = auditLog;
        this.rateLimiter = rateLimiter;
        this.confirmations = confirmations;
        this.giveBlacklist = buildBlacklist();
    }

    public String confirmPlayer(String playerKey) {
        return confirmations != null ? confirmations.confirm(playerKey) : null;
    }

    public void handleAction(CraftyAI plugin, Player player, String actionString, String tier) {
        handleAction(plugin, player, actionString, tier, false, null);
    }

    public void handleAction(CraftyAI plugin, Player player, String actionString, String tier, boolean alreadyConfirmed) {
        handleAction(plugin, player, actionString, tier, alreadyConfirmed, null);
    }

    public void handleAction(CraftyAI plugin, Player player, String actionString, String tier, boolean alreadyConfirmed, String originalQuestion) {
        if (actionString == null || actionString.trim().isEmpty() || actionString.equalsIgnoreCase("null")) return;
        if (!player.hasPermission("crafty.actions")) return;
        try {
            handleActionInternal(plugin, player, actionString, tier, alreadyConfirmed, originalQuestion);
        } catch (Throwable t) {
            try {
                plugin.getLogger().warning("[CraftyAI] Action handler error for " + player.getName() +
                        " (action='" + actionString + "'): " + t.getClass().getSimpleName() +
                        " \u2014 " + t.getMessage());
                adapter.sendMessage(player, "&c[CraftyAI] Action failed: " + t.getMessage());
            } catch (Throwable ignored) {}
        }
    }

    private void handleActionInternal(CraftyAI plugin, Player player, String actionString, String tier) {
        handleActionInternal(plugin, player, actionString, tier, false, null);
    }

    private void handleActionInternal(CraftyAI plugin, Player player, String actionString, String tier, boolean alreadyConfirmed, String originalQuestion) {

        boolean agenticEnabled = plugin.getConfig().getBoolean("ai.enable_actions",
            plugin.getConfig().getBoolean("ai.agentic_tasks_enabled", true));
        if (!agenticEnabled) {
            adapter.sendMessage(player, "&7&o[Agentic tasks disabled in config \u2014 action not executed]");
            return;
        }
        if (!player.hasPermission("crafty.agentic")) {
            adapter.sendMessage(player, "&7&o[Missing permission: crafty.agentic \u2014 action not executed]");
            return;
        }

        String playerKey = player.getUniqueId().toString();
        if (rateLimiter != null && !rateLimiter.tryAcquire(playerKey)) {
            long sec = rateLimiter.secondsUntilReset(playerKey);
            adapter.sendMessage(player, "&c&o[Rate limit: try again in " + sec + "s]");
            return;
        }

        String[] actions = actionString.split("\\|");
        int actionLimit = Math.min(actions.length, MAX_ACTIONS_PER_REQUEST);
        for (int actionIdx = 0; actionIdx < actionLimit; actionIdx++) {
            String action = actions[actionIdx];
            String upper = action.toUpperCase().trim();
            if (upper.isEmpty()) continue;

            String perm = AgenticActions.permissionFor(action);
            if (perm != null && !player.hasPermission(perm)) {
                adapter.sendMessage(player, "&c&o[Missing permission: " + perm + "]");
                if (auditLog != null) auditLog.log(player.getName(), playerKey, plugin.getServerId(), action, false, "missing permission " + perm);
                continue;
            }

            AgenticActions.Risk risk = AgenticActions.riskFor(action);
            boolean requireConfirmation = plugin.getConfig().getBoolean("ai.require_confirmation", true);
            if (risk == AgenticActions.Risk.DESTRUCTIVE && !alreadyConfirmed && requireConfirmation) {
                if (confirmations != null) confirmations.request(playerKey, action);
                adapter.sendMessage(player, "&c&o\u26A0 Destructive action: &f" + upper + " &c&o\u2014 run &e/crafty confirm &c&owithin 30s to execute.");
                if (auditLog != null) auditLog.log(player.getName(), playerKey, plugin.getServerId(), action, false, "awaiting confirmation");
                continue;
            }

            try {
                executeAtomicAction(player, upper);
                if (!executeParameterizedAction(plugin, player, action, upper, tier, playerKey, originalQuestion)) continue;
            } catch (Exception e) {
                plugin.getLogger().warning("[Neural] Failed to execute AI action '" + action + "': " + e.getMessage());
                if (auditLog != null) auditLog.log(player.getName(), playerKey, plugin.getServerId(), action, false, "exception: " + e.getMessage());
            }
            if (auditLog != null) auditLog.log(player.getName(), playerKey, plugin.getServerId(), action, true, "ok");
        }
    }

    private void executeAtomicAction(Player player, String upper) {
        switch (upper) {
            case "TIME_DAY":
                adapter.runSync(() -> player.getWorld().setTime(1000));
                adapter.sendMessage(player, "&eTime set to day.");
                break;
            case "TIME_NIGHT":
                adapter.runSync(() -> player.getWorld().setTime(14000));
                adapter.sendMessage(player, "&9Time set to night.");
                break;
            case "WEATHER_CLEAR":
                adapter.runSync(() -> { player.getWorld().setStorm(false); player.getWorld().setThundering(false); });
                adapter.sendMessage(player, "&bWeather cleared.");
                break;
            case "WEATHER_RAIN":
                adapter.runSync(() -> { player.getWorld().setStorm(true); player.getWorld().setThundering(false); });
                adapter.sendMessage(player, "&9Weather set to rain.");
                break;
            case "WEATHER_THUNDER":
                adapter.runSync(() -> { player.getWorld().setStorm(true); player.getWorld().setThundering(true); });
                adapter.sendMessage(player, "&cThunderstorm activated.");
                break;
            case "HEAL":
                adapter.runEntitySync(player, () -> player.setHealth(player.getMaxHealth()));
                adapter.sendMessage(player, "&aHealed!");
                break;
            case "FEED":
                adapter.runEntitySync(player, () -> { player.setFoodLevel(20); player.setSaturation(20f); });
                adapter.sendMessage(player, "&6Fully fed!");
                break;
            case "TELEPORT_SPAWN":
                adapter.runEntitySync(player, () -> player.teleport(player.getWorld().getSpawnLocation()));
                adapter.sendMessage(player, "&dTeleported to spawn.");
                break;
            case "GAMEMODE_CREATIVE":
            case "GAMEMODE_SURVIVAL":
            case "GAMEMODE_SPECTATOR":
                if (!player.hasPermission("crafty.actions.gamemode")) {
                    adapter.sendMessage(player, "&cGamemode action denied \u2014 missing crafty.actions.gamemode permission.");
                    break;
                }
                GameMode mode = upper.equals("GAMEMODE_CREATIVE") ? GameMode.CREATIVE
                              : upper.equals("GAMEMODE_SURVIVAL") ? GameMode.SURVIVAL : GameMode.SPECTATOR;
                adapter.runEntitySync(player, () -> player.setGameMode(mode));
                adapter.sendMessage(player, "&bGamemode updated to " + mode.name() + ".");
                break;
            case "KILL_MOBS":
                adapter.runEntitySync(player, () -> {
                    int killed = 0;
                    for (org.bukkit.entity.Entity entity : player.getNearbyEntities(50, 50, 50)) {
                        if (entity instanceof org.bukkit.entity.Monster) { entity.remove(); killed++; }
                    }
                    adapter.sendMessage(player, "&cEliminated " + killed + " nearby hostile mobs.");
                });
                break;
        }
    }

    private boolean executeParameterizedAction(CraftyAI plugin, Player player, String action, String upper, String tier, String playerKey, String originalQuestion) {
        try {
            if (upper.startsWith("GIVE:")) {
                handleGive(player, action, plugin);
            } else if (upper.startsWith("EFFECT:")) {
                handleEffect(player, action);
            } else if (upper.startsWith("ENCHANT:")) {
                handleEnchant(player, action);
            } else if (upper.startsWith("TP:")) {
                handleTeleport(player, action);
            } else if (upper.startsWith("SCAN_BLOCKS:")) {
                if (!plugin.getConfig().getBoolean("ai.allow_block_scanning", true)) {
                    adapter.sendMessage(player, "&7&o[Vision scanning is disabled by this server]");
                    return false;
                }
                handleScanBlocks(plugin, player, action, tier, playerKey, originalQuestion);
            } else if (upper.startsWith("SCHEDULE_TASK:")) {
                handleScheduleTask(plugin, player, action, playerKey);
            } else if (upper.startsWith("DELAYED_ACTION:")) {
                handleDelayedAction(plugin, player, action, playerKey);
            } else if (upper.startsWith("CHAT:")) {
                handleChatCommand(plugin, player, action, playerKey);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Neural] Parameterized action failed: " + e.getMessage());
            if (auditLog != null) auditLog.log(player.getName(), playerKey, plugin.getServerId(), action, false, "exception: " + e.getMessage());
            return false;
        }
        return true;
    }

    private void handleGive(Player player, String action, CraftyAI plugin) {
        String[] parts = action.split(":", 3);
        if (parts.length < 2) return;
        String materialName = parts[1].toUpperCase().trim();
        Material material = Material.matchMaterial(materialName);
        if (material == null) return;
        if (giveBlacklist.contains(material)) {
            adapter.sendMessage(player, "&cCannot give blacklisted item: " + material.name().toLowerCase().replace("_", " ") + ".");
            return;
        }
        int amount = 1;
        if (parts.length >= 3) {
            try { amount = Integer.parseInt(parts[2].trim()); } catch (NumberFormatException ignored) {}
        }
        int maxGiveAmount = plugin.getConfig().getInt("ai.max_give_amount", 64);
        int finalAmount = Math.max(1, Math.min(maxGiveAmount, amount));
        adapter.runEntitySync(player, () -> {
            player.getInventory().addItem(new ItemStack(material, finalAmount));
            adapter.sendMessage(player, "&aReceived " + finalAmount + "x " + material.name().toLowerCase().replace("_", " ") + ".");
        });
    }

    private void handleEffect(Player player, String action) {
        String[] parts = action.split(":", 3);
        if (parts.length < 2) return;
        String effectName = parts[1].toUpperCase().trim();
        PotionEffectType type = PotionEffectType.getByName(effectName);
        if (type == null) return;
        int duration = 60 * 20;
        int amplifier = 0;
        if (parts.length >= 3) {
            String[] effectParts = parts[2].trim().split(":", 2);
            try { duration = Integer.parseInt(effectParts[0].trim()) * 20; } catch (NumberFormatException ignored) {}
            if (effectParts.length >= 2) {
                try { amplifier = Integer.parseInt(effectParts[1].trim()) - 1; } catch (NumberFormatException ignored) {}
            }
        }
        int finalDuration = Math.max(20, Math.min(12000, duration));
        int finalAmplifier = Math.max(0, Math.min(4, amplifier));
        adapter.runEntitySync(player, () -> {
            player.addPotionEffect(new PotionEffect(type, finalDuration, finalAmplifier));
            adapter.sendMessage(player, "&bApplied effect: " + type.getName().toLowerCase() + " level " + (finalAmplifier + 1));
        });
    }

    private void handleEnchant(Player player, String action) {
        String[] parts = action.split(":", 3);
        if (parts.length < 2) return;
        String enchantName = parts[1].toUpperCase().trim();
        Enchantment enchant = Enchantment.getByName(enchantName);
        if (enchant == null) return;
        int level = 1;
        if (parts.length >= 3) {
            try { level = Integer.parseInt(parts[2].trim()); } catch (NumberFormatException ignored) {}
        }
        int finalLevel = Math.max(1, Math.min(enchant.getMaxLevel(), level));
        adapter.runEntitySync(player, () -> {
            ItemStack item = getHeldItem(player);
            if (item != null && item.getType() != Material.AIR) {
                try { item.addEnchantment(enchant, finalLevel); }
                catch (IllegalArgumentException e) { item.addUnsafeEnchantment(enchant, finalLevel); }
                adapter.sendMessage(player, "&dEnchanted held item with " + enchant.getName().toLowerCase());
            }
        });
    }

    private void handleTeleport(Player player, String action) {
        String[] parts = action.split(":", 4);
        if (parts.length < 4) return;
        try {
            double x = Double.parseDouble(parts[1].trim());
            double y = Double.parseDouble(parts[2].trim());
            double z = Double.parseDouble(parts[3].trim());
            if (Math.abs(x) < 30000000 && Math.abs(z) < 30000000 && y > -64 && y < 320) {
                adapter.runEntitySync(player, () -> {
                    player.teleport(new Location(player.getWorld(), x, y, z));
                    adapter.sendMessage(player, "&dTeleported to AI-specified location.");
                });
            }
        } catch (NumberFormatException ignored) {}
    }

    private void handleScanBlocks(CraftyAI plugin, Player player, String action, String tier, String playerKey, String originalQuestion) {
        String[] parts = action.split(":", 5);
        int radius = 8;
        boolean includePlayers = false;
        boolean includeEntities = true;
        boolean includeBlocks = true;
        try {
            if (parts.length >= 2) radius = Math.max(2, Math.min(32, Integer.parseInt(parts[1].trim())));
            if (parts.length >= 3) includePlayers = !"0".equals(parts[2].trim());
            if (parts.length >= 4) includeEntities = !"0".equals(parts[3].trim());
            if (parts.length >= 5) includeBlocks = !"0".equals(parts[4].trim());
        } catch (NumberFormatException ignored) {}

        int maxRadius = 8;
        if (tier != null) {
            if (tier.equalsIgnoreCase("pro")) maxRadius = 16;
            else if (tier.equalsIgnoreCase("enterprise")) maxRadius = 32;
        }
        radius = Math.min(radius, maxRadius);

        long now = System.currentTimeMillis();
        if (lastBlockScanTime.size() > MAX_BLOCK_SCAN_TRACKED) {
            cleanOldBlockScanEntries(now);
        }
        final long[] scanResult = {0};
        lastBlockScanTime.compute(playerKey, (key, lastScan) -> {
            if (lastScan == null || (now - lastScan) >= 30000L) {
                scanResult[0] = 0;
                return now;
            }
            scanResult[0] = lastScan;
            return lastScan;
        });
        if (scanResult[0] != 0 && now - scanResult[0] < 30000L) {
            adapter.sendMessage(player, "&c&o[Block scan rate limit: wait " + ((30000L - (now - scanResult[0])) / 1000) + "s]");
            return;
        }
        final int scanRadius = Math.min(radius, 16);
        final boolean incP = includePlayers;
        final boolean incE = includeEntities;
        final boolean incB = includeBlocks;

        adapter.sendActionBar(player, "&b&l" + plugin.getAiName().toUpperCase() + " IS SCANNING...");
        adapter.runEntitySync(player, () -> {
            String aiName = plugin.getConfig().getString("ai.name", "Crafty");
            java.util.LinkedHashMap<String, Integer> blockCounts = new java.util.LinkedHashMap<>();
            java.util.LinkedHashMap<String, Integer> mobTypes = new java.util.LinkedHashMap<>();
            java.util.List<String> playerNames = new java.util.ArrayList<>();
            int blockCount = 0;
            int mobCount = 0;
            int playerCount = 0;
            int bx = player.getLocation().getBlockX();
            int by = player.getLocation().getBlockY();
            int bz = player.getLocation().getBlockZ();
            org.bukkit.World world = player.getWorld();
            if (incB) {
                int step = scanRadius > 8 ? 3 : 2;
                for (int dx = -scanRadius; dx <= scanRadius; dx += step) {
                    for (int dy = -scanRadius; dy <= scanRadius; dy += step) {
                        for (int dz = -scanRadius; dz <= scanRadius; dz += step) {
                            int chunkX = (bx + dx) >> 4;
                            int chunkZ = (bz + dz) >> 4;
                            if (!world.isChunkLoaded(chunkX, chunkZ)) continue;
                            String name = world.getBlockAt(bx + dx, by + dy, bz + dz).getType().name().toLowerCase().replace("_", " ");
                            blockCounts.merge(name, 1, Integer::sum);
                            blockCount++;
                        }
                    }
                }
            }
            if (incE) {
                for (org.bukkit.entity.Entity e : player.getNearbyEntities(scanRadius, scanRadius, scanRadius)) {
                    if (e instanceof Player) {
                        String name = e.getName();
                        if (!playerNames.contains(name)) playerNames.add(name);
                        playerCount++;
                    } else if (e instanceof org.bukkit.entity.LivingEntity) {
                        String typeName = e.getType().name().toLowerCase().replace("_", " ");
                        mobTypes.merge(typeName, 1, Integer::sum);
                        mobCount++;
                    }
                }
            }
            plugin.getLogger().info("[CraftyAI] SCAN_BLOCKS r=" + scanRadius + " \u2014 " + blockCount + " blocks, " + mobCount + " mobs, " + playerCount + " players");

            StringBuilder scanCtx = new StringBuilder();
            scanCtx.append("[Block Scan Results]\n");
            scanCtx.append("Player position: ").append(bx).append(", ").append(by).append(", ").append(bz).append("\n");
            int solidAbove = 0;
            for (int dy = 1; dy <= 5; dy++) {
                if (world.getBlockAt(bx, by + dy, bz).getType() != org.bukkit.Material.AIR) solidAbove++;
            }
            scanCtx.append("Underground: ").append(solidAbove >= 3 ? "yes" : "no").append("\n");
            scanCtx.append("Radius: ").append(scanRadius).append(" blocks\n");
            if (incB && !blockCounts.isEmpty()) {
                scanCtx.append("Blocks: ");
                int n = 0;
                for (Map.Entry<String, Integer> e : blockCounts.entrySet()) {
                    if (n++ >= 12) { scanCtx.append("+").append(blockCounts.size() - 12).append(" more"); break; }
                    if (n > 1) scanCtx.append(", ");
                    scanCtx.append(e.getKey()).append(" x").append(e.getValue());
                }
                scanCtx.append("\n");
            }
            if (incE && !mobTypes.isEmpty()) {
                scanCtx.append("Entities: ");
                int n = 0;
                for (Map.Entry<String, Integer> e : mobTypes.entrySet()) {
                    if (n++ > 0) scanCtx.append(", ");
                    scanCtx.append(e.getKey()).append(" x").append(e.getValue());
                }
                scanCtx.append("\n");
            }
            if (!playerNames.isEmpty()) {
                scanCtx.append("Players nearby: ").append(String.join(", ", playerNames)).append("\n");
            }
            scanCtx.append(com.demonz.craftyai.common.ScanFlow.privacyRules());

            CraftyEngine engine = plugin.getEngine();
            if (engine == null) {
                adapter.sendMessage(player, "&b&l[" + aiName + "] &7> &fI tried to scan but my neural link is offline.");
                return;
            }
            final String scanContext = scanCtx.toString();
            engine.ask(player,
                    com.demonz.craftyai.common.ScanFlow.followUpPrompt(originalQuestion), scanContext, null,
                    new CraftyEngine.Callback() {
                        @Override public void onSuccess(String response) {
                            adapter.runEntitySync(player, () -> {
                                if (!player.isOnline()) return;
                                String answer = engine.parseAnswer(response);
                                if (answer == null || answer.isEmpty()) answer = response;
                                adapter.sendMessage(player, "&b&l[" + aiName + "] &7> &f" + answer);
                                adapter.playSound(player, plugin.getConfig().getString("chat.sounds.success", "ENTITY_EXPERIENCE_ORB_PICKUP"), 1.0f, 1.2f);
                            });
                        }
                        @Override public void onFailure(String error) {
                            plugin.getLogger().warning("[CraftyAI] Scan follow-up failed: " + error);

                            adapter.runEntitySync(player, () -> {
                                if (!player.isOnline()) return;
                                adapter.sendMessage(player, "&b&l[" + aiName + "] &7> &fI scanned the area, but my thoughts got scrambled on the way back. Try again in a moment.");
                            });
                        }
                    });
        });
    }

    private void handleScheduleTask(CraftyAI plugin, Player player, String action, String playerKey) {

        String[] parts = action.split(":", 4);
        if (parts.length < 4) {
            adapter.sendMessage(player, "&c[CraftyAI] Invalid schedule format.");
            return;
        }
        String cronExpr = parts[1].trim();
        String actionType = parts[2].trim().toLowerCase();
        String message = parts[3].trim();
        if (!"chat".equals(actionType)) {
            adapter.sendMessage(player, "&c[CraftyAI] Only 'chat' type tasks are supported (e.g. reminders, broadcasts).");
            return;
        }
        String name = message.length() > 40 ? message.substring(0, 40) + "..." : message;
        adapter.sendMessage(player, "&e[CraftyAI] Scheduling task...");

        final String fCron = cronExpr, fType = actionType, fMsg = message, fName = name;
        final Player fPlayer = player;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String targetUrl = com.demonz.craftyai.common.GatewayRequestHeaders.getGatewayUrl();
                java.net.URL url = new java.net.URL(targetUrl + "/v1/schedule-task");
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("X-Client-Type", "minecraft-spigot");
                conn.setRequestProperty("X-CraftyAI-Version", plugin.getDescription().getVersion());
                String apiKey = plugin.getConfig().getString("server.secret", "");
                if (apiKey != null && !apiKey.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setDoOutput(true); conn.setConnectTimeout(8000); conn.setReadTimeout(8000);
                String body = "{\"name\":\"" + escapeJson(fName) + "\",\"cron_expr\":\"" + escapeJson(fCron) + "\",\"action_type\":\"" + fType + "\",\"action_payload\":{\"message\":\"" + escapeJson(fMsg) + "\"}}";
                try (java.io.OutputStream os = conn.getOutputStream()) { os.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
                int code = conn.getResponseCode();
                String respBody;
                try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(code >= 400 ? conn.getErrorStream() : conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder(); String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    respBody = sb.toString();
                } finally { conn.disconnect(); }
                final String response = respBody;
                final int httpCode = code;
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (httpCode == 200 && response.contains("\"success\":true")) {
                        adapter.sendMessage(fPlayer, "&a[CraftyAI] Task scheduled: &f" + fName + " &a(every " + fCron + ")");
                    } else {
                        String err = "\"error\":\"";
                        int ei = response.indexOf(err);
                        String errStr = ei >= 0 ? response.substring(ei + err.length(), response.indexOf("\"", ei + err.length())) : "HTTP " + httpCode;
                        adapter.sendMessage(fPlayer, "&c[CraftyAI] Schedule failed: " + errStr);
                    }
                });
            } catch (Exception e) {
                plugin.getServer().getScheduler().runTask(plugin, () ->
                    adapter.sendMessage(fPlayer, "&c[CraftyAI] Schedule error: " + e.getMessage()));
            }
        });
    }

    private void handleDelayedAction(CraftyAI plugin, Player player, String action, String playerKey) {
        String[] dParts = action.split(":", 3);
        if (dParts.length < 3) {
            adapter.sendMessage(player, "&c[CraftyAI] Format: DELAYED_ACTION:<seconds>:<action>");
            return;
        }
        int delaySec = 0;
        try { delaySec = Math.max(1, Math.min(300, Integer.parseInt(dParts[1].trim()))); } catch (NumberFormatException ignored) {}
        String innerAction = dParts[2].trim();
        if (delaySec <= 0 || innerAction.isEmpty()) {
            adapter.sendMessage(player, "&c[CraftyAI] Invalid delay or action");
            return;
        }
        adapter.sendMessage(player, "&e[CraftyAI] Will execute in " + delaySec + "s: &f" + innerAction);
        final String fInner = innerAction;
        final Player fPlayer = player;
        final CraftyAI fPlugin = plugin;
        final long fDelay = delaySec * 1000L;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try { Thread.sleep(fDelay); } catch (InterruptedException ie) {
                LOGGER.warning("[CraftyAI] Delayed action interrupted for " + fPlayer.getName() + ": " + ie.getMessage());
                Thread.currentThread().interrupt();
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                adapter.sendMessage(fPlayer, "&a[CraftyAI] Executing delayed action: &f" + fInner);
                handleAction(fPlugin, fPlayer, fInner, fPlugin.getTier());
            });
        });
    }

    private void handleChatCommand(CraftyAI plugin, Player player, String action, String playerKey) {
        String cmd = action.substring("CHAT:".length()).trim();
        if (cmd.isEmpty()) return;
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        cmd = normalizeLocateCommand(cmd);
        if (!AgenticActions.isAllowedChatCommand(cmd)) {
            adapter.sendMessage(player, "&c[CraftyAI] Blocked unsafe AI command. Only /locate structure and /locate biome are allowed.");
            if (auditLog != null) auditLog.log(player.getName(), playerKey, plugin.getServerId(), action, false, "command not allowlisted");
            return;
        }

        String lowerCmd = cmd.toLowerCase();

        if (lowerCmd.startsWith("locate structure ")) {
            final String locateCmd = cmd;
            adapter.runSync(() -> {
                try {
                    if (!dispatchNativeCommand(player, locateCmd)) {
                        player.performCommand(prefixVanillaCommand(locateCmd));
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("[CraftyAI] /locate failed: " + e.getMessage());
                }
            });
            return;
        }

        final String finalCmd = cmd;
        adapter.runSync(() -> {
            try {
                if (!dispatchNativeCommand(player, finalCmd)) {
                    player.performCommand(prefixVanillaCommand(finalCmd));
                }
                adapter.sendMessage(player, "&7[CraftyAI] Ran: /" + finalCmd);
            } catch (Exception e) {
                plugin.getLogger().warning("[CraftyAI] CHAT command failed: " + finalCmd + " \u2014 " + e.getMessage());
                adapter.sendMessage(player, "&c[CraftyAI] Command failed: " + e.getMessage());
            }
        });
    }

    private Location locateStructureViaAPI(Player player, String structureTypeName) {
        try {
            Class<?> structureTypeClass = Class.forName("org.bukkit.generator.structure.StructureType");
            Object structureType = structureTypeClass.getMethod("valueOf", String.class).invoke(null, structureTypeName);
            if (structureType == null) return null;
            for (java.lang.reflect.Method m : player.getWorld().getClass().getMethods()) {
                if (m.getName().equals("locateNearest") && m.getParameterCount() == 3) {
                    return (Location) m.invoke(player.getWorld(), player.getLocation(), structureType, 50000);
                }
            }
        } catch (Exception e) {
            LOGGER.fine("[CraftyAI] locateStructureViaAPI: " + e.getMessage());
        }
        return null;
    }

    private boolean dispatchNativeCommand(Player player, String cmd) {
        try {
            Object craftServer = Bukkit.getServer();
            Object nmsServer = craftServer.getClass().getMethod("getServer").invoke(craftServer);
            if (nmsServer == null) return false;

            Object craftPlayer = player.getClass().getMethod("getHandle").invoke(player);
            Object source;
            try {
                source = nmsServer.getClass().getMethod("createCommandSourceStack").invoke(nmsServer);
            } catch (Exception e) {
                source = craftPlayer.getClass().getMethod("createCommandSourceStack").invoke(craftPlayer);
            }
            if (source == null) return false;

            Object commands = nmsServer.getClass().getMethod("getCommands").invoke(nmsServer);
            if (commands == null) return false;

            java.lang.reflect.Method target = null;
            for (java.lang.reflect.Method m : commands.getClass().getMethods()) {
                if (m.getName().equals("performPrefixedCommand") && m.getParameterCount() == 2) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                LOGGER.warning("[CraftyAI] performPrefixedCommand not found on " + commands.getClass().getName());
                return false;
            }

            target.invoke(commands, source, "/" + cmd);
            return true;
        } catch (Exception e) {
            LOGGER.warning("[CraftyAI] NMS dispatch failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    private static String normalizeLocateCommand(String cmd) {
        String lower = cmd.toLowerCase();
        if (!lower.startsWith("locate structure ") && !lower.startsWith("locate biome ") && !lower.startsWith("locate poi ")) return cmd;
        int prefixLen = lower.startsWith("locate structure ") ? "locate structure ".length()
                       : lower.startsWith("locate biome ") ? "locate biome ".length()
                       : "locate poi ".length();
        String rest = cmd.substring(prefixLen).trim();
        String prefix = cmd.substring(0, prefixLen);
        String restLower = rest.toLowerCase();

        if (restLower.startsWith("minecraft:")) return cmd;

        if (rest.startsWith("#")) return cmd;

        if (restLower.startsWith("minecraft") && restLower.length() > "minecraft".length()) {
            rest = "minecraft:" + rest.substring("minecraft".length());
        }

        else {
            rest = "minecraft:" + rest;
        }
        return prefix + rest;
    }

    private static final Set<String> VANILLA_COMMANDS = new HashSet<>(Arrays.asList(
        "kill", "tp", "teleport", "give", "effect", "enchant", "gamemode",
        "time", "weather", "seed", "difficulty", "whitelist", "ban", "pardon",
        "kick", "ban-ip", "pardon-ip", "op", "deop", "save-all", "save-off",
        "save-on", "list", "say", "stop", "locate", "spreadplayers",
        "summon", "data", "execute", "function", "particle", "playsound",
        "title", "worldborder", "difficulty", "xp", "experience"
    ));

    private static String prefixVanillaCommand(String cmd) {
        String firstWord = cmd.contains(" ") ? cmd.substring(0, cmd.indexOf(' ')) : cmd;
        if (VANILLA_COMMANDS.contains(firstWord.toLowerCase())) {
            return "minecraft:" + cmd;
        }
        return cmd;
    }

    private ItemStack getHeldItem(Player player) {
        try {
            return (ItemStack) player.getInventory().getClass()
                    .getMethod("getItemInMainHand").invoke(player.getInventory());
        } catch (Exception e) {
            try {
                return (ItemStack) player.getInventory().getClass()
                        .getMethod("getItemInHand").invoke(player.getInventory());
            } catch (Exception ex) { return null; }
        }
    }

    private void cleanOldBlockScanEntries(long now) {
        lastBlockScanTime.entrySet().removeIf(e -> now - e.getValue() > BLOCK_SCAN_TTL_MS);
    }

    private static Set<Material> buildBlacklist() {
        Set<Material> set = new HashSet<Material>();
        for (String name : Arrays.asList("BARRIER","COMMAND_BLOCK","COMMAND_BLOCK_MINECART",
                "REPEATING_COMMAND_BLOCK","CHAIN_COMMAND_BLOCK","STRUCTURE_BLOCK",
                "STRUCTURE_VOID","BEDROCK","END_PORTAL_FRAME","SPAWNER")) {
            try {
                Material m = Material.matchMaterial(name);
                if (m != null) set.add(m);
            } catch (NoSuchFieldError ignored) {}
        }
        return Collections.unmodifiableSet(set);
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"':  sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }
}
