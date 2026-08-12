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

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import org.bstats.bukkit.Metrics;
import com.demonz.craftyai.common.ActionAuditLog;
import com.demonz.craftyai.common.ActionRateLimiter;
import com.demonz.craftyai.common.ActionConfirmation;
import com.demonz.craftyai.common.ControlPlaneProcessor;
import com.demonz.craftyai.common.CraftyAIConfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CraftyAI V1.2 - Main Plugin Class (Slim Orchestrator)
 * ====================================================
 * Java 8 compatible. Uses HttpURLConnection.
 * Supports: Spigot/Paper/Folia/Purpur 1.8 - 1.21+
 *
 * Handlers extracted:
 *   ChatHandler   — chat activation, question extraction, AI dispatch
 *   ActionHandler — agentic action execution
 *   CommandHandler — /crafty command routing
 *   AnnouncementPoller — server announcement broadcast
 */
public class CraftyAI extends JavaPlugin implements Listener {

    private PlatformDetector platform;
    private VersionAdapter adapter;
    private CraftyEngine engine;
    private ConversationCache conversations;
    private KnowledgeBase knowledgeBase;
    private LocalBrain localBrain;
    private VisionListener visionListener;
    private final Set<UUID> welcomedPlayers = ConcurrentHashMap.newKeySet();

    private ActionAuditLog auditLog;
    private ActionRateLimiter actionRateLimiter;
    private ActionConfirmation actionConfirmations;

    private ChatHandler chatHandler;
    private ActionHandler actionHandler;
    private CommandHandler commandHandler;
    private AnnouncementPoller announcementPoller;

    private volatile String tier = "free";

