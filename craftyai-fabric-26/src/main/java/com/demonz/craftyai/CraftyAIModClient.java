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

import com.demonz.craftyai.common.AgenticActions;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.demonz.craftyai.common.CraftyAIConfig;
import com.demonz.craftyai.common.ConfigLoader;
import com.demonz.craftyai.common.GatewayRequestHeaders;
import com.demonz.craftyai.common.GatewayHttpClientHelper;
import com.demonz.craftyai.common.LocalBrain;
import com.demonz.craftyai.common.NeuralResponse;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CraftyAI Client Module (MC 26.x — Mojang Mappings)
 * ======================
 * Handles singleplayer AI chat and provides a config GUI.
 * When the player is on a dedicated server, the server-side mod handles everything.
 * When in singleplayer (integrated server), this client module intercepts chat.
 */
public class CraftyAIModClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("craftyai-client");
    private static final int MAX_HISTORY = 10;
    private static final String CLIENT_TYPE = "minecraft-fabric-client";
    private static final Gson GSON = new GsonBuilder().create();

    private static final Set<String> GIVE_BLACKLIST = Set.of(
        "barrier", "command_block", "chain_command_block", "repeating_command_block",
        "command_block_minecart", "structure_block", "structure_void",
        "bedrock", "end_portal_frame", "spawner"
    );

    private static volatile CraftyAIConfig config;
    private static boolean configLoaded = false;
    private static String clientConfigDir;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final int MAX_CACHED_PLAYERS = 500;
    private final ConcurrentHashMap<UUID, LinkedList<Map<String, String>>> conversationCache = new ConcurrentHashMap<>();
    private volatile long lastChatTime = 0;
    private volatile long lastVisionScanTime = 0;
    private static final long VISION_SCAN_COOLDOWN_MS = 5000L;

    private final com.demonz.craftyai.common.ActionRateLimiter actionRateLimiter = new com.demonz.craftyai.common.ActionRateLimiter();
    private final com.demonz.craftyai.common.ActionConfirmation actionConfirmations = new com.demonz.craftyai.common.ActionConfirmation();

    private KeyMapping settingsKey;
    private KeyMapping visionScanKey;
    private LocalBrain localBrain;

    private boolean shownWelcome = false;

    private static CraftyAIModClient INSTANCE;

    public static CraftyAIModClient getInstance() {
        return INSTANCE;
    }

    @Override
    public void onInitializeClient() {
        INSTANCE = this;
        LOGGER.info("[CraftyAI] Client module initializing...");

        // Load config from the game directory
        clientConfigDir = "config";
        loadClientConfig();
        localBrain = new LocalBrain(clientConfigDir, LOGGER::info);

        // Register keybinding for settings screen (M key by default)
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(CraftyAIMod.MOD_ID, "main"));
        settingsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.craftyai.settings",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_M,
                category
        ));

        // Register keybinding for vision scan (V key by default)
        visionScanKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.craftyai.vision_scan",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                category
        ));

        // Open settings screen on keybind press + client-side welcome on first world join
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (settingsKey.consumeClick()) {
                LOGGER.info("[CraftyAI] Settings key M pressed!");
                boolean isScreenNull = true;
                try {
                    isScreenNull = (client.screen == null);
                } catch (Throwable t) {
                    try {
                        java.lang.reflect.Field f = client.getClass().getDeclaredField("screen");
                        f.setAccessible(true);
                        isScreenNull = (f.get(client) == null);
                    } catch (Throwable t2) {
                        try {
                            java.lang.reflect.Field f2 = client.getClass().getDeclaredField("currentScreen");
                            f2.setAccessible(true);
                            isScreenNull = (f2.get(client) == null);
                        } catch (Throwable t3) {
                            isScreenNull = true;
                        }
                    }
                }
                LOGGER.info("[CraftyAI] isScreenNull = {}", isScreenNull);
                if (isScreenNull) {
                    safeSetScreen(client, new CraftyAISettingsScreen(null));
                }
            }

            // Vision scan key — scan the block the player is looking at
            while (visionScanKey.consumeClick()) {
                if (client.player != null && client.level != null) {
                    performVisionScan(client);
                }
            }

            // Show welcome message once per session when player enters a world
            if (!shownWelcome && client.player != null && client.level != null) {
                shownWelcome = true;
                if (config.op_welcome_message) {
                    client.player.sendSystemMessage(Component.literal(""));
                    client.player.sendSystemMessage(Component.literal("\u00A7b\u00A7l\u2726 CraftyAI \u00A77v" + GatewayRequestHeaders.MOD_VERSION + " \u00A78\u2014 \u00A77AI Companion"));
                    String firstAlias = (config.aliases != null && config.aliases.length > 0) ? config.aliases[0] : "crafty";
                    client.player.sendSystemMessage(Component.literal("\u00A77  Type \u00A7e" + config.prefix + firstAlias + " <question>\u00A77 in chat to talk to your AI."));
                    client.player.sendSystemMessage(Component.literal("\u00A77  Press \u00A7eM\u00A77 to open settings. Press \u00A7eV\u00A77 to vision-scan. Use \u00A7e/crafty status\u00A77 to check status."));
                    boolean hasApiKey = (config.api_key != null && !config.api_key.isEmpty() && !config.api_key.equals("YOUR_API_KEY_HERE"));
                    boolean hasCustomProvider = (config.custom_provider_enabled && config.custom_provider_url != null && !config.custom_provider_url.isEmpty());
                    if (!hasApiKey && !hasCustomProvider) {
                        client.player.sendSystemMessage(Component.literal("\u00A7c  \u26A0 No API key or custom provider set! Press M or use /crafty apikey <key>"));
                    }
                    client.player.sendSystemMessage(Component.literal("\u00A78  Hide this: set op_welcome_message to false in config/craftyai.json"));
                    client.player.sendSystemMessage(Component.literal(""));
                }
            }
        });

        // Register client-side commands
        registerClientCommands();

        // Register client-side chat activation with dynamic prefixes and aliases
        ClientSendMessageEvents.ALLOW_CHAT.register((message) -> {
            net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
            if (client != null && client.getCurrentServer() != null && !client.isLocalServer()) return true; // Only intercept in singleplayer
            if (message == null || message.trim().isEmpty()) return true;
            String lower = message.toLowerCase().trim();
            String prefixVal = config.prefix != null ? config.prefix : "@";
            boolean requirePrefix = config.require_prefix;
            List<String> aliasesList = config.aliases != null ? Arrays.asList(config.aliases) : Arrays.asList("crafty", "craftyai", "ai", "helper");

            for (String alias : aliasesList) {
                String prefixed = prefixVal.toLowerCase() + alias;
                if (lower.startsWith(prefixed + " ")) {
                    String question = message.trim().substring(prefixed.length()).trim();
                    if (!question.isEmpty()) {
                        handleClientChatDirect(question);
                    }
                    return false; // Cancel the original chat message
                }
                if (!requirePrefix && lower.startsWith(alias + " ")) {
                    String question = message.trim().substring(alias.length()).trim();
                    if (!question.isEmpty()) {
                        handleClientChatDirect(question);
                    }
                    return false; // Cancel the original chat message
                }
            }
            return true; // Let other messages through
        });

        // Reset shownWelcome on disconnect so it shows again when joining a new world
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            shownWelcome = false;
            conversationCache.clear();
            LOGGER.info("[CraftyAI] Cleared client conversation cache on disconnect.");
        });

        performStartupHandshake();
        String firstAlias = (config.aliases != null && config.aliases.length > 0) ? config.aliases[0] : "crafty";
        LOGGER.info("[CraftyAI] Client module ready. Press M for settings, V for vision scan. Type @{} in chat.", firstAlias);
    }

    /**
     * Perform a vision scan of the block the player is looking at.
     */
    private void performVisionScan(Minecraft client) {
        long now = System.currentTimeMillis();
        if (now - lastVisionScanTime < VISION_SCAN_COOLDOWN_MS) {
            client.player.sendSystemMessage(Component.literal("\u00A7c[CraftyAI] Please wait before scanning again."));
            return;
        }
        lastVisionScanTime = now;
        HitResult hit = client.hitResult;
        if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
            BlockHitResult blockHit = (BlockHitResult) hit;
            var blockPos = blockHit.getBlockPos();
            var blockState = client.level.getBlockState(blockPos);
            String blockName = blockState.getBlock().getName().getString();
            client.player.sendSystemMessage(Component.literal("\u00A7b\u00A7l[CraftyAI Vision Scan] \u00A77Scanning: \u00A7f" + blockName + " \u00A78at " + blockPos.toShortString()));
            client.player.sendSystemMessage(Component.literal("\u00A77Analyzing with AI..."));

            // Build vision context and send to AI
            StringBuilder visionCtx = new StringBuilder();
            visionCtx.append("[Vision Scan]\n");
            visionCtx.append("Block: ").append(blockName).append("\n");
            visionCtx.append("Position: ").append(blockPos.toShortString()).append("\n");
            if (client.level != null) {
                visionCtx.append("Biome: ").append(client.level.getBiome(blockPos).unwrapKey().map(k -> k.identifier().getPath()).orElse("unknown")).append("\n");
            }

            String question = "I'm looking at a " + blockName + " block. What is it useful for in Minecraft? Any tips?";
            handleClientChat(null, question, visionCtx.toString());

            LOGGER.info("[CraftyAI] Vision scan sent to AI: {} at {}", blockName, blockPos.toShortString());
        } else if (hit != null && hit.getType() == HitResult.Type.ENTITY) {
            net.minecraft.world.entity.Entity entity = ((net.minecraft.world.phys.EntityHitResult) hit).getEntity();
            String entityName = entity.getName().getString();
            client.player.sendSystemMessage(Component.literal("\u00A7b\u00A7l[CraftyAI Vision Scan] \u00A77Scanning: \u00A7f" + entityName));
            client.player.sendSystemMessage(Component.literal("\u00A77Analyzing with AI..."));

            String question = "I'm looking at a " + entityName + " entity. What should I know about it?";
            handleClientChat(null, question, "[Vision Scan]\nEntity: " + entityName);

            LOGGER.info("[CraftyAI] Vision scan sent to AI: entity {}", entityName);
        } else {
            client.player.sendSystemMessage(Component.literal("\u00A7b[CraftyAI Vision] \u00A77No block or entity in crosshair."));
        }
    }

    private void performStartupHandshake() {
        // Skip handshake if custom provider is enabled
        if (config.custom_provider_enabled) {
            LOGGER.info("[CraftyAI] Custom provider enabled — skipping gateway handshake.");
            return;
        }

        String sid = com.demonz.craftyai.common.SessionManager.getSessionId(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir());

        CompletableFuture.runAsync(() -> {
            try {
                // Determine server name for handshake
                String serverName = "";
                Minecraft mc = Minecraft.getInstance();
                if (mc != null) {
                    if (mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null) {
                        serverName = mc.getSingleplayerServer().getWorldData().getLevelName();
                    } else if (mc.getCurrentServer() != null) {
                        serverName = mc.getCurrentServer().ip;
                    }
                }
                String nameField = (serverName != null && !serverName.isEmpty())
                    ? ",\"name\":" + jsonString(serverName) : "";
                String json = "{\"version\":\"" + GatewayRequestHeaders.MOD_VERSION + "\",\"client_type\":\""
                        + CLIENT_TYPE + "\",\"server_id\":\"" + sid + "\"" + nameField + "}";
                HttpRequest request = GatewayHttpClientHelper.apply(HttpRequest.newBuilder()
                    .uri(URI.create(GatewayRequestHeaders.getGatewayUrl() + "/v1/handshake"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(json)), CLIENT_TYPE, sid)
                    .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    try {
                        com.google.gson.JsonObject resJson = com.demonz.craftyai.common.JsonParserAdapter.parse(response.body()).getAsJsonObject();
                        if (resJson.has("tier")) {
                            config.tier = resJson.get("tier").getAsString();
                        }
                        if (resJson.has("warnings")) {
                            com.google.gson.JsonArray warnings = resJson.getAsJsonArray("warnings");
                            for (com.google.gson.JsonElement warnEl : warnings) {
                                if (!warnEl.isJsonObject()) continue;
                                String message = warnEl.getAsJsonObject().has("message") ? warnEl.getAsJsonObject().get("message").getAsString() : "Unknown warning";
                                net.minecraft.client.Minecraft.getInstance().execute(() -> {
                                    if (net.minecraft.client.Minecraft.getInstance().player != null) {
                                        net.minecraft.client.Minecraft.getInstance().player.sendSystemMessage(
                                            Component.literal("§c§l[CraftyAI Warning] §7" + message));
                                    }
                                });
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.warn("[CraftyAI] Failed to parse handshake response fields: {}", e.getMessage());
                    }
                }
                LOGGER.info("[CraftyAI] Startup handshake complete. Session ID: {}", sid);
            } catch (Exception e) {
                LOGGER.warn("[CraftyAI] Failed to complete startup handshake: {}", e.getMessage());
            }
        });
    }

    public void performAutoMintKey(FabricClientCommandSource source) {
        performAutoMintKey(source, null);
    }

    public void performAutoMintKey(FabricClientCommandSource source, java.util.function.Consumer<String> callback) {
        String sid = com.demonz.craftyai.common.SessionManager.getSessionId(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir());
        CompletableFuture.runAsync(() -> {
            try {
                String playerName = Minecraft.getInstance().getUser() != null ? Minecraft.getInstance().getUser().getName() : "Player";
                String json = "{\"version\":\"" + GatewayRequestHeaders.MOD_VERSION + "\",\"client_type\":\"" + CLIENT_TYPE + "\",\"server_id\":\"" + sid + "\",\"name\":\"" + playerName + "\"}";
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(GatewayRequestHeaders.getGatewayUrl() + "/v1/handshake-no-key"))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "CraftyAI-Minecraft/" + GatewayRequestHeaders.MOD_VERSION + " (" + CLIENT_TYPE + ")")
                    .header("X-Client-Type", CLIENT_TYPE)
                    .header("X-CraftyAI-Version", GatewayRequestHeaders.MOD_VERSION)
                    .header("X-CraftyAI-Client", CLIENT_TYPE)
                    .header("X-Session-Id", sid)
                    .header("X-Server-ID", sid)
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                LOGGER.info("[CraftyAI] Auto-mint response: {} -> {}", response.statusCode(), response.body());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    com.google.gson.JsonObject res = com.demonz.craftyai.common.JsonParserAdapter.parse(response.body()).getAsJsonObject();
                    if (res.has("api_key")) {
                        String key = res.get("api_key").getAsString();
                        config.api_key = key;
                        if (res.has("tier")) config.tier = res.get("tier").getAsString();
                        saveCurrentConfig();
                        Minecraft.getInstance().execute(() -> {
                            if (source != null) {
                                source.sendFeedback(Component.literal("\u00A7a[CraftyAI] Auto-minted key successfully: \u00A7f" + key));
                            }
                            if (callback != null) callback.accept(key);
                        });
                        return;
                    }
                }
                Minecraft.getInstance().execute(() -> {
                    if (source != null) {
                        source.sendFeedback(Component.literal("\u00A7c[CraftyAI] Auto-mint failed: HTTP " + response.statusCode()));
                    }
                    if (callback != null) callback.accept(null);
                });
            } catch (Exception e) {
                LOGGER.warn("[CraftyAI] Auto-mint failed: {}", e.getMessage());
                Minecraft.getInstance().execute(() -> {
                    if (source != null) {
                        source.sendFeedback(Component.literal("\u00A7c[CraftyAI] Auto-mint failed: " + e.getMessage()));
                    }
                    if (callback != null) callback.accept(null);
                });
            }
        });
    }

    private void loadClientConfig() {
        config = ConfigLoader.loadOrCreateConfig(clientConfigDir, "craftyai.json", LOGGER::info);
        configLoaded = true;
    }

    public static CraftyAIConfig getConfig() {
        return config;
    }

    public static void reloadConfig() {
        config = ConfigLoader.loadOrCreateConfig("config", "craftyai.json", LOGGER::info);
        configLoaded = true;
    }

    public static void setTierFromServer(String tier) {
        if (config != null && tier != null && !tier.isEmpty()) {
            config.tier = tier;
        }
    }

    public static void saveCurrentConfig() {
        if (config != null) {
            ConfigLoader.saveConfig("config", "craftyai.json", config, LOGGER::info);
        }
    }

    private void registerClientCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            for (String root : new String[]{"crafty", "craftyclient"}) {
                var tree = ClientCommands.literal(root)
                    .executes(ctx -> {
                        FabricClientCommandSource source = ctx.getSource();
                        source.sendFeedback(Component.literal("\u00A7b\u00A7lCraftyAI \u00A77v" + GatewayRequestHeaders.MOD_VERSION + " \u00A78\u2014 \u00A77DemonZ Development"));
                        source.sendFeedback(Component.literal("\u00A77Platform: \u00A7fFabric Client"));
                        source.sendFeedback(Component.literal("\u00A77Usage: \u00A7f/" + root + " <status|key|apikey|mint|settings|ask|scan>"));
                        source.sendFeedback(Component.literal("\u00A77Press \u00A7eM \u00A77to open settings. Press \u00A7eV \u00A77to vision-scan."));
                        return 1;
                    })
                    .then(ClientCommands.literal("status")
                        .executes(ctx -> {
                            FabricClientCommandSource source = ctx.getSource();
                            source.sendFeedback(Component.literal("\u00A7b[CraftyAI] \u00A77Client Status:"));
                            source.sendFeedback(Component.literal("\u00A77  API Key: \u00A7f" + (config.api_key != null && config.api_key.startsWith("cai_") ? "configured" : "not set")));
                            String sid = com.demonz.craftyai.common.SessionManager.getSessionId(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir());
                            source.sendFeedback(Component.literal("\u00A77  Session ID: \u00A7f" + sid));
                            source.sendFeedback(Component.literal("\u00A77  Custom Provider: \u00A7f" + (config.custom_provider_enabled ? "enabled" : "disabled")));
                            source.sendFeedback(Component.literal("\u00A77  Agentic Tasks: \u00A7f" + (config.agentic_tasks_enabled ? "enabled" : "disabled")));
                            source.sendFeedback(Component.literal("\u00A77  Press \u00A7eM \u00A77to open settings."));
                            return 1;
                        })
                    );

                // Add apikey, key, & api subcommands with 0-arg and 1-arg execution
                for (String keyLiteral : new String[]{"apikey", "key", "api"}) {
                    tree.then(ClientCommands.literal(keyLiteral)
                        .executes(ctx -> {
                            FabricClientCommandSource source = ctx.getSource();
                            source.sendFeedback(Component.literal("\u00A7b[CraftyAI] \u00A77API Key Status: \u00A7f" +
                                (config.api_key != null && config.api_key.startsWith("cai_") ? "configured" : "\u00A7cnot set")));
                            source.sendFeedback(Component.literal("\u00A77To set a key manually: \u00A7f/crafty api <cai_your_key>"));
                            source.sendFeedback(Component.literal("\u00A77To auto-mint a key: \u00A7f/crafty mint"));
                            return 1;
                        })
                        .then(ClientCommands.argument("key", StringArgumentType.greedyString())
                            .executes(ctx -> {
                                String key = StringArgumentType.getString(ctx, "key").trim();
                                if (!key.matches("^cai_[a-zA-Z0-9_-]{16,128}$")) {
                                    ctx.getSource().sendFeedback(Component.literal("\u00A7cInvalid API key format. Keys start with cai_."));
                                    return 0;
                                }
                                config.api_key = key;
                                saveCurrentConfig();
                                ctx.getSource().sendFeedback(Component.literal("\u00A7a[CraftyAI] API key saved successfully."));
                                return 1;
                            })
                        )
                    );
                }

                // Add mint & automint subcommands
                for (String mintLiteral : new String[]{"mint", "automint"}) {
                    tree.then(ClientCommands.literal(mintLiteral)
                        .executes(ctx -> {
                            ctx.getSource().sendFeedback(Component.literal("\u00A7b[CraftyAI] Requesting auto-minted API key..."));
                            performAutoMintKey(ctx.getSource());
                            return 1;
                        })
                    );
                }

                // Add settings, gui, config subcommands
                for (String settingsLiteral : new String[]{"settings", "gui", "config"}) {
                    tree.then(ClientCommands.literal(settingsLiteral)
                        .executes(ctx -> {
                            Minecraft client = Minecraft.getInstance();
                            client.execute(() -> safeSetScreen(client, new CraftyAISettingsScreen(null)));
                            return 1;
                        })
                    );
                }

                // Add ask subcommand
                tree.then(ClientCommands.literal("ask")
                    .then(ClientCommands.argument("question", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String question = StringArgumentType.getString(ctx, "question");
                            handleClientChat(ctx.getSource(), question);
                            return 1;
                        })
                    )
                );

                // Add scan & vision subcommands
                for (String scanLiteral : new String[]{"scan", "vision"}) {
                    tree.then(ClientCommands.literal(scanLiteral)
                        .executes(ctx -> {
                            Minecraft client = Minecraft.getInstance();
                            if (client.player != null && client.level != null) {
                                performVisionScan(client);
                            }
                            return 1;
                        })
                    );
                }

                // Add confirm subcommand
                tree.then(ClientCommands.literal("confirm")
                    .executes(ctx -> {
                        Minecraft client = Minecraft.getInstance();
                        if (client.player == null) return 0;
                        String playerKey = client.player.getUUID().toString();
                        String pending = actionConfirmations.peek(playerKey);
                        if (pending == null) {
                            ctx.getSource().sendFeedback(Component.literal("\u00A77[CraftyAI] No destructive action pending."));
                        } else {
                            ctx.getSource().sendFeedback(Component.literal("\u00A7a[CraftyAI] Confirmed pending action: \u00A7f" + pending.toUpperCase() + "\u00A7a. Re-run your request to execute it now."));
                        }
                        return 1;
                    })
                );

                dispatcher.register(tree);
            }
        });
    }

    private void handleClientChatDirect(String question) {
        handleClientChatDirect(question, null);
    }

    private void handleClientChatDirect(String question, String extraContext) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        boolean hasApiKey = (config.api_key != null && !config.api_key.isEmpty() && !config.api_key.equals("YOUR_API_KEY_HERE"));
        boolean hasCustomProvider = (config.custom_provider_enabled && config.custom_provider_url != null && !config.custom_provider_url.isEmpty());
        if (config.force_local_mode || (!hasApiKey && !hasCustomProvider)) {
            String localAnswer = localBrain.generateOfflineResponse(question, mc.getUser().getName());
            if (localAnswer != null) {
                String prefix = config.force_local_mode ? "\u00A7e[FORCED-LOCAL]" : "\u00A7e[OFFLINE]";
                mc.player.sendSystemMessage(Component.literal("\u00A7b[" + config.ai_name + "] " + prefix + " \u00A77> \u00A7f" + localAnswer));
                if (!config.force_local_mode) {
                    mc.player.sendSystemMessage(Component.literal("\u00A78\u00A7oTip: Set your API key with /crafty apikey to get online responses."));
                }
            } else {
                mc.player.sendSystemMessage(Component.literal("\u00A7c[CraftyAI] API key not set or local brain failed. Use /crafty apikey <key> or press M."));
            }
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastChatTime < config.getCooldownMs()) {
            mc.player.sendSystemMessage(Component.literal("\u00A7c[CraftyAI] Please wait before asking again."));
            return;
        }
        lastChatTime = now;

        String sid = com.demonz.craftyai.common.SessionManager.getSessionId(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir());

        mc.player.sendSystemMessage(Component.literal("\u00A7b[" + config.ai_name + "] \u00A77Thinking..."));

        UUID playerId = mc.player.getUUID();
        LinkedList<Map<String, String>> history = conversationCache.computeIfAbsent(playerId, k -> new LinkedList<>());
        String historyJson = buildHistoryJson(history);

        String context = buildContext(mc.player);
        if (extraContext != null && !extraContext.isEmpty()) {
            context = extraContext + "\n" + context;
        }

        final String finalContext = context;
        CompletableFuture.supplyAsync(() -> sendAIRequest(question, mc.player.getName().getString(), finalContext, historyJson))
            .thenAccept(responseBody -> {
                mc.execute(() -> {
                    if (mc.player == null) return;
                    if (responseBody != null && responseBody.startsWith("__ERROR__:")) {
                        // Structured error from gateway
                        String errorMsg = responseBody.substring("__ERROR__:".length());
                        mc.player.sendSystemMessage(Component.literal("\u00A7c[CraftyAI] " + errorMsg));
                    } else if (responseBody != null) {
                        NeuralResponse res = GSON.fromJson(responseBody, NeuralResponse.class);
                        String answer = res != null ? res.getAnswer() : null;
                        String action = res != null ? res.getAction() : null;
                        if (action == null || action.isEmpty() || "null".equalsIgnoreCase(action)) {
                            action = inferActionFromText(answer, question);
                        } else if (action.toUpperCase().startsWith("SCHEDULE_TASK:") && question != null && question.toLowerCase().matches(".*\\b(in|after|wait)\\s+\\d+\\s*(seconds?|sec|minutes?|min)\\b.*")) {
                            action = inferActionFromText(answer, question);
                        }
                        if (answer != null && !answer.isEmpty()) {
                            addToHistory(playerId, question, answer);
                            String actionUpper = action != null ? action.toUpperCase().trim() : "";
                            boolean hasAction = action != null && !action.isEmpty() && !action.equalsIgnoreCase("null");
                            boolean isSelfFeedback = hasAction && (actionUpper.startsWith("SCAN_BLOCKS") || actionUpper.startsWith("DELAYED_ACTION") || actionUpper.startsWith("SCHEDULE_TASK"));

                            if (hasAction && !isSelfFeedback) {
                                executeAction(action);
                                final String actionForFollowUp = action;
                                final String originalQuestion = question;
                                final String playerNameStr = mc.player.getName().getString();
                                final String worldStr = mc.level.dimension().identifier().toString();
                                final String bgCtx = buildContext(mc.player);
                                final String aiNameFinal = config.ai_name != null ? config.ai_name : "Crafty";
                                final String answerFinal = answer;
                                Thread fbThread = new Thread(() -> {
                                    try {
                                        String actionContext = "[Action Executed]\nAction: " + actionForFollowUp + "\nPlayer: " + playerNameStr + "\nWorld: " + worldStr;
                                        String fullQuestion = "The player asked: \"" + originalQuestion + "\". The action \"" + actionForFollowUp + "\" was executed. Respond naturally in 1-2 sentences confirming what was done. Be conversational and brief. Do NOT output action codes or technical details.\n\n" + actionContext;
                                        java.util.LinkedList<java.util.Map<String, String>> followUpHist = conversationCache.computeIfAbsent(playerId, k -> new java.util.LinkedList<>());
                                        String followUpHistoryJson = buildHistoryJson(followUpHist);
                                        String followUpResponse = sendAIRequest(fullQuestion, playerNameStr, bgCtx, followUpHistoryJson);
                                        if (followUpResponse != null && !followUpResponse.startsWith("__ERROR__:")) {
                                            NeuralResponse followUpRes = GSON.fromJson(followUpResponse, NeuralResponse.class);
                                            String followUp = followUpRes != null ? followUpRes.getAnswer() : null;
                                            if (followUp != null && !followUp.isEmpty()) {
                                                mc.execute(() -> {
                                                    if (mc.player != null) {
                                                        mc.player.sendSystemMessage(Component.literal("\u00A7b[" + aiNameFinal + "] \u00A77> \u00A7f" + followUp));
                                                        addToHistory(playerId, "(action follow-up)", followUp);
                                                    }
                                                });
                                            }
                                        } else {
                                            mc.execute(() -> {
                                                if (mc.player != null) {
                                                    mc.player.sendSystemMessage(Component.literal("\u00A7b[" + aiNameFinal + "] \u00A77> \u00A7f" + answerFinal));
                                                }
                                            });
                                        }
                                    } catch (Exception e) {
                                        LOGGER.warn("[CraftyAI] Action follow-up failed: " + e.getMessage());
                                        mc.execute(() -> {
                                            if (mc.player != null) {
                                                mc.player.sendSystemMessage(Component.literal("\u00A7b[" + aiNameFinal + "] \u00A77> \u00A7f" + answerFinal));
                                            }
                                        });
                                    }
                                }, "CraftyAI-ActionFeedback");
                                fbThread.setDaemon(true);
                                fbThread.start();
                            } else {
                                mc.player.sendSystemMessage(Component.literal("\u00A7b[" + config.ai_name + "] \u00A77> \u00A7f" + answer));
                                if (hasAction) {
                                    executeAction(action);
                                }
                            }
                        } else {
                            mc.player.sendSystemMessage(Component.literal("\u00A7c[CraftyAI] Got an empty response."));
                        }
                    } else {
                        String playerName = mc.getUser().getName();
                        String localAnswer = this.localBrain.generateOfflineResponse(question, playerName);
                        if (localAnswer != null) {
                            mc.player.sendSystemMessage(Component.literal("\u00A7b[" + config.ai_name + "] \u00A77> \u00A7f" + localAnswer));
                            // Enqueue for later processing
                            this.localBrain.enqueueRequest(question, playerName, "Offline from Client Chat");
                        } else {
                            mc.player.sendSystemMessage(Component.literal("\u00A7c[CraftyAI] Could not reach the AI. Connection is offline."));
                        }
                    }
                });
            });
    }

    private static String buildContext(net.minecraft.client.player.LocalPlayer player) {
        StringBuilder ctx = new StringBuilder();
        try {
            Minecraft mc = Minecraft.getInstance();

            // --- PERMISSIONS ---
            ctx.append("[PERMISSIONS]\n");
            
            // Gamemode
            String gameMode = "unknown";
            if (mc.gameMode != null) {
                gameMode = mc.gameMode.getPlayerMode().getName();
            }
            ctx.append("GameMode: ").append(gameMode).append("\n");
            
            // OP status — works for both singleplayer (cheats) and multiplayer (OP)
            boolean hasOp = player.canUseGameMasterBlocks();
            ctx.append("OP Status: ").append(hasOp ? "YES — has operator permissions" : "NO — does NOT have OP permissions").append("\n");
            
            // World type detection
            boolean isSingleplayer = mc.hasSingleplayerServer();
            ctx.append("World Type: ").append(isSingleplayer ? "singleplayer" : "multiplayer").append("\n");
            
            // Cheats — in singleplayer, check if commands are allowed
            boolean cheatsEnabled = hasOp; // On multiplayer, OP = cheats
            if (isSingleplayer && mc.getSingleplayerServer() != null) {
                // In singleplayer, the integrated server manages this
                cheatsEnabled = mc.getSingleplayerServer().getPlayerList().isAllowCommandsForAllPlayers();
            }
            ctx.append("Cheats: ").append(cheatsEnabled ? "enabled" : "disabled").append("\n");
            
            // Flying
            ctx.append("Can Fly: ").append(player.getAbilities().mayfly ? "yes" : "no").append("\n");
            
            // Difficulty
            if (mc.level != null) {
                ctx.append("Difficulty: ").append(mc.level.getDifficulty().name().toLowerCase()).append("\n");
            }
            ctx.append("\n");

            // --- ENVIRONMENT ---
            ctx.append("[ENVIRONMENT]\n");
            if (mc.level != null) {
                ctx.append("Dimension: ").append(mc.level.dimension().identifier().getPath()).append("\n");
                long time = mc.level.getDefaultClockTime() % 24000;
                String timeOfDay = time < 6000 ? "Morning" : time < 12000 ? "Day" : time < 18000 ? "Evening" : "Night";
                ctx.append("Time: ").append(timeOfDay).append("\n");
                
                // Weather
                boolean raining = mc.level.isRaining();
                boolean thundering = mc.level.isThundering();
                ctx.append("Weather: ").append(thundering ? "thunderstorm" : raining ? "rain" : "clear").append("\n");
            }
            ctx.append("\n");

            // --- PLAYER STATUS ---
            ctx.append("[PLAYER STATUS]\n");
            ctx.append("Health: ").append((int) player.getHealth()).append("/").append((int) player.getMaxHealth()).append("\n");
            ctx.append("Food: ").append(player.getFoodData().getFoodLevel()).append("/20\n");
            ctx.append("XP Level: ").append(player.experienceLevel).append("\n");
            ctx.append("Coords: ").append(player.blockPosition().getX())
               .append(",").append(player.blockPosition().getY())
               .append(",").append(player.blockPosition().getZ()).append("\n");

            // Active effects
            if (!player.getActiveEffects().isEmpty()) {
                ctx.append("Active Effects: ");
                player.getActiveEffects().forEach(effect -> {
                    String effectName = effect.getEffect().value().getDisplayName().getString();
                    int amp = effect.getAmplifier() + 1;
                    int dur = effect.getDuration() / 20;
                    ctx.append(effectName).append(" ").append(amp).append(" (").append(dur).append("s), ");
                });
                ctx.append("\n");
            }

        } catch (Exception e) {
            LOGGER.warn("[CraftyAI] Failed to build context: {}", e.getMessage());
        }
        return ctx.toString();
    }

    private void handleClientChat(FabricClientCommandSource source, String question) {
        boolean hasApiKey = (config.api_key != null && !config.api_key.isEmpty() && !config.api_key.equals("YOUR_API_KEY_HERE"));
        boolean hasCustomProvider = (config.custom_provider_enabled && config.custom_provider_url != null && !config.custom_provider_url.isEmpty());
        if (!hasApiKey && !hasCustomProvider) {
            source.sendFeedback(Component.literal("\u00A7c[CraftyAI] API key or custom provider not set. Use /craftyclient apikey <key> or press M to open settings."));
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastChatTime < config.getCooldownMs()) {
            source.sendFeedback(Component.literal("\u00A7c[CraftyAI] Please wait before asking again."));
            return;
        }
        lastChatTime = now;

        source.sendFeedback(Component.literal("\u00A7b[" + config.ai_name + "] \u00A77Thinking..."));

        String sid = com.demonz.craftyai.common.SessionManager.getSessionId(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir());

        UUID playerId = source.getPlayer().getUUID();
        LinkedList<Map<String, String>> history = conversationCache.computeIfAbsent(playerId, k -> new LinkedList<>());
        String historyJson = buildHistoryJson(history);

        String context = buildContext(source.getPlayer());

        CompletableFuture.supplyAsync(() -> sendAIRequest(question, source.getPlayer().getName().getString(), context, historyJson))
            .thenAccept(responseBody -> {
                Minecraft.getInstance().execute(() -> {
                    if (responseBody != null && responseBody.startsWith("__ERROR__:")) {
                        String errorMsg = responseBody.substring("__ERROR__:".length());
                        source.sendFeedback(Component.literal("\u00A7c[CraftyAI] " + errorMsg));
                    } else if (responseBody != null) {
                        NeuralResponse res = GSON.fromJson(responseBody, NeuralResponse.class);
                        String answer = res != null ? res.getAnswer() : null;
                        String action = res != null ? res.getAction() : null;
                        if (action == null || action.isEmpty() || "null".equalsIgnoreCase(action)) {
                            action = inferActionFromText(answer, question);
                        } else if (action.toUpperCase().startsWith("SCHEDULE_TASK:") && question != null && question.toLowerCase().matches(".*\\b(in|after|wait)\\s+\\d+\\s*(seconds?|sec|minutes?|min)\\b.*")) {
                            action = inferActionFromText(answer, question);
                        }
                        if (answer != null && !answer.isEmpty()) {
                            addToHistory(playerId, question, answer);
                            String actionUpper = action != null ? action.toUpperCase().trim() : "";
                            boolean hasAction = action != null && !action.isEmpty() && !action.equalsIgnoreCase("null");
                            boolean isSelfFeedback = hasAction && (actionUpper.startsWith("SCAN_BLOCKS") || actionUpper.startsWith("DELAYED_ACTION") || actionUpper.startsWith("SCHEDULE_TASK"));

                            if (hasAction && !isSelfFeedback) {
                                executeAction(action);
                                final String actionForFollowUp = action;
                                final String originalQuestion = question;
                                final String playerNameStr = source.getPlayer().getName().getString();
                                final String worldStr = source.getPlayer().level().dimension().identifier().toString();
                                final String bgCtx = buildContext(source.getPlayer());
                                final String aiNameFinal = config.ai_name != null ? config.ai_name : "Crafty";
                                final String answerFinal = answer;
                                Thread fbThread2 = new Thread(() -> {
                                    try {
                                        String actionContext = "[Action Executed]\nAction: " + actionForFollowUp + "\nPlayer: " + playerNameStr + "\nWorld: " + worldStr;
                                        String fullQuestion = "The player asked: \"" + originalQuestion + "\". The action \"" + actionForFollowUp + "\" was executed. Respond naturally in 1-2 sentences confirming what was done. Be conversational and brief. Do NOT output action codes or technical details.\n\n" + actionContext;
                                        java.util.LinkedList<java.util.Map<String, String>> followUpHist = conversationCache.computeIfAbsent(playerId, k -> new java.util.LinkedList<>());
                                        String followUpHistoryJson = buildHistoryJson(followUpHist);
                                        String followUpResponse = sendAIRequest(fullQuestion, playerNameStr, bgCtx, followUpHistoryJson);
                                        if (followUpResponse != null && !followUpResponse.startsWith("__ERROR__:")) {
                                            NeuralResponse followUpRes = GSON.fromJson(followUpResponse, NeuralResponse.class);
                                            String followUp = followUpRes != null ? followUpRes.getAnswer() : null;
                                            if (followUp != null && !followUp.isEmpty()) {
                                                Minecraft.getInstance().execute(() -> {
                                                    if (Minecraft.getInstance().player != null) {
                                                        source.sendFeedback(Component.literal("\u00A7b[" + aiNameFinal + "] \u00A77> \u00A7f" + followUp));
                                                        addToHistory(playerId, "(action follow-up)", followUp);
                                                    }
                                                });
                                            }
                                        } else {
                                            Minecraft.getInstance().execute(() -> {
                                                source.sendFeedback(Component.literal("\u00A7b[" + aiNameFinal + "] \u00A77> \u00A7f" + answerFinal));
                                            });
                                        }
                                    } catch (Exception e) {
                                        LOGGER.warn("[CraftyAI] Action follow-up failed: " + e.getMessage());
                                        Minecraft.getInstance().execute(() -> {
                                            source.sendFeedback(Component.literal("\u00A7b[" + aiNameFinal + "] \u00A77> \u00A7f" + answerFinal));
                                        });
                                    }
                                }, "CraftyAI-ActionFeedback");
                                fbThread2.setDaemon(true);
                                fbThread2.start();
                            } else {
                                source.sendFeedback(Component.literal("\u00A7b[" + config.ai_name + "] \u00A77> \u00A7f" + answer));
                                if (hasAction) {
                                    executeAction(action);
                                }
                            }
                        } else {
                            source.sendFeedback(Component.literal("\u00A7c[CraftyAI] Got an empty response. Try again."));
                        }
                    } else {
                        String playerName = net.minecraft.client.Minecraft.getInstance().getUser().getName();
                        String localAnswer = CraftyAIModClient.this.localBrain.generateOfflineResponse(question, playerName);
                        if (localAnswer != null) {
                            source.sendFeedback(Component.literal("\u00A7b[" + config.ai_name + "] \u00A7e[OFFLINE] \u00A77> \u00A7f" + localAnswer));
                            CraftyAIModClient.this.localBrain.enqueueRequest(question, playerName, "Offline from Client");
                        } else {
                            source.sendFeedback(Component.literal("\u00A7c[CraftyAI] Could not reach the AI. Connection is offline."));
                        }
                    }
                });
            });
    }

    private void handleClientChat(Object source, String question, String extraContext) {
        // Delegate to the main handler with extra vision context appended
        handleClientChatDirect(question, extraContext);
    }

    private static String jsonString(String value) {
        if (value == null) return "\"\"";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\"";
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

    private String sendAIRequest(String question, String playerName, String context, String historyJson) {
        try {
            final CraftyAIConfig cfg = config;
            String sid = com.demonz.craftyai.common.SessionManager.getSessionId(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir());

            // Use effective API URL and key from config (supports custom provider)
            String effectiveUrl = cfg.getEffectiveApiUrl();
            String effectiveKey = cfg.getEffectiveApiKey();

            String jsonPayload;
            HttpRequest.Builder requestBuilder;

            if (cfg.custom_provider_enabled) {
                // chat-completions-compatible provider: use /v1/chat/completions with messages array
                List<Map<String, String>> messages = new ArrayList<>();
                Map<String, String> systemMsg = new HashMap<>();
                systemMsg.put("role", "system");
                systemMsg.put("content", "You are " + cfg.ai_name + ", a helpful AI assistant in Minecraft." +
                        (context != null && !context.isEmpty() ? "\n\nContext:\n" + context : ""));
                messages.add(systemMsg);

                // Parse history and add to messages
                try {
                    com.google.gson.JsonArray historyArray = com.demonz.craftyai.common.JsonParserAdapter.parse(historyJson).getAsJsonArray();
                    for (com.google.gson.JsonElement elem : historyArray) {
                        if (elem.isJsonObject()) {
                            Map<String, String> msg = new HashMap<>();
                            msg.put("role", elem.getAsJsonObject().get("role").getAsString());
                            msg.put("content", elem.getAsJsonObject().get("content").getAsString());
                            messages.add(msg);
                        }
                    }
                } catch (Exception e) {
                    LOGGER.warn("[CraftyAI] Failed to parse conversation history: {}", e.getMessage());
                }

                Map<String, String> userMsg = new HashMap<>();
                userMsg.put("role", "user");
                userMsg.put("content", question);
                messages.add(userMsg);

                Map<String, Object> payload = new HashMap<>();
                String model = (cfg.custom_provider_model != null && !cfg.custom_provider_model.isEmpty())
                        ? cfg.custom_provider_model : "default";
                payload.put("model", model);
                payload.put("messages", messages);
                payload.put("max_tokens", 1024);

                jsonPayload = GSON.toJson(payload);

                String chatUrl = effectiveUrl.replaceAll("/v1/chat/completions/?$", "").replaceAll("/+$", "");
                requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(chatUrl + "/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload));

                // Apply custom provider headers (no gateway-specific headers)
                GatewayHttpClientHelper.applyCustomProvider(requestBuilder, effectiveUrl, effectiveKey, CLIENT_TYPE, sid);
            } else {
                // CraftyAI Gateway: use /v1/chat with gateway payload format
                Map<String, Object> payload = new HashMap<>();
                payload.put("prompt", question);
                payload.put("player_name", playerName);
                payload.put("client_type", CLIENT_TYPE);
                payload.put("version", GatewayRequestHeaders.MOD_VERSION);
                payload.put("context", context);
                payload.put("server_id", sid);

                try {
                    com.google.gson.JsonArray historyArray = com.demonz.craftyai.common.JsonParserAdapter.parse(historyJson).getAsJsonArray();
                    payload.put("history", historyArray);
                } catch (Exception e) {
                    payload.put("history", new com.google.gson.JsonArray());
                }

                jsonPayload = GSON.toJson(payload);

                requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(effectiveUrl + "/v1/chat"))
                    .header("Authorization", "Bearer " + effectiveKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload));

                // Apply gateway headers
                GatewayHttpClientHelper.apply(requestBuilder, CLIENT_TYPE, sid);
            }

            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                if (cfg.custom_provider_enabled) {
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
            // Return structured error info for the caller to display
            int code = response.statusCode();
            LOGGER.warn("[CraftyAI] API returned HTTP {}: {}", code, response.body());
            if (code == 401 || code == 403) {
                try {
                    String body = response.body();
                    if (body != null) {
                        if (body.contains("suspended") || body.contains("revoked")) {
                            return "__ERROR__:\u00A7c\u00A7lAccount Suspended. \u00A7r\u00A77Create a ticket at \u00A7b\u00A7ndiscord.gg/zCkE44hsBR\u00A7r\u00A77 to appeal.";
                        }
                        if (body.contains("paused") || body.contains("inactive")) {
                            return "__ERROR__:\u00A7c\u00A7lSubscription Paused. \u00A7r\u00A77Resume it at the dashboard or contact your admin.";
                        }
                        try {
                            com.google.gson.JsonObject errJson = com.demonz.craftyai.common.JsonParserAdapter.parse(body).getAsJsonObject();
                            if (errJson.has("error")) {
                                String errMsg = errJson.get("error").getAsString();
                                if (errMsg != null && !errMsg.isEmpty()) {
                                    return "__ERROR__:" + errMsg;
                                }
                            }
                        } catch (Exception parseEx) {
                            LOGGER.warn("[CraftyAI] Failed to parse API error response: {}", parseEx.getMessage());
                        }
                    }
                } catch (Exception e) {
                    LOGGER.warn("[CraftyAI] Failed to parse API error response: {}", e.getMessage());
                }
                return "__ERROR__:API key invalid or expired. Check settings (M key).";
            }
            if (code == 429) {
                String body = response.body();
                if (body != null && body.contains("Daily token limit exceeded")) {
                    return "__ERROR__:\u00A7c[Limit] Daily token limit exceeded for your tier.";
                }
                return "__ERROR__:Rate limited. Please wait a moment before asking again.";
            }
            if (code == 503) return "__ERROR__:AI service temporarily unavailable. Try again shortly.";
            return "__ERROR__:Server error (HTTP " + code + "). Try again later.";
        } catch (java.net.http.HttpTimeoutException e) {
            LOGGER.error("[CraftyAI] Request timed out: {}", e.getMessage());
            return "__ERROR__:Request timed out. Check your internet connection.";
        } catch (java.net.ConnectException e) {
            LOGGER.error("[CraftyAI] Connection refused: {}", e.getMessage());
            return "__ERROR__:Could not connect to the AI service.";
        } catch (Exception e) {
            LOGGER.error("[CraftyAI] Request failed: {}", e.getMessage());
            return null;
        }
    }

    // --- Utilities ---

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
        // Cap total cache size to prevent unbounded memory growth
        if (conversationCache.size() > MAX_CACHED_PLAYERS) {
            Iterator<UUID> it = conversationCache.keySet().iterator();
            while (conversationCache.size() > MAX_CACHED_PLAYERS && it.hasNext()) {
                it.next();
                it.remove();
            }
        }
    }

    private String buildHistoryJson(LinkedList<Map<String, String>> history) {
        synchronized (history) {
            return GSON.toJson(history);
        }
    }

    private void executeAction(String actionString) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return;

        // Agentic tasks are FREE for all tiers.
        // No tier check. Only config + OP/cheats gates remain.

        if (!config.agentic_tasks_enabled || !config.ai_enable_actions) {
            mc.player.sendSystemMessage(Component.literal("\u00A78[\u00A7bCraftyAI\u00A78] \u00A77Action suggested: \u00A7e" + actionString + " \u00A78(agentic tasks disabled in config)"));
            LOGGER.info("[CraftyAI] Skipped agentic action {} — agentic tasks disabled in config", actionString);
            return;
        }

        // Client-side: actions require cheats/OP — warn if not available
        if (!mc.player.canUseGameMasterBlocks()) {
            mc.player.sendSystemMessage(Component.literal("\u00A78[\u00A7bCraftyAI\u00A78] \u00A77Action suggested: \u00A7e" + actionString + " \u00A78(requires OP/cheats)"));
            LOGGER.info("[CraftyAI] Skipped action {} — player lacks OP/cheats", actionString);
            return;
        }

        String playerKey = mc.player.getUUID().toString();
        if (!actionRateLimiter.tryAcquire(playerKey)) {
            long sec = actionRateLimiter.secondsUntilReset(playerKey);
            mc.player.sendSystemMessage(Component.literal("§c§o[Rate limit: try again in " + sec + "s]"));
            return;
        }

        String[] actions = actionString.split("\\|");
        for (String action : actions) {
            String upper = action.toUpperCase().trim();
            if (upper.isEmpty()) continue;

            // Safety confirmation flow on client-side
            com.demonz.craftyai.common.AgenticActions.Risk risk = com.demonz.craftyai.common.AgenticActions.riskFor(action);
            if (risk == com.demonz.craftyai.common.AgenticActions.Risk.DESTRUCTIVE && (config == null || config.require_confirmation)) {
                String confirmed = actionConfirmations.confirm(playerKey);
                if (confirmed == null || !confirmed.equalsIgnoreCase(upper)) {
                    actionConfirmations.request(playerKey, action);
                    mc.player.sendSystemMessage(Component.literal("§c§o⚠ Destructive action: §f" + upper + " §c§o— run §e/craftyclient confirm §c§owithin 30s to execute."));
                    continue;
                }
            }

            String command = null;
            String feedback = null;

            switch (upper) {
            case "TIME_DAY":
                command = "time set day";
                feedback = "\u00A7e\u2600 Time set to day";
                break;
            case "TIME_NIGHT":
                command = "time set night";
                feedback = "\u00A79\u263D Time set to night";
                break;
            case "WEATHER_CLEAR":
                command = "weather clear";
                feedback = "\u00A7a\u2600 Weather cleared";
                break;
            case "WEATHER_RAIN":
                command = "weather rain";
                feedback = "\u00A79\u2602 Weather set to rain";
                break;
            case "WEATHER_THUNDER":
                command = "weather thunder";
                feedback = "\u00A7c\u26A1 Thunderstorm activated";
                break;
            case "HEAL":
                command = "effect give @s minecraft:instant_health 1 255";
                feedback = "\u00A7a\u2764 Healed!";
                break;
            case "FEED":
                command = "effect give @s minecraft:saturation 1 255";
                feedback = "\u00A76\u2615 Fully fed!";
                break;
            case "KILL_MOBS":
                command = "kill @e[type=!player,distance=..50,type=!item,type=!xp_orb]";
                feedback = "\u00A7c\u2620 Nearby hostile mobs eliminated";
                break;
            case "TELEPORT_SPAWN":
                command = "tp @s 0 64 0";
                feedback = "\u00A7d\u2728 Teleporting to spawn...";
                break;
            case "GAMEMODE_CREATIVE":
                command = "gamemode creative";
                feedback = "\u00A7b\u2726 Switched to Creative mode";
                break;
            case "GAMEMODE_SURVIVAL":
                command = "gamemode survival";
                feedback = "\u00A7a\u2694 Switched to Survival mode";
                break;
            case "GAMEMODE_SPECTATOR":
                command = "gamemode spectator";
                feedback = "\u00A77\u2639 Switched to Spectator mode";
                break;
            default:
                // Handle SCAN_BLOCKS
                if (action.toUpperCase().startsWith("SCAN_BLOCKS:")) {
                    String[] parts = action.split(":");
                    int radius = 8;
                    try {
                        if (parts.length >= 2) radius = Math.min(32, Math.max(1, Integer.parseInt(parts[1].trim())));
                    } catch (NumberFormatException ignored) {}
                    boolean includePlayers = parts.length >= 3 && "1".equals(parts[2]);
                    boolean includeEntities = parts.length >= 4 && "1".equals(parts[3]);
                    boolean includeBlocks = parts.length >= 5 && "1".equals(parts[4]);
                    if (!includeBlocks && !includeEntities && !includePlayers) {
                        includeBlocks = true; includeEntities = true;
                    }
                    net.minecraft.core.BlockPos playerPos = mc.player.blockPosition();
                    int blockCount = 0;
                    int entityCount = 0;
                    int playerCount = 0;
                    java.util.Map<String, Integer> blockTypes = new java.util.LinkedHashMap<>();
                    java.util.Map<String, Integer> entityTypes = new java.util.LinkedHashMap<>();
                    java.util.List<String> playerNames = new java.util.ArrayList<>();
                    if (includeBlocks) {
                        for (int x = -radius; x <= radius; x++) {
                            for (int y = -radius; y <= radius; y++) {
                                for (int z = -radius; z <= radius; z++) {
                                    net.minecraft.core.BlockPos pos = playerPos.offset(x, y, z);
                                    String blockName = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(pos).getBlock()).getPath();
                                    blockTypes.merge(blockName, 1, Integer::sum);
                                    blockCount++;
                                }
                            }
                        }
                    }
                    if (includeEntities) {
                        java.util.List<net.minecraft.world.entity.Entity> entities = mc.level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class, mc.player.getBoundingBox().inflate(radius));
                        for (net.minecraft.world.entity.Entity e : entities) {
                            if (e instanceof net.minecraft.world.entity.player.Player) {
                                String name = e.getName().getString();
                                if (!playerNames.contains(name)) playerNames.add(name);
                                playerCount++;
                                continue;
                            }
                            String typeName = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
                            entityTypes.merge(typeName, 1, Integer::sum);
                            entityCount++;
                        }
                    }
                    if (mc.level != null) {
                        String selfName = mc.player.getName().getString();
                        for (net.minecraft.world.entity.player.Player p : mc.level.players()) {
                            String name = p.getName().getString();
                            if (!name.equals(selfName) && !playerNames.contains(name)) playerNames.add(name);
                        }
                        playerCount = playerNames.size();
                    }
                    String aiName = config.ai_name != null ? config.ai_name : "Crafty";
                    StringBuilder msg = new StringBuilder();
                    msg.append("\u00A7b[").append(aiName).append("] \u00A77> \u00A7f");
                    if (blockCount == 0 && entityCount == 0 && playerCount == 0) {
                        msg.append("It\u00A7cs quiet around here \u2014 nothing notable within \u00A7e").append(radius).append("\u00A7f blocks.");
                    } else {
                        msg.append("Scan results within \u00A7e").append(radius).append("\u00A7f blocks:\n");
                        if (includeBlocks && !blockTypes.isEmpty()) {
                            msg.append("\u00A77  [\u00A7eBlocks\u00A77] \u00A7f");
                            blockTypes.entrySet().stream()
                                .sorted((a, b) -> b.getValue() - a.getValue())
                                .limit(6)
                                .forEach(e -> msg.append("\u00A77").append(e.getKey().replace('_', ' ')).append(" \u00A77x\u00A7e").append(e.getValue()).append("\u00A77, "));
                            if (msg.charAt(msg.length() - 2) == ',') msg.setLength(msg.length() - 2);
                            msg.append("\n");
                        }
                        if (includeEntities && !entityTypes.isEmpty()) {
                            msg.append("\u00A77  [\u00A7cMobs\u00A77] \u00A7f");
                            entityTypes.entrySet().stream()
                                .sorted((a, b) -> b.getValue() - a.getValue())
                                .forEach(e -> msg.append("\u00A7c").append(e.getKey().replace('_', ' ')).append(" \u00A77x\u00A7c").append(e.getValue()).append("\u00A77, "));
                            if (msg.charAt(msg.length() - 2) == ',') msg.setLength(msg.length() - 2);
                            msg.append("\n");
                        }
                        if (!playerNames.isEmpty()) {
                            msg.append("\u00A77  [\u00A7bPlayers\u00A77] \u00A7f");
                            for (int i = 0; i < playerNames.size(); i++) {
                                msg.append("\u00A7b").append(playerNames.get(i));
                                if (i < playerNames.size() - 1) msg.append("\u00A77, ");
                            }
                            msg.append("\n");
                        }
                        if (blockCount > 0 && includeBlocks) {
                            msg.append("\u00A77  [\u00A7aSummary\u00A77] \u00A7f").append(blockCount).append(" blocks");
                            if (entityCount > 0) msg.append(", ").append(entityCount).append(" mobs");
                            if (playerCount > 0) msg.append(", ").append(playerCount).append(" player").append(playerCount > 1 ? "s" : "");
                            String topBlock = blockTypes.entrySet().stream().max(java.util.Map.Entry.comparingByValue()).map(e -> e.getKey().replace('_', ' ')).orElse("air");
                            msg.append(". Mostly \u00A7e").append(topBlock).append("\u00A7f.");
                            boolean hasOre = blockTypes.keySet().stream().anyMatch(k -> k.contains("ore"));
                            if (hasOre) msg.append(" \u00A7aOre deposits found!");
                            msg.append("\n");
                        }
                    }
                    mc.player.sendSystemMessage(Component.literal(msg.toString()));
                    LOGGER.info("[CraftyAI] SCAN_BLOCKS r={} — {} blocks, {} mobs, {} players", radius, blockCount, entityCount, playerCount);

                    // Send scan results back to AI so it can respond with informed decisions
                    StringBuilder scanCtx = new StringBuilder();
                    scanCtx.append("[Block Scan Results]\n");
                    scanCtx.append("Player position: ").append(playerPos.getX()).append(", ").append(playerPos.getY()).append(", ").append(playerPos.getZ()).append("\n");
                    // Detect if underground (solid blocks above player at Y+1 to Y+5)
                    int solidAbove = 0;
                    for (int dy = 1; dy <= 5; dy++) {
                        if (!mc.level.getBlockState(playerPos.offset(0, dy, 0)).isAir()) solidAbove++;
                    }
                    scanCtx.append("Underground: ").append(solidAbove >= 3 ? "yes" : "no").append("\n");
                    scanCtx.append("Radius: ").append(radius).append(" blocks\n");
                    if (includeBlocks && !blockTypes.isEmpty()) {
                        scanCtx.append("Blocks: ");
                        blockTypes.entrySet().stream()
                            .sorted((a, b) -> b.getValue() - a.getValue())
                            .forEach(e -> scanCtx.append(e.getKey().replace('_', ' ')).append(" x").append(e.getValue()).append(", "));
                        if (scanCtx.charAt(scanCtx.length() - 2) == ',') scanCtx.setLength(scanCtx.length() - 2);
                        scanCtx.append("\n");
                    }
                    if (includeEntities && !entityTypes.isEmpty()) {
                        scanCtx.append("Entities: ");
                        entityTypes.entrySet().stream()
                            .sorted((a, b) -> b.getValue() - a.getValue())
                            .forEach(e -> scanCtx.append(e.getKey().replace('_', ' ')).append(" x").append(e.getValue()).append(", "));
                        if (scanCtx.charAt(scanCtx.length() - 2) == ',') scanCtx.setLength(scanCtx.length() - 2);
                        scanCtx.append("\n");
                    }
                    if (!playerNames.isEmpty()) {
                        scanCtx.append("Players nearby: ").append(String.join(", ", playerNames)).append("\n");
                    }
                    final String scanContext = scanCtx.toString();
                    handleClientChatDirect("Here are the scan results from my previous request. Based on this data, what do you recommend?", scanContext);
                    continue; // skip sendCommand
                }
                // Handle DELAYED_ACTION:<seconds>:<innerAction>
                if (action.toUpperCase().startsWith("DELAYED_ACTION:")) {
                    String[] parts = action.split(":", 3);
                    if (parts.length >= 3) {
                        int delaySec = Math.max(1, Math.min(300, Integer.parseInt(parts[1])));
                        String innerAction = parts[2];
                        final String fa = innerAction;
                        mc.player.sendSystemMessage(Component.literal("\u00A7e\u23F3 Action queued: " + innerAction + " in " + delaySec + "s"));
                        Thread delayedThread = new Thread(() -> {
                            try { Thread.sleep(delaySec * 1000L); } catch (InterruptedException e) { return; }
                            mc.execute(() -> {
                                if (mc.player != null && mc.player.isAlive()) {
                                    executeAction(fa);
                                }
                            });
                        }, "CraftyAI-DelayedAction");
                        delayedThread.setDaemon(true);
                        delayedThread.start();
                    }
                    continue;
                }
                // Handle SCHEDULE_TASK — send to server for storage
                if (action.toUpperCase().startsWith("SCHEDULE_TASK:")) {
                    String[] parts = action.split(":", 4);
                    if (parts.length < 4) {
                        mc.player.sendSystemMessage(Component.literal("\u00A7c[CraftyAI] Invalid schedule format."));
                        continue;
                    }
                    String cronExpr = parts[1].trim();
                    String actionType = parts[2].trim().toLowerCase();
                    String message = parts[3].trim();
                    String taskName = message.length() > 40 ? message.substring(0, 40) + "..." : message;
                    final String fCron = cronExpr;
                    final String fType = actionType;
                    final String fName = taskName;
                    final String fMsg = message;
                    mc.player.sendSystemMessage(Component.literal("\u00A7e\u23F0 Scheduling task..."));
                    final String apiKeySnap = config.api_key;
                    final String serverIdSnap = config.server_id;
                    Thread schedThread = new Thread(() -> {
                        try {
                            java.util.Map<String, Object> schedPayload = new java.util.LinkedHashMap<>();
                            schedPayload.put("name", fName);
                            schedPayload.put("cron_expr", fCron);
                            schedPayload.put("action_type", fType);
                            java.util.Map<String, String> schedInner = new java.util.LinkedHashMap<>();
                            schedInner.put("message", fMsg);
                            schedPayload.put("action_payload", schedInner);
                            String json = GSON.toJson(schedPayload);
                            String targetUrl = GatewayRequestHeaders.getGatewayUrl() + "/v1/schedule-task";
                            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                                .uri(java.net.URI.create(targetUrl))
                                .header("Content-Type", "application/json")
                                .header("Authorization", "Bearer " + apiKeySnap)
                                .header("X-Server-ID", serverIdSnap)
                                .header("User-Agent", "CraftyAI-Minecraft/" + com.demonz.craftyai.common.GatewayRequestHeaders.MOD_VERSION)
                                .header("X-Client-Type", "minecraft-java")
                                .header("X-CraftyAI-Version", com.demonz.craftyai.common.GatewayRequestHeaders.MOD_VERSION)
                                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json))
                                .build();
                            java.net.http.HttpResponse<String> resp = httpClient.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
                            String respBody = resp.body();
                            if (resp.statusCode() == 200) {
                                mc.execute(() -> mc.player.sendSystemMessage(Component.literal("\u00A7a\u2714 Task scheduled: " + fName + " (" + fCron + ")")));
                            } else {
                                String errMsg = respBody.contains("error") ? respBody.substring(respBody.indexOf("\"error\""), Math.min(respBody.indexOf("\"error\"") + 80, respBody.length())) : "HTTP " + resp.statusCode();
                                mc.execute(() -> mc.player.sendSystemMessage(Component.literal("\u00A7c\u2716 Schedule failed: " + errMsg)));
                            }
                        } catch (Exception e) {
                            mc.execute(() -> mc.player.sendSystemMessage(Component.literal("\u00A7c\u2716 Schedule error: " + e.getMessage())));
                        }                     }, "CraftyAI-ScheduleTask");
                    schedThread.setDaemon(true);
                    schedThread.start();
                    continue;
                }
                // Handle CHAT:<command> — execute vanilla command as player
                if (upper.startsWith("CHAT:")) {
                    String cmd = action.substring("CHAT:".length()).trim();
                    if (cmd.startsWith("/")) cmd = cmd.substring(1);
                    cmd = normalizeLocateCommand(cmd);
                    if (!AgenticActions.isAllowedChatCommand(cmd)) {
                        mc.player.sendSystemMessage(Component.literal("\u00A7c[CraftyAI] Blocked unsafe AI command. Only /locate is allowed."));
                        continue;
                    }
                    final String finalCmd = cmd;
                    mc.player.sendSystemMessage(Component.literal("\u00A77[CraftyAI] Running: /" + finalCmd));
                    mc.player.connection.sendCommand(finalCmd);
                    continue;
                }
                // Handle parameterized actions like GIVE:diamond:64, EFFECT:speed:120, etc.
                if (action.toUpperCase().startsWith("GIVE:")) {
                    String[] parts = action.split(":", 3);
                    if (parts.length >= 2) {
                        String item = parts[1].toLowerCase().trim();
                        if (GIVE_BLACKLIST.contains(item)) {
                            Minecraft.getInstance().player.sendSystemMessage(Component.literal("\u00A7c[CraftyAI] Cannot give blacklisted item: " + item));
                            LOGGER.warn("[CraftyAI] Blocked GIVE action for blacklisted item: {}", item);
                            continue;
                        }
                        String count = parts.length >= 3 ? parts[2].trim() : "1";
                        command = "give @s minecraft:" + item + " " + count;
                        feedback = "\u00A7a\u2726 Given " + count + "x " + item;
                    }
                } else if (action.toUpperCase().startsWith("EFFECT:")) {
                    String[] parts = action.split(":", 3);
                    if (parts.length >= 2) {
                        String effect = parts[1].toLowerCase().trim();
                        String duration = parts.length >= 3 ? parts[2].trim() : "60";
                        command = "effect give @s minecraft:" + effect + " " + duration;
                        feedback = "\u00A7d\u2728 Applied " + effect + " for " + duration + "s";
                    }
                } else if (action.toUpperCase().startsWith("ENCHANT:")) {
                    String[] parts = action.split(":", 3);
                    if (parts.length >= 2) {
                        String enchant = parts[1].toLowerCase().trim();
                        String level = parts.length >= 3 ? parts[2].trim() : "1";
                        command = "enchant @s minecraft:" + enchant + " " + level;
                        feedback = "\u00A7b\u2728 Enchanted with " + enchant + " " + level;
                    }
                } else if (action.toUpperCase().startsWith("TP:")) {
                    String[] parts = action.split(":", 4);
                    if (parts.length >= 4) {
                        command = "tp @s " + parts[1].trim() + " " + parts[2].trim() + " " + parts[3].trim();
                        feedback = "\u00A7d\u2728 Teleporting to " + parts[1] + ", " + parts[2] + ", " + parts[3];
                    }
                }
                break;
        }

        if (command != null) {
            mc.player.sendSystemMessage(Component.literal("\u00A73[CraftyAI] " + (feedback != null ? feedback : "Executing action...")));
            mc.getConnection().sendCommand(command);
            LOGGER.info("[CraftyAI] Executed action: {} -> /{}", action, command);
        } else {
            LOGGER.warn("[CraftyAI] Unknown action trigger: {}", action);
        }
        } // End for loop
    }

    /**
     * Client-side action inference: when the LLM truncates the action field,
     * infer the action from the player's question and the AI's text response.
     */
    private String inferActionFromText(String answer, String question) {
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

    public static void safeSetScreen(Object client, Object screen) {
        if (client == null) return;
        LOGGER.info("[CraftyAI] safeSetScreen called with client={}, screen={}", client.getClass().getName(), screen != null ? screen.getClass().getName() : "null");
        
        for (java.lang.reflect.Method m : client.getClass().getMethods()) {
            if (m.getParameterCount() == 1) {
                String name = m.getName();
                if (name.equals("setScreenAndShow") || name.equals("setScreen") || name.equals("method_1507") || name.equals("m_91152_")) {
                    try {
                        m.setAccessible(true);
                        m.invoke(client, screen);
                        LOGGER.info("[CraftyAI] Reflective setScreen method {} succeeded!", name);
                        return;
                    } catch (Throwable t) {
                        LOGGER.warn("[CraftyAI] Reflective setScreen method {} failed: {}", name, t.toString());
                    }
                }
            }
        }
        for (java.lang.reflect.Method m : client.getClass().getDeclaredMethods()) {
            if (m.getParameterCount() == 1) {
                String name = m.getName();
                if (name.equals("setScreenAndShow") || name.equals("setScreen") || name.equals("method_1507") || name.equals("m_91152_")) {
                    try {
                        m.setAccessible(true);
                        m.invoke(client, screen);
                        LOGGER.info("[CraftyAI] Declared reflective setScreen method {} succeeded!", name);
                        return;
                    } catch (Throwable t) {
                        LOGGER.warn("[CraftyAI] Declared reflective setScreen method {} failed: {}", name, t.toString());
                    }
                }
            }
        }
        LOGGER.error("[CraftyAI] Failed to set screen via all reflective methods!");
    }
}
