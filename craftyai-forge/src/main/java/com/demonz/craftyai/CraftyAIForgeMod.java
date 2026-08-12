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

import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
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
 * CraftyAI V1.0 — Forge Mod (1.20+)
 */
@Mod(CraftyAIForgeMod.MOD_ID)
public class CraftyAIForgeMod {


    public static final String MOD_ID = "craftyai";
    public static CraftyAIForgeMod INSTANCE;
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final Gson GSON = new GsonBuilder().create();

    private static final int MAX_RETRIES = 3;
    private static final int MAX_HISTORY = 10;
    private static final String CLIENT_TYPE = "minecraft-forge";
    private static final int MAX_ACTIONS_PER_REQUEST = 5;

    private static final Set<String> GIVE_BLACKLIST = Set.of(
        "barrier", "command_block", "chain_command_block",
        "repeating_command_block", "command_block_minecart",
        "structure_block", "structure_void", "bedrock",
        "end_portal_frame", "spawner"
    );

    private String aiName = "Crafty";
    private List<String> aliases = Arrays.asList("crafty", "craftyai", "ai", "helper");
    private String gatewayUrl = GatewayRequestHeaders.getGatewayUrl();
    private String apiKey = "";
    private String serverId = "";
    private String prefix = "@";
    private boolean requirePrefix = false;
    private int cooldownMs = 3000;
    private boolean opWelcomeEnabled = true;
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

    public CraftyAIForgeMod() {
        INSTANCE = this;
        // Register game events (chat, commands, player join/leave) on the Forge Event Bus
        MinecraftForge.EVENT_BUS.register(this);
        // Register mod lifecycle events (FMLCommonSetupEvent) on the Mod Event Bus
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onCommonSetup);
        VisionScanner.registerProvider(new ForgeVisionScannerProvider());
        localBrain = new LocalBrain("config", LOGGER::info);

