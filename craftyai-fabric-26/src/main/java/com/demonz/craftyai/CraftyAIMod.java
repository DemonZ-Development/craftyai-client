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

import com.demonz.craftyai.common.CraftyAIConfig;
import com.demonz.craftyai.common.ConfigLoader;
import com.demonz.craftyai.common.GatewayRequestHeaders;
import com.demonz.craftyai.common.GatewayHttpClientHelper;
import com.demonz.craftyai.common.LocalBrain;
import com.demonz.craftyai.common.UpdateChecker;
import com.demonz.craftyai.common.VisionScanner;
import com.demonz.craftyai.common.NeuralResponse;
import com.demonz.craftyai.common.ActionAuditLog;
import com.demonz.craftyai.common.ActionRateLimiter;
import com.demonz.craftyai.common.ActionConfirmation;
import com.demonz.craftyai.common.AgenticActions;
import com.demonz.craftyai.common.ControlPlaneProcessor;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CraftyAI V1.0 — Fabric Mod (MC 26.x — Mojang Mappings)
 */
public class CraftyAIMod implements ModInitializer {

    public static final String MOD_ID = "craftyai";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static CraftyAIMod INSTANCE;
    private static final Gson GSON = new GsonBuilder().create();

    private static final Set<String> GIVE_BLACKLIST = Set.of(
        "barrier", "command_block", "chain_command_block", "repeating_command_block",
        "command_block_minecart", "structure_block", "structure_void",
        "bedrock", "end_portal_frame", "spawner"
    );

    private static final int MAX_RETRIES = 3;
    private static final int MAX_HISTORY = 10;
    private static final String CLIENT_TYPE = "minecraft-fabric";
    private static final int MAX_ACTIONS_PER_REQUEST = 5;

    private String aiName = "Crafty";
    private List<String> aliases = Arrays.asList("crafty", "craftyai", "ai", "helper");
    private String prefix = "@";
    private boolean requirePrefix = false;
    private int cooldownMs = 3000;
    private boolean opWelcomeEnabled = true;

    private String gatewayUrl;
    private String apiKey = "";
    private String serverId = "";
    private CraftyAIConfig loadedConfig;
    private LocalBrain localBrain;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ConcurrentHashMap<UUID, Long> chatCooldowns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, LinkedList<Map<String, String>>> conversationCache = new ConcurrentHashMap<>();
    // Per-player block scan cooldown (1/30s) to prevent tick-lag spam
    private final ConcurrentHashMap<String, Long> lastBlockScanTime = new ConcurrentHashMap<>();
    private static final int MAX_BLOCK_SCAN_TRACKED = 1000;
    private static final long BLOCK_SCAN_TTL_MS = 360_000L;

    // Security: audit log, rate limit, and confirmation flow for agentic actions
    private ActionAuditLog auditLog;
    private ActionRateLimiter actionRateLimiter;
    private ActionConfirmation actionConfirmations;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile long circuitOpenUntil = 0;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private boolean telemetryEnabled = true;
    private static MinecraftServer serverInstance;

