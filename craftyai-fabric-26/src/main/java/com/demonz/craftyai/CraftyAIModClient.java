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

import com.demonz.craftyai.common.ActionHarness;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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
    private static final java.util.concurrent.ScheduledThreadPoolExecutor TIMEOUTS = new java.util.concurrent.ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "craftyai-client-timeouts");
        thread.setDaemon(true);
        return thread;
    });
    static { TIMEOUTS.setRemoveOnCancelPolicy(true); }

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

        clientConfigDir = "config";
        loadClientConfig();
        localBrain = new LocalBrain(clientConfigDir, LOGGER::info);

        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(CraftyAIMod.MOD_ID, "main"));
        settingsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.craftyai.settings",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_M,
                category
        ));

        visionScanKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.craftyai.vision_scan",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                category
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            checkActionWorld(client);
            while (settingsKey.consumeClick()) {
                LOGGER.info("[CraftyAI] Settings key M pressed!");
                boolean isScreenNull = com.demonz.craftyai.common.ModernScreenAccess.current(client) == null;
                if (isScreenNull) {
                    safeSetScreen(client, new CraftyAISettingsScreen(null));
                }
            }

            while (visionScanKey.consumeClick()) {
                if (client.player != null && client.level != null) {
                    performVisionScan(client);
                }
            }

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
                    if (config.force_local_mode || (!hasApiKey && !hasCustomProvider)) {
                        client.player.sendSystemMessage(Component.literal("\u00A7c  \u26A0 No API key or custom provider set! Press M or use /crafty apikey <key>"));
                    }
                    client.player.sendSystemMessage(Component.literal("\u00A78  Hide this: set op_welcome_message to false in config/craftyai.json"));
                    client.player.sendSystemMessage(Component.literal(""));
                }
            }
        });

        registerClientCommands();

        ClientSendMessageEvents.ALLOW_CHAT.register((message) -> {
            net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
            if (client != null && client.getCurrentServer() != null && !client.isLocalServer()) return true;
            if (message == null || message.trim().isEmpty()) return true;
            String lower = message.toLowerCase().trim();
            String prefixVal = config.prefix != null ? config.prefix : "@";
            boolean requirePrefix = config.require_prefix;
            List<String> aliasesList = config.aliases != null ? Arrays.asList(config.aliases) : Arrays.asList("crafty", "craftyai", "ai", "helper");

            for (String alias : aliasesList) {
                String prefixed = prefixVal.toLowerCase() + alias;
                if (lower.startsWith(prefixed + " ")) {
                    if (client != null && client.player != null) {
                        client.player.sendSystemMessage(Component.literal("<" + client.player.getName().getString() + "> " + message));
                    }
                    String question = message.trim().substring(prefixed.length()).trim();
                    if (!question.isEmpty()) {
                        handleClientChatDirect(question);
                    }
                    return false;
                }
                if (!requirePrefix && lower.startsWith(alias + " ")) {
                    if (client != null && client.player != null) {
                        client.player.sendSystemMessage(Component.literal("<" + client.player.getName().getString() + "> " + message));
                    }
                    String question = message.trim().substring(alias.length()).trim();
                    if (!question.isEmpty()) {
                        handleClientChatDirect(question);
                    }
                    return false;
                }
            }
            return true;
        });

        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            shownWelcome = false;
            conversationCache.clear();
            LOGGER.info("[CraftyAI] Cleared client conversation cache on disconnect.");
        });

        performStartupHandshake();
        String firstAlias = (config.aliases != null && config.aliases.length > 0) ? config.aliases[0] : "crafty";
        LOGGER.info("[CraftyAI] Client module ready. Press M for settings, V for vision scan. Type @{} in chat.", firstAlias);
    }

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
            client.player.sendSystemMessage(Component.literal("\u00A7b\u00A7l[CraftyAI Vision Scan] \u00A77Scanning: \u00A7f" + blockName));
            client.player.sendSystemMessage(Component.literal("\u00A77Analyzing with AI..."));

            StringBuilder visionCtx = new StringBuilder();
            visionCtx.append("[Vision Scan]\n");
            visionCtx.append("Block: ").append(blockName).append("\n");

            String question = "I'm looking at a " + blockName + " block. What is it useful for in Minecraft? Any tips?";
            handleClientChat(null, question, visionCtx.toString());

            LOGGER.info("[CraftyAI] Vision scan sent to AI: {}", blockName);
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
        if (config == null || config.force_local_mode || !config.telemetry_enabled) return;

        if (config.custom_provider_enabled) {
            LOGGER.info("[CraftyAI] Custom provider enabled \u2014 skipping gateway handshake.");
            return;
        }

        String sid = com.demonz.craftyai.common.SessionManager.getSessionId(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir());

        CompletableFuture.runAsync(() -> {
            try {

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
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)), CLIENT_TYPE, sid)
                    .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
                                            Component.literal("\u00A7c\u00A7l[CraftyAI Warning] \u00A77" + message));
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
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                LOGGER.info("[CraftyAI] Auto-mint response: HTTP {}", response.statusCode());
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

                for (String mintLiteral : new String[]{"mint", "automint"}) {
                    tree.then(ClientCommands.literal(mintLiteral)
                        .executes(ctx -> {
                            ctx.getSource().sendFeedback(Component.literal("\u00A7b[CraftyAI] Requesting auto-minted API key..."));
                            performAutoMintKey(ctx.getSource());
                            return 1;
                        })
                    );
                }

                for (String settingsLiteral : new String[]{"settings", "gui", "config"}) {
                    tree.then(ClientCommands.literal(settingsLiteral)
                        .executes(ctx -> {
                            Minecraft client = Minecraft.getInstance();
                            client.execute(() -> safeSetScreen(client, new CraftyAISettingsScreen(null)));
                            return 1;
                        })
                    );
                }

                tree.then(ClientCommands.literal("ask")
                    .then(ClientCommands.argument("question", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String question = StringArgumentType.getString(ctx, "question");
                            handleClientChat(ctx.getSource(), question);
                            return 1;
                        })
                    )
                );

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

                tree.then(ClientCommands.literal("cancel")
                    .executes(ctx -> {
                        chatGeneration++;
                        cancelActiveRun();
                        var client = Minecraft.getInstance();
                        if (client.player != null) {
                            actionConfirmations.cancel(client.player.getUUID().toString());
                            client.player.sendSystemMessage(Component.literal("[CraftyAI] Task cancelled. No further actions will run."));
                        }
                        return 1;
                    })
                );

                tree.then(ClientCommands.literal("confirm")
                    .executes(ctx -> {
                        Minecraft client = Minecraft.getInstance();
                        if (client.player == null) return 0;
                        String playerKey = client.player.getUUID().toString();
                        String pending = actionConfirmations.confirm(playerKey);
                        if (pending == null) {
                            ctx.getSource().sendFeedback(Component.literal("\u00A77[CraftyAI] No destructive action pending."));
                        } else {
                            resumeConfirmedAction(pending);
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
        cancelActiveRun();
        final long requestGeneration = ++chatGeneration;
        final var requestClient = Minecraft.getInstance();
        final var requestPlayer = requestClient.player;
        final var requestWorld = requestClient.level;
        if (requestPlayer == null || requestWorld == null) return;
        final String requestPlayerName = requestPlayer.getName().getString();

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
        CompletableFuture.supplyAsync(() -> sendAIRequest(question, requestPlayerName, finalContext, historyJson))
            .thenAccept(responseBody -> {
                mc.execute(() -> {
                    if (requestGeneration != chatGeneration || requestClient.player != requestPlayer || requestClient.level != requestWorld) return;
                    if (mc.player == null) return;
                    if (responseBody != null && responseBody.startsWith("__ERROR__:")) {

                        String errorMsg = responseBody.substring("__ERROR__:".length());
                        mc.player.sendSystemMessage(Component.literal("\u00A7c[CraftyAI] " + errorMsg));
                    } else if (responseBody != null) {
                        NeuralResponse res;
                        try { res = GSON.fromJson(responseBody, NeuralResponse.class); }
                        catch (Exception invalid) { requestPlayer.sendSystemMessage(Component.literal("[CraftyAI] Invalid AI response. No action was run.")); return; }
                        if (res == null) return;
                        String answer = res != null ? res.getAnswer() : null;
                        String action = res != null ? res.getAction() : null;
                        if (action == null || action.isEmpty() || "null".equalsIgnoreCase(action)) {
                            action = inferActionFromText(answer, question);
                        } else if (action.toUpperCase().startsWith("SCHEDULE_TASK:") && question != null && question.toLowerCase().matches(".*\\b(in|after|wait)\\s+\\d+\\s*(seconds?|sec|minutes?|min)\\b.*")) {
                            action = inferActionFromText(answer, question);
                        }
                        boolean hasAction = action != null && !action.isEmpty() && !action.equalsIgnoreCase("null");
                        if (hasAction) {
                            runActionHarness(action, question, answer, finalContext, historyJson, false);
                        } else if (answer != null && !answer.isEmpty()) {
                            addToHistory(playerId, question, answer);
                            Minecraft.getInstance().player.sendSystemMessage(Component.literal("\u00A7b[" + config.ai_name + "] \u00A77> \u00A7f" + answer));
                        } else {
                            mc.player.sendSystemMessage(Component.literal("\u00A7c[CraftyAI] Got an empty response."));
                        }
                    } else {
                        String playerName = mc.getUser().getName();
                        String localAnswer = this.localBrain.generateOfflineResponse(question, playerName);
                        if (localAnswer != null) {
                            mc.player.sendSystemMessage(Component.literal("\u00A7b[" + config.ai_name + "] \u00A77> \u00A7f" + localAnswer));

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

            ctx.append("[PERMISSIONS]\n");

            String gameMode = "unknown";
            if (mc.gameMode != null) {
                gameMode = mc.gameMode.getPlayerMode().getName();
            }
            ctx.append("GameMode: ").append(gameMode).append("\n");

            boolean hasOp = player.canUseGameMasterBlocks();
            ctx.append("OP Status: ").append(hasOp ? "YES \u2014 has operator permissions" : "NO \u2014 does NOT have OP permissions").append("\n");

            boolean isSingleplayer = mc.hasSingleplayerServer();
            ctx.append("World Type: ").append(isSingleplayer ? "singleplayer" : "multiplayer").append("\n");

            boolean cheatsEnabled = hasOp;
            if (isSingleplayer && mc.getSingleplayerServer() != null) {

                cheatsEnabled = mc.getSingleplayerServer().getPlayerList().isAllowCommandsForAllPlayers();
            }
            ctx.append("Cheats: ").append(cheatsEnabled ? "enabled" : "disabled").append("\n");

            ctx.append("Can Fly: ").append(player.getAbilities().mayfly ? "yes" : "no").append("\n");

            if (mc.level != null) {
                ctx.append("Difficulty: ").append(mc.level.getDifficulty().name().toLowerCase()).append("\n");
            }
            ctx.append("\n");

            ctx.append("[ENVIRONMENT]\n");
            if (mc.level != null) {
                ctx.append("Dimension: ").append(mc.level.dimension().identifier().getPath()).append("\n");
                long time = mc.level.getDefaultClockTime() % 24000;
                String timeOfDay = time < 6000 ? "Morning" : time < 12000 ? "Day" : time < 18000 ? "Evening" : "Night";
                ctx.append("Time: ").append(timeOfDay).append("\n");

                boolean raining = mc.level.isRaining();
                boolean thundering = mc.level.isThundering();
                ctx.append("Weather: ").append(thundering ? "thunderstorm" : raining ? "rain" : "clear").append("\n");
            }
            ctx.append("\n");

            ctx.append("[PLAYER STATUS]\n");
            ctx.append("Health: ").append((int) player.getHealth()).append("/").append((int) player.getMaxHealth()).append("\n");
            ctx.append("Food: ").append(player.getFoodData().getFoodLevel()).append("/20\n");
            ctx.append("XP Level: ").append(player.experienceLevel).append("\n");
            ctx.append("Coords: ").append(player.blockPosition().getX())
               .append(",").append(player.blockPosition().getY())
               .append(",").append(player.blockPosition().getZ()).append("\n");

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
        handleClientChatDirect(question);
    }

    private void handleClientChat(Object source, String question, String extraContext) {

        handleClientChatDirect(question, extraContext);
    }

    private static String jsonString(String value) {
        if (value == null) return "\"\"";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\"";
    }

    private static String normalizeLocateCommand(String cmd) {
        return AgenticActions.normalizeLocateCommand(cmd);
    }

    private String sendAIRequest(String question, String playerName, String context, String historyJson) {
        try {
            final CraftyAIConfig cfg = config;
            if (cfg.force_local_mode) return null;
            String sid = com.demonz.craftyai.common.SessionManager.getSessionId(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir());

            String effectiveUrl = cfg.getEffectiveApiUrl();
            String effectiveKey = cfg.getEffectiveApiKey();

            String jsonPayload;
            HttpRequest.Builder requestBuilder;

            if (cfg.custom_provider_enabled) {

                List<Map<String, String>> messages = new ArrayList<>();
                Map<String, String> systemMsg = new HashMap<>();
                systemMsg.put("role", "system");
                systemMsg.put("content", "You are " + cfg.ai_name + ", a helpful AI assistant in Minecraft." +
                        (context != null && !context.isEmpty() ? "\n\nContext:\n" + context : ""));
                messages.add(systemMsg);

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
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8));

                GatewayHttpClientHelper.applyCustomProvider(requestBuilder, effectiveUrl, effectiveKey, CLIENT_TYPE, sid);
            } else {

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
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8));

                GatewayHttpClientHelper.apply(requestBuilder, CLIENT_TYPE, sid);
            }

            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                if (cfg.custom_provider_enabled) {

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

            int code = response.statusCode();
            LOGGER.warn("[CraftyAI] API returned HTTP {}", code);
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

    private void requestScanSummary(String originalQuestion, String scanContext) {
        Minecraft client = Minecraft.getInstance();
        final net.minecraft.client.player.LocalPlayer player = client.player;
        final net.minecraft.client.multiplayer.ClientLevel world = client.level;
        if (player == null || world == null) return;
        final String playerName = player.getName().getString();
        final String context = scanContext + com.demonz.craftyai.common.ScanFlow.privacyRules();
        final String prompt = com.demonz.craftyai.common.ScanFlow.followUpPrompt(originalQuestion);

        com.demonz.craftyai.common.ScanFlow.summarize(() -> sendAIRequest(prompt, playerName, context, "[]"))
                .thenAccept(answer -> client.execute(() -> {
                    if (client.player != player || client.level != world) return;
                    player.sendSystemMessage(Component.literal("\u00A7b[" + config.ai_name + "] \u00A77> \u00A7f" + answer));
                }));
    }

    private volatile RunScope activeRun;
    private long chatGeneration;

    private static final class RunScope {
        final Object player, world;
        volatile boolean valid = true;
        ActionHarness harness;
        String waitingAction;
        CompletableFuture<ActionHarness.Feedback> waiting;
        RunScope(Object player, Object world) { this.player = player; this.world = world; }
        boolean current() { return valid && harness != null && harness.isActive(); }
        boolean inWorld(Minecraft mc) { return valid && mc.player == player && mc.level == world; }
    }

    private void cancelActiveRun() {
        RunScope previous = activeRun;
        activeRun = null;
        if (previous != null) {
            previous.valid = false;
            previous.harness.cancel();
            if (previous.waiting != null) previous.waiting.complete(new ActionHarness.Feedback(previous.waitingAction,
                    ActionHarness.Status.CANCELLED, "Task cancelled."));
        }
    }

    private void checkActionWorld(Minecraft mc) {
        RunScope run = activeRun;
        if (run != null && (!run.inWorld(mc) || config == null || config.force_local_mode || !config.ai_enable_actions || !config.agentic_tasks_enabled)) { chatGeneration++; cancelActiveRun(); }
    }

    private void resumeConfirmedAction(String action) {
        Minecraft mc = Minecraft.getInstance();
        RunScope run = activeRun;
        if (run == null || !run.current() || !run.inWorld(mc) || run.waiting == null
                || run.waiting.isDone() || !action.equals(run.waitingAction)) return;
        CompletableFuture<ActionHarness.Feedback> waiting = run.waiting;
        run.waiting = null;
        executeSingleActionAsync(action, run, true).whenComplete((feedback, error) -> {
            if (error != null) waiting.completeExceptionally(error); else waiting.complete(feedback);
        });
    }

    public void runActionHarness(String initialAction, String question, String initialAnswer, String context, String historyJson, boolean confirmedByUser) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {

            if (confirmedByUser) { resumeConfirmedAction(initialAction); return; }
            cancelActiveRun();
            if (mc.player == null || mc.level == null || config.force_local_mode) return;
            UUID playerId = mc.player.getUUID();
            String playerName = mc.player.getName().getString();
            RunScope run = new RunScope(mc.player, mc.level);
            run.harness = new ActionHarness(action -> executeSingleActionAsync(action, run, false), observations -> {
                String prompt = ActionHarness.formatFollowUpPrompt(question, observations);
                return CompletableFuture.supplyAsync(() -> {
                    if (!run.current() || config.force_local_mode) return null;
                    String body = sendAIRequest(prompt, playerName, context, historyJson);
                    if (body == null || body.startsWith("__ERROR__:")) return null;
                    try { return GSON.fromJson(body, NeuralResponse.class); }
                    catch (Exception invalid) { return null; }
                });
            });
            activeRun = run;
            run.harness.start(initialAction).thenAccept(answer -> mc.execute(() -> {
                if (activeRun != run || !run.inWorld(mc)) return;
                activeRun = null;
                run.valid = false;
                if (answer != null && !answer.trim().isEmpty()) {
                    addToHistory(playerId, question, answer);
                    mc.player.sendSystemMessage(Component.literal("\u00A7b[" + config.ai_name + "] \u00A77> \u00A7f" + answer));
                }
            }));
        });
    }

    private CompletableFuture<ActionHarness.Feedback> executeSingleActionAsync(String action, RunScope run, boolean confirmed) {
        CompletableFuture<ActionHarness.Feedback> result = new CompletableFuture<>();
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (!run.current() || !run.inWorld(mc)) {
                result.complete(new ActionHarness.Feedback(action, ActionHarness.Status.CANCELLED, "The task is no longer active."));
                return;
            }
            try {
                executeActionOnClient(action, run, confirmed).whenComplete((feedback, error) -> {
                    if (error != null) result.completeExceptionally(error); else result.complete(feedback);
                });
            } catch (Exception invalid) {
                result.complete(new ActionHarness.Feedback(action, ActionHarness.Status.FAILED, "Invalid action: " + invalid.getMessage()));
            }
        });
        return result;
    }

    private CompletableFuture<ActionHarness.Feedback> executeActionOnClient(String action, RunScope run, boolean confirmed) {
        Minecraft mc = Minecraft.getInstance();
        if (config.force_local_mode || !config.agentic_tasks_enabled || !config.ai_enable_actions) {
            return CompletableFuture.completedFuture(new ActionHarness.Feedback(action, ActionHarness.Status.DENIED, "Actions are disabled in settings."));
        }
        String upper = action.trim().toUpperCase(java.util.Locale.ROOT);
        String playerKey = mc.player.getUUID().toString();
        String command = null;
        if (!upper.startsWith("SCAN_BLOCKS:") && !upper.startsWith("DELAYED_ACTION:") && !upper.startsWith("SCHEDULE_TASK:")) {
            command = com.demonz.craftyai.common.ActionCommands.command(action);
        }
        if (AgenticActions.riskFor(action) == AgenticActions.Risk.DESTRUCTIVE && config.require_confirmation && !confirmed) {
            actionConfirmations.request(playerKey, action);
            CompletableFuture<ActionHarness.Feedback> waiting = new CompletableFuture<>();
            run.waitingAction = action;
            run.waiting = waiting;
            mc.player.sendSystemMessage(Component.literal("\u00A7e[CraftyAI] Confirm " + AgenticActions.description(action)
                    + " with /craftyclient confirm within 30 seconds. Nothing has run yet."));
            ScheduledFuture<?> expiry = TIMEOUTS.schedule(() -> waiting.complete(new ActionHarness.Feedback(action,
                    ActionHarness.Status.DENIED, "Confirmation expired. Nothing was executed.")), 30, TimeUnit.SECONDS);
            waiting.whenComplete((feedback, error) -> expiry.cancel(false));
            return waiting;
        }
        if (!actionRateLimiter.tryAcquire(playerKey)) {
            return CompletableFuture.completedFuture(new ActionHarness.Feedback(action, ActionHarness.Status.DENIED,
                    "Action limit reached. Try again in " + actionRateLimiter.secondsUntilReset(playerKey) + " seconds."));
        }
        if (upper.startsWith("SCAN_BLOCKS:")) {
            return CompletableFuture.completedFuture(new ActionHarness.Feedback(action, ActionHarness.Status.SUCCEEDED, performLocalBlockScan(action)));
        }
        if (upper.startsWith("DELAYED_ACTION:")) {
            String[] parts = action.split(":", 3);
            if (parts.length != 3 || parts[2].toUpperCase(java.util.Locale.ROOT).startsWith("DELAYED_ACTION:")) throw new IllegalArgumentException("Invalid or nested delayed action.");
            int seconds = Integer.parseInt(parts[1]);
            if (seconds < 1 || seconds > 90) throw new IllegalArgumentException("Delay must be between 1 and 90 seconds within this task.");
            CompletableFuture<ActionHarness.Feedback> delayed = new CompletableFuture<>();
            mc.player.sendSystemMessage(Component.literal("[CraftyAI] Waiting " + seconds + " seconds before the next action."));
            ScheduledFuture<?> timer = TIMEOUTS.schedule(() -> executeSingleActionAsync(parts[2], run, false)
                    .whenComplete((feedback, error) -> { if (error != null) delayed.completeExceptionally(error); else delayed.complete(feedback); }), seconds, TimeUnit.SECONDS);
            run.harness.completion().whenComplete((answer, error) -> { timer.cancel(false); delayed.complete(new ActionHarness.Feedback(action, ActionHarness.Status.CANCELLED, "Task ended.")); });
            return delayed;
        }
        if (upper.startsWith("SCHEDULE_TASK:")) {
            String[] parts = action.split(":", 4);
            if (parts.length != 4) throw new IllegalArgumentException("Invalid schedule format.");
            String message = parts[3].trim();
            return executeScheduleTaskAsync(message.substring(0, Math.min(40, message.length())), parts[1].trim(), parts[2].trim().toLowerCase(java.util.Locale.ROOT), message, action);
        }
        return CommandFeedbackBridge.execute(mc, command, action, run::current);
    }

    private String performLocalBlockScan(String action) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return "World unavailable";

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
        java.util.Map<String, Integer> blockTypes = new java.util.LinkedHashMap<>();
        java.util.Map<String, Integer> entityTypes = new java.util.LinkedHashMap<>();
        java.util.List<String> playerNames = new java.util.ArrayList<>();
        int blockTotal = 0;
        if (includeBlocks) {
            for (int x = -radius; x <= radius; x += 2) {
                for (int y = -radius; y <= radius; y += 2) {
                    for (int z = -radius; z <= radius; z += 2) {
                        net.minecraft.core.BlockPos pos = playerPos.offset(x, y, z);
                        String blockName = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(pos).getBlock()).getPath();
                        blockTypes.merge(blockName, 1, Integer::sum);
                        blockTotal++;
                    }
                }
            }
        }
        if (includeEntities || includePlayers) {
            java.util.List<net.minecraft.world.entity.Entity> entities = mc.level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class, mc.player.getBoundingBox().inflate(radius));
            for (net.minecraft.world.entity.Entity e : entities) {
                if (e == mc.player) continue;
                if (e instanceof net.minecraft.world.entity.player.Player) {
                    String name = e.getName().getString();
                    if (includePlayers && !playerNames.contains(name)) playerNames.add(name);
                    continue;
                }
                String typeName = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
                if (includeEntities) if (includeEntities) if (includeEntities) entityTypes.merge(typeName, 1, Integer::sum);
            }
        }
        LOGGER.info("[CraftyAI] SCAN_BLOCKS r={} \u2014 {} blocks, {} mob types, {} players", radius, blockTotal, entityTypes.size(), playerNames.size());

        StringBuilder scanCtx = new StringBuilder("[Block Scan Results]\n");
        scanCtx.append("Player position: ").append(playerPos.getX()).append(", ").append(playerPos.getY()).append(", ").append(playerPos.getZ()).append("\n");
        int solidAbove = 0;
        for (int dy = 1; dy <= 5; dy++) {
            if (!mc.level.getBlockState(playerPos.offset(0, dy, 0)).isAir()) solidAbove++;
        }
        scanCtx.append("Underground: ").append(solidAbove >= 3 ? "yes" : "no").append("\n");
        scanCtx.append("Radius: ").append(radius).append(" blocks\n");
        if (!blockTypes.isEmpty()) {
            scanCtx.append("Blocks: ");
            int n = 0;
            for (java.util.Map.Entry<String, Integer> e : blockTypes.entrySet()) {
                if (n++ >= 12) { scanCtx.append("+").append(blockTypes.size() - 12).append(" more"); break; }
                if (n > 1) scanCtx.append(", ");
                scanCtx.append(e.getKey().replace('_', ' ')).append(" x").append(e.getValue());
            }
            scanCtx.append("\n");
        }
        if (!entityTypes.isEmpty()) {
            scanCtx.append("Entities: ");
            int n = 0;
            for (java.util.Map.Entry<String, Integer> e : entityTypes.entrySet()) {
                if (n++ > 0) scanCtx.append(", ");
                scanCtx.append(e.getKey().replace('_', ' ')).append(" x").append(e.getValue());
            }
            scanCtx.append("\n");
        }
        if (!playerNames.isEmpty()) {
            scanCtx.append("Players nearby: ").append(String.join(", ", playerNames)).append("\n");
        }
        return scanCtx.toString();
    }

    private CompletableFuture<ActionHarness.Feedback> executeScheduleTaskAsync(String fName, String fCron, String fType, String fMsg, String action) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("\u00A7e\u23F0 Scheduling task..."));
        }
        final String apiKeySnap = config.api_key;
        final String serverIdSnap = config.server_id;
        return CompletableFuture.supplyAsync(() -> {
            try {
                java.util.Map<String, Object> schedPayload = new java.util.LinkedHashMap<>();
                schedPayload.put("name", fName);
                schedPayload.put("cron_expr", fCron);
                schedPayload.put("action_type", fType);
                java.util.Map<String, String> schedInner = new java.util.LinkedHashMap<>();
                schedInner.put("message", fMsg);
                schedPayload.put("action_payload", schedInner);
                String schedJson = GSON.toJson(schedPayload);

                String targetUrl = GatewayRequestHeaders.getGatewayUrl() + "/v1/schedule-task";
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKeySnap)
                    .header("X-Server-ID", serverIdSnap != null ? serverIdSnap : "")
                    .header("User-Agent", "CraftyAI-Minecraft/" + GatewayRequestHeaders.MOD_VERSION)
                    .header("X-Client-Type", "minecraft-java")
                    .header("X-CraftyAI-Version", GatewayRequestHeaders.MOD_VERSION)
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(schedJson, StandardCharsets.UTF_8))
                    .build();
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                String respBody = resp.body();
                if (resp.statusCode() == 200) {
                    return new ActionHarness.Feedback(action, ActionHarness.Status.SUCCEEDED, "Task scheduled: " + fName + " (" + fCron + ")");
                } else {
                    String errMsg = respBody.contains("error") ? respBody.substring(respBody.indexOf("\"error\""), Math.min(respBody.indexOf("\"error\"") + 80, respBody.length())) : "HTTP " + resp.statusCode();
                    return new ActionHarness.Feedback(action, ActionHarness.Status.FAILED, "Schedule failed: " + errMsg);
                }
            } catch (Exception e) {
                return new ActionHarness.Feedback(action, ActionHarness.Status.FAILED, "Schedule error: " + e.getMessage());
            }
        });
    }

    private void executeAction(String actionString) {
        executeAction(actionString, null, false);
    }

    private void executeAction(String actionString, String originalQuestion) {
        executeAction(actionString, originalQuestion, false);
    }

    private void executeAction(String actionString, String originalQuestion, boolean confirmedByUser) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        runActionHarness(actionString, originalQuestion, null, buildContext(mc.player), "[]", confirmedByUser);
    }

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
        if (client != null) com.demonz.craftyai.common.ModernScreenAccess.set(client, screen);
    }
}
