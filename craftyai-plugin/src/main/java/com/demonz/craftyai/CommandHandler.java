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

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Handles all /crafty subcommands. Extracted from CraftyAI.java.
 */
public class CommandHandler {

    private final CraftyAI plugin;

    public CommandHandler(CraftyAI plugin) {
        this.plugin = plugin;
    }

    public boolean handleCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            String aiName = plugin.getAiName();
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "&b&l" + aiName + " &7v" + plugin.getDescription().getVersion() + " &8- &7DemonZ Development"));
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "&7Platform: &f" + plugin.getPlatform().getSoftware().name() + " &8" + plugin.getPlatform().getVersionString()));
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                    "&7Usage: &f/" + label + " <reload|status|mode|apikey|learn|ask|scan|pro|prompt>"));
            return true;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("help")) return handleHelp(sender);
        if (sub.equals("reload")) return handleReload(sender);
        if (sub.equals("confirm")) return handleConfirm(sender);
        if (sub.equals("memory")) return handleMemory(sender);
        if (sub.equals("forget")) return handleForget(sender, args);
        if (sub.equals("privacy")) return handlePrivacy(sender, args);
        if (sub.equals("status")) return handleStatus(sender);
        if (sub.equals("mode")) return handleMode(sender, args);
        if (sub.equals("pro")) return handlePro(sender, args);
        if (sub.equals("prompt")) return handlePrompt(sender, args);
        if (sub.equals("api") || sub.equals("apikey") || sub.equals("key") || sub.equals("setup") || sub.equals("mint") || sub.equals("automint")) return handleApiKey(sender, args);
        if (sub.equals("learn")) return handleLearn(sender, args);
        if (sub.equals("ask")) return handleAsk(sender, args);
        if (sub.equals("scan")) return handleScan(sender);
        if (sub.equals("link")) return handleLink(sender);

        sender.sendMessage(ChatColor.GRAY + "Unknown subcommand. Use " + ChatColor.WHITE + "/crafty help" + ChatColor.GRAY + " for a list of commands.");
        return true;
    }

    // --- Subcommand handlers ---

    private boolean handleHelp(CommandSender sender) {
        String name = plugin.getAiName();
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&b&l============ &fCraftyAI &7v" + plugin.getDescription().getVersion() + " &b&l============"));
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e/crafty help       &8- &7Show this help menu"));
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e/crafty status     &8- &7Show plugin status and tier info"));
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e/crafty ask <msg>  &8- &7Ask " + name + " privately"));
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e/crafty scan       &8- &7Scan nearby blocks and entities"));
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e/crafty mode       &8- &7Toggle response visibility"));
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e/crafty memory     &8- &7View your recent memories"));
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e/crafty forget [n] &8- &7Forget last n memories"));
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e/crafty privacy    &8- &7Toggle private/public responses"));
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e/crafty confirm    &8- &7Confirm a destructive action"));
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e/crafty pro        &8- &7Pro subscription management"));
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e/crafty prompt     &8- &7Custom AI prompt (Pro)"));
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e/crafty link       &8- &7Link server to Discord"));
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e/crafty apikey     &8- &7Generate or set API key"));
        if (sender.hasPermission("crafty.admin")) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&c/crafty reload     &8- &7Reload configuration"));
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&c/crafty learn      &8- &7Teach " + name + " something"));
        }
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&b&l==========================================="));
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("crafty.admin")) { sender.sendMessage(ChatColor.RED + "No permission."); return true; }
        plugin.reloadConfig();
        try {
            com.demonz.craftyai.ConfigMigrator migrator = new com.demonz.craftyai.ConfigMigrator(plugin.getDataFolder(), plugin.getLogger());
            if (migrator.migrate(plugin.getConfig())) { plugin.saveConfig(); sender.sendMessage(ChatColor.AQUA + "[" + plugin.getAiName() + "] Config migrated to v" + ConfigMigrator.CURRENT_VERSION + " schema."); }
        } catch (Throwable t) {
            plugin.getLogger().warning("[CraftyAI] ConfigMigrator failed during /crafty reload (non-fatal): " + t.getMessage());
        }
        if (plugin.getChatHandler() != null) plugin.getChatHandler().reload();
        plugin.reloadEngine();
        if (plugin.getVisionListener() != null) { plugin.getVisionListener().setEngine(plugin.getEngine()); plugin.getVisionListener().reload(); }
        sender.sendMessage(ChatColor.GREEN + "[" + plugin.getAiName() + "] Configuration reloaded.");
        return true;
    }

    private boolean handleConfirm(CommandSender sender) {
        if (!(sender instanceof Player)) { sender.sendMessage(ChatColor.RED + "Players only."); return true; }
        Player player = (Player) sender;
        String key = player.getUniqueId().toString();
        if (plugin.getActionHandler() == null) { sender.sendMessage(ChatColor.RED + "Confirmation system not initialized."); return true; }
        String pending = plugin.getActionHandler().confirmPlayer(key);
        if (pending == null) {
            sender.sendMessage(ChatColor.GRAY + "[" + plugin.getAiName() + "] No pending destructive action to confirm.");
            return true;
        }
        sender.sendMessage(ChatColor.YELLOW + "[" + plugin.getAiName() + "] Executing pending action: " + pending);
        plugin.getActionHandler().handleAction(plugin, player, pending, plugin.getTier(), true);
        return true;
    }

    private boolean handleMemory(CommandSender sender) {
        if (!sender.hasPermission("crafty.use")) { sender.sendMessage(ChatColor.RED + "No permission."); return true; }
        if (plugin.getEngine() == null) { sender.sendMessage(ChatColor.RED + "Engine not initialized."); return true; }
        sender.sendMessage(ChatColor.AQUA + "[" + plugin.getAiName() + "] Fetching your recent memories...");
        plugin.getEngine().listMemoriesAsync(sender.getName(), 10, new CraftyEngine.Callback() {
            public void onSuccess(String response) { sender.sendMessage(ChatColor.AQUA + "[" + plugin.getAiName() + "] " + response); }
            public void onFailure(String error) { sender.sendMessage(ChatColor.RED + "[" + plugin.getAiName() + "] Failed: " + error); }
        });
        return true;
    }

    private boolean handleForget(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage(ChatColor.RED + "Players only."); return true; }
        if (!sender.hasPermission("crafty.use")) { sender.sendMessage(ChatColor.RED + "No permission."); return true; }
        Player p = (Player) sender;
        int n = 1;
        if (args.length >= 2) { try { n = Math.max(1, Math.min(50, Integer.parseInt(args[1]))); } catch (NumberFormatException ignored) {} }
        if (plugin.getEngine() == null) { sender.sendMessage(ChatColor.RED + "Engine not initialized."); return true; }
        sender.sendMessage(ChatColor.AQUA + "[" + plugin.getAiName() + "] Forgetting last " + n + " memories for " + p.getName() + "...");
        plugin.getEngine().forgetMemoriesAsync(p.getName(), n, new CraftyEngine.Callback() {
            public void onSuccess(String response) { sender.sendMessage(ChatColor.GREEN + "[" + plugin.getAiName() + "] " + response); }
            public void onFailure(String error) { sender.sendMessage(ChatColor.RED + "[" + plugin.getAiName() + "] Failed: " + error); }
        });
        return true;
    }

    private boolean handlePrivacy(CommandSender sender, String[] args) {
        if (!sender.hasPermission("crafty.admin")) { sender.sendMessage(ChatColor.RED + "Admin only."); return true; }
        if (args.length < 2) {
            boolean current = plugin.getConfig().getBoolean("ai.privacy_opt_out", false);
            sender.sendMessage(ChatColor.AQUA + "[" + plugin.getAiName() + "] Server memory: " + (current ? ChatColor.RED + "DISABLED" : ChatColor.GREEN + "ENABLED"));
            sender.sendMessage(ChatColor.GRAY + "Toggle: /crafty privacy <on|off>");
            return true;
        }
        boolean off = args[1].equalsIgnoreCase("off");
        plugin.getConfig().set("ai.privacy_opt_out", off);
        plugin.saveConfig();
        sender.sendMessage(ChatColor.GREEN + "[" + plugin.getAiName() + "] Memory " + (off ? "disabled" : "enabled") + " for this server.");
        return true;
    }

    private boolean handleStatus(CommandSender sender) {
        String aiName = plugin.getAiName();
        sender.sendMessage(ChatColor.AQUA + "[" + aiName + "] Status:");
        sender.sendMessage(ChatColor.GRAY + "  AI Name: " + ChatColor.WHITE + aiName);
        boolean forcedLocal = plugin.getConfig().getBoolean("ai.force-local-mode", false);
        sender.sendMessage(ChatColor.GRAY + "  AI Mode: " + (forcedLocal ? ChatColor.YELLOW + "FORCED-LOCAL" : ChatColor.AQUA + "CLOUD-NEURAL"));
        sender.sendMessage(ChatColor.GRAY + "  Version: " + ChatColor.WHITE + plugin.getDescription().getVersion());
        sender.sendMessage(ChatColor.GRAY + "  Connection: " + (plugin.isCustomProviderEnabled() ? ChatColor.LIGHT_PURPLE + "Custom Provider" : ChatColor.AQUA + "CraftyAI Cloud"));

        if (sender.hasPermission("crafty.admin")) {
            ChatHandler ch = plugin.getChatHandler();
            sender.sendMessage(ChatColor.GRAY + "  Platform: " + ChatColor.WHITE + plugin.getPlatform().getSoftware().name());
            sender.sendMessage(ChatColor.GRAY + "  Server Version: " + ChatColor.WHITE + plugin.getPlatform().getVersionString());
            if (ch != null) {
                StringBuilder aliasStr = new StringBuilder();
                List<String> aliases = ch.getAliases();
                for (int i = 0; i < aliases.size(); i++) {
                    if (i > 0) aliasStr.append(", ");
                    aliasStr.append(aliases.get(i));
                }
                sender.sendMessage(ChatColor.GRAY + "  Aliases: " + ChatColor.WHITE + aliasStr.toString());
                sender.sendMessage(ChatColor.GRAY + "  Fuzzy Match: " + ChatColor.WHITE + ch.isFuzzyMatch());
            }
            sender.sendMessage(ChatColor.GRAY + "  Adventure API: " + ChatColor.WHITE + plugin.getPlatform().hasAdventureAPI());
            sender.sendMessage(ChatColor.GRAY + "  Folia: " + ChatColor.WHITE + plugin.getPlatform().hasFoliaScheduler());
            String sid = plugin.getServerId();
            sender.sendMessage(ChatColor.GRAY + "  Session ID: " + ChatColor.AQUA + sid);
            if (plugin.isCustomProviderEnabled()) {
                sender.sendMessage(ChatColor.GRAY + "  Custom Provider: " + ChatColor.LIGHT_PURPLE + "enabled");
                String customModel = plugin.getConfig().getString("server.custom_provider.model", "");
                if (customModel != null && !customModel.isEmpty()) {
                    sender.sendMessage(ChatColor.GRAY + "  Custom Model: " + ChatColor.WHITE + customModel);
                }
            } else {
                String serverSecret = plugin.getConfig().getString("server.secret", "");
                sender.sendMessage(ChatColor.GRAY + "  API Key: " + ChatColor.WHITE + (serverSecret != null && serverSecret.startsWith("cai_") ? "configured" : "not configured"));
                if (!forcedLocal) {
                    String tierStr = plugin.getTier();
                    ChatColor tierColor = tierStr.equalsIgnoreCase("pro") ? ChatColor.GOLD
                            : tierStr.equalsIgnoreCase("enterprise") ? ChatColor.LIGHT_PURPLE : ChatColor.WHITE;
                    sender.sendMessage(ChatColor.GRAY + "  Subscription: " + tierColor + tierStr.toUpperCase());
                }
            }
        }
        return true;
    }

    private boolean handleMode(CommandSender sender, String[] args) {
        if (!sender.hasPermission("crafty.admin")) { sender.sendMessage(ChatColor.RED + "No permission."); return true; }
        boolean current = plugin.getConfig().getBoolean("ai.force-local-mode", false);
        boolean next = !current;
        if (args.length > 1) {
            if (args[1].equalsIgnoreCase("local") || args[1].equalsIgnoreCase("offline")) next = true;
            else if (args[1].equalsIgnoreCase("cloud") || args[1].equalsIgnoreCase("online")) next = false;
        }
        plugin.getConfig().set("ai.force-local-mode", next);
        plugin.saveConfig();
        sender.sendMessage(ChatColor.GREEN + "[" + plugin.getAiName() + "] AI Mode set to " + (next ? ChatColor.YELLOW + "FORCED-LOCAL" : ChatColor.AQUA + "CLOUD-NEURAL"));
        return true;
    }

    private boolean handlePro(CommandSender sender, String[] args) {
        String aiName = plugin.getAiName();
        if (args.length < 2) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&b&l" + aiName + " &7v" + plugin.getDescription().getVersion()));
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&7Usage: &f/crafty pro <status|help|redeem>"));
            sender.sendMessage(ChatColor.GRAY + "  &fstatus  &8- &7Show current Pro tier + expiry");
            sender.sendMessage(ChatColor.GRAY + "  &fhelp    &8- &7How to get a Pro key (Discord ticket)");
            sender.sendMessage(ChatColor.GRAY + "  &fredeem  &8- &7<key> Redeem a Pro key from Discord DM");
            return true;
        }
        String proSub = args[1].toLowerCase();
        if (proSub.equals("status")) {
            String sid = plugin.getServerId();
            String tierStr = plugin.getTier();
            ChatColor tierColor = tierStr.equalsIgnoreCase("pro") ? ChatColor.GOLD
                    : tierStr.equalsIgnoreCase("enterprise") ? ChatColor.LIGHT_PURPLE : ChatColor.WHITE;
            sender.sendMessage(ChatColor.AQUA + "[" + aiName + "] Pro Status:");
            sender.sendMessage(ChatColor.GRAY + "  Tier: " + tierColor + tierStr.toUpperCase());
            sender.sendMessage(ChatColor.GRAY + "  Session ID: " + ChatColor.AQUA + sid);
            if (tierStr.equalsIgnoreCase("free")) {
                sender.sendMessage(ChatColor.GRAY + "  To upgrade: open a Pro ticket on Discord");
                sender.sendMessage(ChatColor.GRAY + "  " + ChatColor.AQUA + "https://discord.gg/zCkE44hsBR");
            } else {
                sender.sendMessage(ChatColor.GRAY + "  Agentic tasks: " + ChatColor.GREEN + "unlocked");
                sender.sendMessage(ChatColor.GRAY + "  Vision scanning: " + ChatColor.GREEN + "unlocked");
                sender.sendMessage(ChatColor.GRAY + "  Block scanning: " + ChatColor.GREEN + "unlocked");
                sender.sendMessage(ChatColor.GRAY + "  Scheduled tasks: " + ChatColor.GREEN + "unlocked");
            }
            return true;
        }
        if (proSub.equals("help")) {
            sender.sendMessage(ChatColor.AQUA + "[" + aiName + "] How to get Pro:");
            sender.sendMessage(ChatColor.GRAY + "  1. Join our Discord: " + ChatColor.AQUA + "https://discord.gg/zCkE44hsBR");
            sender.sendMessage(ChatColor.GRAY + "  2. Open a ticket in #pro-requests");
            sender.sendMessage(ChatColor.GRAY + "  3. An admin will review and DM you a Pro key");
            sender.sendMessage(ChatColor.GRAY + "  4. Run: " + ChatColor.WHITE + "/crafty pro redeem <key>");
            return true;
        }
        if (proSub.equals("redeem") || proSub.equals("use")) {
            return handleProRedeem(sender, args);
        }
        sender.sendMessage(ChatColor.RED + "Unknown subcommand. Use: /crafty pro <status|help|redeem>");
        return true;
    }

    private boolean handleProRedeem(CommandSender sender, String[] args) {
        String aiName = plugin.getAiName();
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /crafty pro redeem <key>");
            sender.sendMessage(ChatColor.GRAY + "Get a Pro key from our Discord: " + ChatColor.AQUA + "https://discord.gg/zCkE44hsBR");
            return true;
        }
        if (!sender.hasPermission("crafty.admin")) { sender.sendMessage(ChatColor.RED + "No permission (requires crafty.admin)."); return true; }
        String proKey = args[2].trim();
        String lowerKey = proKey.toLowerCase();
        if (!lowerKey.startsWith("cai_pro_") && !lowerKey.startsWith("cai_pro-")) { sender.sendMessage(ChatColor.RED + "Invalid Pro key format. Must start with 'cai_pro_' or 'cai_pro-'."); return true; }
        final String sidForRedeem = plugin.getServerId();
        final String version = plugin.getDescription().getVersion();
        sender.sendMessage(ChatColor.AQUA + "[" + aiName + "] Redeeming Pro key...");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String targetUrl = com.demonz.craftyai.common.GatewayRequestHeaders.getGatewayUrl();
                URL url = new URL(targetUrl + "/v1/pro/redeem");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("X-Client-Type", "minecraft-spigot");
                conn.setRequestProperty("X-CraftyAI-Version", plugin.getDescription().getVersion());
                conn.setDoOutput(true);
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                String body = "{\"key\":\"" + escapeJson(proKey) + "\",\"server_id\":\"" + escapeJson(sidForRedeem) + "\"}";
                try (OutputStream os = conn.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
                int code = conn.getResponseCode();
                String respBody;
                try (BufferedReader br = new BufferedReader(new InputStreamReader(code >= 400 ? conn.getErrorStream() : conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder(); String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    respBody = sb.toString();
                } finally { conn.disconnect(); }
                if (code == 200) {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        sender.sendMessage(ChatColor.GREEN + "[" + aiName + "] Pro key redeemed successfully!");
                        sender.sendMessage(ChatColor.GRAY + "  Reloading engine to fetch new tier...");
                    });
                    plugin.getLogger().info("[" + aiName + "] Pro key redeemed for session " + sidForRedeem);
                    if (plugin.getEngine() != null) {
                        plugin.getEngine().handshake(new CraftyEngine.Callback() {
                            public void onSuccess(String r) {}
                            public void onFailure(String e) { plugin.getLogger().warning("[" + aiName + "] Post-redeem handshake failed: " + e); }
                        });
                    }
                } else {
                    String errMsg = respBody.length() > 200 ? respBody.substring(0, 200) : respBody;
                    plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(ChatColor.RED + "[" + aiName + "] Redeem failed (HTTP " + code + "): " + errMsg));
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("[" + aiName + "] Pro redeem failed: " + t.getClass().getSimpleName() + " \u2014 " + t.getMessage());
                plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(ChatColor.RED + "[" + aiName + "] Redeem failed: " + t.getMessage()));
            }
        });
        return true;
    }

    private boolean handlePrompt(CommandSender sender, String[] args) {
        String aiName = plugin.getAiName();
        if (!sender.hasPermission("crafty.admin")) { sender.sendMessage(ChatColor.RED + "No permission (requires crafty.admin)."); return true; }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.AQUA + "[" + aiName + "] Custom Prompt Commands (Pro only):");
            sender.sendMessage(ChatColor.GRAY + "  /crafty prompt view   " + ChatColor.WHITE + "- View current custom prompt");
            sender.sendMessage(ChatColor.GRAY + "  /crafty prompt set    " + ChatColor.WHITE + "- <text> Set custom prompt (1 change/24h)");
            sender.sendMessage(ChatColor.GRAY + "  /crafty prompt reset  " + ChatColor.WHITE + "- Reset to default prompt");
            return true;
        }
        String promptSub = args[1].toLowerCase();
        final String apiKey = plugin.getConfig().getString("server.secret", "");
        final String targetUrl = com.demonz.craftyai.common.GatewayRequestHeaders.getGatewayUrl();
        final String sid = plugin.getServerId();

        if (promptSub.equals("view")) {
            sender.sendMessage(ChatColor.AQUA + "[" + aiName + "] Fetching custom prompt...");
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    URL url = new URL(targetUrl + "/v1/custom-prompt?server_id=" + sid);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("X-Client-Type", "minecraft-spigot");
                    conn.setRequestProperty("X-CraftyAI-Version", plugin.getDescription().getVersion());
                    if (apiKey != null && !apiKey.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                    conn.setConnectTimeout(5000); conn.setReadTimeout(5000);
                    int code = conn.getResponseCode();
                    String respBody;
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(code >= 400 ? conn.getErrorStream() : conn.getInputStream(), StandardCharsets.UTF_8))) {
                        StringBuilder sb = new StringBuilder(); String line;
                        while ((line = br.readLine()) != null) sb.append(line);
                        respBody = sb.toString();
                    } finally { conn.disconnect(); }
                    final int httpCode = code;
                    final String body = respBody;
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (httpCode == 200) {
                            try {
                                Object parsed = JsonParserAdapter.parse(body);
                                if (parsed instanceof JsonObject) {
                                    JsonElement promptEl = ((JsonObject) parsed).get("prompt");
                                    if (promptEl == null || promptEl.isJsonNull()) {
                                        sender.sendMessage(ChatColor.AQUA + "[" + aiName + "] No custom prompt set (using default).");
                                    } else {
                                        sender.sendMessage(ChatColor.AQUA + "[" + aiName + "] Current custom prompt:");
                                        sender.sendMessage(ChatColor.WHITE + "  " + promptEl.getAsString());
                                    }
                                } else { sender.sendMessage(ChatColor.RED + "[" + aiName + "] Unexpected response shape"); }
                            } catch (Throwable t) { sender.sendMessage(ChatColor.RED + "[" + aiName + "] Failed to parse response"); }
                        } else { sender.sendMessage(ChatColor.RED + "[" + aiName + "] Failed to fetch (HTTP " + httpCode + ")"); }
                    });
                } catch (Throwable t) {
                    plugin.getLogger().warning("[" + aiName + "] Custom prompt view failed: " + t.getMessage());
                    plugin.getServer().getScheduler().runTask(plugin, () ->
                            sender.sendMessage(ChatColor.RED + "[" + aiName + "] Custom prompt service is unavailable."));
                }
            });
            return true;
        }

        if (promptSub.equals("set")) {
            if (args.length < 3) { sender.sendMessage(ChatColor.RED + "Usage: /crafty prompt set <text>"); return true; }
            StringBuilder sb = new StringBuilder();
            for (int i = 2; i < args.length; i++) { if (i > 2) sb.append(' '); sb.append(args[i]); }
            final String newPrompt = sb.toString();
            if (newPrompt.length() > 4000) { sender.sendMessage(ChatColor.RED + "Prompt too long (max 4000 chars)."); return true; }
            sender.sendMessage(ChatColor.AQUA + "[" + aiName + "] Saving custom prompt...");
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL(targetUrl + "/v1/custom-prompt?server_id=" + sid);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("PUT");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setRequestProperty("X-Client-Type", "minecraft-spigot");
                    conn.setRequestProperty("X-CraftyAI-Version", plugin.getDescription().getVersion());
                    if (apiKey != null && !apiKey.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                    conn.setDoOutput(true); conn.setConnectTimeout(8000); conn.setReadTimeout(8000);
                    try (OutputStream os = conn.getOutputStream()) { os.write(("{\"prompt\":\"" + escapeJson(newPrompt) + "\"}").getBytes(StandardCharsets.UTF_8)); }
                    int code = conn.getResponseCode();
                    final int httpCode = code;
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (httpCode == 200) { sender.sendMessage(ChatColor.GREEN + "[" + aiName + "] Custom prompt saved!"); sender.sendMessage(ChatColor.GRAY + "  You can change it again in 24 hours."); }
                        else if (httpCode == 429) sender.sendMessage(ChatColor.RED + "[" + aiName + "] Rate limited. Try again in 24 hours.");
                        else if (httpCode == 403) sender.sendMessage(ChatColor.RED + "[" + aiName + "] Pro or Enterprise tier required.");
                        else sender.sendMessage(ChatColor.RED + "[" + aiName + "] Save failed (HTTP " + httpCode + ")");
                    });
                } catch (Throwable t) {
                    plugin.getLogger().warning("[" + aiName + "] Custom prompt set failed: " + t.getMessage());
                    plugin.getServer().getScheduler().runTask(plugin, () ->
                            sender.sendMessage(ChatColor.RED + "[" + aiName + "] Failed to save the custom prompt."));
                } finally { if (conn != null) conn.disconnect(); }
            });
            return true;
        }

        if (promptSub.equals("reset")) {
            sender.sendMessage(ChatColor.AQUA + "[" + aiName + "] Resetting custom prompt...");
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL(targetUrl + "/v1/custom-prompt/reset?server_id=" + sid);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("X-Client-Type", "minecraft-spigot");
                    conn.setRequestProperty("X-CraftyAI-Version", plugin.getDescription().getVersion());
                    if (apiKey != null && !apiKey.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                    conn.setConnectTimeout(8000); conn.setReadTimeout(8000);
                    int code = conn.getResponseCode();
                    final int httpCode = code;
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (httpCode == 200) sender.sendMessage(ChatColor.GREEN + "[" + aiName + "] Custom prompt reset to default.");
                        else sender.sendMessage(ChatColor.RED + "[" + aiName + "] Reset failed (HTTP " + httpCode + ")");
                    });
                } catch (Throwable t) {
                    plugin.getLogger().warning("[" + aiName + "] Custom prompt reset failed: " + t.getMessage());
                    plugin.getServer().getScheduler().runTask(plugin, () ->
                            sender.sendMessage(ChatColor.RED + "[" + aiName + "] Failed to reset the custom prompt."));
                } finally { if (conn != null) conn.disconnect(); }
            });
            return true;
        }
        sender.sendMessage(ChatColor.RED + "Unknown subcommand. Use: /crafty prompt <view|set|reset>");
        return true;
    }

    private boolean handleApiKey(CommandSender sender, String[] args) {
        String aiName = plugin.getAiName();
        if (!sender.hasPermission("crafty.admin")) { sender.sendMessage(ChatColor.RED + "No permission."); return true; }

        if (args.length < 2) {
            String existing = plugin.getConfig().getString("server.secret", "");
            if (existing != null && !existing.isEmpty() && !existing.equals("YOUR_API_KEY_HERE")) {
                sender.sendMessage(ChatColor.YELLOW + "[" + aiName + "] An API key is already configured. Use '/crafty apikey <new-key>' to replace it, or '/crafty apikey rotate' to mint a fresh one.");
                return true;
            }
            return handleAutoMint(sender);
        }
        if (args[1].equalsIgnoreCase("rotate")) {
            plugin.getConfig().set("server.secret", "");
            plugin.saveConfig();
            sender.sendMessage(ChatColor.AQUA + "[" + aiName + "] Cleared existing key. Run '/crafty apikey' (no arg) to mint a fresh one.");
            return true;
        }
        String key = args[1].trim();
        if (!key.matches("^cai_[a-zA-Z0-9_-]{16,128}$")) {
            sender.sendMessage(ChatColor.RED + "Invalid API key format. It should start with cai_.");
            return true;
        }
        plugin.getConfig().set("server.secret", key);
        plugin.saveConfig();
        plugin.reloadEngine();
        if (plugin.getVisionListener() != null) { plugin.getVisionListener().setEngine(plugin.getEngine()); plugin.getVisionListener().reload(); }
        sender.sendMessage(ChatColor.GREEN + "[" + aiName + "] API key saved and neural engine reloaded.");
        return true;
    }

    private boolean handleAutoMint(CommandSender sender) {
        String aiName = plugin.getAiName();
        sender.sendMessage(ChatColor.AQUA + "[" + aiName + "] Minting and securely saving a new API key...");
        final String version = plugin.getDescription().getVersion();
        final String serverName = plugin.getServer().getName();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String targetUrl = com.demonz.craftyai.common.GatewayRequestHeaders.getGatewayUrl();
                URL url = new URL(targetUrl + "/v1/handshake-no-key");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("X-Client-Type", "minecraft-spigot");
                conn.setRequestProperty("X-CraftyAI-Version", version);
                conn.setDoOutput(true); conn.setConnectTimeout(8000); conn.setReadTimeout(8000);
                String body = "{\"name\":\"" + escapeJson(serverName) + "\",\"version\":\"" + escapeJson(version) + "\",\"client_type\":\"minecraft-spigot\"}";
                try (OutputStream os = conn.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
                int code = conn.getResponseCode();
                String respBody;
                try (BufferedReader br = new BufferedReader(new InputStreamReader(code >= 400 ? conn.getErrorStream() : conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder(); String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    respBody = sb.toString();
                } finally { conn.disconnect(); }
                if (code != 200) {
                    plugin.getLogger().warning("[" + aiName + "] handshake-no-key failed: HTTP " + code + " " + respBody);
                    plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(ChatColor.RED + "[" + aiName + "] Key mint failed (HTTP " + code + "). Use '/crafty apikey <cai_...>' to set one manually."));
                    return;
                }
                JsonObject json;
                try {
                    JsonElement parsed = JsonParserAdapter.parse(respBody);
                    if (parsed == null || !parsed.isJsonObject()) {
                        plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(ChatColor.RED + "[" + aiName + "] Key mint returned invalid response."));
                        return;
                    }
                    json = parsed.getAsJsonObject();
                } catch (Throwable t) {
                    plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(ChatColor.RED + "[" + aiName + "] Key mint returned invalid response."));
                    return;
                }
                String newKey = json.has("api_key") && !json.get("api_key").isJsonNull() ? json.get("api_key").getAsString() : "";
                String newServerId = json.has("server_id") && !json.get("server_id").isJsonNull() ? json.get("server_id").getAsString() : "";
                String newTier = json.has("tier") && !json.get("tier").isJsonNull() ? json.get("tier").getAsString() : "free";
                if (newKey.isEmpty() || !newKey.startsWith("cai_")) {
                    plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(ChatColor.RED + "[" + aiName + "] Key mint returned no key."));
                    return;
                }
                final String fKey = newKey, fSid = newServerId, fTier = newTier;
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    plugin.getConfig().set("server.secret", fKey);
                    plugin.getConfig().set("server.id", fSid);
                    plugin.saveConfig();
                    plugin.reloadEngine();
                    if (plugin.getVisionListener() != null) { plugin.getVisionListener().setEngine(plugin.getEngine()); plugin.getVisionListener().reload(); }
                    plugin.getLogger().info("[" + aiName + "] API key generated and saved to plugins/CraftyAI/config.yml.");
                    plugin.getLogger().info("[" + aiName + "] Treat that configuration file as a secret. Tier: " + fTier + ", Server ID: " + fSid);
                    sender.sendMessage(ChatColor.GREEN + "[" + aiName + "] New API key minted and saved securely.");
                    sender.sendMessage(ChatColor.GRAY + "  Server ID: " + ChatColor.AQUA + fSid);
                    sender.sendMessage(ChatColor.GRAY + "  Tier: " + ChatColor.AQUA + fTier);
                });
            } catch (Throwable t) {
                plugin.getLogger().severe("[" + aiName + "] apikey auto-mint failed: " + t.getClass().getSimpleName() + " \u2014 " + t.getMessage());
                plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(ChatColor.RED + "[" + aiName + "] Auto-mint failed."));
            }
        });
        return true;
    }

    private boolean handleLearn(CommandSender sender, String[] args) {
        if (!sender.hasPermission("crafty.admin")) { sender.sendMessage(ChatColor.RED + "No permission."); return true; }
        if (args.length < 3) { sender.sendMessage(ChatColor.GRAY + "Usage: " + ChatColor.WHITE + "/crafty learn <question> | <answer>"); return true; }
        StringBuilder fullLearn = new StringBuilder();
        for (int i = 1; i < args.length; i++) { if (i > 1) fullLearn.append(" "); fullLearn.append(args[i]); }
        String[] parts = fullLearn.toString().split("\\|", 2);
        if (parts.length < 2) { sender.sendMessage(ChatColor.RED + "Separate question and answer with |"); return true; }
        plugin.getEngine().learn(parts[0].trim(), parts[1].trim(), new CraftyEngine.Callback() {
            public void onSuccess(String r) { sender.sendMessage(ChatColor.GREEN + "[" + plugin.getAiName() + "] Memory stored!"); }
            public void onFailure(String e) { sender.sendMessage(ChatColor.RED + "[" + plugin.getAiName() + "] Failed: " + e); }
        });
        return true;
    }

    private boolean handleAsk(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage(ChatColor.RED + "Players only."); return true; }
        final Player player = (Player) sender;
        if (!player.hasPermission("crafty.chat")) { player.sendMessage(ChatColor.RED + "No permission."); return true; }
        if (args.length < 2) { player.sendMessage(ChatColor.GRAY + "Usage: " + ChatColor.WHITE + "/crafty ask <question>"); return true; }
        StringBuilder qb = new StringBuilder();
        for (int i = 1; i < args.length; i++) { if (i > 1) qb.append(" "); qb.append(args[i]); }
        final String question = qb.toString().trim();
        if (question.isEmpty()) { player.sendMessage(ChatColor.RED + "Please provide a question."); return true; }

        final String aiName = plugin.getAiName();
        final VersionAdapter adapter = plugin.getAdapter();
        final ConversationCache conversations = plugin.getConversations();
        final LocalBrain localBrain = plugin.getKnowledgeBase() != null ? new LocalBrain(plugin, plugin.getKnowledgeBase()) : null;

        if (plugin.getConfig().getBoolean("ai.force-local-mode", false)) {
            if (localBrain != null) {
                String localAnswer = localBrain.tryAnswer(question);
                if (localAnswer != null) {
                    String format = plugin.getConfig().getString("chat.format", "&b[{name}] &7➦ &f{response}");
                    adapter.sendMessage(player, "&8[Private] " + format.replace("{name}", aiName).replace("{response}", localAnswer));
                    conversations.addInteraction(player.getUniqueId(), question, localAnswer, true);
                    adapter.sendActionBar(player, "&e&lFORCED LOCAL MODE");
                    adapter.playSound(player, plugin.getConfig().getString("chat.sounds.success", "ENTITY_EXPERIENCE_ORB_PICKUP"), 1.0f, 1.2f);
                    return true;
                }
            }
            adapter.sendMessage(player, "&c[" + aiName + "] &7Local brain failed to generate response.");
            return true;
        }

        if (plugin.getEngine() == null) { adapter.sendMessage(player, "&c[" + aiName + "] &7Neural engine not available."); return true; }

        final CraftyEngine engine = plugin.getEngine();
        // Use ChatHandler.buildContext() instead of always-empty string
        final String context = plugin.getChatHandler() != null ? plugin.getChatHandler().buildContext(player) : "";
        adapter.sendActionBar(player, "&b&l" + aiName.toUpperCase() + " IS THINKING...");
        final List<Map<String, String>> history = conversations.getFormattedHistory(player.getUniqueId(), true);

        engine.ask(player, question, context, history, new CraftyEngine.Callback() {
            public void onSuccess(final String response) {
                plugin.updateTierFromResponse(response);
                adapter.runEntitySync(player, new Runnable() {
                    public void run() {
                        if (!player.isOnline()) return;
                        String answer = engine.parseAnswer(response);
                        if (answer != null && !answer.isEmpty()) {
                            String format = plugin.getConfig().getString("chat.format", "&b[{name}] &7➦ &f{response}");
                            adapter.sendMessage(player, "&8[Private] " + format.replace("{name}", aiName).replace("{response}", answer));
                            conversations.addInteraction(player.getUniqueId(), question, answer, true);
                            String action = engine.parseAction(response);
                            if (action == null || action.isEmpty() || "null".equalsIgnoreCase(action)) {
                                ChatHandler ch = plugin.getChatHandler();
                                if (ch != null) action = ch.inferActionFromText(answer, question);
                            } else if (action.toUpperCase().startsWith("SCHEDULE_TASK:") && question != null && question.toLowerCase().matches(".*\\b(in|after|wait)\\s+\\d+\\s*(seconds?|sec|minutes?|min)\\b.*")) {
                                ChatHandler ch = plugin.getChatHandler();
                                if (ch != null) action = ch.inferActionFromText(answer, question);
                            }
                            if (action != null && (plugin.getConfig().getBoolean("ai.enable_actions", true) || plugin.getConfig().getBoolean("ai_agentic_tasks", false))) {
                                plugin.getActionHandler().handleAction(plugin, player, action, plugin.getTier());
                            }
                            adapter.playSound(player, plugin.getConfig().getString("chat.sounds.success", "ENTITY_EXPERIENCE_ORB_PICKUP"), 1.0f, 1.2f);
                        } else {
                            adapter.sendMessage(player, ChatColor.RED + "\u2716 " + ChatColor.DARK_RED + "[" + aiName + "] " + ChatColor.RED + "Neural link offline.");
                        }
                    }
                });
            }
            public void onFailure(final String error) {
                adapter.runSync(new Runnable() {
                    public void run() {
                        if (!player.isOnline()) return;
                        if (localBrain != null) {
                            String localAnswer = localBrain.tryAnswer(question);
                            if (localAnswer != null) {
                                String format = plugin.getConfig().getString("chat.format", "&b[{name}] &7➦ &f{response}");
                                adapter.sendMessage(player, "&8[Private] " + format.replace("{name}", aiName).replace("{response}", localAnswer));
                                conversations.addInteraction(player.getUniqueId(), question, localAnswer, true);
                                adapter.sendActionBar(player, "&e&lLOCAL MODE");
                                adapter.playSound(player, plugin.getConfig().getString("chat.sounds.error", "BLOCK_NOTE_BLOCK_CHIME"));
                                return;
                            }
                        }
                        adapter.sendMessage(player, "&c[" + aiName + "] &7" + error);
                        adapter.sendActionBar(player, "&c&lERROR");
                        adapter.playSound(player, plugin.getConfig().getString("chat.sounds.error", "BLOCK_NOTE_BLOCK_CHIME"));
                    }
                });
            }
        });
        return true;
    }

    private boolean handleScan(CommandSender sender) {
        if (!(sender instanceof Player)) { sender.sendMessage(ChatColor.RED + "Players only."); return true; }
        final Player player = (Player) sender;
        if (!player.hasPermission("crafty.scan")) { player.sendMessage(ChatColor.RED + "No permission."); return true; }
        if (plugin.getVisionListener() == null) { player.sendMessage(ChatColor.RED + "[" + plugin.getAiName() + "] Vision system not available."); return true; }

        Entity targetEntity = null;
        org.bukkit.block.Block targetBlock = null;

        for (Entity entity : player.getNearbyEntities(5, 5, 5)) {
            if (entity instanceof Player) continue;
            try {
                if (player.hasLineOfSight(entity)) { targetEntity = entity; break; }
            } catch (Throwable t) { continue; }
        }

        try {
            targetBlock = player.getTargetBlock((Set<Material>) null, 6);
        } catch (NoSuchMethodError e) {
            try { targetBlock = player.getTargetBlock(null, 6); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}

        if (targetEntity != null) {
            plugin.getVisionListener().scanEntity(player, targetEntity);
        } else if (targetBlock != null && targetBlock.getType() != Material.AIR) {
            plugin.getVisionListener().scanBlock(player, targetBlock);
        } else {
            player.sendMessage(ChatColor.YELLOW + "[" + plugin.getAiName() + "] " + ChatColor.GRAY + "No block or entity in range to scan. Look at something and try again.");
        }
        return true;
    }

    private boolean handleLink(CommandSender sender) {
        if (!sender.hasPermission("crafty.admin")) { sender.sendMessage(ChatColor.RED + "No permission."); return true; }
        String aiName = plugin.getAiName();
        final String version = plugin.getDescription().getVersion();
        sender.sendMessage(ChatColor.AQUA + "[" + aiName + "] Generating link code for Discord linking...");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String targetUrl = com.demonz.craftyai.common.GatewayRequestHeaders.getGatewayUrl();
                String apiKey = plugin.getConfig().getString("server.secret", "");
                URL url = new URL(targetUrl + "/v1/link-generate");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setRequestProperty("X-Client-Type", "minecraft-spigot");
                conn.setRequestProperty("X-CraftyAI-Version", version);
                conn.setDoOutput(true); conn.setConnectTimeout(8000); conn.setReadTimeout(8000);
                try (OutputStream os = conn.getOutputStream()) { os.write("{}".getBytes(StandardCharsets.UTF_8)); }
                int code = conn.getResponseCode();
                String respBody;
                try (BufferedReader br = new BufferedReader(new InputStreamReader(code >= 400 ? conn.getErrorStream() : conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder(); String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    respBody = sb.toString();
                } finally { conn.disconnect(); }
                if (code != 200) {
                    plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(ChatColor.RED + "[" + aiName + "] Link code generation failed (HTTP " + code + ")."));
                    return;
                }
                JsonObject json;
                try {
                    JsonElement parsed = JsonParserAdapter.parse(respBody);
                    if (parsed == null || !parsed.isJsonObject()) {
                        plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(ChatColor.RED + "[" + aiName + "] Invalid response."));
                        return;
                    }
                    json = parsed.getAsJsonObject();
                } catch (Throwable t) {
                    plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(ChatColor.RED + "[" + aiName + "] Invalid response."));
                    return;
                }
                String linkCode = json.has("code") && !json.get("code").isJsonNull() ? json.get("code").getAsString() : "";
                if (linkCode.isEmpty()) {
                    plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(ChatColor.RED + "[" + aiName + "] No link code returned."));
                    return;
                }
                final String fCode = linkCode;
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    sender.sendMessage(ChatColor.GREEN + "=========================================");
                    sender.sendMessage(ChatColor.GREEN + " [" + aiName + "] DISCORD LINK CODE");
                    sender.sendMessage(ChatColor.GREEN + " Code: " + ChatColor.AQUA + fCode);
                    sender.sendMessage(ChatColor.GREEN + " Expiry: 15 minutes");
                    sender.sendMessage(ChatColor.GREEN + "");
                    sender.sendMessage(ChatColor.GREEN + " Tell your server owner to run:");
                    sender.sendMessage(ChatColor.GREEN + "   /link " + fCode);
                    sender.sendMessage(ChatColor.GREEN + " in Discord to link this server.");
                    sender.sendMessage(ChatColor.GREEN + "=========================================");
                });
            } catch (Exception e) {
                plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(ChatColor.RED + "[" + aiName + "] Link failed: " + e.getMessage()));
            }
        });
        return true;
    }

    public List<String> handleTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("crafty")) return null;
        if (args.length == 1) {
            List<String> completions = new ArrayList<String>(Arrays.asList("help", "reload", "status", "apikey", "mode", "ask", "scan", "pro", "prompt", "memory", "forget", "privacy", "confirm", "link"));
            if (sender.hasPermission("crafty.admin")) completions.add("learn");
            String input = args[0].toLowerCase();
            Iterator<String> it = completions.iterator();
            while (it.hasNext()) { if (!it.next().startsWith(input)) it.remove(); }
            return completions;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("pro")) return Arrays.asList("status", "help");
        if (args.length == 2 && args[0].equalsIgnoreCase("prompt")) return Arrays.asList("view", "set", "reset");
        return Collections.emptyList();
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"':  sb.append("\\\""); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }
}