    @Override
    public void onInitialize() {
        INSTANCE = this;
        LOGGER.info("[CraftyAI] Neural Engine initializing (Fabric)...");

        loadConfig();
        registerCommands();
        registerChatListener();
        registerVisionScanner();
        initializeLocalBrain();
        registerOpWelcome();
        
        if (telemetryEnabled) {
            performStartupHandshake();
            // Control-plane heartbeat: five minutes matches the negotiated v2 cadence.
            scheduler.scheduleWithFixedDelay(this::performStartupHandshake, 5, 5, TimeUnit.MINUTES);
        } else {
            LOGGER.info("[CraftyAI] Telemetry disabled. Status reporting is inactive.");
        }

        // Clean up telemetry scheduled heartbeat thread on server stop
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            scheduler.shutdownNow();
            LOGGER.info("[CraftyAI] Telemetry heartbeat scheduler shut down.");
        });

        // Check for updates
        UpdateChecker.checkForUpdatesAsync(LOGGER::info);

        LOGGER.info("[CraftyAI] Neural Engine Online — Fabric Mod v" + GatewayRequestHeaders.MOD_VERSION);
    }

    public void reloadConfig() {
        loadConfig();
        try {
            CraftyAIModClient.reloadConfig();
            if (loadedConfig != null) CraftyAIModClient.setTierFromServer(loadedConfig.tier);
        } catch (NoClassDefFoundError ignored) { /* dedicated server — no client module */ }
        LOGGER.info("[CraftyAI] Configuration reloaded from disk.");
    }

    private void loadConfig() {
        String previousTier = loadedConfig != null ? loadedConfig.tier : "free";
        loadedConfig = ConfigLoader.loadOrCreateConfig("config", "craftyai.json", LOGGER::info);
        if (previousTier != null && !"free".equals(previousTier)) {
            loadedConfig.tier = previousTier;
        }
        this.aiName = loadedConfig.ai_name;
        this.apiKey = loadedConfig.api_key;
        this.serverId = com.demonz.craftyai.common.SessionManager.getSessionId(
                net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir());
        // If config has a saved server_id that differs from session (e.g. session file deleted),
        // restore the config's server_id so API key stays matched to its server
        if (loadedConfig.server_id != null && !loadedConfig.server_id.isEmpty()
                && !loadedConfig.server_id.equals(this.serverId)) {
            com.demonz.craftyai.common.SessionManager.setSessionId(
                    net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir(), loadedConfig.server_id);
            this.serverId = loadedConfig.server_id;
        }
        // Use custom provider URL if enabled, otherwise gateway URL
        if (loadedConfig.custom_provider_enabled && loadedConfig.custom_provider_url != null && !loadedConfig.custom_provider_url.isEmpty()) {
            this.gatewayUrl = loadedConfig.custom_provider_url;
        } else {
            this.gatewayUrl = GatewayRequestHeaders.getGatewayUrl();
        }
        this.opWelcomeEnabled = loadedConfig.op_welcome_message;
        this.telemetryEnabled = loadedConfig.telemetry_enabled;
        this.prefix = loadedConfig.prefix != null ? loadedConfig.prefix : "@";
        this.requirePrefix = loadedConfig.require_prefix;
        if (loadedConfig.cooldown_seconds > 0) {
            this.cooldownMs = loadedConfig.cooldown_seconds * 1000;
        }
        if (loadedConfig.aliases != null && loadedConfig.aliases.length > 0) {
            this.aliases = Arrays.asList(loadedConfig.aliases);
        }
        // Initialize security helpers (audit log, rate limiter, confirmation flow)
        String configDir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().toString();
        if (this.auditLog == null) {
            this.auditLog = new ActionAuditLog(configDir, LOGGER::info);
        }
        if (this.actionRateLimiter == null) {
            this.actionRateLimiter = new ActionRateLimiter();
        }
        if (this.actionConfirmations == null) {
            this.actionConfirmations = new ActionConfirmation();
        }
    }

    private void initializeLocalBrain() {
        localBrain = new LocalBrain("config", LOGGER::info);
    }

    private void performStartupHandshake() {
        // Skip handshake if custom provider is enabled
        if (loadedConfig.custom_provider_enabled) {
            LOGGER.info("[CraftyAI] Custom provider enabled — skipping gateway handshake.");
            return;
        }

        if (com.demonz.craftyai.common.CraftyAIConfig.needsAutoMint(apiKey, loadedConfig.custom_provider_enabled)) {
            LOGGER.info("[CraftyAI] API key unconfigured or in legacy format. Automatically minting a new key...");
            autoMintApiKey();
            return;
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("version", GatewayRequestHeaders.MOD_VERSION);
        payload.put("client_type", CLIENT_TYPE);
        payload.put("server_id", serverId);
        payload.put("protocol_version", 2);
        payload.put("capabilities", Arrays.asList("control_plane_v2", "safe_config", "safe_commands", "command_ack"));
        payload.put("software_name", "fabric-26");
        payload.put("config_revision", loadedConfig.control_revision);

        HttpRequest request = GatewayHttpClientHelper.apply(HttpRequest.newBuilder()
                .uri(URI.create(gatewayUrl + "/v1/handshake"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload))), CLIENT_TYPE, serverId)
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() == 200) {
                        updateTierFromResponse(response.body());
                        deliverWarningsFromHandshake(response.body());
                        processControlPlane(response.body());
                    }
                })
                .exceptionally(e -> {
                    LOGGER.warn("[CraftyAI] Startup handshake connection failed: {}", e.getMessage());
                    return null;
                });
    }

    private void processControlPlane(String responseBody) {
        final ControlPlaneProcessor.Result result = ControlPlaneProcessor.process(responseBody, loadedConfig, new ControlPlaneProcessor.Handler() {
            @Override
            public void showMessage(final String title, final String message, final String severity) {
                final MinecraftServer server = serverInstance;
                String color = "critical".equals(severity) ? "\u00A7c\u00A7l" : "warning".equals(severity) ? "\u00A7e\u00A7l" : "\u00A7b\u00A7l";
                LOGGER.info("[Control] {}: {}", title, message);
                if (server != null) {
                    server.execute(() -> {
                        Component text = Component.literal(color + "[" + title + "] \u00A77" + message);
                        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                            if (isOp(player)) player.sendSystemMessage(text);
                        }
                    });
                }
            }

            @Override
            public boolean persistConfiguration(CraftyAIConfig config) {
                return ConfigLoader.saveConfig("config", "craftyai.json", config, LOGGER::info);
            }

            @Override
            public void reloadConfiguration() {
                CraftyAIMod.this.reloadConfig();
            }

            @Override
            public void log(String message) {
                LOGGER.warn(message);
            }
        });
        if (result.hasAcknowledgements()) sendControlAck(result);
    }

    private void sendControlAck(ControlPlaneProcessor.Result result) {
        HttpRequest request = GatewayHttpClientHelper.apply(HttpRequest.newBuilder()
                .uri(URI.create(GatewayRequestHeaders.getGatewayUrl() + "/v1/control/ack"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(result.toAckJson(serverId))), CLIENT_TYPE, serverId)
                .build();
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .thenAccept(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        LOGGER.warn("[Control] Acknowledgement rejected with HTTP {}", response.statusCode());
                    }
                })
                .exceptionally(error -> {
                    LOGGER.warn("[Control] Acknowledgement failed: {}", error.getMessage());
                    return null;
                });
    }

    private void autoMintApiKey() {
        Map<String, String> payload = new HashMap<>();
        String serverName = "Fabric Server";
        if (serverInstance != null) {
            if (serverInstance.getMotd() != null && !serverInstance.getMotd().isEmpty()) {
                serverName = serverInstance.getMotd().replaceAll("§[0-9a-fk-or]", "").trim();
                if (serverName.isEmpty()) serverName = "Fabric Server";
            }
        }
        payload.put("name", serverName);
        payload.put("version", GatewayRequestHeaders.MOD_VERSION);
        payload.put("client_type", CLIENT_TYPE);

        HttpRequest request = GatewayHttpClientHelper.apply(HttpRequest.newBuilder()
                .uri(URI.create(gatewayUrl + "/v1/handshake-no-key"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload))), CLIENT_TYPE, serverId)
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() == 200) {
                        try {
                            com.google.gson.JsonObject json = com.demonz.craftyai.common.JsonParserAdapter.parse(response.body()).getAsJsonObject();
                            String newKey = json.has("api_key") && !json.get("api_key").isJsonNull() ? json.get("api_key").getAsString() : "";
                            String newServerId = json.has("server_id") && !json.get("server_id").isJsonNull() ? json.get("server_id").getAsString() : "";
                            String newTier = json.has("tier") && !json.get("tier").isJsonNull() ? json.get("tier").getAsString() : "free";
                            if (newKey.isEmpty() || !newKey.startsWith("cai_")) {
                                LOGGER.warn("[CraftyAI] Auto-mint returned no key.");
                                return;
                            }
                            loadedConfig.api_key = newKey;
                            loadedConfig.server_id = newServerId;
                            ConfigLoader.saveConfig("config", "craftyai.json", loadedConfig, LOGGER::info);
                            this.apiKey = newKey;
                            if (newServerId != null && !newServerId.isEmpty()) {
                                this.serverId = newServerId;
                                com.demonz.craftyai.common.SessionManager.setSessionId(
                                    net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir(), newServerId);
                            }
                            loadedConfig.tier = newTier;
                            LOGGER.info("=========================================");
                            LOGGER.info("[CraftyAI] NEW API KEY (save now, never shown again):");
                            LOGGER.info("  {}", newKey);
                            LOGGER.info("  Tier: {}", newTier);
                            LOGGER.info("  Server ID: {}", newServerId);
                            LOGGER.info("=========================================");
                            // Sync client module's config so chat commands pick up the new key
                            try {
                                CraftyAIModClient.reloadConfig();
                                CraftyAIModClient.setTierFromServer(newTier);
                            } catch (NoClassDefFoundError ignored) { /* dedicated server — no client module */ }
                            performStartupHandshake();
                        } catch (Exception e) {
                            LOGGER.warn("[CraftyAI] Auto-mint parse failed: {}", e.getMessage());
                        }
                    } else {
                        LOGGER.warn("[CraftyAI] Auto-mint failed (HTTP {})", response.statusCode());
                    }
                })
                .exceptionally(e -> {
                    LOGGER.warn("[CraftyAI] Auto-mint connection failed: {}", e.getMessage());
                    return null;
                });
    }

    private void deliverWarningsFromHandshake(String responseBody) {
        try {
            com.google.gson.JsonObject json = com.demonz.craftyai.common.JsonParserAdapter.parse(responseBody).getAsJsonObject();
            if (!json.has("warnings")) return;
            com.google.gson.JsonArray warnings = json.getAsJsonArray("warnings");
            if (warnings.size() == 0) return;

            for (com.google.gson.JsonElement warnEl : warnings) {
                if (!warnEl.isJsonObject()) continue;
                String message = warnEl.getAsJsonObject().has("message") ? warnEl.getAsJsonObject().get("message").getAsString() : "Unknown warning";
                LOGGER.warn("[CraftyAI] Session Warning: " + message.replaceAll("§.", ""));

                // Broadcast warning to all OP players on the server
                if (serverInstance != null) {
                    final String warnMessage = message;
                    serverInstance.execute(() -> {
                        for (ServerPlayer player : serverInstance.getPlayerList().getPlayers()) {
                            if (isOp(player)) {
                                player.sendSystemMessage(Component.literal("\u00A7c\u00A7l[CraftyAI Warning] \u00A77" + warnMessage));
                            }
                        }
                    });
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[CraftyAI] Warning parse error: {}", e.getMessage());
        }
    }

    private void registerOpWelcome() {
        // Clean up player conversation cache on disconnect
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            conversationCache.remove(handler.getPlayer().getUUID());
        });

        if (!opWelcomeEnabled) return;
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (!server.isDedicatedServer()) return;
            ServerPlayer player = handler.getPlayer();
            if (isOp(player)) {
                server.execute(() -> {
                    player.sendSystemMessage(Component.literal(""));
                    player.sendSystemMessage(Component.literal("\u00A7b\u00A7l\u2726 CraftyAI \u00A77v" + GatewayRequestHeaders.MOD_VERSION + " \u00A78\u2014 \u00A77Neural AI Companion"));
                    player.sendSystemMessage(Component.literal("\u00A77  Thanks for using CraftyAI by \u00A7bDemonZ Development\u00A77!"));
                    player.sendSystemMessage(Component.literal("\u00A77  Type \u00A7e@" + aiName.toLowerCase() + " <question> \u00A77to chat."));
                    player.sendSystemMessage(Component.literal(""));
                });
            }
        });
    }

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("crafty")
                    .then(Commands.literal("help").executes(context -> {
                        context.getSource().sendSuccess(() -> Component.literal("§b§l§m------------§r §6crafty ai v" + GatewayRequestHeaders.MOD_VERSION + " §b§l§m------------"), false);
                        context.getSource().sendSuccess(() -> Component.literal("§7Neural AI companion by §bDemonZ Development§7."), false);
                        context.getSource().sendSuccess(() -> Component.literal("§7Type §e@" + aiName.toLowerCase() + " <question> §7in chat, or use these commands:"), false);
                        context.getSource().sendSuccess(() -> Component.literal(""), false);
                        context.getSource().sendSuccess(() -> Component.literal("§e/crafty status   §8- §7Show plugin status and tier info"), false);
                        context.getSource().sendSuccess(() -> Component.literal("§e/crafty ask <q>  §8- §7Ask crafty ai privately"), false);
                        context.getSource().sendSuccess(() -> Component.literal("§e/crafty scan     §8- §7Vision scan nearby area"), false);
                        context.getSource().sendSuccess(() -> Component.literal("§e/crafty confirm  §8- §7Confirm a pending destructive action"), false);
                        context.getSource().sendSuccess(() -> Component.literal("§e/crafty pro      §8- §7Pro tier management"), false);
                        context.getSource().sendSuccess(() -> Component.literal("§e/crafty link     §8- §7Link this server to Discord"), false);
                        context.getSource().sendSuccess(() -> Component.literal("§e/crafty reload   §8- §7Reload configuration (admin)"), false);
                        return 1;
                    }))
                    .then(Commands.literal("status").executes(context -> {
                        context.getSource().sendSuccess(() -> Component.literal("§b[CraftyAI] Status: §aOnline"), false);
                        return 1;
                    }))
                    // /crafty ask <question> — PRIVATE response to the executing player only
                    .then(Commands.literal("ask")
                            .requires(source -> {
                                try { return isOp(source.getPlayerOrException()); }
                                catch (Exception e) { return false; }
                            })
                            .then(Commands.argument("question", StringArgumentType.greedyString())
                                    .executes(context -> {
                                        ServerPlayer player = context.getSource().getPlayerOrException();
                                        String question = StringArgumentType.getString(context, "question");
                                        handleAskCommand(player, question);
                                        return 1;
                                    })
                            )
                    )
                    // /crafty scan — vision scan of what the player is looking at
                    .then(Commands.literal("scan")
                            .requires(source -> {
                                try { return isOp(source.getPlayerOrException()); }
                                catch (Exception e) { return false; }
                            })
                            .executes(context -> {
                                ServerPlayer player = context.getSource().getPlayerOrException();
                                handleScanCommand(player);
                                return 1;
                            }))
                    // /crafty reload — reload config
                    .then(Commands.literal("reload")
                            .executes(context -> {
                                net.minecraft.world.entity.Entity entity = context.getSource().getEntity();
                                if (entity instanceof ServerPlayer) {
                                    if (!isOp((ServerPlayer) entity)) {
                                        context.getSource().sendFailure(Component.literal("§c[CraftyAI] Only operators can reload config."));
                                        return 0;
                                    }
                                }
                                loadConfig();
                                context.getSource().sendSuccess(() -> Component.literal("§a[CraftyAI] Configuration reloaded successfully."), true);
                                return 1;
                            })
                    )
                    // /crafty confirm — confirm a pending destructive action
                    .then(Commands.literal("confirm")
                            .executes(context -> {
                                ServerPlayer player = context.getSource().getPlayerOrException();
                                if (!isOp(player)) {
                                    player.sendSystemMessage(Component.literal("§c[CraftyAI] Only operators can confirm actions."));
                                    return 0;
                                }
                                String playerKey = player.getUUID().toString();
                                if (actionConfirmations == null) {
                                    player.sendSystemMessage(Component.literal("§c[CraftyAI] Confirmation system unavailable."));
                                    return 0;
                                }
                                String pending = actionConfirmations.confirm(playerKey);
                                if (pending == null) {
                                    player.sendSystemMessage(Component.literal("§7[CraftyAI] No destructive action pending. Re-run your request first."));
                                } else {
                                    player.sendSystemMessage(Component.literal("§a[CraftyAI] Confirmed and executing: §f" + pending.toUpperCase()));
                                    handleAction(player, pending);
                                }
                                return 1;
                            })
                    )
                    // /crafty pro <status|help> — show Pro tier info
                    .then(Commands.literal("pro")
                            .executes(context -> {
                                String tierStr = (loadedConfig != null && loadedConfig.tier != null && !loadedConfig.tier.isEmpty()) ? loadedConfig.tier : "free";
                                String tierUpper = tierStr.toUpperCase();
                                String tierColor = tierStr.equalsIgnoreCase("pro") ? "§6"
                                        : tierStr.equalsIgnoreCase("enterprise") ? "§d" : "§f";
                                ServerPlayer player = null;
                                try { player = context.getSource().getPlayerOrException(); } catch (Exception ignored) {}
                                if (player != null) {
                                    player.sendSystemMessage(Component.literal("§b[CraftyAI] Pro Status:"));
                                    player.sendSystemMessage(Component.literal("§7  Tier: " + tierColor + tierUpper));
                                    player.sendSystemMessage(Component.literal("§7  To upgrade: open a Pro ticket on Discord"));
                                    player.sendSystemMessage(Component.literal("§7  §bhttps://discord.gg/zCkE44hsBR"));
                                } else {
                                    context.getSource().sendSuccess(() -> Component.literal("§b[CraftyAI] Tier: " + tierColor + tierUpper), false);
                                }
                                return 1;
                            })
                            .then(Commands.literal("status")
                                    .executes(context -> {
                                        String tierStr = (loadedConfig != null && loadedConfig.tier != null && !loadedConfig.tier.isEmpty()) ? loadedConfig.tier : "free";
                                        String tierUpper = tierStr.toUpperCase();
                                        String tierColor = tierStr.equalsIgnoreCase("pro") ? "§6"
                                                : tierStr.equalsIgnoreCase("enterprise") ? "§d" : "§f";
                                        ServerPlayer player = null;
                                        try { player = context.getSource().getPlayerOrException(); } catch (Exception ignored) {}
                                        if (player != null) {
                                            player.sendSystemMessage(Component.literal("§b[CraftyAI] Pro Status:"));
                                            player.sendSystemMessage(Component.literal("§7  Tier: " + tierColor + tierUpper));
                                        }
                                        return 1;
                                    })
                            )
                            .then(Commands.literal("help")
                                    .executes(context -> {
                                        ServerPlayer player = null;
                                        try { player = context.getSource().getPlayerOrException(); } catch (Exception ignored) {}
                                        if (player != null) {
                                            player.sendSystemMessage(Component.literal("§b[CraftyAI] How to get Pro:"));
                                            player.sendSystemMessage(Component.literal("§7  1. Join our Discord: §bhttps://discord.gg/zCkE44hsBR"));
                                            player.sendSystemMessage(Component.literal("§7  2. Open a ticket in #pro-requests"));
                                            player.sendSystemMessage(Component.literal("§7  3. An admin will review and DM you a Pro key"));
                                            player.sendSystemMessage(Component.literal("§7  4. Run: §f/crafty pro redeem <key>"));
                                        }
                                        return 1;
                                    })
                            )
                            .then(Commands.literal("redeem")
                                    .then(Commands.argument("key", StringArgumentType.greedyString())
                                            .executes(context -> {
                                                ServerPlayer player = null;
                                                try { player = context.getSource().getPlayerOrException(); } catch (Exception ignored) {}
                                                if (player == null) return 0;
                                                if (!isOp(player)) {
                                                    player.sendSystemMessage(Component.literal("§c[CraftyAI] Only operators can redeem Pro keys."));
                                                    return 0;
                                                }
                                                String proKey = StringArgumentType.getString(context, "key").trim();
                                                String lowerKey = proKey.toLowerCase();
                                                if (!lowerKey.startsWith("cai_pro_") && !lowerKey.startsWith("cai_pro-")) {
                                                    player.sendSystemMessage(Component.literal("§c[CraftyAI] Invalid Pro key format."));
                                                    return 0;
                                                }
                                                player.sendSystemMessage(Component.literal("§b[CraftyAI] Redeeming Pro key..."));
                                                redeemProKey(player, proKey);
                                                return 1;
                                            })
                                    )
                            )
                    )
                    // /crafty link — generate Discord link code
                    .then(Commands.literal("link")
                            .requires(source -> {
                                if (!source.isPlayer()) return false;
                                try {
                                    ServerPlayer p = source.getPlayerOrException();
                                    return p.level().getServer().getPlayerList().isOp(p.nameAndId());
                                } catch (Exception e) { return false; }
                            })
                            .executes(context -> {
                                ServerPlayer player = null;
                                try { player = context.getSource().getPlayerOrException(); } catch (Exception ignored) {}
                                if (player == null) {
                                    context.getSource().sendFailure(Component.literal("§cOnly players can execute this command."));
                                    return 0;
                                }
                                handleLinkCommand(player);
                                return 1;
                            })
                    )
            );
        });
    }

    /**
     * Handle /crafty ask — response is PRIVATE to the executing player only.
     */
    private void handleAskCommand(ServerPlayer player, String question) {
        if (isOnCooldown(player)) {
            player.sendSystemMessage(Component.literal("\u00A7c[CraftyAI] Please wait before asking again."));
            return;
        }

        VisionScanner.ScanResult scanResult = VisionScanner.scan(player, (ServerLevel) player.level());
        String context = scanResult.toContextString();

        LinkedList<Map<String, String>> history = conversationCache.computeIfAbsent(player.getUUID(), k -> new LinkedList<>());
        List<Map<String, String>> historyCopy;
        synchronized (history) {
            historyCopy = new ArrayList<>(history);
        }

        if (isCircuitOpen() || (loadedConfig != null && loadedConfig.force_local_mode)) {
            String offlineResponse = localBrain.generateOfflineResponse(question, player.getName().getString());
            player.sendSystemMessage(Component.literal("\u00A7b[" + aiName + "] \u00A77(Offline) \u00A7f" + offlineResponse));
            return;
        }

        player.sendSystemMessage(Component.literal("\u00A7b\u00A7l" + aiName.toUpperCase() + " IS THINKING..."), true);

        CompletableFuture.supplyAsync(() -> sendWithRetry(question, player.getName().getString(), context, historyCopy, 0))
                .thenAccept(responseBody -> {
                    player.level().getServer().execute(() -> {
                        player.sendSystemMessage(Component.literal(""), true);
                        if (responseBody != null && responseBody.startsWith("__ERROR__:")) {
                            String errorMsg = responseBody.substring("__ERROR__:".length());
                            // PRIVATE: only send to the player who ran the command
                            player.sendSystemMessage(Component.literal("\u00A7c[CraftyAI] " + errorMsg));
                        } else if (responseBody != null) {
                            updateTierFromResponse(responseBody);
                            NeuralResponse res;
                            try {
                                res = GSON.fromJson(responseBody, NeuralResponse.class);
                            } catch (Exception parseEx) {
                                LOGGER.error("[CraftyAI] Failed to parse API response: {}", parseEx.getMessage());
                                player.sendSystemMessage(Component.literal("\u00A7c[CraftyAI] Failed to parse AI response."));
                                return;
                            }
                            String answer = res != null ? res.getAnswer() : null;
                            if (answer != null && !answer.isEmpty()) {
                                // PRIVATE: only send to the player who ran the command
                                player.sendSystemMessage(Component.literal("\u00A78[Private] \u00A7b[" + aiName + "] \u00A77> \u00A7f" + answer));
                                addToHistory(player.getUUID(), question, answer);
                            }
                            String action = res != null ? res.getAction() : null;
                            if (action == null || action.isEmpty() || "null".equalsIgnoreCase(action)) {
                                action = inferActionFromText(answer, question);
                            } else if (action.toUpperCase().startsWith("SCHEDULE_TASK:") && question != null && question.toLowerCase().matches(".*\\b(in|after|wait)\\s+\\d+\\s*(seconds?|sec|minutes?|min)\\b.*")) {
                                action = inferActionFromText(answer, question);
                            }
                            if (action != null) {
                                handleAction(player, action);
                            }
                            resetCircuit();
                        } else {
                            String offlineResponse = localBrain.generateOfflineResponse(question, player.getName().getString());
                            player.sendSystemMessage(Component.literal("\u00A7b[" + aiName + "] \u00A77(Offline) \u00A7f" + offlineResponse));
                        }
                    });
                });
    }

    /**
     * Handle /crafty scan — vision scan command.
     */
    private void handleScanCommand(ServerPlayer player) {
        VisionScanner.ScanResult scanResult = VisionScanner.scan(player, (ServerLevel) player.level());
        String context = scanResult.toContextString();
        player.sendSystemMessage(Component.literal("\u00A7b[CraftyAI] \u00A77Vision Scan Results:"));
        // Send a condensed version
        String[] lines = context.split("\n");
        for (String line : lines) {
            if (!line.isEmpty()) {
                player.sendSystemMessage(Component.literal("\u00A77  " + line));
            }
        }
    }

    private void registerChatListener() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            serverInstance = server;
        });
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            String rawText = message.signedContent().trim();
            String visibility = loadedConfig.response_visibility != null ? loadedConfig.response_visibility : "default";
            boolean isPrivateVal = visibility.equalsIgnoreCase("always-private") ||
                    (visibility.equalsIgnoreCase("default") && rawText.startsWith(prefix));
            if (visibility.equalsIgnoreCase("always-public")) {
                isPrivateVal = false;
            }
            final boolean isPrivate = isPrivateVal;
            String question = extractQuestion(message.signedContent());
            if (question == null) return true;

            if (isOnCooldown(sender)) {
                sender.sendSystemMessage(Component.literal("§c[CraftyAI] Please wait before asking again."));
                return false;
            }

            if (isPrivate) {
                // Send the message locally to the player so they see it in their chat log
                sender.sendSystemMessage(Component.literal("<" + sender.getName().getString() + "> " + rawText));
            } else {
                broadcastToAll(Component.literal("<" + sender.getName().getString() + "> " + rawText));
            }

            processAIChatRequest(sender, question, isPrivate);
            return false;
        });
    }

    private void processAIChatRequest(ServerPlayer sender, String question, boolean isPrivate) {
        VisionScanner.ScanResult scanResult = VisionScanner.scan(sender, (ServerLevel) sender.level());
        String context = scanResult.toContextString();

        LinkedList<Map<String, String>> history = conversationCache.computeIfAbsent(sender.getUUID(), k -> new LinkedList<>());
        List<Map<String, String>> historyCopy;
        synchronized (history) {
            historyCopy = new ArrayList<>(history);
        }

        if (isCircuitOpen() || (loadedConfig != null && loadedConfig.force_local_mode)) {
            String offlineResponse = localBrain.generateOfflineResponse(question, sender.getName().getString());
            if (isPrivate) {
                sender.sendSystemMessage(Component.literal("\u00A7b[" + aiName + "] \u00A77(Offline) \u00A7f" + offlineResponse));
            } else {
                broadcastToAll(Component.literal("\u00A7b[" + aiName + "] \u00A77(Offline) \u00A7f" + offlineResponse));
            }
            return;
        }

        sender.sendSystemMessage(Component.literal("\u00A7b\u00A7l" + aiName.toUpperCase() + " IS THINKING..."), true);

        CompletableFuture.supplyAsync(() -> sendWithRetry(question, sender.getName().getString(), context, historyCopy, 0))
                .thenAccept(responseBody -> {
                    sender.level().getServer().execute(() -> {
                            sender.sendSystemMessage(Component.literal(""), true);
                            if (responseBody != null && responseBody.startsWith("__ERROR__:")) {
                                String errorMsg = responseBody.substring("__ERROR__:".length());
                                sender.sendSystemMessage(Component.literal("\u00A7c[CraftyAI] " + errorMsg));
                                String offlineResponse = localBrain.generateOfflineResponse(question, sender.getName().getString());
                                if (isPrivate) {
                                    sender.sendSystemMessage(Component.literal("\u00A7b[" + aiName + "] \u00A77(Fallback) \u00A7f" + offlineResponse));
                                } else {
                                    broadcastToAll(Component.literal("\u00A7b[" + aiName + "] \u00A77(Fallback) \u00A7f" + offlineResponse));
                                }
                            } else if (responseBody != null) {
                                updateTierFromResponse(responseBody);
                                NeuralResponse res;
                                try {
                                    res = GSON.fromJson(responseBody, NeuralResponse.class);
                                } catch (Exception parseEx) {
                                    LOGGER.error("[CraftyAI] Failed to parse API response: {}", parseEx.getMessage());
                                    sender.sendSystemMessage(Component.literal("\u00A7c[CraftyAI] Failed to parse AI response."));
                                    return;
                                }
                                String answer = res != null ? res.getAnswer() : null;
                                if (answer != null && !answer.isEmpty()) {
                                    if (isPrivate) {
                                        sender.sendSystemMessage(Component.literal("\u00A78[Private] \u00A7b[" + aiName + "] \u00A77> \u00A7f" + answer));
                                    } else {
                                        broadcastToAll(Component.literal("\u00A7b[" + aiName + "] \u00A77> \u00A7f" + answer));
                                    }
                                    addToHistory(sender.getUUID(), question, answer);
                                }
                                String action = res != null ? res.getAction() : null;
                                if (action == null || action.isEmpty() || "null".equalsIgnoreCase(action)) {
                                    action = inferActionFromText(answer, question);
                                } else if (action.toUpperCase().startsWith("SCHEDULE_TASK:") && question != null && question.toLowerCase().matches(".*\\b(in|after|wait)\\s+\\d+\\s*(seconds?|sec|minutes?|min)\\b.*")) {
                                    action = inferActionFromText(answer, question);
                                }
                                if (action != null) {
                                    handleAction(sender, action);
                                }
                                resetCircuit();
                            } else {
                                String offlineResponse = localBrain.generateOfflineResponse(question, sender.getName().getString());
                                if (isPrivate) {
                                    sender.sendSystemMessage(Component.literal("\u00A7b[" + aiName + "] \u00A77(Offline) \u00A7f" + offlineResponse));
                                } else {
                                    broadcastToAll(Component.literal("\u00A7b[" + aiName + "] \u00A77(Offline) \u00A7f" + offlineResponse));
                                }
                            }
                    });
                });
    }

    /**
     * Broadcast a message to ALL players on the server.
     */
    private void broadcastToAll(Component message) {
        if (serverInstance == null) return;
        for (ServerPlayer player : serverInstance.getPlayerList().getPlayers()) {
            player.sendSystemMessage(message);
        }
    }

    /**
     * v1.2.0-beta: Redeem a Pro key via /v1/pro/redeem
     */
    private void redeemProKey(ServerPlayer player, String proKey) {
        try {
            String sessionId = this.serverId != null ? this.serverId : "unknown";
            java.net.URI uri = java.net.URI.create(com.demonz.craftyai.common.GatewayRequestHeaders.getGatewayUrl() + "/v1/pro/redeem");
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-Client-Type", "minecraft-fabric-26");
            conn.setRequestProperty("X-CraftyAI-Version", com.demonz.craftyai.common.GatewayRequestHeaders.MOD_VERSION);
            conn.setDoOutput(true);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            String body = "{\"key\":\"" + escapeJson(proKey) + "\",\"server_id\":\"" + escapeJson(sessionId) + "\"}";
            try (java.io.OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            String respBody;
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(
                    code >= 400 ? conn.getErrorStream() : conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                respBody = sb.toString();
            } finally {
                conn.disconnect();
            }
            if (code == 200) {
                LOGGER.info("[CraftyAI] Pro key redeemed for session {}", sessionId);
                player.sendSystemMessage(Component.literal("§a[CraftyAI] Pro key redeemed successfully!"));
                performStartupHandshake();
            } else {
                String err = respBody.length() > 200 ? respBody.substring(0, 200) : respBody;
                player.sendSystemMessage(Component.literal("§c[CraftyAI] Redeem failed (HTTP " + code + "): " + err));
            }
        } catch (Throwable t) {
            LOGGER.warn("[CraftyAI] Pro redeem error: {} — {}", t.getClass().getSimpleName(), t.getMessage());
            player.sendSystemMessage(Component.literal("§c[CraftyAI] Redeem error: " + t.getMessage()));
        }
    }

    private void handleAction(ServerPlayer player, String actionString) {
        if (actionString == null || actionString.trim().isEmpty() || actionString.equalsIgnoreCase("null")) return;
        // Wrap in try-catch so a single failed action doesn't kill the executor.
        try {
            handleActionInternal(player, actionString);
        } catch (Throwable t) {
            try {
                LOGGER.warn("[CraftyAI] Action handler error for {} (action='{}'): {} — {}",
                        player.getName().getString(), actionString,
                        t.getClass().getSimpleName(), t.getMessage());
                player.sendSystemMessage(Component.literal("§c[CraftyAI] Action failed: " + t.getMessage()));
            } catch (Throwable ignored) {}
        }
    }

    private static String escapeJson(String s) {
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

    private void handleActionInternal(ServerPlayer player, String actionString) {

        // Agentic tasks are FREE for all tiers.
        // No tier check. Only config + OP permission gates remain.

        // Check local configuration
        if (!loadedConfig.agentic_tasks_enabled || !loadedConfig.ai_enable_actions) {
            player.sendSystemMessage(Component.literal("§8[CraftyAI] §7Action suggested: §e" + actionString + " §8(agentic tasks disabled in config)"));
            LOGGER.info("[CraftyAI] Skipped agentic action {} — agentic tasks disabled", actionString);
            return;
        }

        if (!isOp(player)) {
            player.sendSystemMessage(Component.literal("§c[CraftyAI] Missing permission: crafty.agentic — action not executed"));
            return;
        }

        // Per-player rate limit (5 actions per 60s by default)
        String playerKey = player.getUUID().toString();
        if (actionRateLimiter != null && !actionRateLimiter.tryAcquire(playerKey)) {
            long sec = actionRateLimiter.secondsUntilReset(playerKey);
            player.sendSystemMessage(Component.literal("§c[CraftyAI] Rate limit: try again in " + sec + "s"));
            return;
        }

        String[] actions = actionString.split("\\|");
        int actionLimit = Math.min(actions.length, MAX_ACTIONS_PER_REQUEST);
        for (int actionIdx = 0; actionIdx < actionLimit; actionIdx++) {
            String action = actions[actionIdx];
            String upper = action.toUpperCase().trim();
            if (upper.isEmpty()) continue;

            // Per-action permission check (centralized via AgenticActions).
            // On Fabric 1.26, per-action perms map to OP level (no granular perm mod bundled).
            String perm = AgenticActions.permissionFor(action);
            if (perm != null) {
                if (!isOp(player)) {
                    player.sendSystemMessage(Component.literal("§c[CraftyAI] Missing permission: " + perm));
                    if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, false, "missing permission " + perm);
                    continue;
                }
            }

            // Destructive action confirmation flow
            AgenticActions.Risk risk = AgenticActions.riskFor(action);
            if (risk == AgenticActions.Risk.DESTRUCTIVE && (loadedConfig == null || loadedConfig.require_confirmation)) {
                String pending = actionConfirmations != null ? actionConfirmations.confirm(playerKey) : null;
                if (pending == null || !pending.equalsIgnoreCase(upper)) {
                    if (actionConfirmations != null) actionConfirmations.request(playerKey, action);
                    player.sendSystemMessage(Component.literal("§c⚠ Destructive action: §f" + upper + " §c— run §e/crafty confirm §cwithin 30s to execute."));
                    if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, false, "awaiting confirmation");
                    continue;
                }
                // Confirmed; fall through to execution
            }

            // --- ATOMIC ACTIONS ---
            switch (upper) {
                case "TIME_DAY":
                    runServerCommand(player, "time set day");
                    player.sendSystemMessage(Component.literal("§eTime set to day."));
                    if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                    continue;
                case "TIME_NIGHT":
                    runServerCommand(player, "time set night");
                    player.sendSystemMessage(Component.literal("§9Time set to night."));
                    if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                    continue;
                case "WEATHER_CLEAR":
                    runServerCommand(player, "weather clear");
                    player.sendSystemMessage(Component.literal("§bWeather cleared."));
                    if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                    continue;
                case "WEATHER_RAIN":
                    runServerCommand(player, "weather rain");
                    player.sendSystemMessage(Component.literal("§9Weather set to rain."));
                    if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                    continue;
                case "WEATHER_THUNDER":
                    runServerCommand(player, "weather thunder");
                    player.sendSystemMessage(Component.literal("§c⚡ Thunderstorm activated"));
                    if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                    continue;
                case "HEAL":
                    runServerCommand(player, "effect give @s minecraft:instant_health 1 255");
                    player.sendSystemMessage(Component.literal("§aHealed!"));
                    if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                    continue;
                case "FEED":
                    runServerCommand(player, "effect give @s minecraft:saturation 1 255");
                    player.sendSystemMessage(Component.literal("§6Fully fed!"));
                    if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                    continue;
                case "KILL_MOBS":
                    runServerCommand(player, "kill @e[type=!player,distance=..50,type=!item,type=!xp_orb]");
                    player.sendSystemMessage(Component.literal("§c☠ Nearby mobs eliminated"));
                    if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                    continue;
                case "TELEPORT_SPAWN":
                    runServerCommand(player, "tp @s 0 64 0");
                    player.sendSystemMessage(Component.literal("§d✨ Teleporting to spawn..."));
                    if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                    continue;
                case "GAMEMODE_CREATIVE":
                    runServerCommand(player, "gamemode creative");
                    player.sendSystemMessage(Component.literal("§bGamemode updated to Creative."));
                    if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                    continue;
                case "GAMEMODE_SURVIVAL":
                    runServerCommand(player, "gamemode survival");
                    player.sendSystemMessage(Component.literal("§bGamemode updated to Survival."));
                    if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                    continue;
                case "GAMEMODE_SPECTATOR":
                    runServerCommand(player, "gamemode spectator");
                    player.sendSystemMessage(Component.literal("§bGamemode updated to Spectator."));
                    if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                    continue;
            }

            // --- PARAMETERIZED ACTIONS ---
            try {
                if (upper.startsWith("GIVE:")) {
                    String[] parts = action.split(":", 3);
                    if (parts.length >= 2) {
                        String itemId = parts[1].toLowerCase(Locale.ROOT).trim();
                        if (GIVE_BLACKLIST.contains(itemId)) {
                            player.sendSystemMessage(Component.literal("§c[CraftyAI] Cannot give blacklisted item: " + itemId));
                            LOGGER.warn("[CraftyAI] Blocked GIVE action for blacklisted item: {}", itemId);
                            if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, false, "blacklisted item");
                            continue;
                        }
                        try {
                            Identifier id = Identifier.withDefaultNamespace(itemId);
                            if (net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(id)) {
                                int amount = parts.length >= 3 ? Integer.parseInt(parts[2].trim()) : 1;
                                amount = Math.max(1, Math.min(64, amount));
                                runServerCommand(player, "give @s " + id + " " + amount);
                                player.sendSystemMessage(Component.literal("§aReceived " + amount + "x " + parts[1]));
                            }
                        } catch (Exception e) {
                            LOGGER.warn("[CraftyAI] Failed to process give command: {}", e.getMessage());
                        }
                        if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                    }
                } else if (upper.startsWith("EFFECT:")) {
                    String[] parts = action.split(":", 3);
                    if (parts.length >= 2) {
                        String effect = parts[1].toLowerCase(Locale.ROOT).trim();
                        int duration = 60;
                        try {
                            if (parts.length >= 3) duration = Math.max(1, Math.min(600, Integer.parseInt(parts[2].trim())));
                        } catch (NumberFormatException ignored) {}
                        runServerCommand(player, "effect give @s minecraft:" + effect + " " + duration);
                        player.sendSystemMessage(Component.literal("§d✨ Applied " + effect + " for " + duration + "s"));
                        if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                    }
                } else if (upper.startsWith("ENCHANT:")) {
                    String[] parts = action.split(":", 3);
                    if (parts.length >= 2) {
                        String enchant = parts[1].toLowerCase(Locale.ROOT).trim();
                        int level = 1;
                        try {
                            if (parts.length >= 3) level = Math.max(1, Math.min(5, Integer.parseInt(parts[2].trim())));
                        } catch (NumberFormatException ignored) {}
                        runServerCommand(player, "enchant @s minecraft:" + enchant + " " + level);
                        player.sendSystemMessage(Component.literal("§b✨ Enchanted with " + enchant + " " + level));
                        if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                    }
                } else if (upper.startsWith("TP:")) {
                    String[] parts = action.split(":", 4);
                    if (parts.length >= 4) {
                        String x = parts[1].trim();
                        String y = parts[2].trim();
                        String z = parts[3].trim();
                        runServerCommand(player, "tp @s " + x + " " + y + " " + z);
                        player.sendSystemMessage(Component.literal("§d✨ Teleporting to " + x + ", " + y + ", " + z));
                        if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                    }
                } else if (upper.startsWith("SCAN_BLOCKS:")) {
                    // SCAN_BLOCKS:<radius>:<include_players>:<include_entities>:<include_blocks>
                    String[] parts = action.split(":", 5);
                    int radius = 8;
                    boolean incP = false, incE = true, incB = true;
                    try {
                        if (parts.length >= 2) radius = Math.max(2, Math.min(32, Integer.parseInt(parts[1].trim())));
                        if (parts.length >= 3) incP = !"0".equals(parts[2].trim());
                        if (parts.length >= 4) incE = !"0".equals(parts[3].trim());
                        if (parts.length >= 5) incB = !"0".equals(parts[4].trim());
                    } catch (NumberFormatException ignored) {}
                    int maxRadius = 8;
                    if (loadedConfig != null && loadedConfig.tier != null) {
                        if (loadedConfig.tier.equalsIgnoreCase("pro")) maxRadius = 16;
                        else if (loadedConfig.tier.equalsIgnoreCase("enterprise")) maxRadius = 32;
                    }
                    // Per-player 30s cooldown (port from Spigot)
                    long nowMs = System.currentTimeMillis();
                    if (lastBlockScanTime.size() > MAX_BLOCK_SCAN_TRACKED) {
                        lastBlockScanTime.entrySet().removeIf(e -> nowMs - e.getValue() > BLOCK_SCAN_TTL_MS);
                    }
                    String scanKey = (serverId != null ? serverId : "") + ":" + player.getName().getString();
                    final long[] scanResult = {0};
                    lastBlockScanTime.compute(scanKey, (k, lastScan) -> {
                        if (lastScan == null || (nowMs - lastScan) >= 30000L) {
                            scanResult[0] = 0;
                            return nowMs;
                        }
                        scanResult[0] = lastScan;
                        return lastScan;
                    });
                    if (scanResult[0] != 0 && nowMs - scanResult[0] < 30000L) {
                        player.sendSystemMessage(Component.literal("§c§o[Block scan rate limit: wait " + ((30000L - (nowMs - scanResult[0])) / 1000) + "s]"));
                        if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, false, "rate_limited");
                        continue;
                    }
                    final int scanR = Math.min(radius, maxRadius);
                    final boolean fIncP = incP, fIncE = incE, fIncB = incB;
                    player.level().getServer().execute(() -> {
                        net.minecraft.core.BlockPos origin = player.blockPosition();
                        net.minecraft.world.level.Level world = player.level();
                        StringBuilder out = new StringBuilder("§b[Scan] §7radius=" + scanR + "\n");
                        if (fIncB) {
                            java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
                            for (int dx = -scanR; dx <= scanR; dx += 2) {
                                for (int dy = -scanR; dy <= scanR; dy += 2) {
                                    for (int dz = -scanR; dz <= scanR; dz += 2) {
                                        net.minecraft.core.BlockPos bp = origin.offset(dx, dy, dz);
                                        net.minecraft.world.level.block.state.BlockState bs = world.getBlockState(bp);
                                        String name = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(bs.getBlock()).getPath().replace('_', ' ');
                                        counts.merge(name, 1, Integer::sum);
                                    }
                                }
                            }
                            counts.entrySet().stream()
                                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                                .limit(8)
                                .forEach(e -> out.append("§7  block: §f").append(e.getKey()).append(" §8x").append(e.getValue()).append("\n"));
                        }
                        if (fIncE) {
                            int mob = 0;
                            for (net.minecraft.world.entity.LivingEntity e : world.getEntitiesOfClass(
                                    net.minecraft.world.entity.LivingEntity.class,
                                    net.minecraft.world.phys.AABB.ofSize(origin.getCenter(), scanR * 2, scanR * 2, scanR * 2),
                                    en -> en != player && !(en instanceof net.minecraft.world.entity.player.Player))) mob++;
                            out.append("§7  mobs nearby: §f").append(mob).append("\n");
                        }
                        if (fIncP) {
                            int pl = 0;
                            for (net.minecraft.world.entity.player.Player e : world.getEntitiesOfClass(
                                    net.minecraft.world.entity.player.Player.class,
                                    net.minecraft.world.phys.AABB.ofSize(origin.getCenter(), scanR * 2, scanR * 2, scanR * 2),
                                    en -> en != player)) pl++;
                            out.append("§7  players nearby: §f").append(pl).append("\n");
                        }
                        player.sendSystemMessage(Component.literal(out.toString().trim()));
                    });
                    if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                } else if (upper.startsWith("SCHEDULE_TASK:")) {
                    // SCHEDULE_TASK:<cron_expr>:<type>:<message>
                    String[] parts = action.split(":", 4);
                    if (parts.length < 4) {
                        player.sendSystemMessage(Component.literal("§c[CraftyAI] Invalid schedule format. Use: SCHEDULE_TASK:<cron>:<type>:<message>"));
                        continue;
                    }
                    String cronExpr = parts[1].trim();
                    String actionType = parts[2].trim().toLowerCase();
                    String message = parts[3].trim();
                    if (!"chat".equals(actionType) && !"action".equals(actionType)) {
                        player.sendSystemMessage(Component.literal("§c[CraftyAI] Schedule type must be 'chat' or 'action'"));
                        continue;
                    }
                    final String fCron = cronExpr;
                    final String fType = actionType;
                    final String fMsg = message;
                    final String fName = message.length() > 40 ? message.substring(0, 40) + "..." : message;
                    player.sendSystemMessage(Component.literal("§e[CraftyAI] Scheduling task..."));
                    final ServerPlayer fPlayer = player;
                    new Thread(() -> {
                        try {
                            String json = "{\"name\":\"" + escapeJson(fName) + "\",\"cron_expr\":\"" + escapeJson(fCron) + "\",\"action_type\":\"" + fType + "\",\"action_payload\":{\"message\":\"" + escapeJson(fMsg) + "\"}}";
                            java.net.http.HttpRequest request = GatewayHttpClientHelper.apply(java.net.http.HttpRequest.newBuilder()
                                .uri(java.net.URI.create(GatewayRequestHeaders.getGatewayUrl() + "/v1/schedule-task"))
                                .header("Content-Type", "application/json")
                                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json)),
                                CLIENT_TYPE, serverId).build();
                            java.net.http.HttpResponse<String> resp = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                            String bodyStr = resp.body();
                            if (resp.statusCode() == 200 && bodyStr.contains("\"success\":true")) {
                                String taskName = "\"name\":\"";
                                int ni = bodyStr.indexOf(taskName);
                                String nameStr = ni >= 0 ? bodyStr.substring(ni + taskName.length(), bodyStr.indexOf("\"", ni + taskName.length())) : fName;
                                fPlayer.level().getServer().execute(() ->
                                    fPlayer.sendSystemMessage(Component.literal("§a[CraftyAI] Task scheduled: §f" + nameStr + "§a (every " + fCron + ")")));
                            } else {
                                String err = "\"error\":\"";
                                int ei = bodyStr.indexOf(err);
                                String errStr = ei >= 0 ? bodyStr.substring(ei + err.length(), bodyStr.indexOf("\"", ei + err.length())) : "HTTP " + resp.statusCode();
                                fPlayer.level().getServer().execute(() ->
                                    fPlayer.sendSystemMessage(Component.literal("§c[CraftyAI] Schedule failed: " + errStr)));
                            }
                        } catch (Exception e) {
                            fPlayer.level().getServer().execute(() ->
                                fPlayer.sendSystemMessage(Component.literal("§c[CraftyAI] Schedule error: " + e.getMessage())));
                        }
                    }, "CraftyAI-Schedule").start();
                    if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                } else if (upper.startsWith("DELAYED_ACTION:")) {
                    // DELAYED_ACTION:<seconds>:<inner_action>
                    String[] dParts = action.split(":", 3);
                    if (dParts.length < 3) {
                        player.sendSystemMessage(Component.literal("§c[CraftyAI] Format: DELAYED_ACTION:<seconds>:<action>"));
                        continue;
                    }
                    int delaySec = 0;
                    try { delaySec = Math.max(1, Math.min(300, Integer.parseInt(dParts[1].trim()))); } catch (NumberFormatException ignored) {}
                    String innerAction = dParts[2].trim();
                    if (delaySec <= 0 || innerAction.isEmpty()) {
                        player.sendSystemMessage(Component.literal("§c[CraftyAI] Invalid delay or action"));
                        continue;
                    }
                    player.sendSystemMessage(Component.literal("§e[CraftyAI] Will execute in " + delaySec + "s: §f" + innerAction));
                    final String fInner = innerAction;
                    final ServerPlayer fP = player;
                    final String fKey = playerKey;
                    final String fSid = serverId;
                    final long fDelay = delaySec * 1000L;
                    final net.minecraft.server.MinecraftServer fSrv = player.level().getServer();
                    new Thread(() -> {
                        try { Thread.sleep(fDelay); } catch (InterruptedException ie) { return; }
                        fSrv.execute(() -> {
                            fP.sendSystemMessage(Component.literal("§a[CraftyAI] Executing delayed action: §f" + fInner));
                            handleActionInternal(fP, fInner);
                        });
                    }, "CraftyAI-Delay").start();
                    if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "delayed");
                } else if (upper.startsWith("CHAT:")) {
                    String cmd = action.substring("CHAT:".length()).trim();
                    if (cmd.startsWith("/")) cmd = cmd.substring(1);
                    cmd = normalizeLocateCommand(cmd);
                    if (!AgenticActions.isAllowedChatCommand(cmd)) {
                        player.sendSystemMessage(Component.literal("§c[CraftyAI] Blocked unsafe AI command. Only /locate is allowed."));
                        if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, false, "command not allowlisted");
                        continue;
                    }
                    final String finalCmd = cmd;
                    final net.minecraft.server.MinecraftServer srv = player.level().getServer();
                    final ServerPlayer p = player;
                    srv.execute(() -> {
                        try {
                            srv.getCommands().performPrefixedCommand(p.createCommandSourceStack(), "/" + finalCmd);
                            p.sendSystemMessage(Component.literal("§7[CraftyAI] Ran: /" + finalCmd));
                        } catch (Exception e) {
                            LOGGER.warn("[CraftyAI] CHAT command failed: " + finalCmd + " — " + e.getMessage());
                            p.sendSystemMessage(Component.literal("§c[CraftyAI] Command failed: " + e.getMessage()));
                        }
                    });
                    if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                }
            } catch (Exception e) {
                LOGGER.warn("[CraftyAI] Failed to execute action '" + action + "': " + e.getMessage());
                if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, false, "exception: " + e.getMessage());
            }
        }
    }

    private boolean isOp(ServerPlayer player) {
        return player.level().getServer().getPlayerList().isOp(player.nameAndId());
    }

    private void runServerCommand(ServerPlayer player, String command) {
        MinecraftServer server = player.level().getServer();
        server.execute(() ->
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command));
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
        if (restLower.startsWith("minecraft") && restLower.length() > "minecraft".length()) {
            rest = "minecraft:" + rest.substring("minecraft".length());
        } else {
            rest = "minecraft:" + rest;
        }
        return prefix + rest;
    }

    private String sendWithRetry(String question, String playerName, String context, List<Map<String, String>> history, int attempt) {
        try {
            String effectiveUrl = loadedConfig.getEffectiveApiUrl();
            String effectiveKey = loadedConfig.getEffectiveApiKey();

            String jsonPayload;
            HttpRequest.Builder requestBuilder;

            if (loadedConfig.custom_provider_enabled) {
                // chat-completions-compatible provider: use /v1/chat/completions with messages array
                List<Map<String, String>> messages = new ArrayList<>();
                Map<String, String> systemMsg = new HashMap<>();
                systemMsg.put("role", "system");
                systemMsg.put("content", "You are " + aiName + ", a helpful AI assistant in Minecraft." +
                        (context != null && !context.isEmpty() ? "\n\nContext:\n" + context : ""));
                messages.add(systemMsg);
                if (history != null) {
                    messages.addAll(history);
                }
                Map<String, String> userMsg = new HashMap<>();
                userMsg.put("role", "user");
                userMsg.put("content", question);
                messages.add(userMsg);

                Map<String, Object> payload = new HashMap<>();
                String model = (loadedConfig.custom_provider_model != null && !loadedConfig.custom_provider_model.isEmpty())
                        ? loadedConfig.custom_provider_model : "default";
                payload.put("model", model);
                payload.put("messages", messages);
                payload.put("max_tokens", 1024);

                jsonPayload = GSON.toJson(payload);

                String baseUrl = effectiveUrl.replaceAll("/v1/chat/completions/?$", "").replaceAll("/+$", "");
                requestBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/v1/chat/completions"))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(30))
                        .POST(HttpRequest.BodyPublishers.ofString(jsonPayload));

                // Apply custom provider headers (no gateway-specific headers)
                GatewayHttpClientHelper.applyCustomProvider(requestBuilder, effectiveUrl, effectiveKey, CLIENT_TYPE, serverId);
            } else {
                // CraftyAI Gateway: use /v1/chat with gateway payload format
                Map<String, Object> payload = new HashMap<>();
                payload.put("prompt", question);
                payload.put("player_name", playerName);
                payload.put("context", context);
                payload.put("history", history);
                payload.put("server_id", serverId);
                payload.put("client_type", CLIENT_TYPE);
                payload.put("version", GatewayRequestHeaders.MOD_VERSION);

                jsonPayload = GSON.toJson(payload);

                requestBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(effectiveUrl + "/v1/chat"))
                        .header("Authorization", "Bearer " + effectiveKey)
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(30))
                        .POST(HttpRequest.BodyPublishers.ofString(jsonPayload));

                // Apply gateway headers
                GatewayHttpClientHelper.apply(requestBuilder, CLIENT_TYPE, serverId);
            }

            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                if (loadedConfig.custom_provider_enabled) {
                    // Parse chat-completions-compatible response and wrap in NeuralResponse format
                    try {
                        com.google.gson.JsonObject json = com.demonz.craftyai.common.JsonParserAdapter.parse(response.body()).getAsJsonObject();
                        com.google.gson.JsonArray choices = json.getAsJsonArray("choices");
                        if (choices != null && choices.size() > 0) {
                            String content = choices.get(0).getAsJsonObject()
                                    .getAsJsonObject("message").get("content").getAsString();
                            NeuralResponse neuralRes = new NeuralResponse();
                            neuralRes.answer = content;
                            neuralRes.response = content;
                            return GSON.toJson(neuralRes);
                        }
                        return "__ERROR__:Empty response from custom provider";
                    } catch (Exception e) {
                        LOGGER.warn("[CraftyAI] Failed to parse chat-completions-compatible response: {}", e.getMessage());
                        return "__ERROR__:Failed to parse custom provider response";
                    }
                }
                return response.body();
            }
            
            if (response.statusCode() == 429) {
                String body = response.body();
                if (body != null && body.contains("Daily request limit exceeded")) {
                    return "__ERROR__:\u00a7c\u00a7l[!] Daily Request Limit Reached. \u00a7r\u00a77Create a ticket at \u00a7b\u00a7ndiscord.gg/zCkE44hsBR\u00a7r\u00a77 to upgrade.";
                }
                if (body != null && body.contains("Daily token limit exceeded")) {
                    return "__ERROR__:\u00a7c\u00a7l[!] Daily Token Limit Reached. \u00a7r\u00a77Create a ticket at \u00a7b\u00a7ndiscord.gg/zCkE44hsBR\u00a7r\u00a77 to upgrade.";
                }
                return "__ERROR__:Rate limited. Slow down.";
            }
            if (response.statusCode() >= 500 && attempt < MAX_RETRIES) {
                try {
                    TimeUnit.MILLISECONDS.sleep((long) Math.pow(2, attempt + 1) * 1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    LOGGER.warn("[CraftyAI] Retry sleep interrupted (attempt {}): {}", attempt + 1, ie.getMessage());
                    return "__ERROR__:Retry interrupted";
                }
                return sendWithRetry(question, playerName, context, history, attempt + 1);
            }
            return "__ERROR__:Server error (HTTP " + response.statusCode() + ")";
        } catch (Exception e) {
            if (attempt < MAX_RETRIES) {
                try {
                    TimeUnit.MILLISECONDS.sleep((long) Math.pow(2, attempt + 1) * 1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    LOGGER.warn("[CraftyAI] Retry sleep interrupted on exception (attempt {}): {}", attempt + 1, ie.getMessage());
                    return "__ERROR__:Retry interrupted";
                }
                return sendWithRetry(question, playerName, context, history, attempt + 1);
            }
            recordFailure();
            return null;
        }
    }

    private void addToHistory(UUID playerId, String question, String answer) {
        LinkedList<Map<String, String>> history = conversationCache.computeIfAbsent(playerId, k -> new LinkedList<>());
        synchronized (history) {
            Map<String, String> userMsg = new HashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", question);
            history.add(userMsg);
            Map<String, String> aiMsg = new HashMap<>();
            aiMsg.put("role", "assistant");
            aiMsg.put("content", answer);
            history.add(aiMsg);
            while (history.size() > MAX_HISTORY) history.removeFirst();
        }
    }

    private void recordFailure() {
        if (consecutiveFailures.incrementAndGet() >= 5) circuitOpenUntil = System.currentTimeMillis() + 30000;
    }

    private void resetCircuit() {
        consecutiveFailures.set(0);
        circuitOpenUntil = 0;
    }

    private boolean isCircuitOpen() {
        if (circuitOpenUntil > 0 && System.currentTimeMillis() > circuitOpenUntil) {
            resetCircuit();
            return false;
        }
        return circuitOpenUntil > 0;
    }

    private String extractQuestion(String message) {
        if (message == null || message.trim().isEmpty()) return null;
        String lower = message.toLowerCase().trim();
        for (String alias : aliases) {
            String prefixed = prefix.toLowerCase() + alias;
            if (lower.startsWith(prefixed + " ")) return message.substring(prefixed.length()).trim();
            if (!requirePrefix && lower.startsWith(alias + " ")) return message.substring(alias.length()).trim();
        }
        return null;
    }

    private boolean isOnCooldown(ServerPlayer player) {
        long now = System.currentTimeMillis();
        final long[] previousTime = {0};
        chatCooldowns.compute(player.getUUID(), (key, prev) -> {
            if (prev == null || (now - prev) >= cooldownMs) {
                previousTime[0] = 0;
                return now;
            }
            previousTime[0] = prev;
            return prev;
        });
        return previousTime[0] != 0 && (now - previousTime[0]) < cooldownMs;
    }

    private void updateTierFromResponse(String responseBody) {
        try {
            com.google.gson.JsonObject json = com.demonz.craftyai.common.JsonParserAdapter.parse(responseBody).getAsJsonObject();
            if (json.has("tier")) {
                loadedConfig.tier = json.get("tier").getAsString();
            }
        } catch (Exception e) {
            LOGGER.warn("[CraftyAI] Failed to parse tier from handshake response: {}", e.getMessage());
        }
    }

    private void registerVisionScanner() {
        VisionScanner.registerProvider(new FabricVisionScannerProvider());
        LOGGER.info("[CraftyAI] Vision Scanner registered (Fabric provider)");
    }

    private void handleLinkCommand(ServerPlayer player) {
        player.sendSystemMessage(Component.literal("§b[CraftyAI] §7Generating link code for Discord linking..."));
        HttpRequest request = GatewayHttpClientHelper.apply(HttpRequest.newBuilder()
                .uri(URI.create(gatewayUrl + "/v1/link-generate"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")), CLIENT_TYPE, serverId)
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    player.level().getServer().execute(() -> {
                        if (response.statusCode() == 200) {
                            try {
                                com.google.gson.JsonObject json = com.demonz.craftyai.common.JsonParserAdapter.parse(response.body()).getAsJsonObject();
                                String code = json.has("code") && !json.get("code").isJsonNull() ? json.get("code").getAsString() : "";
                                if (!code.isEmpty()) {
                                    player.sendSystemMessage(Component.literal("§a========================================="));
                                    player.sendSystemMessage(Component.literal("§a [CraftyAI] DISCORD LINK CODE"));
                                    player.sendSystemMessage(Component.literal("§a Code: §b" + code));
                                    player.sendSystemMessage(Component.literal("§a Expiry: 15 minutes"));
                                    player.sendSystemMessage(Component.literal("§a"));
                                    player.sendSystemMessage(Component.literal("§a Tell your server owner to run:"));
                                    player.sendSystemMessage(Component.literal("§a   /link " + code));
                                    player.sendSystemMessage(Component.literal("§a in Discord to link this server."));
                                    player.sendSystemMessage(Component.literal("§a========================================="));
                                } else {
                                    player.sendSystemMessage(Component.literal("§c[CraftyAI] No link code returned."));
                                }
                            } catch (Exception e) {
                                player.sendSystemMessage(Component.literal("§c[CraftyAI] Failed to parse link response."));
                            }
                        } else {
                            player.sendSystemMessage(Component.literal("§c[CraftyAI] Link code generation failed (HTTP " + response.statusCode() + ")."));
                        }
                    });
                })
                .exceptionally(e -> {
                    player.level().getServer().execute(() -> player.sendSystemMessage(Component.literal("§c[CraftyAI] Link failed: " + e.getMessage())));
                    return null;
                });
    }

    /**
     * Client-side action inference: when the LLM truncates the action field,
     * infer the action from the player's question and the AI's text response.
     */
    public String inferActionFromText(String answer, String question) {
        if (answer == null || question == null) return null;
        String la = answer.toLowerCase();
        String lq = question.toLowerCase();

        if (la.contains("scanning") || la.contains("looking around") || la.contains("checking surroundings")) {
            return "SCAN_BLOCKS:8:0:1:1";
        }

        if (lq.matches(".*\\b(in|after|wait)\\s+\\d+\\s*(seconds?|sec|minutes?|min)\\b.*")) {
            java.util.regex.Matcher secM = java.util.regex.Pattern.compile("(\\d+)\\s*(?:second|sec)").matcher(lq);
            java.util.regex.Matcher minM = java.util.regex.Pattern.compile("(\\d+)\\s*minute").matcher(lq);
            int delaySec = 10;
            if (secM.find()) delaySec = Integer.parseInt(secM.group(1));
            else if (minM.find()) delaySec = Integer.parseInt(minM.group(1)) * 60;
            delaySec = Math.max(1, Math.min(300, delaySec));
            String innerAction = "TELEPORT_SPAWN";
            if (lq.contains("time") && lq.contains("day")) innerAction = "TIME_DAY";
            else if (lq.contains("time") && lq.contains("night")) innerAction = "TIME_NIGHT";
            else if (lq.contains("heal")) innerAction = "HEAL";
            else if (lq.contains("feed")) innerAction = "FEED";
            else if (lq.contains("weather") && lq.contains("clear")) innerAction = "WEATHER_CLEAR";
            else if (lq.contains("weather") && lq.contains("rain")) innerAction = "WEATHER_RAIN";
            else if (lq.contains("weather") && lq.contains("thunder")) innerAction = "WEATHER_THUNDER";
            else if (lq.contains("kill") && lq.contains("mob")) innerAction = "KILL_MOBS";
            else if (lq.contains("teleport") || lq.contains(" tp ")) {
                java.util.regex.Matcher coordM = java.util.regex.Pattern.compile("tp\\s+(?:me\\s+)?(?:to\\s+)?(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)").matcher(lq);
                if (coordM.find()) innerAction = "TP:" + coordM.group(1) + ":" + coordM.group(2) + ":" + coordM.group(3);
                else innerAction = "TELEPORT_SPAWN";
            }
            return "DELAYED_ACTION:" + delaySec + ":" + innerAction;
        }

        if (la.contains("scheduled") || la.contains("recurring") || lq.contains("every day") || lq.contains("daily") || lq.contains("every hour") || lq.contains("remind me")) {
            String cron = "0 9 * * *";
            if (lq.contains("every hour")) cron = "0 * * * *";
            else if (lq.contains("every minute")) cron = "* * * * *";
            else {
                java.util.regex.Matcher hm = java.util.regex.Pattern.compile("at\\s+(\\d+)\\s*(am|pm)?").matcher(lq);
                if (hm.find()) {
                    int h = Integer.parseInt(hm.group(1));
                    if ("pm".equalsIgnoreCase(hm.group(2)) && h < 12) h += 12;
                    if ("am".equalsIgnoreCase(hm.group(2)) && h == 12) h = 0;
                    cron = "0 " + h + " * * *";
                }
            }
            java.util.regex.Matcher mm = java.util.regex.Pattern.compile("(?:remind(?:\\s+me)?|say|tell|announce)\\s+(?:me\\s+)?(.+)").matcher(lq);
            String msg = mm.find() ? mm.group(1).substring(0, Math.min(100, mm.group(1).length())) : "Reminder!";
            return "SCHEDULE_TASK:" + cron + ":chat:" + msg;
        }

        return null;
    }
}