    // --- Session ID Management (synchronized) ---
    private synchronized String ensureServerId() {
        File sessionFile = new File(getDataFolder(), ".craftyai_session");
        if (sessionFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(sessionFile), java.nio.charset.StandardCharsets.UTF_8))) {
                String existing = reader.readLine();
                if (existing != null && !existing.trim().isEmpty()) {
                    existing = existing.trim();
                    if (existing.matches("^srv-[a-fA-F0-9]{8}$")) {
                        return existing;
                    }
                    getLogger().info("[CraftyAI] Migrating session ID to srv- format...");
                    String cleaned = existing.replace("-", "");
                    String migrated = "srv-" + (cleaned.length() >= 8 ? cleaned.substring(0, 8) : String.format("%-8s", cleaned).replace(' ', '0'));
                    try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(new java.io.FileOutputStream(sessionFile), java.nio.charset.StandardCharsets.UTF_8))) {
                        writer.print(migrated);
                    }
                    getLogger().info("[CraftyAI] Session ID migrated: " + migrated);
                    return migrated;
                }
            } catch (IOException e) {
                getLogger().warning("[CraftyAI] Failed to read session file: " + e.getMessage());
            }
        }
        String id = "srv-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        try {
            if (!getDataFolder().exists()) getDataFolder().mkdirs();
            try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(new java.io.FileOutputStream(sessionFile), java.nio.charset.StandardCharsets.UTF_8))) {
                writer.print(id);
            }
        } catch (IOException e) {
            getLogger().warning("[CraftyAI] Failed to write session file: " + e.getMessage());
        }
        if (getConfig().contains("server.id")) {
            getConfig().set("server.id", null);
            saveConfig();
        }
        return id;
    }

    public String getServerId() { return ensureServerId(); }

    // package-private for CommandHandler
    void reloadEngine() {
        String serverSecret;
        String serverId = ensureServerId();
        String gatewayUrl;
        String customModel = null;
        boolean customProvider = isCustomProviderEnabled();
        if (customProvider) {
            serverSecret = getConfig().getString("server.custom_provider.api_key", "");
            gatewayUrl = getConfig().getString("server.custom_provider.url", "");
            customModel = getConfig().getString("server.custom_provider.model", "");
        } else {
            serverSecret = getConfig().getString("server.secret", "");
            gatewayUrl = com.demonz.craftyai.common.GatewayRequestHeaders.getGatewayUrl();
        }
        if (engine != null) engine.shutdown();
        String serverName = org.bukkit.Bukkit.getServer().getName();
        engine = new CraftyEngine(this, adapter, serverSecret, serverId, gatewayUrl, customProvider, customModel, serverName);
        if (com.demonz.craftyai.common.CraftyAIConfig.needsAutoMint(serverSecret, customProvider)) {
            getLogger().warning("[CraftyAI] API key unconfigured or in legacy format. Automatically minting a new key...");
            autoMintApiKey();
        }
        if (customProvider) {
            String hostDisplay;
            try { hostDisplay = new java.net.URI(gatewayUrl).getHost() + "/***"; } catch (Exception e) { hostDisplay = "***"; }
            getLogger().info("[CraftyAI] Custom AI provider enabled: " + hostDisplay);
        }
    }

    public boolean isCustomProviderEnabled() {
        return getConfig().getBoolean("server.custom_provider.enabled", false);
    }

    private static String simpleJsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private void autoMintApiKey() {
        final String version = getDescription().getVersion();
        final String serverName = getServer().getName();
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                String targetUrl = com.demonz.craftyai.common.GatewayRequestHeaders.getGatewayUrl();
                java.net.URL url = new java.net.URL(targetUrl + "/v1/handshake-no-key");
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("X-Client-Type", "minecraft-spigot");
                conn.setRequestProperty("X-CraftyAI-Version", version);
                String sessionId = com.demonz.craftyai.common.SessionManager.getSessionId(getDataFolder().toPath());
                if (sessionId != null && !sessionId.isEmpty()) {
                    conn.setRequestProperty("X-Server-ID", sessionId);
                }
                conn.setDoOutput(true);
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                String body = "{\"name\":\"" + simpleJsonEscape(serverName) + "\",\"version\":\"" + simpleJsonEscape(version) + "\",\"client_type\":\"minecraft-spigot\"}";
                try (java.io.OutputStream os = conn.getOutputStream()) { os.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
                int code = conn.getResponseCode();
                String respBody;
                java.io.InputStream errStream = code >= 400 ? conn.getErrorStream() : null;
                java.io.InputStream respStream = errStream != null ? errStream : conn.getInputStream();
                try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(respStream, java.nio.charset.StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder(); String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    respBody = sb.toString();
                } finally { conn.disconnect(); }
                if (code != 200) {
                    getLogger().warning("[CraftyAI] Auto-mint failed (HTTP " + code + "). Run /crafty apikey <key> manually.");
                    return;
                }
                com.google.gson.JsonObject json;
                try {
                    com.google.gson.JsonElement parsed = com.demonz.craftyai.JsonParserAdapter.parse(respBody);
                    if (parsed == null || !parsed.isJsonObject()) {
                        getLogger().warning("[CraftyAI] Auto-mint returned invalid response.");
                        return;
                    }
                    json = parsed.getAsJsonObject();
                } catch (Throwable t) {
                    getLogger().warning("[CraftyAI] Auto-mint returned invalid response.");
                    return;
                }
                String newKey = json.has("api_key") && !json.get("api_key").isJsonNull() ? json.get("api_key").getAsString() : "";
                String newServerId = json.has("server_id") && !json.get("server_id").isJsonNull() ? json.get("server_id").getAsString() : "";
                String newTier = json.has("tier") && !json.get("tier").isJsonNull() ? json.get("tier").getAsString() : "free";
                if (newKey.isEmpty() || !newKey.startsWith("cai_")) {
                    getLogger().warning("[CraftyAI] Auto-mint returned no key.");
                    return;
                }
                final String fKey = newKey, fSid = newServerId, fTier = newTier;
                getServer().getScheduler().runTask(this, () -> {
                    getConfig().set("server.secret", fKey);
                    if (fSid != null && !fSid.isEmpty()) getConfig().set("server.id", fSid);
                    saveConfig();
                    // Persist new server ID to session file so client-side code picks it up
                    if (fSid != null && !fSid.isEmpty()) {
                        try {
                            java.nio.file.Files.write(
                                new java.io.File(getDataFolder(), ".craftyai_session").toPath(),
                                fSid.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        } catch (Exception ignored) {}
                    }
                    reloadEngine();
                    getLogger().info("[CraftyAI] API key generated and saved to plugins/CraftyAI/config.yml.");
                    getLogger().info("[CraftyAI] Treat that configuration file as a secret. Tier: " + fTier + ", Server ID: " + fSid);
                });
            } catch (Exception e) {
                getLogger().warning("[CraftyAI] Auto-mint failed: " + e.getMessage());
            }
        });
    }

    // --- Lifecycle ---

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        // Removed duplicate migrateConfig(). ConfigMigrator (below) is the canonical migration system.
        try {
            com.demonz.craftyai.ConfigMigrator migrator = new com.demonz.craftyai.ConfigMigrator(getDataFolder(), getLogger());
            if (migrator.migrate(getConfig())) saveConfig();
        } catch (Throwable t) {
            getLogger().warning("[CraftyAI] ConfigMigrator failed (non-fatal): " + t.getMessage());
        }
        ensureServerId();

        if (getConfig().getBoolean("enable_metrics", true)) {
            new Metrics(this, 28401);
        }

        platform = new PlatformDetector(getLogger());
        adapter = new VersionAdapter(this, platform);

        reloadEngine();
        conversations = new ConversationCache();
        knowledgeBase = new KnowledgeBase(this);
        localBrain = new LocalBrain(this, knowledgeBase);

        auditLog = new ActionAuditLog(getDataFolder().getAbsolutePath(), getLogger()::info);
        actionRateLimiter = new ActionRateLimiter();
        actionConfirmations = new ActionConfirmation();

        chatHandler = new ChatHandler(this, adapter, conversations, localBrain);
        actionHandler = new ActionHandler(adapter, auditLog, actionRateLimiter, actionConfirmations);
        commandHandler = new CommandHandler(this);

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(chatHandler, this);

        visionListener = new VisionListener(this, adapter, engine);
        getServer().getPluginManager().registerEvents(visionListener, this);

        if (getConfig().getBoolean("enable_metrics", true)) {
            engine.handshake(new CraftyEngine.Callback() {
                public void onSuccess(String response) {
                    getLogger().info("[CraftyAI] Neural uplink established!");
                    updateTierFromResponse(response);
                    deliverWarningsFromResponse(null, response);
                    processControlPlaneResponse(response);
                }
                public void onFailure(String error) {
                    getLogger().warning("[CraftyAI] Handshake pending: " + error);
                }
            });
            final Runnable[] heartbeatRef = new Runnable[1];
            heartbeatRef[0] = new Runnable() {
                public void run() {
                    try {
                        if (engine != null && adapter != null) {
                            engine.handshake(new CraftyEngine.Callback() {
                                public void onSuccess(String response) {
                                    deliverWarningsFromResponse(null, response);
                                    processControlPlaneResponse(response);
                                }
                                public void onFailure(String error) {}
                            });
                        }
                    } finally {
                        adapter.runAsyncLater(heartbeatRef[0], 20L * 60 * 5);
                    }
                }
            };
            adapter.runAsyncLater(heartbeatRef[0], 20L * 60 * 5);
        } else {
            getLogger().info("[CraftyAI] Metrics disabled. Heartbeat and status reporting are inactive.");
        }

        announcementPoller = new AnnouncementPoller(this, adapter);
        announcementPoller.start();

        new UpdateChecker(this).checkForUpdates();

        getLogger().info("==============================================");
        getLogger().info("  CraftyAI V" + getDescription().getVersion() + " - Neural Engine Online       ");
        getLogger().info("  Platform: " + padRight(platform.getSoftware().name(), 29));
        getLogger().info("  Version:  " + padRight(platform.getVersionString(), 28));
        getLogger().info("  AI Name:  " + padRight(chatHandler.getAiName(), 29));
        getLogger().info("==============================================");

        getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
                final Player p = event.getPlayer();
                if (chatHandler == null || engine == null || conversations == null) return;
                if (!p.isOp() && !p.hasPermission("crafty.admin")) return;
                if (welcomedPlayers.size() > 10000) welcomedPlayers.clear();
                if (!welcomedPlayers.add(p.getUniqueId())) return;
                adapter.runAsyncLater(new Runnable() {
                    public void run() {
                        adapter.runEntitySync(p, new Runnable() {
                            public void run() {
                                if (!p.isOnline()) return;
                                if (getConfig().getBoolean("op_welcome_message", true)) {
                                    String aiName = chatHandler.getAiName();
                                    sendPlayerMessage(p, "");
                                    sendPlayerMessage(p, ChatColor.AQUA + "" + ChatColor.BOLD + "\u2726 CraftyAI " + ChatColor.GRAY + "v" + getDescription().getVersion() + " " + ChatColor.DARK_GRAY + "\u2014 " + ChatColor.GRAY + "Neural AI Companion");
                                    sendPlayerMessage(p, ChatColor.GRAY + "  Thanks for using CraftyAI by " + ChatColor.AQUA + "DemonZ Development" + ChatColor.GRAY + "!");
                                    sendPlayerMessage(p, ChatColor.GRAY + "  Type " + ChatColor.YELLOW + "@" + aiName.toLowerCase() + " <question>" + ChatColor.GRAY + " to chat with your AI.");
                                    sendPlayerMessage(p, ChatColor.GRAY + "  Use " + ChatColor.YELLOW + "/crafty status" + ChatColor.GRAY + " to check the neural link.");
                                    sendPlayerMessage(p, ChatColor.DARK_GRAY + "  Disable: " + ChatColor.GRAY + "op_welcome_message: false in config.yml");
                                    sendPlayerMessage(p, "");
                                }
                                if (engine != null) {
                                    engine.handshake(new CraftyEngine.Callback() {
                                        public void onSuccess(String response) {
                                            deliverWarningsFromResponse(p, response);
                                            processControlPlaneResponse(response);
                                        }
                                        public void onFailure(String error) {}
                                    });
                                }
                            }
                        });
                    }
                }, 40L);
            }
        }, CraftyAI.this);

        getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onQuit(PlayerQuitEvent event) {
                if (conversations != null) conversations.removePlayer(event.getPlayer().getUniqueId());
                if (chatHandler != null) chatHandler.removeCooldown(event.getPlayer().getUniqueId());
            }
        }, CraftyAI.this);
    }

    @Override
    public void onDisable() {
        org.bukkit.event.HandlerList.unregisterAll((org.bukkit.plugin.Plugin) this);
        if (engine != null) engine.shutdown();
        if (conversations != null) conversations.clearAll();
        engine = null;
        adapter = null;
        conversations = null;
        knowledgeBase = null;
        localBrain = null;
        visionListener = null;
        getLogger().info("[CraftyAI] Neural engine offline.");
    }

    // --- Command delegation ---

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("crafty")) return false;
        try {
            return commandHandler.handleCommand(sender, command, label, args);
        } catch (Throwable t) {
            try {
                getLogger().severe("[" + chatHandler.getAiName() + "] Unhandled error in /crafty " +
                        (args.length > 0 ? args[0] : "") + ": " + t.getClass().getSimpleName() +
                        " \u2014 " + t.getMessage());
                if (t.getStackTrace() != null && t.getStackTrace().length > 0) {
                    getLogger().severe("  at " + t.getStackTrace()[0]);
                }
            } catch (Throwable ignored) {}
            try { sender.sendMessage(ChatColor.RED + "[" + chatHandler.getAiName() + "] Internal error. See console for details."); }
            catch (Throwable ignored) {}
            return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (commandHandler == null) return new ArrayList<>();
        return commandHandler.handleTabComplete(sender, command, alias, args);
    }

    // --- Tier + Warning helpers ---

    public String getTier() { return tier; }

    private void processControlPlaneResponse(final String response) {
        if (response == null || adapter == null || engine == null || isCustomProviderEnabled()) return;
        adapter.runSync(new Runnable() {
            @Override
            public void run() {
                final CraftyAIConfig shared = readSharedControlConfig();
                final ControlPlaneProcessor.Result result = ControlPlaneProcessor.process(response, shared, new ControlPlaneProcessor.Handler() {
                    @Override
                    public void showMessage(String title, String message, String severity) {
                        String color = "critical".equals(severity) ? "&c&l" : "warning".equals(severity) ? "&e&l" : "&b&l";
                        getLogger().info("[Control] " + title + ": " + message);
                        for (Player player : getServer().getOnlinePlayers()) {
                            if (player.isOp() || player.hasPermission("crafty.admin")) {
                                adapter.sendMessage(player, color + "[" + title + "] &7" + message);
                            }
                        }
                    }

                    @Override
                    public boolean persistConfiguration(CraftyAIConfig config) {
                        return persistSharedControlConfig(config);
                    }

                    @Override
                    public void reloadConfiguration() {
                        reloadRuntimeConfiguration();
                    }

                    @Override
                    public void log(String message) {
                        getLogger().warning(message);
                    }
                });

                if (result.hasAcknowledgements() && engine != null) {
                    engine.sendControlAck(result.toAckJson(getServerId()), new CraftyEngine.Callback() {
                        @Override public void onSuccess(String ignored) { }
                        @Override public void onFailure(String error) { getLogger().warning("[Control] " + error); }
                    });
                }
            }
        });
    }

    private CraftyAIConfig readSharedControlConfig() {
        CraftyAIConfig config = new CraftyAIConfig();
        config.control_revision = getConfig().getLong("control.revision", 0L);
        config.ai_name = getConfig().getString("ai.name", "Crafty");
        List<String> configuredAliases = getConfig().getStringList("ai.aliases");
        if (configuredAliases.isEmpty()) {
            configuredAliases = java.util.Arrays.asList("crafty", "craftyai", "ai", "helper");
        }
        config.aliases = configuredAliases.toArray(new String[configuredAliases.size()]);
        config.prefix = getConfig().getString("ai.activation.prefix", "@");
        config.require_prefix = getConfig().getBoolean("ai.activation.require-prefix", false);
        config.cooldown_seconds = getConfig().getInt("ai.activation.cooldown", 3);
        config.response_visibility = getConfig().getString("ai.activation.response-visibility", "default");
        config.ai_enable_actions = getConfig().getBoolean("ai.enable_actions", true);
        config.agentic_tasks_enabled = config.ai_enable_actions;
        config.force_local_mode = getConfig().getBoolean("ai.force-local-mode", false);
        config.require_confirmation = getConfig().getBoolean("ai.require_confirmation", true);
        config.allow_block_scanning = getConfig().getBoolean("ai.allow_block_scanning", true);
        config.vision_activation = getConfig().getString("vision.activation", "item_right_click");
        config.vision_shift_scan_enabled = getConfig().getBoolean("vision.shift_scan_enabled", false);
        config.op_welcome_message = getConfig().getBoolean("op_welcome_message", true);
        return config;
    }

    private boolean persistSharedControlConfig(CraftyAIConfig config) {
        File configFile = new File(getDataFolder(), "config.yml");
        File tempFile = new File(getDataFolder(), "config.yml.tmp");
        try {
            org.bukkit.configuration.file.YamlConfiguration staged = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(configFile);
            staged.set("control.revision", config.control_revision);
            staged.set("ai.name", config.ai_name);
            staged.set("ai.aliases", java.util.Arrays.asList(config.aliases));
            staged.set("ai.activation.prefix", config.prefix);
            staged.set("ai.activation.require-prefix", config.require_prefix);
            staged.set("ai.activation.cooldown", config.cooldown_seconds);
            staged.set("ai.activation.response-visibility", config.response_visibility);
            staged.set("ai.enable_actions", config.ai_enable_actions);
            staged.set("ai.force-local-mode", config.force_local_mode);
            staged.set("ai.require_confirmation", config.require_confirmation);
            staged.set("ai.allow_block_scanning", config.allow_block_scanning);
            staged.set("vision.activation", config.vision_activation);
            staged.set("vision.shift_scan_enabled", config.vision_shift_scan_enabled);
            staged.set("op_welcome_message", config.op_welcome_message);
            staged.save(tempFile);
            try {
                java.nio.file.Files.move(tempFile.toPath(), configFile.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                java.nio.file.Files.move(tempFile.toPath(), configFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception error) {
            getLogger().warning("[Control] Atomic config save failed: " + error.getMessage());
            try { java.nio.file.Files.deleteIfExists(tempFile.toPath()); } catch (Exception ignored) { }
            return false;
        }
    }

    private void reloadRuntimeConfiguration() {
        reloadConfig();
        if (chatHandler != null) chatHandler.reload();
        if (visionListener != null) visionListener.reload();
    }

    void updateTierFromResponse(String response) {
        try {
            com.google.gson.JsonElement parsed = JsonParserAdapter.parse(response);
            if (parsed.isJsonObject()) {
                com.google.gson.JsonObject json = parsed.getAsJsonObject();
                if (json.has("tier")) this.tier = json.get("tier").getAsString();
            }
        } catch (Exception ignored) {}
    }

    private void deliverWarningsFromResponse(final Player target, String response) {
        try {
            com.google.gson.JsonElement parsed = JsonParserAdapter.parse(response);
            if (!parsed.isJsonObject()) return;
            com.google.gson.JsonObject json = parsed.getAsJsonObject();
            if (!json.has("warnings")) return;
            final com.google.gson.JsonArray warnings = json.getAsJsonArray("warnings");
            if (warnings.size() == 0) return;
            for (com.google.gson.JsonElement warningElement : warnings) {
                if (!warningElement.isJsonObject()) continue;
                com.google.gson.JsonObject warning = warningElement.getAsJsonObject();
                String message = warning.has("message") ? warning.get("message").getAsString() : "Unknown warning";
                getLogger().warning("[CraftyAI] Session Warning: " + message.replaceAll("§.", ""));
            }
            adapter.runSync(new Runnable() {
                public void run() {
                    if (target != null) {
                        deliverWarningArray(target, warnings);
                    } else {
                        for (Player op : getServer().getOnlinePlayers()) {
                            if (op.isOp() || op.hasPermission("crafty.admin")) deliverWarningArray(op, warnings);
                        }
                    }
                }
            });
        } catch (Exception e) {
            getLogger().fine("[CraftyAI] Warning delivery error: " + e.getMessage());
        }
    }

    private void deliverWarningArray(Player player, com.google.gson.JsonArray warnings) {
        if (warnings == null || warnings.size() == 0) return;
        sendPlayerMessage(player, "");
        sendPlayerMessage(player, ChatColor.RED + "" + ChatColor.BOLD + "\u26A0 CraftyAI Session Warnings \u26A0");
        for (com.google.gson.JsonElement warnEl : warnings) {
            if (!warnEl.isJsonObject()) continue;
            com.google.gson.JsonObject warn = warnEl.getAsJsonObject();
            String message = warn.has("message") ? warn.get("message").getAsString() : "Unknown warning";
            sendPlayerMessage(player, ChatColor.translateAlternateColorCodes('\u00a7', message));
        }
        sendPlayerMessage(player, "");
    }

    // --- Public getters ---

    public CraftyEngine getEngine() { return engine; }
    public PlatformDetector getPlatform() { return platform; }
    public VersionAdapter getAdapter() { return adapter; }
    public ConversationCache getConversations() { return conversations; }
    public KnowledgeBase getKnowledgeBase() { return knowledgeBase; }
    public String getAiName() { return chatHandler != null ? chatHandler.getAiName() : "Crafty"; }
    public ActionHandler getActionHandler() { return actionHandler; }
    public ChatHandler getChatHandler() { return chatHandler; }
    public VisionListener getVisionListener() { return visionListener; }
    public void sendPrivateResponse(Player player, String message) { adapter.sendMessage(player, message); }

    // Adventure-safe player messaging with sendRawMessage fallback
    private static void sendPlayerMessage(Player player, String legacySectionMessage) {
        try {
            Class<?> componentClass = Class.forName("net.kyori.adventure.text.Component");
            Class<?> legacyClass = Class.forName("net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer");
            Object serializer = legacyClass.getMethod("legacySection").invoke(null);
            Object component = serializer.getClass().getMethod("deserialize", String.class).invoke(serializer, legacySectionMessage);
            player.getClass().getMethod("sendMessage", componentClass).invoke(player, component);
        } catch (Throwable t) {
            player.sendRawMessage(legacySectionMessage);
        }
    }

    private String padRight(String s, int width) {
        if (s.length() >= width) return s.substring(0, width);
        StringBuilder sb = new StringBuilder(s);
        for (int i = s.length(); i < width; i++) sb.append(' ');
        return sb.toString();
    }
}
