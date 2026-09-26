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
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.registry.Registries;
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

    private final ConcurrentHashMap<String, Long> lastBlockScanTime = new ConcurrentHashMap<>();
    private static final int MAX_BLOCK_SCAN_TRACKED = 1000;
    private static final long BLOCK_SCAN_TTL_MS = 360_000L;

    private ActionAuditLog auditLog;
    private ActionRateLimiter actionRateLimiter;
    private ActionConfirmation actionConfirmations;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile long circuitOpenUntil = 0;
    private final com.demonz.craftyai.common.LifecycleHeartbeat scheduler = new com.demonz.craftyai.common.LifecycleHeartbeat();

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

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (telemetryEnabled && !loadedConfig.force_local_mode) scheduler.start(this::performStartupHandshake);
        });

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            scheduler.stop();
            LOGGER.info("[CraftyAI] Telemetry heartbeat scheduler shut down.");
        });

        UpdateChecker.checkForUpdatesAsync(LOGGER::info, "fabric");

        LOGGER.info("[CraftyAI] Neural Engine Online \u2014 Fabric Mod v" + com.demonz.craftyai.common.GatewayRequestHeaders.MOD_VERSION);
    }

    public void reloadConfig() {
        loadConfig();
        try {
            CraftyAIModClient.reloadConfig();
            if (loadedConfig != null) CraftyAIModClient.setTierFromServer(loadedConfig.tier);
        } catch (NoClassDefFoundError ignored) {  }
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

        if (loadedConfig.server_id != null && !loadedConfig.server_id.isEmpty()
                && !loadedConfig.server_id.equals(this.serverId)) {
            com.demonz.craftyai.common.SessionManager.setSessionId(
                    net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir(), loadedConfig.server_id);
            this.serverId = loadedConfig.server_id;
        }

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
        if (loadedConfig == null || loadedConfig.force_local_mode || !loadedConfig.telemetry_enabled) return;

        if (loadedConfig.custom_provider_enabled) {
            LOGGER.info("[CraftyAI] Custom provider enabled \u2014 skipping gateway handshake.");
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
        payload.put("software_name", "fabric");
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
                        Text text = Text.literal(color + "[" + title + "] \u00A77" + message);
                        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                            if (player.hasPermissionLevel(2)) player.sendMessage(text, false);
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
        payload.put("name", serverInstance != null ? serverInstance.getName() : "Fabric Server");
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
                            LOGGER.info("[CraftyAI] New API key saved to config/craftyai.json. Keep that file private.");
                            LOGGER.info("  Tier: {}", newTier);
                            LOGGER.info("  Server ID: {}", newServerId);
                            LOGGER.info("=========================================");
                            try {
                                CraftyAIModClient.reloadConfig();
                                CraftyAIModClient.setTierFromServer(newTier);
                            } catch (NoClassDefFoundError ignored) {  }
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

                LOGGER.warn("[CraftyAI] Session Warning: " + message.replaceAll("\u00A7.", ""));

                net.minecraft.server.MinecraftServer server = serverInstance;

                if (server != null) {
                    String chatMsg = "\u00A7c\u00A7l[CraftyAI Warning] \u00A77" + message;
                    for (net.minecraft.server.network.ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                        if (player.hasPermissionLevel(2)) {
                            player.sendMessage(net.minecraft.text.Text.literal(chatMsg), false);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[CraftyAI] Warning parse error: {}", e.getMessage());
        }
    }

    private void registerOpWelcome() {

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            conversationCache.remove(handler.getPlayer().getUuid());
        });

        if (!opWelcomeEnabled) return;
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (!server.isDedicated()) return;
            ServerPlayerEntity player = handler.getPlayer();
            if (player.hasPermissionLevel(2)) {
                server.execute(() -> {
                    player.sendMessage(Text.literal(""), false);
                    player.sendMessage(Text.literal("\u00A7b\u00A7l\u2726 CraftyAI \u00A77v" + GatewayRequestHeaders.MOD_VERSION + " \u00A78\u2014 \u00A77Neural AI Companion"), false);
                    player.sendMessage(Text.literal("\u00A77  Thanks for using CraftyAI by \u00A7bDemonZ Development\u00A77!"), false);
                    player.sendMessage(Text.literal("\u00A77  Type \u00A7e@" + aiName.toLowerCase() + " <question> \u00A77to chat."), false);
                    player.sendMessage(Text.literal(""), false);
                });
            }
        });
    }

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("crafty")
                    .then(CommandManager.literal("help").executes(context -> {
                        context.getSource().sendFeedback(() -> Text.literal("\u00A7b\u00A7l\u00A7m------------\u00A7r \u00A76crafty ai v" + GatewayRequestHeaders.MOD_VERSION + " \u00A7b\u00A7l\u00A7m------------"), false);
                        context.getSource().sendFeedback(() -> Text.literal("\u00A77Neural AI companion by \u00A7bDemonZ Development\u00A77."), false);
                        context.getSource().sendFeedback(() -> Text.literal("\u00A77Type \u00A7e@" + aiName.toLowerCase() + " <question> \u00A77in chat, or use these commands:"), false);
                        context.getSource().sendFeedback(() -> Text.literal(""), false);
                        context.getSource().sendFeedback(() -> Text.literal("\u00A7e/crafty status   \u00A78- \u00A77Show plugin status and tier info"), false);
                        context.getSource().sendFeedback(() -> Text.literal("\u00A7e/crafty ask <q>  \u00A78- \u00A77Ask crafty ai privately"), false);
                        context.getSource().sendFeedback(() -> Text.literal("\u00A7e/crafty scan     \u00A78- \u00A77Vision scan nearby area"), false);
                        context.getSource().sendFeedback(() -> Text.literal("\u00A7e/crafty confirm  \u00A78- \u00A77Confirm a pending destructive action"), false);
                        context.getSource().sendFeedback(() -> Text.literal("\u00A7e/crafty pro      \u00A78- \u00A77Pro tier management"), false);
                        context.getSource().sendFeedback(() -> Text.literal("\u00A7e/crafty link     \u00A78- \u00A77Link this server to Discord"), false);
                        context.getSource().sendFeedback(() -> Text.literal("\u00A7e/crafty reload   \u00A78- \u00A77Reload configuration (admin)"), false);
                        return 1;
                    }))
                    .then(CommandManager.literal("status").executes(context -> {
                        context.getSource().sendFeedback(() -> Text.literal("\u00A7b[CraftyAI] v" + GatewayRequestHeaders.MOD_VERSION
                                + " \u00A77| Tier: " + (loadedConfig == null ? "free" : loadedConfig.tier)
                                + " | " + (isCircuitOpen() ? "Reconnecting" : "Ready")), false);
                        return 1;
                    }))

                    .then(CommandManager.literal("ask")
                            .then(CommandManager.argument("question", StringArgumentType.greedyString())
                                    .executes(context -> {
                                        ServerPlayerEntity player = context.getSource().getPlayer();
                                        if (player == null) {
                                            context.getSource().sendError(Text.literal("\u00A7cOnly players can execute this command."));
                                            return 0;
                                        }
                                        String question = StringArgumentType.getString(context, "question");
                                        handleAskCommand(player, question);
                                        return 1;
                                    })
                            )
                    )

                    .then(CommandManager.literal("scan")
                            .executes(context -> {
                        ServerPlayerEntity player = context.getSource().getPlayer();
                        if (player == null) {
                            context.getSource().sendError(Text.literal("\u00A7cOnly players can execute this command."));
                            return 0;
                        }
                        handleScanCommand(player);
                        return 1;
                    }))

                    .then(CommandManager.literal("reload")
                            .requires(source -> source.hasPermissionLevel(2))
                            .executes(context -> {
                                loadConfig();
                                context.getSource().sendFeedback(() -> Text.literal("\u00A7a[CraftyAI] Configuration reloaded successfully."), true);
                                return 1;
                            })
                    )

                    .then(CommandManager.literal("confirm")
                            .executes(context -> {
                                ServerPlayerEntity player = context.getSource().getPlayer();
                                if (player == null) {
                                    context.getSource().sendError(Text.literal("\u00A7cOnly players can execute this command."));
                                    return 0;
                                }
                                if (!player.hasPermissionLevel(2)) {
                                    player.sendMessage(Text.literal("\u00A7c[CraftyAI] Only operators can confirm actions."), false);
                                    return 0;
                                }
                                String playerKey = player.getUuid().toString();
                                if (actionConfirmations == null) {
                                    player.sendMessage(Text.literal("\u00A7c[CraftyAI] Confirmation system unavailable."), false);
                                    return 0;
                                }
                                String pending = actionConfirmations.peek(playerKey);
                                if (pending == null) {
                                    player.sendMessage(Text.literal("\u00A77[CraftyAI] No destructive action pending. Re-run your request first."), false);
                                } else {
                                    player.sendMessage(Text.literal("\u00A7a[CraftyAI] Confirmed pending action: \u00A7f" + pending.toUpperCase() + "\u00A7a. Re-run your request to execute it now."), false);
                                }
                                return 1;
                            })
                    )

                    .then(CommandManager.literal("pro")
                            .executes(context -> {
                                ServerPlayerEntity player = context.getSource().getPlayer();
                                String tierStr = (loadedConfig != null && loadedConfig.tier != null && !loadedConfig.tier.isEmpty()) ? loadedConfig.tier : "free";
                                String tierUpper = tierStr.toUpperCase();
                                String tierColor = tierStr.equalsIgnoreCase("pro") ? "\u00A76"
                                        : tierStr.equalsIgnoreCase("enterprise") ? "\u00A7d" : "\u00A7f";
                                if (player != null) {
                                    player.sendMessage(Text.literal("\u00A7b[CraftyAI] Pro Status:"), false);
                                    player.sendMessage(Text.literal("\u00A77  Tier: " + tierColor + tierUpper), false);
                                    player.sendMessage(Text.literal("\u00A77  To upgrade: open a Pro ticket on Discord"), false);
                                    player.sendMessage(Text.literal("\u00A77  \u00A7bhttps://discord.gg/zCkE44hsBR"), false);
                                } else {
                                    context.getSource().sendFeedback(() -> Text.literal("\u00A7b[CraftyAI] Tier: " + tierColor + tierUpper), false);
                                }
                                return 1;
                            })
                            .then(CommandManager.literal("status")
                                    .executes(context -> {
                                        ServerPlayerEntity player = context.getSource().getPlayer();
                                        String tierStr = (loadedConfig != null && loadedConfig.tier != null && !loadedConfig.tier.isEmpty()) ? loadedConfig.tier : "free";
                                        String tierUpper = tierStr.toUpperCase();
                                        String tierColor = tierStr.equalsIgnoreCase("pro") ? "\u00A76"
                                                : tierStr.equalsIgnoreCase("enterprise") ? "\u00A7d" : "\u00A7f";
                                        if (player != null) {
                                            player.sendMessage(Text.literal("\u00A7b[CraftyAI] Pro Status:"), false);
                                            player.sendMessage(Text.literal("\u00A77  Tier: " + tierColor + tierUpper), false);
                                        }
                                        return 1;
                                    })
                            )
                            .then(CommandManager.literal("help")
                                    .executes(context -> {
                                        ServerPlayerEntity player = context.getSource().getPlayer();
                                        if (player != null) {
                                            player.sendMessage(Text.literal("\u00A7b[CraftyAI] How to get Pro:"), false);
                                            player.sendMessage(Text.literal("\u00A77  1. Join our Discord: \u00A7bhttps://discord.gg/zCkE44hsBR"), false);
                                            player.sendMessage(Text.literal("\u00A77  2. Open a ticket in #pro-requests"), false);
                                            player.sendMessage(Text.literal("\u00A77  3. An admin will review and DM you a Pro key"), false);
                                            player.sendMessage(Text.literal("\u00A77  4. Run: \u00A7f/crafty pro redeem <key>"), false);
                                        }
                                        return 1;
                                    })
                            )
                            .then(CommandManager.literal("redeem")
                                    .then(CommandManager.argument("key", StringArgumentType.greedyString())
                                            .executes(context -> {
                                                ServerPlayerEntity player = context.getSource().getPlayer();
                                                if (player == null || !player.hasPermissionLevel(2)) {
                                                    if (player != null) player.sendMessage(Text.literal("\u00A7c[CraftyAI] Only operators can redeem Pro keys."), false);
                                                    return 0;
                                                }
                                                String proKey = StringArgumentType.getString(context, "key").trim();
                                                String lowerKey = proKey.toLowerCase();
                                                if (!lowerKey.startsWith("cai_pro_") && !lowerKey.startsWith("cai_pro-")) {
                                                    player.sendMessage(Text.literal("\u00A7c[CraftyAI] Invalid Pro key format. Must start with 'CAI_PRO-'."), false);
                                                    return 0;
                                                }
                                                player.sendMessage(Text.literal("\u00A7b[CraftyAI] Redeeming Pro key..."), false);
                                                redeemProKey(player, proKey);
                                                return 1;
                                            })
                                    )
                            )
                    )

                    .then(CommandManager.literal("link")
                            .requires(source -> source.hasPermissionLevel(2))
                            .executes(context -> {
                                ServerPlayerEntity player = context.getSource().getPlayer();
                                if (player == null) {
                                    context.getSource().sendError(Text.literal("\u00A7cOnly players can execute this command."));
                                    return 0;
                                }
                                handleLinkCommand(player);
                                return 1;
                            })
                    )
            );
        });
    }

    private void handleAskCommand(ServerPlayerEntity player, String question) {
        if (isOnCooldown(player)) {
            player.sendMessage(Text.literal("\u00A7c[CraftyAI] Please wait before asking again."), false);
            return;
        }

        VisionScanner.ScanResult scanResult = VisionScanner.scan(player, player.getServerWorld());
        String context = scanResult.toContextString();

        UUID privateKey = UUID.nameUUIDFromBytes(("p_" + player.getUuid().toString()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        LinkedList<Map<String, String>> history = conversationCache.computeIfAbsent(privateKey, k -> new LinkedList<>());
        List<Map<String, String>> historyCopy;
        synchronized (history) {
            historyCopy = new ArrayList<>(history);
        }

        if (isCircuitOpen() || (loadedConfig != null && loadedConfig.force_local_mode)) {
            localBrain.enqueueRequest(question, player.getName().getString(), context);
            String offlineResponse = localBrain.generateOfflineResponse(question, player.getName().getString());
            player.sendMessage(Text.literal("\u00A7b[" + aiName + "] \u00A77(Offline) \u00A7f" + offlineResponse), false);
            return;
        }

        player.sendMessage(Text.literal("\u00A7b\u00A7l" + aiName.toUpperCase() + " IS THINKING..."), true);

        CompletableFuture.supplyAsync(() -> sendWithRetry(question, player.getName().getString(), context, historyCopy, 0))
                .thenAccept(responseBody -> {
                    player.getServer().execute(() -> {
                        player.sendMessage(Text.literal(""), true);
                        if (responseBody != null && responseBody.startsWith("__ERROR__:")) {
                            String errorMsg = responseBody.substring("__ERROR__:".length());

                            player.sendMessage(Text.literal("\u00A7c[CraftyAI] " + errorMsg), false);
                        } else if (responseBody != null) {
                            updateTierFromResponse(responseBody);
                            NeuralResponse res;
                            try {
                                res = GSON.fromJson(responseBody, NeuralResponse.class);
                            } catch (Exception parseEx) {
                                LOGGER.error("[CraftyAI] Failed to parse API response: {}", parseEx.getMessage());
                                player.sendMessage(Text.literal("\u00A7c[CraftyAI] Failed to parse AI response."), false);
                                return;
                            }
                            String answer = res != null ? res.getAnswer() : null;
                            if (answer != null && !answer.isEmpty()) {

                                player.sendMessage(Text.literal("\u00A78[Private] \u00A7b[" + aiName + "] \u00A77> \u00A7f" + answer), false);
                                addToHistory(player.getUuid(), question, answer);
                            }
                            String action = res != null ? res.getAction() : null;
                            if (action != null) {
                                if (action.toUpperCase().startsWith("SCHEDULE_TASK:") && question != null && question.toLowerCase().matches(".*\\b(in|after|wait)\\s+\\d+\\s*(seconds?|sec|minutes?|min)\\b.*")) {
                                    action = "DELAYED_ACTION:" + question.replaceAll(".*?(\\d+)\\s*(second|sec|min|minute).*", "$1") + ":TELEPORT_SPAWN";
                                }
                                handleAction(player, action, question);
                            }
                            resetCircuit();
                        } else {
                            localBrain.enqueueRequest(question, player.getName().getString(), context);
                            String offlineResponse = localBrain.generateOfflineResponse(question, player.getName().getString());
                            player.sendMessage(Text.literal("\u00A7b[" + aiName + "] \u00A77(Offline) \u00A7f" + offlineResponse), false);
                        }
                    });
                });
    }

    private void handleScanCommand(ServerPlayerEntity player) {
        VisionScanner.ScanResult scanResult = VisionScanner.scan(player, player.getServerWorld());
        String context = com.demonz.craftyai.common.ScanFlow.buildAiContext(scanResult);
        String prompt = com.demonz.craftyai.common.ScanFlow.scanCommandPrompt(aiName);
        boolean hasTarget = scanResult.scanTarget != null && !"none".equals(scanResult.scanTarget.targetType);

        if (isCircuitOpen() || (loadedConfig != null && loadedConfig.force_local_mode)) {
            String offlineResponse = localBrain.generateOfflineResponse(
                    hasTarget ? "what am I looking at" : "scan my surroundings",
                    player.getName().getString());
            player.sendMessage(Text.literal("\u00A7b[" + aiName + "] \u00A77(Offline) \u00A7f" + offlineResponse), false);
            return;
        }

        player.sendMessage(Text.literal("\u00A7b\u00A7l" + aiName.toUpperCase() + " IS SCANNING..."), true);

        CompletableFuture.supplyAsync(() -> sendWithRetry(prompt, player.getName().getString(), context, new ArrayList<>(), 0))
                .thenAccept(responseBody -> {
                    player.getServer().execute(() -> {
                        player.sendMessage(Text.literal(""), true);
                        if (responseBody != null && responseBody.startsWith("__ERROR__:")) {
                            String errorMsg = responseBody.substring("__ERROR__:".length());
                            player.sendMessage(Text.literal("\u00A7c[CraftyAI] " + errorMsg), false);
                            return;
                        }
                        if (responseBody == null) {

                            player.sendMessage(Text.literal("\u00A7b[" + aiName + "] \u00A77> \u00A7fI scanned the area, but my thoughts got scrambled on the way back. Try again in a moment."), false);
                            return;
                        }
                        updateTierFromResponse(responseBody);
                        NeuralResponse res;
                        try {
                            res = GSON.fromJson(responseBody, NeuralResponse.class);
                        } catch (Exception parseEx) {
                            LOGGER.error("[CraftyAI] Failed to parse API response: {}", parseEx.getMessage());
                            player.sendMessage(Text.literal("\u00A7c[CraftyAI] Failed to parse AI response."), false);
                            return;
                        }
                        String answer = res != null ? res.getAnswer() : null;
                        if (answer != null && !answer.isEmpty()) {
                            player.sendMessage(Text.literal("\u00A78[Private] \u00A7b[" + aiName + "] \u00A77> \u00A7f" + answer), false);
                        }
                        resetCircuit();
                    });
                });
    }

    private void registerChatListener() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            serverInstance = server;
        });
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            String rawText = message.getContent().getString().trim();
            String visibility = loadedConfig.response_visibility != null ? loadedConfig.response_visibility : "default";
            boolean isPrivateVal = visibility.equalsIgnoreCase("always-private") ||
                    (visibility.equalsIgnoreCase("default") && rawText.startsWith(prefix));
            if (visibility.equalsIgnoreCase("always-public")) {
                isPrivateVal = false;
            }
            final boolean isPrivate = isPrivateVal;
            String question = extractQuestion(message.getContent().getString());
            if (question == null) return true;

            if (isOnCooldown(sender)) {
                sender.sendMessage(Text.literal("\u00A7c[CraftyAI] Please wait before asking again."), false);
                return false;
            }

            if (isPrivate) {

                sender.sendMessage(Text.literal("<" + sender.getName().getString() + "> " + rawText), false);
            } else {
                broadcastToAll(Text.literal("<" + sender.getName().getString() + "> " + rawText));
            }

            processAIChatRequest(sender, question, isPrivate);
            return false;
        });
    }

    private void processAIChatRequest(ServerPlayerEntity sender, String question, boolean isPrivate) {
        VisionScanner.ScanResult scanResult = VisionScanner.scan(sender, sender.getServerWorld());
        String context = scanResult.toContextString();

        LinkedList<Map<String, String>> history = conversationCache.computeIfAbsent(sender.getUuid(), k -> new LinkedList<>());
        List<Map<String, String>> historyCopy;
        synchronized (history) {
            historyCopy = new ArrayList<>(history);
        }

        if (isCircuitOpen() || (loadedConfig != null && loadedConfig.force_local_mode)) {
            String offlineResponse = localBrain.generateOfflineResponse(question, sender.getName().getString());
            if (isPrivate) {
                sender.sendMessage(Text.literal("\u00A7b[" + aiName + "] \u00A77(Offline) \u00A7f" + offlineResponse), false);
            } else {
                broadcastToAll(Text.literal("\u00A7b[" + aiName + "] \u00A77(Offline) \u00A7f" + offlineResponse));
            }
            return;
        }

        sender.sendMessage(Text.literal("\u00A7b\u00A7l" + aiName.toUpperCase() + " IS THINKING..."), true);

        CompletableFuture.supplyAsync(() -> sendWithRetry(question, sender.getName().getString(), context, historyCopy, 0))
                .thenAccept(responseBody -> {
                    sender.getServer().execute(() -> {
                            sender.sendMessage(Text.literal(""), true);
                            if (responseBody != null && responseBody.startsWith("__ERROR__:")) {
                                String errorMsg = responseBody.substring("__ERROR__:".length());
                                sender.sendMessage(Text.literal("\u00A7c[CraftyAI] " + errorMsg), false);
                                String offlineResponse = localBrain.generateOfflineResponse(question, sender.getName().getString());
                                if (isPrivate) {
                                    sender.sendMessage(Text.literal("\u00A7b[" + aiName + "] \u00A77(Fallback) \u00A7f" + offlineResponse), false);
                                } else {
                                    broadcastToAll(Text.literal("\u00A7b[" + aiName + "] \u00A77(Fallback) \u00A7f" + offlineResponse));
                                }
                            } else if (responseBody != null) {
                                updateTierFromResponse(responseBody);
                                NeuralResponse res;
                                try {
                                    res = GSON.fromJson(responseBody, NeuralResponse.class);
                                } catch (Exception parseEx) {
                                    LOGGER.error("[CraftyAI] Failed to parse API response: {}", parseEx.getMessage());
                                    sender.sendMessage(Text.literal("\u00A7c[CraftyAI] Failed to parse AI response."), false);
                                    return;
                                }
                                String answer = res != null ? res.getAnswer() : null;
                                if (answer != null && !answer.isEmpty()) {
                                    if (isPrivate) {
                                        sender.sendMessage(Text.literal("\u00A78[Private] \u00A7b[" + aiName + "] \u00A77> \u00A7f" + answer), false);
                                    } else {
                                        broadcastToAll(Text.literal("\u00A7b[" + aiName + "] \u00A77> \u00A7f" + answer));
                                    }
                                    addToHistory(sender.getUuid(), question, answer);
                                }
                                String action = res != null ? res.getAction() : null;
                                if (action != null) {
                                if (action.toUpperCase().startsWith("SCHEDULE_TASK:") && question != null && question.toLowerCase().matches(".*\\b(in|after|wait)\\s+\\d+\\s*(seconds?|sec|minutes?|min)\\b.*")) {
                                    action = "DELAYED_ACTION:" + question.replaceAll(".*?(\\d+)\\s*(second|sec|min|minute).*", "$1") + ":TELEPORT_SPAWN";
                                }
                                handleAction(sender, action, question);
                            }
                                resetCircuit();
                            } else {
                                String offlineResponse = localBrain.generateOfflineResponse(question, sender.getName().getString());
                                if (isPrivate) {
                                    sender.sendMessage(Text.literal("\u00A7b[" + aiName + "] \u00A77(Offline) \u00A7f" + offlineResponse), false);
                                } else {
                                    broadcastToAll(Text.literal("\u00A7b[" + aiName + "] \u00A77(Offline) \u00A7f" + offlineResponse));
                                }
                            }
                    });
                });
    }

    private void broadcastToAll(Text message) {
        MinecraftServer server = serverInstance;
        if (server == null) return;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            player.sendMessage(message, false);
        }
    }

    private void redeemProKey(ServerPlayerEntity player, String proKey) {
        try {

            String sessionId = this.serverId != null && !this.serverId.isEmpty() ? this.serverId : "unknown";
            java.net.URI uri = java.net.URI.create(gatewayUrl + "/v1/pro/redeem");
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) uri.toURL().openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-Client-Type", "minecraft-fabric");
            conn.setRequestProperty("X-CraftyAI-Version", GatewayRequestHeaders.MOD_VERSION);
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
                player.sendMessage(Text.literal("\u00A7a[CraftyAI] Pro key redeemed successfully!"), false);

                performStartupHandshake();
            } else {
                String err = respBody.length() > 200 ? respBody.substring(0, 200) : respBody;
                player.sendMessage(Text.literal("\u00A7c[CraftyAI] Redeem failed (HTTP " + code + "): " + err), false);
            }
        } catch (Throwable t) {
            LOGGER.warn("[CraftyAI] Pro redeem error: {} \u2014 {}", t.getClass().getSimpleName(), t.getMessage());
            player.sendMessage(Text.literal("\u00A7c[CraftyAI] Redeem error: " + t.getMessage()), false);
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

    private void handleAction(ServerPlayerEntity player, String actionString) {
        handleAction(player, actionString, null);
    }

    private void handleAction(ServerPlayerEntity player, String actionString, String originalQuestion) {
        if (actionString == null || actionString.trim().isEmpty() || actionString.equalsIgnoreCase("null")) return;

        try {
            handleActionInternal(player, actionString, originalQuestion);
        } catch (Throwable t) {
            try {
                LOGGER.warn("[CraftyAI] Action handler error for " + player.getName().getString() +
                        " (action='" + actionString + "'): " + t.getClass().getSimpleName() +
                        " \u2014 " + t.getMessage());
                player.sendMessage(Text.literal("\u00A7c[CraftyAI] Action failed: " + t.getMessage()), false);
            } catch (Throwable ignored) {}
        }
    }

    private void handleActionInternal(ServerPlayerEntity player, String actionString) {
        handleActionInternal(player, actionString, null);
    }

    private void handleActionInternal(ServerPlayerEntity player, String actionString, String originalQuestion) {

        if (!loadedConfig.agentic_tasks_enabled || !loadedConfig.ai_enable_actions) {
            player.sendMessage(Text.literal("\u00A78[CraftyAI] \u00A77Action suggested: \u00A7e" + actionString + " \u00A78(agentic tasks disabled in config)"), false);
            LOGGER.info("[CraftyAI] Skipped agentic action {} \u2014 agentic tasks disabled", actionString);
            return;
        }

        if (!player.hasPermissionLevel(2)) {
            player.sendMessage(Text.literal("\u00A7c[CraftyAI] Missing permission: crafty.agentic \u2014 action not executed"), false);
            return;
        }

        String playerKey = player.getUuid().toString();
        if (actionRateLimiter != null && !actionRateLimiter.tryAcquire(playerKey)) {
            long sec = actionRateLimiter.secondsUntilReset(playerKey);
            player.sendMessage(Text.literal("\u00A7c[CraftyAI] Rate limit: try again in " + sec + "s"), false);
            return;
        }

        String[] actions = actionString.split("\\|");
        int actionLimit = Math.min(actions.length, MAX_ACTIONS_PER_REQUEST);
        for (int actionIdx = 0; actionIdx < actionLimit; actionIdx++) {
            String action = actions[actionIdx];
            String upper = action.toUpperCase().trim();
            if (upper.isEmpty()) continue;

            String perm = AgenticActions.permissionFor(action);
            if (perm != null) {
                if (!player.hasPermissionLevel(2)) {
                    player.sendMessage(Text.literal("\u00A7c[CraftyAI] Missing permission: " + perm), false);
                    if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, false, "missing permission " + perm);
                    continue;
                }
            }

            AgenticActions.Risk risk = AgenticActions.riskFor(action);
            if (risk == AgenticActions.Risk.DESTRUCTIVE && (loadedConfig == null || loadedConfig.require_confirmation)) {
                String pending = actionConfirmations != null ? actionConfirmations.confirm(playerKey) : null;
                if (pending == null || !pending.equalsIgnoreCase(upper)) {
                    if (actionConfirmations != null) actionConfirmations.request(playerKey, action);
                    player.sendMessage(Text.literal("\u00A7c\u26A0 Destructive action: \u00A7f" + upper + " \u00A7c\u2014 run \u00A7e/crafty confirm \u00A7cwithin 30s to execute."), false);
                    if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, false, "awaiting confirmation");
                    continue;
                }

            }

            switch (upper) {
                case "TIME_DAY":
                    player.getServer().execute(() -> player.getServerWorld().setTimeOfDay(1000));
                    player.sendMessage(Text.literal("\u00A7eTime set to day."), false);
                    if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                    continue;
                case "TIME_NIGHT":
                    player.getServer().execute(() -> player.getServerWorld().setTimeOfDay(13000));
                    player.sendMessage(Text.literal("\u00A79Time set to night."), false);
                    if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                    continue;
                case "WEATHER_CLEAR":
                    player.getServer().execute(() -> player.getServerWorld().setWeather(12000, 0, false, false));
                    player.sendMessage(Text.literal("\u00A7bWeather cleared."), false);
                    if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                    continue;
                case "WEATHER_RAIN":
                    player.getServer().execute(() -> player.getServerWorld().setWeather(0, 12000, true, false));
                    player.sendMessage(Text.literal("\u00A79Weather set to rain."), false);
                    if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                    continue;
                case "WEATHER_THUNDER":
                    player.getServer().execute(() -> player.getServerWorld().setWeather(0, 12000, true, true));
                    player.sendMessage(Text.literal("\u00A7c\u26A1 Thunderstorm activated"), false);
                    if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                    continue;
                case "HEAL":
                    player.getServer().execute(() -> player.setHealth(player.getMaxHealth()));
                    player.sendMessage(Text.literal("\u00A7aHealed!"), false);
                    if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                    continue;
                case "FEED":
                    player.getServer().execute(() -> {
                        player.getHungerManager().setFoodLevel(20);
                        player.getHungerManager().setSaturationLevel(20f);
                    });
                    player.sendMessage(Text.literal("\u00A76Fully fed!"), false);
                    if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                    continue;
                case "KILL_MOBS":
                    player.getServer().execute(() -> {
                        int killed = 0;
                        for (net.minecraft.entity.LivingEntity entity : player.getServerWorld().getEntitiesByClass(
                                net.minecraft.entity.LivingEntity.class,
                                new net.minecraft.util.math.Box(player.getBlockPos()).expand(50),
                                e -> e != player && e instanceof net.minecraft.entity.mob.Monster)) {
                            entity.damage(player.getServerWorld().getDamageSources().generic(), 9999f);
                            killed++;
                        }
                        player.sendMessage(Text.literal("\u00A7c\u2620 Eliminated " + killed + " nearby mobs"), false);
                    });
                    if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                    continue;
                case "TELEPORT_SPAWN":
                    player.getServer().execute(() -> {
                        net.minecraft.util.math.BlockPos spawn = player.getServerWorld().getSpawnPos();
                        player.teleport(player.getServerWorld(), spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, player.getYaw(), player.getPitch());
                    });
                    player.sendMessage(Text.literal("\u00A7d\u2728 Teleporting to spawn..."), false);
                    if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                    continue;
                case "GAMEMODE_CREATIVE":
                    player.getServer().execute(() -> player.changeGameMode(net.minecraft.world.GameMode.CREATIVE));
                    player.sendMessage(Text.literal("\u00A7bGamemode updated to Creative."), false);
                    if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                    continue;
                case "GAMEMODE_SURVIVAL":
                    player.getServer().execute(() -> player.changeGameMode(net.minecraft.world.GameMode.SURVIVAL));
                    player.sendMessage(Text.literal("\u00A7bGamemode updated to Survival."), false);
                    if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                    continue;
                case "GAMEMODE_SPECTATOR":
                    player.getServer().execute(() -> player.changeGameMode(net.minecraft.world.GameMode.SPECTATOR));
                    player.sendMessage(Text.literal("\u00A7bGamemode updated to Spectator."), false);
                    if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                    continue;
            }

            try {
                if (upper.startsWith("GIVE:")) {
                    String[] parts = action.split(":", 3);
                    if (parts.length >= 2) {
                        String itemId = parts[1].toLowerCase().trim();
                        if (GIVE_BLACKLIST.contains(itemId)) {
                            player.sendMessage(Text.literal("\u00A7c[CraftyAI] Cannot give blacklisted item: " + itemId), false);
                            LOGGER.warn("[CraftyAI] Blocked GIVE action for blacklisted item: {}", itemId);
                            if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, false, "blacklisted item");
                            continue;
                        }
                        player.getServer().execute(() -> {
                            try {
                                Identifier id = Identifier.of("minecraft", itemId);
                                net.minecraft.item.Item item = net.minecraft.registry.Registries.ITEM.get(id);
                                if (item != null) {
                                    int amount = 1;
                                    if (parts.length >= 3) amount = Integer.parseInt(parts[2].trim());
                                    player.getInventory().insertStack(new net.minecraft.item.ItemStack(item, Math.min(64, amount)));
                                    player.sendMessage(Text.literal("\u00A7aReceived " + amount + "x " + parts[1]), false);
                                }
                            } catch (Exception e) {
                                LOGGER.warn("[CraftyAI] Failed to process give command: {}", e.getMessage());
                            }
                        });
                        if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                    }
                } else if (upper.startsWith("EFFECT:")) {
                    String[] parts = action.split(":", 3);
                    if (parts.length >= 2) {
                        final String effect = parts[1].toLowerCase().trim();
                        int dur = 60;
                        try {
                            if (parts.length >= 3) dur = Math.max(1, Math.min(600, Integer.parseInt(parts[2].trim())));
                        } catch (NumberFormatException ignored) {
                            LOGGER.warn("[CraftyAI] Invalid duration in EFFECT action: {}", ignored.getMessage());
                        }
                        final int duration = dur;
                        player.getServer().execute(() -> {
                            net.minecraft.entity.effect.StatusEffectInstance inst =
                                new net.minecraft.entity.effect.StatusEffectInstance(
                                    Registries.STATUS_EFFECT.get(new Identifier("minecraft", effect)),
                                    duration * 20, 0, false, true);
                            player.addStatusEffect(inst);
                            player.sendMessage(Text.literal("\u00A7d\u2728 Applied " + effect + " for " + duration + "s"), false);
                        });
                        if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                    }
                } else if (upper.startsWith("ENCHANT:")) {
                    String[] parts = action.split(":", 3);
                    if (parts.length >= 2) {
                        final String enchant = parts[1].toLowerCase().trim();
                        int lvl = 1;
                        try {
                            if (parts.length >= 3) lvl = Math.max(1, Math.min(5, Integer.parseInt(parts[2].trim())));
                        } catch (NumberFormatException ignored) {
                            LOGGER.warn("[CraftyAI] Invalid level in ENCHANT action: {}", ignored.getMessage());
                        }
                        final int level = lvl;
                        player.getServer().execute(() -> {
                            net.minecraft.item.ItemStack held = player.getMainHandStack();
                            if (held.isEmpty()) {
                                player.sendMessage(Text.literal("\u00A7cHold an item in your main hand to enchant it."), false);
                                return;
                            }
                            held.addEnchantment(
                                Registries.ENCHANTMENT.get(new Identifier("minecraft", enchant)),
                                level);
                            player.sendMessage(Text.literal("\u00A7b\u2728 Enchanted with " + enchant + " " + level), false);
                        });
                        if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                    }
                } else if (upper.startsWith("TP:")) {
                    String[] parts = action.split(":", 4);
                    if (parts.length >= 4) {
                        String x = parts[1].trim();
                        String y = parts[2].trim();
                        String z = parts[3].trim();
                        try {
                            final double dx = Double.parseDouble(x);
                            final double dy = Double.parseDouble(y);
                            final double dz = Double.parseDouble(z);
                            player.getServer().execute(() -> player.teleport(player.getServerWorld(), dx, dy, dz, player.getYaw(), player.getPitch()));
                            player.sendMessage(Text.literal("\u00A7d\u2728 Teleporting to " + x + ", " + y + ", " + z), false);
                            if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                        } catch (NumberFormatException e) {
                            player.sendMessage(Text.literal("\u00A7cInvalid coordinates: " + x + " " + y + " " + z), false);
                            if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, false, "invalid coordinates");
                        }
                    }
                } else if (upper.startsWith("SCAN_BLOCKS:")) {

                    String[] parts = action.split(":", 5);
                    int radius = 8;
                    boolean incP = false, incE = true, incB = true;
                    try {
                        if (parts.length >= 2) radius = Math.max(2, Math.min(32, Integer.parseInt(parts[1].trim())));
                        if (parts.length >= 3) incP = !"0".equals(parts[2].trim());
                        if (parts.length >= 4) incE = !"0".equals(parts[3].trim());
                        if (parts.length >= 5) incB = !"0".equals(parts[4].trim());
                    } catch (NumberFormatException ignored) {
                        LOGGER.warn("[CraftyAI] Invalid SCAN_BLOCKS param: {}", ignored.getMessage());
                    }
                    int maxRadius = 8;
                    if (loadedConfig != null && loadedConfig.tier != null) {
                        if (loadedConfig.tier.equalsIgnoreCase("pro")) maxRadius = 16;
                        else if (loadedConfig.tier.equalsIgnoreCase("enterprise")) maxRadius = 32;
                    }

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
                        player.sendMessage(Text.literal("\u00A7c\u00A7o[Block scan rate limit: wait " + ((30000L - (nowMs - scanResult[0])) / 1000) + "s]"), false);
                        if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, false, "rate_limited");
                        continue;
                    }
                    final int scanR = Math.min(radius, maxRadius);
                    final boolean fIncP = incP, fIncE = incE, fIncB = incB;
                    final String fQuestion = originalQuestion;
                    final ServerPlayerEntity fPlayer = player;
                    player.getServer().execute(() -> {
                        net.minecraft.util.math.BlockPos origin = fPlayer.getBlockPos();
                        net.minecraft.world.World world = fPlayer.getServerWorld();
                        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
                        java.util.Map<String, Integer> mobCounts = new java.util.LinkedHashMap<>();
                        java.util.List<String> nearbyPlayerNames = new ArrayList<>();
                        int mobTotal = 0;
                        int playerTotal = 0;
                        int blockTotal = 0;
                        if (fIncB) {
                            for (int dx = -scanR; dx <= scanR; dx += 2) {
                                for (int dy = -scanR; dy <= scanR; dy += 2) {
                                    for (int dz = -scanR; dz <= scanR; dz += 2) {
                                        net.minecraft.util.math.BlockPos bp = origin.add(dx, dy, dz);
                                        net.minecraft.block.BlockState bs = world.getBlockState(bp);
                                        String name = Registries.BLOCK.getId(bs.getBlock()).getPath().replace('_', ' ');
                                        counts.merge(name, 1, Integer::sum);
                                        blockTotal++;
                                    }
                                }
                            }
                        }
                        if (fIncE) {
                            for (net.minecraft.entity.LivingEntity e : world.getEntitiesByClass(
                                    net.minecraft.entity.LivingEntity.class,
                                    new net.minecraft.util.math.Box(origin).expand(scanR),
                                    en -> en != fPlayer && !(en instanceof net.minecraft.entity.player.PlayerEntity))) {
                                String typeName = Registries.ENTITY_TYPE.getId(e.getType()).getPath().replace('_', ' ');
                                mobCounts.merge(typeName, 1, Integer::sum);
                                mobTotal++;
                            }
                        }
                        if (fIncP) {
                            for (net.minecraft.entity.player.PlayerEntity e : world.getEntitiesByClass(
                                    net.minecraft.entity.player.PlayerEntity.class,
                                    new net.minecraft.util.math.Box(origin).expand(scanR),
                                    en -> en != fPlayer)) {
                                String n = e.getName().getString();
                                if (!nearbyPlayerNames.contains(n)) nearbyPlayerNames.add(n);
                                playerTotal++;
                            }
                        }
                        LOGGER.info("[CraftyAI] SCAN_BLOCKS r={} \u2014 {} blocks, {} mobs, {} players", scanR, blockTotal, mobTotal, playerTotal);

                        StringBuilder scanCtx = new StringBuilder("[Block Scan Results]\n");
                        scanCtx.append("Player position: ").append(origin.getX()).append(", ").append(origin.getY()).append(", ").append(origin.getZ()).append("\n");
                        int solidAbove = 0;
                        for (int dy = 1; dy <= 5; dy++) {
                            if (!world.getBlockState(origin.add(0, dy, 0)).isAir()) solidAbove++;
                        }
                        scanCtx.append("Underground: ").append(solidAbove >= 3 ? "yes" : "no").append("\n");
                        scanCtx.append("Radius: ").append(scanR).append(" blocks\n");
                        if (!counts.isEmpty()) {
                            scanCtx.append("Blocks: ");
                            int n = 0;
                            for (java.util.Map.Entry<String, Integer> e : counts.entrySet()) {
                                if (n++ >= 12) { scanCtx.append("+").append(counts.size() - 12).append(" more"); break; }
                                if (n > 1) scanCtx.append(", ");
                                scanCtx.append(e.getKey()).append(" x").append(e.getValue());
                            }
                            scanCtx.append("\n");
                        }
                        if (!mobCounts.isEmpty()) {
                            scanCtx.append("Entities: ");
                            int n = 0;
                            for (java.util.Map.Entry<String, Integer> e : mobCounts.entrySet()) {
                                if (n++ > 0) scanCtx.append(", ");
                                scanCtx.append(e.getKey()).append(" x").append(e.getValue());
                            }
                            scanCtx.append("\n");
                        }
                        if (!nearbyPlayerNames.isEmpty()) {
                            scanCtx.append("Players nearby: ").append(String.join(", ", nearbyPlayerNames)).append("\n");
                        }
                        scanCtx.append(com.demonz.craftyai.common.ScanFlow.privacyRules());

                        String prompt = com.demonz.craftyai.common.ScanFlow.followUpPrompt(fQuestion);
                        CompletableFuture.supplyAsync(() -> sendWithRetry(prompt, fPlayer.getName().getString(), scanCtx.toString(), new ArrayList<>(), 0))
                                .thenAccept(responseBody -> {
                                    fPlayer.getServer().execute(() -> {
                                        if (fPlayer.isDisconnected()) return;
                                        String aiReply = null;
                                        if (responseBody != null && !responseBody.startsWith("__ERROR__:")) {
                                            updateTierFromResponse(responseBody);
                                            try {
                                                NeuralResponse res = GSON.fromJson(responseBody, NeuralResponse.class);
                                                aiReply = res != null ? res.getAnswer() : null;
                                            } catch (Exception ignored) {}
                                        }
                                        if (aiReply == null || aiReply.isEmpty()) {

                                            LOGGER.warn("[CraftyAI] Scan follow-up unavailable ({})", responseBody == null ? "no response" : "error");
                                            aiReply = "I scanned the area, but my thoughts got scrambled on the way back. Try again in a moment.";
                                        }
                                        fPlayer.sendMessage(Text.literal("\u00A7b[" + aiName + "] \u00A77> \u00A7f" + aiReply), false);
                                        resetCircuit();
                                    });
                                });
                    });
                    if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                } else if (upper.startsWith("SCHEDULE_TASK:")) {

                    String[] parts = action.split(":", 4);
                    if (parts.length < 4) {
                        player.sendMessage(Text.literal("\u00A7c[CraftyAI] Invalid schedule format."), false);
                        continue;
                    }
                    String cronExpr = parts[1].trim();
                    String actionType = parts[2].trim().toLowerCase();
                    String message = parts[3].trim();
                    if (!"chat".equals(actionType) && !"action".equals(actionType)) {
                        player.sendMessage(Text.literal("\u00A7c[CraftyAI] Type must be 'chat' or 'action'"), false);
                        continue;
                    }
                    final String fCron = cronExpr, fType = actionType, fMsg = message;
                    final String fName = message.length() > 40 ? message.substring(0, 40) + "..." : message;
                    player.sendMessage(Text.literal("\u00A7e[CraftyAI] Scheduling task..."), false);
                    final net.minecraft.server.network.ServerPlayerEntity fPlayer = player;
                    new Thread(() -> {
                        try {
                            String json = "{\"name\":\"" + escapeJson(fName) + "\",\"cron_expr\":\"" + escapeJson(fCron) + "\",\"action_type\":\"" + fType + "\",\"action_payload\":{\"message\":\"" + escapeJson(fMsg) + "\"}}";
                            java.net.http.HttpRequest request = GatewayHttpClientHelper.apply(java.net.http.HttpRequest.newBuilder()
                                .uri(java.net.URI.create((loadedConfig != null ? loadedConfig.getEffectiveApiUrl() : GatewayRequestHeaders.getGatewayUrl()) + "/v1/schedule-task"))
                                .header("Content-Type", "application/json")
                                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json)),
                                CLIENT_TYPE, serverId).build();
                            java.net.http.HttpResponse<String> resp = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                            String respBody = resp.body();
                            if (resp.statusCode() == 200 && respBody.contains("\"success\":true")) {
                                fPlayer.getServer().execute(() ->
                                    fPlayer.sendMessage(Text.literal("\u00A7a[CraftyAI] Task scheduled: \u00A7f" + fName + "\u00A7a (every " + fCron + ")"), false));
                            } else {
                                String err = "\"error\":\"";
                                int ei = respBody.indexOf(err);
                                String errStr = ei >= 0 ? respBody.substring(ei + err.length(), respBody.indexOf("\"", ei + err.length())) : "HTTP " + resp.statusCode();
                                fPlayer.getServer().execute(() ->
                                    fPlayer.sendMessage(Text.literal("\u00A7c[CraftyAI] Schedule failed: " + errStr), false));
                            }
                        } catch (Exception e) {
                            fPlayer.getServer().execute(() ->
                                fPlayer.sendMessage(Text.literal("\u00A7c[CraftyAI] Schedule error: " + e.getMessage()), false));
                        }
                    }, "CraftyAI-Schedule").start();
                    if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                } else if (upper.startsWith("DELAYED_ACTION:")) {
                    String[] dParts = action.split(":", 3);
                    if (dParts.length < 3) {
                        player.sendMessage(Text.literal("\u00A7c[CraftyAI] Format: DELAYED_ACTION:<seconds>:<action>"), false);
                        continue;
                    }
                    int delaySec = 0;
                    try { delaySec = Math.max(1, Math.min(300, Integer.parseInt(dParts[1].trim()))); } catch (NumberFormatException ignored) {}
                    String innerAction = dParts[2].trim();
                    if (delaySec <= 0 || innerAction.isEmpty()) {
                        player.sendMessage(Text.literal("\u00A7c[CraftyAI] Invalid delay or action"), false);
                        continue;
                    }
                    player.sendMessage(Text.literal("\u00A7e[CraftyAI] Will execute in " + delaySec + "s: \u00A7f" + innerAction), false);
                    final String fInner = innerAction;
                    final net.minecraft.server.network.ServerPlayerEntity fP = player;
                    final long fDelay = delaySec * 1000L;
                    final net.minecraft.server.MinecraftServer fSrv = player.getServer();
                    new Thread(() -> {
                        try { Thread.sleep(fDelay); } catch (InterruptedException ie) { return; }
                        fSrv.execute(() -> {
                            fP.sendMessage(Text.literal("\u00A7a[CraftyAI] Executing delayed action: \u00A7f" + fInner), false);
                            handleActionInternal(fP, fInner);
                        });
                    }, "CraftyAI-Delay").start();
                    if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "delayed");
                } else if (upper.startsWith("CHAT:")) {
                    String cmd = action.substring("CHAT:".length()).trim();
                    if (cmd.startsWith("/")) cmd = cmd.substring(1);
                    cmd = normalizeLocateCommand(cmd);
                    if (!AgenticActions.isAllowedChatCommand(cmd)) {
                        player.sendMessage(Text.literal("\u00A7c[CraftyAI] Blocked unsafe AI command. Only /locate is allowed."), false);
                        if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, false, "command not allowlisted");
                        continue;
                    }
                    final String finalCmd = cmd;
                    final net.minecraft.server.MinecraftServer srv = player.getServer();
                    final net.minecraft.server.network.ServerPlayerEntity p = player;
                    srv.execute(() -> {
                        try {
                            srv.getCommandManager().executeWithPrefix(p.getCommandSource(), "/" + finalCmd);
                            p.sendMessage(Text.literal("\u00A77[CraftyAI] Ran: /" + finalCmd), false);
                        } catch (Exception e) {
                            LOGGER.warn("[CraftyAI] CHAT command failed: " + finalCmd + " \u2014 " + e.getMessage());
                            p.sendMessage(Text.literal("\u00A7c[CraftyAI] Command failed: " + e.getMessage()), false);
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

                GatewayHttpClientHelper.applyCustomProvider(requestBuilder, effectiveUrl, effectiveKey, CLIENT_TYPE, serverId);
            } else {

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

                GatewayHttpClientHelper.apply(requestBuilder, CLIENT_TYPE, serverId);
            }

            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                if (loadedConfig.custom_provider_enabled) {

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
                Thread.sleep((long) Math.pow(2, attempt + 1) * 1000);
                return sendWithRetry(question, playerName, context, history, attempt + 1);
            }
            return "__ERROR__:Server error (HTTP " + response.statusCode() + ")";
        } catch (Exception e) {
            if (attempt < MAX_RETRIES) {
                try { Thread.sleep((long) Math.pow(2, attempt + 1) * 1000); } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    LOGGER.debug("[CraftyAI] Retry sleep interrupted");
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

    private boolean isOnCooldown(ServerPlayerEntity player) {
        long now = System.currentTimeMillis();
        final long[] previousTime = {0};
        chatCooldowns.compute(player.getUuid(), (key, prev) -> {
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

    private void handleLinkCommand(ServerPlayerEntity player) {
        player.sendMessage(Text.literal("\u00A7b[CraftyAI] \u00A77Generating link code for Discord linking..."), false);
        HttpRequest request = GatewayHttpClientHelper.apply(HttpRequest.newBuilder()
                .uri(URI.create(gatewayUrl + "/v1/link-generate"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")), CLIENT_TYPE, serverId)
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    player.getServer().execute(() -> {
                        if (response.statusCode() == 200) {
                            try {
                                com.google.gson.JsonObject json = com.demonz.craftyai.common.JsonParserAdapter.parse(response.body()).getAsJsonObject();
                                String code = json.has("code") && !json.get("code").isJsonNull() ? json.get("code").getAsString() : "";
                                if (!code.isEmpty()) {
                                    player.sendMessage(Text.literal("\u00A7a========================================="), false);
                                    player.sendMessage(Text.literal("\u00A7a [CraftyAI] DISCORD LINK CODE"), false);
                                    player.sendMessage(Text.literal("\u00A7a Code: \u00A7b" + code), false);
                                    player.sendMessage(Text.literal("\u00A7a Expiry: 15 minutes"), false);
                                    player.sendMessage(Text.literal("\u00A7a"), false);
                                    player.sendMessage(Text.literal("\u00A7a Tell your server owner to run:"), false);
                                    player.sendMessage(Text.literal("\u00A7a   /link " + code), false);
                                    player.sendMessage(Text.literal("\u00A7a in Discord to link this server."), false);
                                    player.sendMessage(Text.literal("\u00A7a========================================="), false);
                                } else {
                                    player.sendMessage(Text.literal("\u00A7c[CraftyAI] No link code returned."), false);
                                }
                            } catch (Exception e) {
                                player.sendMessage(Text.literal("\u00A7c[CraftyAI] Failed to parse link response."), false);
                            }
                        } else {
                            player.sendMessage(Text.literal("\u00A7c[CraftyAI] Link code generation failed (HTTP " + response.statusCode() + ")."), false);
                        }
                    });
                })
                .exceptionally(e -> {
                    player.getServer().execute(() -> player.sendMessage(Text.literal("\u00A7c[CraftyAI] Link failed: " + e.getMessage()), false));
                    return null;
                });
    }
}