        // Register server stopping lifecycle event to shut down the scheduler
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.server.ServerStoppingEvent event) -> {
            scheduler.shutdownNow();
            LOGGER.info("[CraftyAI] Scheduler shut down on server stop.");
        });
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            LiteralArgumentBuilder.<CommandSourceStack>literal("crafty")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal("\u00A7b\u00A7lCraftyAI \u00A77v" + GatewayRequestHeaders.MOD_VERSION + " \u00A78- \u00A77DemonZ Development"), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("\u00A77Commands: \u00A7f/crafty ask <question> \u00A78| \u00A7f/crafty scan \u00A78| \u00A7f/crafty confirm"), false);
                    return 1;
                })
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("help").executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal("\u00A7b\u00A7l\u00A7m------------\u00A7r \u00A76crafty ai v" + GatewayRequestHeaders.MOD_VERSION + " \u00A7b\u00A7l\u00A7m------------"), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("\u00A77Neural AI companion by \u00A7bDemonZ Development\u00A77."), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("\u00A77Type \u00A7e" + prefix + aiName.toLowerCase() + " <question> \u00A77in chat, or use these commands:"), false);
                    ctx.getSource().sendSuccess(() -> Component.literal(""), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("\u00A7e/crafty status   \u00A78- \u00A77Show plugin status and tier info"), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("\u00A7e/crafty ask <q>  \u00A78- \u00A77Ask crafty ai privately"), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("\u00A7e/crafty scan     \u00A78- \u00A77Vision scan nearby area"), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("\u00A7e/crafty confirm  \u00A78- \u00A77Confirm a pending destructive action"), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("\u00A7e/crafty pro      \u00A78- \u00A77Pro tier management"), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("\u00A7e/crafty link     \u00A78- \u00A77Link this server to Discord"), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("\u00A7e/crafty reload   \u00A78- \u00A77Reload configuration (admin)"), false);
                    return 1;
                }))
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("ask")
                    .then(RequiredArgumentBuilder.<CommandSourceStack, String>argument("question", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            String question = StringArgumentType.getString(ctx, "question");
                            if (isOnCooldown(player)) {
                                player.sendSystemMessage(Component.literal("\u00A7c[CraftyAI] Please wait before asking again."));
                                return 0;
                            }
                            // /crafty ask is private — response only to the player
                            processAIRequest(player, question, false);
                            return 1;
                        })
                    )
                )
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("reload")
                    .executes(ctx -> {
                        if (ctx.getSource().getEntity() != null) {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            if (!player.hasPermissions(2)) {
                                ctx.getSource().sendSuccess(() -> Component.literal("\u00A7c[CraftyAI] Only operators can reload config."), false);
                                return 0;
                            }
                        }
                        reloadConfig();
                        ctx.getSource().sendSuccess(() -> Component.literal("\u00A7a[CraftyAI] Configuration reloaded from disk."), false);
                        return 1;
                    })
                )
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("scan")
                    .executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        VisionScanner.ScanResult scanResult = VisionScanner.scan(player, player.serverLevel());
                        player.sendSystemMessage(Component.literal("§b§l[CraftyAI Vision Scan]"));
                        player.sendSystemMessage(Component.literal("§7Biome: §f" + scanResult.biome.name));
                        player.sendSystemMessage(Component.literal("§7Dimension: §f" + scanResult.dimension));
                        player.sendSystemMessage(Component.literal("§7Time: §f" + scanResult.timeOfDay + " §8| §7Weather: §f" + scanResult.weather));
                        player.sendSystemMessage(Component.literal("§7Health: §f" + scanResult.health + "/" + scanResult.maxHealth + " §8| §7Food: §f" + scanResult.foodLevel + "/20"));
                        player.sendSystemMessage(Component.literal("§7Entities: §f" + scanResult.nearbyEntities.size() + " §8| §7Blocks: §f" + scanResult.nearbyBlocks.size()));
                        player.sendSystemMessage(Component.literal("§7GameMode: §f" + scanResult.gameMode + " §8| §7OP: §f" + (scanResult.hasOp ? "Yes" : "No")));
                        if (!scanResult.activeEffects.isEmpty()) {
                            player.sendSystemMessage(Component.literal("§7Effects: §f" + String.join(", ", scanResult.activeEffects)));
                        }
                        return 1;
                    })
                )
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("confirm")
                    .executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        if (!player.hasPermissions(2)) {
                            player.sendSystemMessage(Component.literal("§c[CraftyAI] Only operators can confirm actions."));
                            return 0;
                        }
                        String playerKey = player.getUUID().toString();
                        if (actionConfirmations == null) {
                            player.sendSystemMessage(Component.literal("§c[CraftyAI] Confirmation system unavailable."));
                            return 0;
                        }
                        String pending = actionConfirmations.peek(playerKey);
                        if (pending == null) {
                            player.sendSystemMessage(Component.literal("§7[CraftyAI] No destructive action pending. Re-run your request first."));
                        } else {
                            player.sendSystemMessage(Component.literal("§a[CraftyAI] Confirmed pending action: §f" + pending.toUpperCase() + "§a. Re-run your request to execute it now."));
                        }
                        return 1;
                    })
                )
                // /crafty link — generate Discord link code
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("link")
                    .requires(source -> {
                        try { return source.getPlayerOrException().hasPermissions(2); }
                        catch (Exception e) { return false; }
                    })
                    .executes(ctx -> {
                        ServerPlayer player = null;
                        try { player = ctx.getSource().getPlayerOrException(); } catch (Exception ignored) {}
                        if (player == null) {
                            ctx.getSource().sendSuccess(() -> Component.literal("§cOnly players can execute this command."), false);
                            return 0;
                        }
                        handleLinkCommand(player);
                        return 1;
                    })
                )
                // /crafty pro <status|help> — show Pro tier info
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("pro")
                    .executes(ctx -> {
                        String tierStr = (loadedConfig != null && loadedConfig.tier != null && !loadedConfig.tier.isEmpty()) ? loadedConfig.tier : "free";
                        String tierUpper = tierStr.toUpperCase();
                        String tierColor = tierStr.equalsIgnoreCase("pro") ? "§6"
                                : tierStr.equalsIgnoreCase("enterprise") ? "§d" : "§f";
                        ServerPlayer player = null;
                        try { player = ctx.getSource().getPlayerOrException(); } catch (Exception ignored) {}
                        if (player != null) {
                            player.sendSystemMessage(Component.literal("§b[CraftyAI] Pro Status:"));
                            player.sendSystemMessage(Component.literal("§7  Tier: " + tierColor + tierUpper));
                            player.sendSystemMessage(Component.literal("§7  To upgrade: open a Pro ticket on Discord"));
                            player.sendSystemMessage(Component.literal("§7  §bhttps://discord.gg/zCkE44hsBR"));
                        } else {
                            ctx.getSource().sendSuccess(() -> Component.literal("§b[CraftyAI] Tier: " + tierColor + tierUpper), false);
                        }
                        return 1;
                    })
                    .then(LiteralArgumentBuilder.<CommandSourceStack>literal("status")
                        .executes(ctx -> {
                            String tierStr = (loadedConfig != null && loadedConfig.tier != null && !loadedConfig.tier.isEmpty()) ? loadedConfig.tier : "free";
                            String tierUpper = tierStr.toUpperCase();
                            String tierColor = tierStr.equalsIgnoreCase("pro") ? "§6"
                                    : tierStr.equalsIgnoreCase("enterprise") ? "§d" : "§f";
                            ServerPlayer player = null;
                            try { player = ctx.getSource().getPlayerOrException(); } catch (Exception ignored) {}
                            if (player != null) {
                                player.sendSystemMessage(Component.literal("§b[CraftyAI] Pro Status:"));
                                player.sendSystemMessage(Component.literal("§7  Tier: " + tierColor + tierUpper));
                            }
                            return 1;
                        })
                    )
                    .then(LiteralArgumentBuilder.<CommandSourceStack>literal("help")
                        .executes(ctx -> {
                            ServerPlayer player = null;
                            try { player = ctx.getSource().getPlayerOrException(); } catch (Exception ignored) {}
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
                    .then(LiteralArgumentBuilder.<CommandSourceStack>literal("redeem")
                        .then(RequiredArgumentBuilder.<CommandSourceStack, String>argument("key", StringArgumentType.greedyString())
                            .executes(ctx -> {
                                ServerPlayer player = null;
                                try { player = ctx.getSource().getPlayerOrException(); } catch (Exception ignored) {}
                                if (player == null) return 0;
                                if (!player.hasPermissions(2)) {
                                    player.sendSystemMessage(Component.literal("§c[CraftyAI] Only operators can redeem Pro keys."));
                                    return 0;
                                }
                                String proKey = StringArgumentType.getString(ctx, "key").trim();
                                String lowerProKey = proKey.toLowerCase();
                                if (!lowerProKey.startsWith("cai_pro_") && !lowerProKey.startsWith("cai_pro-")) {
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
        );
    }

    // Registered on the Mod Event Bus via constructor (FMLJavaModLoadingContext)
    public void onCommonSetup(FMLCommonSetupEvent event) {
        loadConfig();
        LOGGER.info("[CraftyAI] Neural Engine Online — Forge Mod v" + com.demonz.craftyai.common.GatewayRequestHeaders.MOD_VERSION);
        
        if (telemetryEnabled) {
            performStartupHandshake();
            // Control-plane heartbeat: five minutes matches the negotiated v2 cadence.
            scheduler.scheduleWithFixedDelay(this::performStartupHandshake, 5, 5, TimeUnit.MINUTES);
        } else {
            LOGGER.info("[CraftyAI] Telemetry disabled. Status reporting is inactive.");
        }

        // Check for updates
        UpdateChecker.checkForUpdatesAsync(LOGGER::info);
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
                net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get());
        // If config has a saved server_id that differs from session (e.g. session file deleted),
        // restore the config's server_id so API key stays matched to its server
        if (loadedConfig.server_id != null && !loadedConfig.server_id.isEmpty()
                && !loadedConfig.server_id.equals(this.serverId)) {
            com.demonz.craftyai.common.SessionManager.setSessionId(
                    net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get(), loadedConfig.server_id);
            this.serverId = loadedConfig.server_id;
        }
        // Custom provider support: use custom URL/key if enabled
        if (loadedConfig.custom_provider_enabled && loadedConfig.custom_provider_url != null && !loadedConfig.custom_provider_url.isEmpty()) {
            this.gatewayUrl = loadedConfig.custom_provider_url;
            if (loadedConfig.custom_provider_key != null && !loadedConfig.custom_provider_key.isEmpty()) {
                this.apiKey = loadedConfig.custom_provider_key;
            }
            LOGGER.info("[CraftyAI] Custom provider enabled: {}", loadedConfig.custom_provider_url);
        } else {
            this.gatewayUrl = GatewayRequestHeaders.getGatewayUrl();
        }
        this.opWelcomeEnabled = loadedConfig.op_welcome_message;
        this.telemetryEnabled = loadedConfig.telemetry_enabled;
        this.prefix = loadedConfig.prefix != null ? loadedConfig.prefix : "@";
        this.requirePrefix = loadedConfig.require_prefix;
        // Apply cooldown_seconds from config
        if (loadedConfig.cooldown_seconds > 0) {
            this.cooldownMs = loadedConfig.cooldown_seconds * 1000;
        }
        // Apply aliases from config if available
        if (loadedConfig.aliases != null && loadedConfig.aliases.length > 0) {
            this.aliases = Arrays.asList(loadedConfig.aliases);
        }
        // Initialize security helpers (audit log, rate limiter, confirmation flow)
        String configDir = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get().toString();
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

    /**
     * Reload config from disk (called via /crafty reload or settings save).
     */
    public void reloadConfig() {
        loadConfig();
        try {
            CraftyAIForgeModClient.reloadConfig();
            if (loadedConfig != null) CraftyAIForgeModClient.setTierFromServer(loadedConfig.tier);
        } catch (NoClassDefFoundError ignored) { /* dedicated server — no client module */ }
        LOGGER.info("[CraftyAI] Configuration reloaded from disk.");
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
        payload.put("software_name", "forge");
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
                final net.minecraft.server.MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
                String color = "critical".equals(severity) ? "\u00A7c\u00A7l" : "warning".equals(severity) ? "\u00A7e\u00A7l" : "\u00A7b\u00A7l";
                LOGGER.info("[Control] {}: {}", title, message);
                if (server != null) {
                    server.execute(() -> {
                        Component text = Component.literal(color + "[" + title + "] \u00A77" + message);
                        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                            if (player.hasPermissions(2)) player.sendSystemMessage(text);
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
                CraftyAIForgeMod.this.reloadConfig();
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
        payload.put("name", "Forge Server");
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
                                    net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get(), newServerId);
                            }
                            loadedConfig.tier = newTier;
                            LOGGER.info("=========================================");
                            LOGGER.info("[CraftyAI] NEW API KEY (save now, never shown again):");
                            LOGGER.info("  {}", newKey);
                            LOGGER.info("  Tier: {}", newTier);
                            LOGGER.info("  Server ID: {}", newServerId);
                            LOGGER.info("=========================================");
                            try {
                                CraftyAIForgeModClient.reloadConfig();
                                CraftyAIForgeModClient.setTierFromServer(newTier);
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
                
                // Log to console
                LOGGER.warn("[CraftyAI] Session Warning: " + message.replaceAll("§.", ""));
                
                // Broadcast to online admins
                net.minecraft.server.MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
                if (server != null) {
                    Component chatMsg = Component.literal("§c§l[CraftyAI Warning] §7" + message);
                    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                        if (player.hasPermissions(2)) {
                            player.sendSystemMessage(chatMsg);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[CraftyAI] Warning parse error: {}", e.getMessage());
        }
    }

    @SubscribeEvent
    public void onPlayerLogout(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            conversationCache.remove(player.getUUID());
        }
    }

    @SubscribeEvent
    public void onPlayerJoin(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!player.hasPermissions(2)) return;

        if (opWelcomeEnabled) {
            player.sendSystemMessage(Component.literal(""));
            player.sendSystemMessage(Component.literal("§b§l\u2726 CraftyAI §7v" + GatewayRequestHeaders.MOD_VERSION + " §8\u2014 §7Neural AI Companion"));
            player.sendSystemMessage(Component.literal("§7  Thanks for using CraftyAI by §bDemonZ Development§7!"));
            player.sendSystemMessage(Component.literal("§7  Type §e" + prefix + aiName.toLowerCase() + " <question> §7to chat."));
            player.sendSystemMessage(Component.literal(""));
        }

        // Fetch and deliver any queued warnings
        CompletableFuture.supplyAsync(() -> {
            try {
                Map<String, String> payload = new HashMap<>();
                payload.put("version", GatewayRequestHeaders.MOD_VERSION);
                payload.put("client_type", CLIENT_TYPE);
                payload.put("server_id", serverId);

                HttpRequest req = GatewayHttpClientHelper.apply(HttpRequest.newBuilder()
                        .uri(URI.create(gatewayUrl + "/v1/handshake"))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload))), CLIENT_TYPE, serverId)
                        .build();

                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) return resp.body();
            } catch (Exception ignored) {}
            return null;
        }).thenAccept(body -> {
            if (body == null) return;
            updateTierFromResponse(body);
            try {
                com.google.gson.JsonObject json = com.demonz.craftyai.common.JsonParserAdapter.parse(body).getAsJsonObject();
                if (!json.has("warnings")) return;
                com.google.gson.JsonArray warnings = json.getAsJsonArray("warnings");
                if (warnings.size() == 0) return;

                player.getServer().execute(() -> {
                    player.sendSystemMessage(Component.literal(""));
                    player.sendSystemMessage(Component.literal("§c§l\u26A0 CraftyAI Session Warnings \u26A0"));
                    for (com.google.gson.JsonElement warnEl : warnings) {
                        if (!warnEl.isJsonObject()) continue;
                        String message = warnEl.getAsJsonObject().has("message") ? warnEl.getAsJsonObject().get("message").getAsString() : "Unknown warning";
                        player.sendSystemMessage(Component.literal(message));
                    }
                    player.sendSystemMessage(Component.literal(""));
                });
            } catch (Exception ignored) {}
        });
    }

    @SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        String question = extractQuestion(event.getRawText());
        if (question == null) return;

        if (isOnCooldown(player)) {
            player.sendSystemMessage(Component.literal("§7[CraftyAI] Please wait before sending another request."));
            return;
        }

        String visibility = loadedConfig.response_visibility != null ? loadedConfig.response_visibility : "default";
        boolean isPrivate = visibility.equalsIgnoreCase("always-private") ||
                (visibility.equalsIgnoreCase("default") && event.getRawText().trim().startsWith(prefix));
        if (visibility.equalsIgnoreCase("always-public")) {
            isPrivate = false;
        }

        // Cancel the event and manually broadcast/send the player's message first to guarantee correct ordering
        event.setCanceled(true);
        if (isPrivate) {
            player.sendSystemMessage(Component.literal("<" + player.getName().getString() + "> " + event.getRawText()));
        } else {
            broadcastToAll(player, Component.literal("<" + player.getName().getString() + "> " + event.getRawText()));
        }

        processAIRequest(player, question, !isPrivate);
    }

    /**
     * Process an AI request — if isPublic, broadcast response to all players; otherwise private to sender.
     */
    private void processAIRequest(ServerPlayer player, String question, boolean isPublic) {
        VisionScanner.ScanResult scanResult = VisionScanner.scan(player, player.serverLevel());
        String context = scanResult.toContextString();

        LinkedList<Map<String, String>> history = conversationCache.computeIfAbsent(player.getUUID(), k -> new LinkedList<>());
        List<Map<String, String>> historyCopy;
        synchronized (history) {
            historyCopy = new ArrayList<>(history);
        }

        if (isCircuitOpen() || (loadedConfig != null && loadedConfig.force_local_mode)) {
            String offlineResponse = localBrain.generateOfflineResponse(question, player.getName().getString());
            Component offlineMsg = Component.literal("§b[" + aiName + "] §7(Offline) §f" + offlineResponse);
            if (isPublic) {
                broadcastToAll(player, offlineMsg);
            } else {
                player.sendSystemMessage(offlineMsg);
            }
            return;
        }

        player.sendSystemMessage(Component.literal("§b§l" + aiName.toUpperCase() + " IS THINKING..."), true);

        CompletableFuture.supplyAsync(() -> sendWithRetry(question, player.getName().getString(), context, historyCopy, 0))
                .thenAccept(body -> {
                    player.getServer().execute(() -> {
                        player.sendSystemMessage(Component.literal(""), true);
                        if (body != null && body.startsWith("__ERROR__:")) {
                            String errorMsg = body.substring("__ERROR__:".length());
                            Component errorMsgComp = Component.literal("§c[CraftyAI] " + errorMsg);
                            player.sendSystemMessage(errorMsgComp);
                            String offlineResponse = localBrain.generateOfflineResponse(question, player.getName().getString());
                            Component fallbackMsg = Component.literal("§b[" + aiName + "] §7(Fallback) §f" + offlineResponse);
                            if (isPublic) {
                                broadcastToAll(player, fallbackMsg);
                            } else {
                                player.sendSystemMessage(fallbackMsg);
                            }
                        } else if (body != null) {
                            updateTierFromResponse(body);
                            NeuralResponse res = null;
                            try {
                                res = GSON.fromJson(body, NeuralResponse.class);
                            } catch (Exception e) {
                                LOGGER.warn("[CraftyAI] Failed to parse AI response: " + e.getMessage());
                            }
                            String answer = res != null ? res.getAnswer() : null;
                            if (answer != null && !answer.isEmpty()) {
                                Component responseMsg = Component.literal("§b[" + aiName + "] §7> §f" + answer);
                                if (isPublic) {
                                    broadcastToAll(player, responseMsg);
                                } else {
                                    player.sendSystemMessage(Component.literal("§8[Private] ").append(responseMsg));
                                }
                                addToHistory(player.getUUID(), question, answer);
                            }
                            String action = res != null ? res.getAction() : null;
                            if (loadedConfig.ai_enable_actions && loadedConfig.agentic_tasks_enabled) {
                                if (action == null || action.isEmpty()) {
                                    action = inferActionFromText(answer, question);
                                }
                                if (action != null && action.toUpperCase().startsWith("SCHEDULE_TASK:") && question != null && question.toLowerCase().matches(".*\\b(in|after|wait)\\s+\\d+\\s*(seconds?|sec|minutes?|min)\\b.*")) {
                                    action = "DELAYED_ACTION:" + question.replaceAll(".*?(\\d+)\\s*(second|sec|min|minute).*", "$1") + ":TELEPORT_SPAWN";
                                }
                                if (action != null) {
                                    handleAction(player, action);
                                }
                            }
                            resetCircuit();
                        } else {
                            String offlineResponse = localBrain.generateOfflineResponse(question, player.getName().getString());
                            Component offlineMsg = Component.literal("§b[" + aiName + "] §7(Offline) §f" + offlineResponse);
                            if (isPublic) {
                                broadcastToAll(player, offlineMsg);
                            } else {
                                player.sendSystemMessage(offlineMsg);
                            }
                        }
                    });
                });
    }

    /**
     * Broadcast a message to all online players.
     */
    private void broadcastToAll(ServerPlayer source, Component message) {
        net.minecraft.server.MinecraftServer server = source.getServer();
        if (server != null) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                p.sendSystemMessage(message);
            }
        }
    }

    /**
     * v1.2.0-beta: Redeem a Pro key via /v1/pro/redeem
     */
    private void redeemProKey(ServerPlayer player, String proKey) {
        try {
            // Use the already-loaded serverId instead of broken file.listFiles() on a regular file.
            String sessionId = this.serverId != null && !this.serverId.isEmpty() ? this.serverId : "unknown";
            java.net.URI uri = java.net.URI.create(GatewayRequestHeaders.getGatewayUrl() + "/v1/pro/redeem");
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-Client-Type", "minecraft-forge");
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

    private void handleAction(ServerPlayer player, String actionString) {
        if (actionString == null || actionString.trim().isEmpty() || actionString.equalsIgnoreCase("null")) return;
        if (!player.hasPermissions(2)) {
            player.sendSystemMessage(Component.literal("§c[CraftyAI] Missing permission: crafty.agentic — action not executed"));
            return;
        }
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

    private void handleActionInternal(ServerPlayer player, String actionString) {

        // Agentic tasks are FREE for all tiers.
        // No tier check. Only config + OP permission gates remain.

        // Check local configuration
        if (!loadedConfig.agentic_tasks_enabled || !loadedConfig.ai_enable_actions) {
            player.sendSystemMessage(Component.literal("§8[CraftyAI] §7Action suggested: §e" + actionString + " §8(agentic tasks disabled in config)"));
            LOGGER.info("[CraftyAI] Skipped agentic action {} — agentic tasks disabled", actionString);
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
            // On Forge, per-action perms map to OP-level (no granular perm mod bundled).
            // The perm string is logged for admin audit trails even when allowed.
            String perm = AgenticActions.permissionFor(action);
            if (perm != null) {
                // Forge has no LuckPerms; enforce via OP level 2 (matched to the master
                // hasPermissions(2) gate at the top of this method).
                if (!player.hasPermissions(2)) {
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
                    player.getServer().execute(() -> player.serverLevel().setDayTime(1000));
                    player.sendSystemMessage(Component.literal("§eTime set to day."));
                    if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                    continue;
                case "TIME_NIGHT":
                    player.getServer().execute(() -> player.serverLevel().setDayTime(13000));
                    player.sendSystemMessage(Component.literal("§9Time set to night."));
                    if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                    continue;
                case "WEATHER_CLEAR":
                    player.getServer().execute(() -> player.serverLevel().setWeatherParameters(12000, 0, false, false));
                    player.sendSystemMessage(Component.literal("§bWeather cleared."));
                    if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                    continue;
                case "WEATHER_RAIN":
                    player.getServer().execute(() -> player.serverLevel().setWeatherParameters(0, 12000, true, false));
                    player.sendSystemMessage(Component.literal("§9Weather set to rain."));
                    if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                    continue;
                case "WEATHER_THUNDER":
                    player.getServer().execute(() -> player.serverLevel().setWeatherParameters(0, 12000, true, true));
                    player.sendSystemMessage(Component.literal("§c⚡ Thunderstorm activated"));
                    if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                    continue;
                case "HEAL":
                    player.getServer().execute(() -> player.setHealth(player.getMaxHealth()));
                    player.sendSystemMessage(Component.literal("§aHealed!"));
                    if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                    continue;
                case "FEED":
                    player.getServer().execute(() -> {
                        player.getFoodData().setFoodLevel(20);
                        player.getFoodData().setSaturation(20f);
                    });
                    player.sendSystemMessage(Component.literal("§6Fully fed!"));
                    if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                    continue;
                case "KILL_MOBS":
                    player.getServer().execute(() -> {
                        int killed = 0;
                        for (net.minecraft.world.entity.LivingEntity entity : player.level().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                                new net.minecraft.world.phys.AABB(player.blockPosition()).inflate(50))) {
                            if (entity != player && entity instanceof net.minecraft.world.entity.monster.Monster) {
                                entity.die(player.damageSources().generic());
                                killed++;
                            }
                        }
                        player.sendSystemMessage(Component.literal("§c☠ Eliminated " + killed + " nearby mobs"));
                    });
                    if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                    continue;
                case "TELEPORT_SPAWN":
                    player.getServer().execute(() -> {
                        net.minecraft.server.level.ServerLevel level = player.serverLevel();
                        net.minecraft.core.BlockPos spawn = level.getSharedSpawnPos();
                        player.teleportTo(level, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, player.getYRot(), player.getXRot());
                    });
                    player.sendSystemMessage(Component.literal("§d✨ Teleporting to spawn..."));
                    if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                    continue;
                case "GAMEMODE_CREATIVE":
                    player.getServer().execute(() -> player.setGameMode(net.minecraft.world.level.GameType.CREATIVE));
                    player.sendSystemMessage(Component.literal("§bGamemode updated to Creative."));
                    if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                    continue;
                case "GAMEMODE_SURVIVAL":
                    player.getServer().execute(() -> player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL));
                    player.sendSystemMessage(Component.literal("§bGamemode updated to Survival."));
                    if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                    continue;
                case "GAMEMODE_SPECTATOR":
                    player.getServer().execute(() -> player.setGameMode(net.minecraft.world.level.GameType.SPECTATOR));
                    player.sendSystemMessage(Component.literal("§bGamemode updated to Spectator."));
                    if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                    continue;
            }

            // --- PARAMETERIZED ACTIONS ---
            try {
                if (upper.startsWith("GIVE:")) {
                    String[] parts = action.split(":", 3);
                    if (parts.length >= 2) {
                        if (GIVE_BLACKLIST.contains(parts[1].toLowerCase().trim())) {
                            player.sendSystemMessage(Component.literal("§cCannot give blacklisted item."));
                            if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, false, "blacklisted item");
                            continue;
                        }
                        player.getServer().execute(() -> {
                            try {
                                net.minecraft.resources.ResourceLocation id = new net.minecraft.resources.ResourceLocation("minecraft", parts[1].toLowerCase().trim());
                                net.minecraft.world.item.Item item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(id);
                                if (item != null) {
                                    int amount = 1;
                                    if (parts.length >= 3) {
                                        try {
                                            amount = Integer.parseInt(parts[2].trim());
                                        } catch (NumberFormatException ignored) {}
                                    }
                                    player.getInventory().add(new net.minecraft.world.item.ItemStack(item, Math.min(64, amount)));
                                    player.sendSystemMessage(Component.literal("§aReceived " + amount + "x " + parts[1]));
                                }
                            } catch (Exception ignored) {}
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
                        } catch (NumberFormatException ignored) {}
                        final int duration = dur;
                        player.getServer().execute(() -> {
                            net.minecraft.world.entity.LivingEntity target = player;
                            net.minecraft.world.effect.MobEffect mobEffect =
                                net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT.get(
                                    new net.minecraft.resources.ResourceLocation("minecraft", effect)
                                );
                            if (mobEffect != null) {
                                try {
                                    for (java.lang.reflect.Constructor<?> c : net.minecraft.world.effect.MobEffectInstance.class.getConstructors()) {
                                        if (c.getParameterCount() >= 2) {
                                            try {
                                                Object inst = c.newInstance(mobEffect, duration * 20, 0, false, true);
                                                target.addEffect((net.minecraft.world.effect.MobEffectInstance) inst);
                                                break;
                                            } catch (Throwable ignored) {
                                                try {
                                                    Object holder = net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT.wrapAsHolder(mobEffect);
                                                    Object inst = c.newInstance(holder, duration * 20, 0, false, true);
                                                    target.addEffect((net.minecraft.world.effect.MobEffectInstance) inst);
                                                    break;
                                                } catch (Throwable ignored2) {}
                                            }
                                        }
                                    }
                                } catch (Throwable ignored) {}
                                player.sendSystemMessage(Component.literal("§d✨ Applied " + effect + " for " + duration + "s"));
                            }
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
                        } catch (NumberFormatException ignored) {}
                        final int level = lvl;
                        player.getServer().execute(() -> {
                            net.minecraft.world.item.ItemStack held = player.getMainHandItem();
                            if (held.isEmpty()) {
                                player.sendSystemMessage(Component.literal("§cHold an item in your main hand to enchant it."));
                                return;
                            }
                            net.minecraft.world.item.enchantment.Enchantment ench =
                                net.minecraft.core.registries.BuiltInRegistries.ENCHANTMENT.get(
                                    new net.minecraft.resources.ResourceLocation("minecraft", enchant)
                                );
                            if (ench != null) {
                                held.enchant(ench, level);
                                player.sendSystemMessage(Component.literal("§b✨ Enchanted with " + enchant + " " + level));
                            }
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
                            player.getServer().execute(() -> {
                                player.teleportTo(player.serverLevel(), dx, dy, dz, player.getYRot(), player.getXRot());
                            });
                            player.sendSystemMessage(Component.literal("§d✨ Teleporting to " + x + ", " + y + ", " + z));
                            if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                        } catch (NumberFormatException e) {
                            player.sendSystemMessage(Component.literal("§cInvalid coordinates: " + x + " " + y + " " + z));
                            if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, false, "invalid coordinates");
                        }
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
                    player.getServer().execute(() -> {
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
                        player.sendSystemMessage(Component.literal("§c[CraftyAI] Invalid schedule format."));
                        continue;
                    }
                    String cronExpr = parts[1].trim();
                    String actionType = parts[2].trim().toLowerCase();
                    String message = parts[3].trim();
                    if (!"chat".equals(actionType) && !"action".equals(actionType)) {
                        player.sendSystemMessage(Component.literal("§c[CraftyAI] Type must be 'chat' or 'action'"));
                        continue;
                    }
                    final String fCron = cronExpr, fType = actionType, fMsg = message;
                    final String fName = message.length() > 40 ? message.substring(0, 40) + "..." : message;
                    player.sendSystemMessage(Component.literal("§e[CraftyAI] Scheduling task..."));
                    final net.minecraft.server.level.ServerPlayer fPlayer = player;
                    new Thread(() -> {
                        try {
                            String json = "{\"name\":\"" + escapeJson(fName) + "\",\"cron_expr\":\"" + escapeJson(fCron) + "\",\"action_type\":\"" + fType + "\",\"action_payload\":{\"message\":\"" + escapeJson(fMsg) + "\"}}";
                            java.net.http.HttpRequest request = GatewayHttpClientHelper.apply(java.net.http.HttpRequest.newBuilder()
                                .uri(java.net.URI.create(GatewayRequestHeaders.getGatewayUrl() + "/v1/schedule-task"))
                                .header("Content-Type", "application/json")
                                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json)),
                                CLIENT_TYPE, serverId).build();
                            java.net.http.HttpResponse<String> resp = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                            String respBody = resp.body();
                            if (resp.statusCode() == 200 && respBody.contains("\"success\":true")) {
                                fPlayer.getServer().execute(() ->
                                    fPlayer.sendSystemMessage(Component.literal("§a[CraftyAI] Task scheduled: §f" + fName + "§a (every " + fCron + ")")));
                            } else {
                                String err = "\"error\":\"";
                                int ei = respBody.indexOf(err);
                                String errStr = ei >= 0 ? respBody.substring(ei + err.length(), respBody.indexOf("\"", ei + err.length())) : "HTTP " + resp.statusCode();
                                fPlayer.getServer().execute(() ->
                                    fPlayer.sendSystemMessage(Component.literal("§c[CraftyAI] Schedule failed: " + errStr)));
                            }
                        } catch (Exception e) {
                            fPlayer.getServer().execute(() ->
                                fPlayer.sendSystemMessage(Component.literal("§c[CraftyAI] Schedule error: " + e.getMessage())));
                        }
                    }, "CraftyAI-Schedule").start();
                    if (auditLog != null) auditLog.log(player.getName().getString(), playerKey, serverId, action, true, "ok");
                } else if (upper.startsWith("DELAYED_ACTION:")) {
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
                    final net.minecraft.server.level.ServerPlayer fP = player;
                    final long fDelay = delaySec * 1000L;
                    final net.minecraft.server.MinecraftServer fSrv = player.getServer();
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
                    final var srv = player.getServer();
                    final var p = player;
                    if (srv != null) {
                        srv.execute(() -> {
                            try {
                                srv.getCommands().performPrefixedCommand(p.createCommandSourceStack(), "/" + finalCmd);
                                p.sendSystemMessage(Component.literal("§7[CraftyAI] Ran: /" + finalCmd));
                            } catch (Exception e) {
                                LOGGER.warn("[CraftyAI] CHAT command failed: " + finalCmd + " — " + e.getMessage());
                                p.sendSystemMessage(Component.literal("§c[CraftyAI] Command failed: " + e.getMessage()));
                            }
                        });
                    }
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
                // chat-completions-compatible provider: use /v1/chat/completions with messages array
                List<Map<String, String>> messages = new ArrayList<>();
                Map<String, String> systemMsg = new HashMap<>();
                systemMsg.put("role", "system");
                systemMsg.put("content", "You are " + (loadedConfig.ai_name != null ? loadedConfig.ai_name : "Crafty") + ", a helpful AI assistant in Minecraft." +
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

                // Strip trailing path if user already configured a full endpoint URL
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

            HttpRequest request = requestBuilder.build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) return response.body();

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
                try { Thread.sleep((long) Math.pow(2, attempt + 1) * 1000); } catch (InterruptedException ie) { LOGGER.warn("[CraftyAI] Retry sleep interrupted: {}", ie.getMessage()); Thread.currentThread().interrupt(); }
                return sendWithRetry(question, playerName, context, history, attempt + 1);
            }
            return "__ERROR__:Server error (HTTP " + response.statusCode() + ")";
        } catch (Exception e) {
            if (attempt < MAX_RETRIES) {
                try { Thread.sleep((long) Math.pow(2, attempt + 1) * 1000); } catch (InterruptedException ie) { LOGGER.warn("[CraftyAI] Retry sleep interrupted: {}", ie.getMessage()); Thread.currentThread().interrupt(); }
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

    private String inferActionFromText(String answer, String question) {
        if (answer == null) return null;
        String upper = answer.toUpperCase();
        if (upper.contains("SCAN_BLOCKS") || (upper.contains("SCAN") && question != null && question.toLowerCase().matches(".*\\b(scan|look|see|what.*around|surrounding|nearby|area|check|examine|view|survey|search|find|locate|explore|map|observe|spot|detect|inspect|watch|lookout|environ)\\b.*"))) {
            String radiusMatch = question != null ? question.replaceAll(".*?(\\d+).*", "$1") : "8";
            int radius = 8;
            try { radius = Math.min(32, Math.max(1, Integer.parseInt(radiusMatch))); } catch (Exception ignored) {}
            if (radiusMatch.equals(question)) radius = 8;
            boolean incPlayers = upper.contains("PLAYER");
            boolean incEntities = upper.contains("ENTITY") || !upper.contains("BLOCK");
            boolean incBlocks = upper.contains("BLOCK");
            if (!incBlocks && !incEntities && !incPlayers) { incBlocks = true; incEntities = true; }
            return "SCAN_BLOCKS:" + radius + ":" + (incPlayers ? "1" : "0") + ":" + (incEntities ? "1" : "0") + ":" + (incBlocks ? "1" : "0");
        }
        if ((upper.contains("IN") || upper.contains("AFTER") || upper.contains("WAIT")) && question != null && question.toLowerCase().matches(".*\\b(in|after|wait)\\s+\\d+\\s*(seconds?|sec|minutes?|min)\\b.*")) {
            String delayStr = question.replaceAll(".*?(\\d+)\\s*(second|sec|min|minute).*", "$1");
            try {
                int delaySec = Integer.parseInt(delayStr);
                if (delayStr.equals(question)) delaySec = 10;
                String innerAction = "TELEPORT_SPAWN";
                if (upper.contains("HEAL") || upper.contains("FEED")) innerAction = upper.contains("HEAL") ? "HEAL" : "FEED";
                else if (upper.contains("TIME")) innerAction = upper.contains("DAY") ? "TIME_DAY" : "TIME_NIGHT";
                else if (upper.contains("CLEAR") || upper.contains("RAIN") || upper.contains("THUNDER")) innerAction = upper.contains("CLEAR") ? "WEATHER_CLEAR" : upper.contains("RAIN") ? "WEATHER_RAIN" : "WEATHER_THUNDER";
                return "DELAYED_ACTION:" + delaySec + ":" + innerAction;
            } catch (Exception ignored) {}
        }
        if (upper.contains("SCHEDULE") || upper.contains("REMIND") || upper.contains("DAILY") || upper.contains("EVERY")) {
            return null;
        }
        return null;
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
                    player.getServer().execute(() -> {
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
                    player.getServer().execute(() -> player.sendSystemMessage(Component.literal("§c[CraftyAI] Link failed: " + e.getMessage())));
                    return null;
                });
    }
}
