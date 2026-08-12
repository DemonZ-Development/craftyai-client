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

import com.demonz.craftyai.common.CraftyAIConfig;
import com.demonz.craftyai.common.ConfigLoader;
import com.demonz.craftyai.common.GatewayRequestHeaders;
import com.demonz.craftyai.common.GatewayHttpClientHelper;
import com.demonz.craftyai.common.NeuralResponse;
import com.demonz.craftyai.common.VisionScanner;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.KeyMapping;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
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

/**
 * CraftyAI Forge Client Module
 * ============================
 * Client-side support for singleplayer AI chat and configuration GUI.
 */
@Mod.EventBusSubscriber(modid = CraftyAIForgeMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class CraftyAIForgeModClient {

    public static boolean isKeyTriggered(KeyMapping key) {
        if (key == null) return false;
        try { if (key.consumeClick()) return true; } catch (Throwable ignored) {}
        try { if (key.isDown()) return true; } catch (Throwable ignored) {}
        try {
            for (java.lang.reflect.Method m : key.getClass().getMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == boolean.class) {
                    String name = m.getName().toLowerCase();
                    if (name.contains("click") || name.contains("consume") || name.contains("down") || name.contains("press") || name.startsWith("m_")) {
                        m.setAccessible(true);
                        Object val = m.invoke(key);
                        if (val instanceof Boolean b && b) return true;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }


    private static final Logger LOGGER = LoggerFactory.getLogger("craftyai-client");
    private static final int MAX_HISTORY = 10;
    private static final String CLIENT_TYPE = "minecraft-forge-client";
    private static final Gson GSON = new GsonBuilder().create();

    private static final Set<String> GIVE_BLACKLIST = Set.of(
        "barrier", "command_block", "chain_command_block",
        "repeating_command_block", "command_block_minecart",
        "structure_block", "structure_void", "bedrock",
        "end_portal_frame", "spawner"
    );

    private static volatile CraftyAIConfig config;
    private static KeyMapping settingsKey;
    private static KeyMapping visionScanKey;
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private static final int MAX_CACHED_PLAYERS = 500;
    private static final ConcurrentHashMap<UUID, LinkedList<Map<String, String>>> conversationCache = new ConcurrentHashMap<>();
    private static volatile long lastChatTime = 0;
    private static volatile long lastVisionScanTime = 0;
    private static final long VISION_SCAN_COOLDOWN_MS = 5000L;
    private static final com.demonz.craftyai.common.ActionRateLimiter actionRateLimiter = new com.demonz.craftyai.common.ActionRateLimiter();
    private static final com.demonz.craftyai.common.ActionConfirmation actionConfirmations = new com.demonz.craftyai.common.ActionConfirmation();

    public static Object getMinecraftInstance() {
        try {
            return Minecraft.getInstance();
        } catch (Throwable t) {
            try {
                for (java.lang.reflect.Method m : Minecraft.class.getMethods()) {
                    if (m.getParameterCount() == 0 && java.lang.reflect.Modifier.isStatic(m.getModifiers()) && m.getReturnType().equals(Minecraft.class)) {
                        m.setAccessible(true);
                        return m.invoke(null);
                    }
                }
                for (java.lang.reflect.Method m : Minecraft.class.getDeclaredMethods()) {
                    if (m.getParameterCount() == 0 && java.lang.reflect.Modifier.isStatic(m.getModifiers()) && m.getReturnType().equals(Minecraft.class)) {
                        m.setAccessible(true);
                        return m.invoke(null);
                    }
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    public static void safeSetScreen(Object client, Object screen) {
        if (client == null) return;
        try {
            if (client instanceof Minecraft mc) {
                mc.setScreen(screen instanceof net.minecraft.client.gui.screens.Screen sc ? sc : null);
                return;
            }
        } catch (Throwable ignored) {}

        Class<?> screenClass = screen != null ? screen.getClass() : null;
        try {
            for (java.lang.reflect.Method m : client.getClass().getMethods()) {
                if (m.getParameterCount() == 1) {
                    String name = m.getName();
                    Class<?> paramType = m.getParameterTypes()[0];
                    if (name.equals("setScreenAndShow") || name.equals("setScreen") || name.equals("m_91152_") || name.equals("method_1507") ||
                            (screenClass != null && paramType.isAssignableFrom(screenClass)) ||
                            paramType.getName().contains("Screen")) {
                        try {
                            m.setAccessible(true);
                            m.invoke(client, screen);
                            return;
                        } catch (Throwable ignored) {}
                    }
                }
            }
            for (java.lang.reflect.Method m : client.getClass().getDeclaredMethods()) {
                if (m.getParameterCount() == 1) {
                    String name = m.getName();
                    Class<?> paramType = m.getParameterTypes()[0];
                    if (name.equals("setScreenAndShow") || name.equals("setScreen") || name.equals("m_91152_") || name.equals("method_1507") ||
                            (screenClass != null && paramType.isAssignableFrom(screenClass)) ||
                            paramType.getName().contains("Screen")) {
                        try {
                            m.setAccessible(true);
                            m.invoke(client, screen);
                            return;
                        } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable ignored) {}
    }


    public static void sendSystemMsg(Object player, Component msg) {
        if (player == null) {
            try {
                Minecraft mc = (Minecraft) getMinecraftInstance();
                if (mc != null) player = mc.player;
            } catch (Throwable ignored) {}
        }
        if (player == null) return;
        try {
            if (player instanceof net.minecraft.world.entity.player.Player p) {
                p.displayClientMessage(msg, false);
                return;
            }
        } catch (Throwable ignored) {}
        try {
            for (java.lang.reflect.Method m : player.getClass().getMethods()) {
                if (m.getParameterCount() >= 1 && Component.class.isAssignableFrom(m.getParameterTypes()[0])) {
                    m.setAccessible(true);
                    if (m.getParameterCount() == 1) {
                        m.invoke(player, msg);
                    } else if (m.getParameterCount() == 2 && m.getParameterTypes()[1] == boolean.class) {
                        m.invoke(player, msg, false);
                    }
                    return;
                }
            }
        } catch (Throwable ignored) {}
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        try {
            Object mcObj = getMinecraftInstance();
            if (mcObj == null) return;
            if (settingsKey != null && isKeyTriggered(settingsKey)) {
                boolean isScreenNull = true;
                if (mcObj instanceof Minecraft mc) {
                    try {
                        isScreenNull = (mc.screen == null);
                    } catch (Throwable t) {
                        try {
                            java.lang.reflect.Field f = mc.getClass().getDeclaredField("screen");
                            f.setAccessible(true);
                            isScreenNull = (f.get(mc) == null);
                        } catch (Throwable t2) {
                            try {
                                java.lang.reflect.Field f2 = mc.getClass().getDeclaredField("currentScreen");
                                f2.setAccessible(true);
                                isScreenNull = (f2.get(mc) == null);
                            } catch (Throwable t3) {
                                isScreenNull = true;
                            }
                        }
                    }
                }
                if (isScreenNull) {
                    safeSetScreen(mcObj, new CraftyAIForgeSettingsScreen(null));
                }
            }
            if (mcObj instanceof Minecraft mc) {
                if (!shownWelcome && mc.player != null && mc.level != null) {
                    shownWelcome = true;
                    if (config != null && config.op_welcome_message) {
                        sendSystemMsg(mc.player, Component.literal(""));
                        sendSystemMsg(mc.player, Component.literal("\u00A7b\u00A7l\u2726 CraftyAI \u00A77v" + GatewayRequestHeaders.MOD_VERSION + " \u00A78\u2014 \u00A77AI Companion"));
                        String prefixVal = config.prefix != null ? config.prefix : "@";
                        sendSystemMsg(mc.player, Component.literal("\u00A77  Type \u00A7e" + prefixVal + config.ai_name + " <question>\u00A77 in chat to talk to your AI."));
                        sendSystemMsg(mc.player, Component.literal("\u00A77  Press \u00A7eM\u00A77 to open settings. Press \u00A7eV\u00A77 to vision-scan. Use \u00A7e/crafty status\u00A77 to check status."));
                        boolean hasApiKey = (config.api_key != null && !config.api_key.isEmpty() && !config.api_key.equals("YOUR_API_KEY_HERE"));
                        boolean hasCustomProvider = (config.custom_provider_enabled && config.custom_provider_url != null && !config.custom_provider_url.isEmpty());
                        if (!hasApiKey && !hasCustomProvider) {
                            sendSystemMsg(mc.player, Component.literal("\u00A7c  \u26A0 No API key or custom provider set! Press M or use /crafty apikey <key>"));
                        }
                        sendSystemMsg(mc.player, Component.literal("\u00A78  Hide this: set op_welcome_message to false in config/craftyai.json"));
                        sendSystemMsg(mc.player, Component.literal(""));
                    }
                }
                // Vision scan key binding
                if (visionScanKey != null && isKeyTriggered(visionScanKey) && mc.player != null) {
                    long now = System.currentTimeMillis();
                    if (now - lastVisionScanTime < VISION_SCAN_COOLDOWN_MS) {
                        sendSystemMsg(mc.player, Component.literal("\u00A7c[CraftyAI] Please wait before scanning again."));
                        return;
                    }
                    lastVisionScanTime = now;
                    VisionScanner.ScanResult scanResult = VisionScanner.scan(mc.player, mc.player.level());
                    sendSystemMsg(mc.player, Component.literal("\u00A7b\u00A7l[CraftyAI Vision Scan]"));
                    sendSystemMsg(mc.player, Component.literal("\u00A77Biome: \u00A7f" + scanResult.biome.name));
                    sendSystemMsg(mc.player, Component.literal("\u00A77Dimension: \u00A7f" + scanResult.dimension));
                    sendSystemMsg(mc.player, Component.literal("\u00A77Time: \u00A7f" + scanResult.timeOfDay + " \u00A78| \u00A77Weather: \u00A7f" + scanResult.weather));
                    sendSystemMsg(mc.player, Component.literal("\u00A77Health: \u00A7f" + scanResult.health + "/" + scanResult.maxHealth + " \u00A78| \u00A77Food: \u00A7f" + scanResult.foodLevel + "/20"));
                    sendSystemMsg(mc.player, Component.literal("\u00A77Entities: \u00A7f" + scanResult.nearbyEntities.size() + " \u00A78| \u00A77Blocks: \u00A7f" + scanResult.nearbyBlocks.size()));
                    // Send vision data to AI for analysis
                    StringBuilder visionQuestion = new StringBuilder("I performed a vision scan. ");
                    visionQuestion.append("Biome: ").append(scanResult.biome.name);
                    visionQuestion.append(", Dimension: ").append(scanResult.dimension);
                    visionQuestion.append(", Time: ").append(scanResult.timeOfDay);
                    visionQuestion.append(", Weather: ").append(scanResult.weather);
                    visionQuestion.append(", ").append(scanResult.nearbyEntities.size()).append(" entities nearby");
                    visionQuestion.append(", ").append(scanResult.nearbyBlocks.size()).append(" blocks nearby");
                    visionQuestion.append(". What should I know about my surroundings?");
                    sendSystemMsg(mc.player, Component.literal("\u00A77Analyzing with AI..."));
                    handleClientChat(null, visionQuestion.toString());
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[CraftyAI] onClientTick error: {}", t.toString());
        }
    }

    private static boolean shownWelcome = false;

    @SubscribeEvent
    public static void onClientLogout(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        shownWelcome = false;
        conversationCache.clear();
        LOGGER.info("[CraftyAI] Cleared client conversation cache and welcome flag on disconnect.");
    }

    @SubscribeEvent
    public static void onClientChat(ClientChatEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.getCurrentServer() != null && !mc.isLocalServer()) return; // Only intercept in singleplayer
        if (event.getMessage() == null || event.getMessage().trim().isEmpty()) return;
        String msg = event.getMessage().trim();
        String lower = msg.toLowerCase();
        String prefixVal = config.prefix != null ? config.prefix : "@";
        boolean requirePrefix = config.require_prefix;
        List<String> aliasesList = config.aliases != null ? Arrays.asList(config.aliases) : Arrays.asList("crafty", "craftyai", "ai", "helper");

        for (String alias : aliasesList) {
            String prefixed = prefixVal.toLowerCase() + alias;
            if (lower.startsWith(prefixed + " ")) {
                event.setCanceled(true);
                String question = msg.substring(prefixed.length()).trim();
                if (!question.isEmpty()) {
                    handleClientChat(null, question);
                }
                return;
            }
            if (!requirePrefix && lower.startsWith(alias + " ")) {
                event.setCanceled(true);
                String question = msg.substring(alias.length()).trim();
                if (!question.isEmpty()) {
                    handleClientChat(null, question);
                }
                return;
            }
        }
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
            LiteralArgumentBuilder.<CommandSourceStack>literal("craftyclient")
                .executes(ctx -> {
                    sendSystemMsg(ctx.getSource(), Component.literal("\u00A7b\u00A7lCraftyAI \u00A77v" + GatewayRequestHeaders.MOD_VERSION + " \u00A78- \u00A77DemonZ Development"));
                    sendSystemMsg(ctx.getSource(), Component.literal("\u00A77Platform: \u00A7fForge Client"));
                    sendSystemMsg(ctx.getSource(), Component.literal("\u00A77Usage: \u00A7f/craftyclient <status|apikey|ask|settings>"));
                    return 1;
                })
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("status")
                    .executes(ctx -> {
                        sendSystemMsg(ctx.getSource(), Component.literal("\u00A7b[CraftyAI] \u00A77Client Status:"));
                        sendSystemMsg(ctx.getSource(), Component.literal("\u00A77  API Key: \u00A7f" + (config.api_key != null && config.api_key.startsWith("cai_") ? "configured" : "not set")));
                        String sid = com.demonz.craftyai.common.SessionManager.getSessionId(net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get());
                        sendSystemMsg(ctx.getSource(), Component.literal("\u00A77  Session ID: \u00A7f" + sid));
                        sendSystemMsg(ctx.getSource(), Component.literal("\u00A77  Press \u00A7eM \u00A77to open settings."));
                        return 1;
                    })
                )
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("apikey")
                    .then(RequiredArgumentBuilder.<CommandSourceStack, String>argument("key", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String key = StringArgumentType.getString(ctx, "key").trim();
                            if (!key.matches("^cai_[a-zA-Z0-9_-]{16,128}$")) {
                                sendSystemMsg(ctx.getSource(), Component.literal("\u00A7cInvalid API key format. Keys start with cai_."));
                                return 0;
                            }
                            config.api_key = key;
                            saveConfig();
                            sendSystemMsg(ctx.getSource(), Component.literal("\u00A7a[CraftyAI] API key saved."));
                            return 1;
                        })
                    )
                )
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("settings")
                    .executes(ctx -> {
                        Minecraft.getInstance().execute(() ->
                            Minecraft.getInstance().setScreen(new CraftyAIForgeSettingsScreen(null))
                        );
                        return 1;
                    })
                )
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("ask")
                    .then(RequiredArgumentBuilder.<CommandSourceStack, String>argument("question", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String question = StringArgumentType.getString(ctx, "question");
                            handleClientChat(ctx.getSource(), question);
                            return 1;
                        })
                    )
                )
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("confirm")
                    .executes(ctx -> {
                        Minecraft client = Minecraft.getInstance();
                        if (client.player == null) return 0;
                        String playerKey = client.player.getUUID().toString();
                        String pending = actionConfirmations.peek(playerKey);
                        if (pending == null) {
                            sendSystemMsg(ctx.getSource(), Component.literal("§7[CraftyAI] No destructive action pending."));
                        } else {
                            sendSystemMsg(ctx.getSource(), Component.literal("§a[CraftyAI] Confirmed pending action: §f" + pending.toUpperCase() + "§a. Re-run your request to execute it now."));
                        }
                        return 1;
                    })
                )
        );
    }

    public static CraftyAIConfig getConfig() { return config; }
    public static void reloadConfig() {
        config = ConfigLoader.loadOrCreateConfig("config", "craftyai.json", LOGGER::info);
    }
    public static void setTierFromServer(String tier) {
        if (config != null && tier != null && !tier.isEmpty()) {
            config.tier = tier;
        }
    }
    public static void saveConfig() {
        ConfigLoader.saveConfig("config", "craftyai.json", config, LOGGER::info);
    }

    private static void performStartupHandshake() {
        String sid = com.demonz.craftyai.common.SessionManager.getSessionId(
                net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get());

        CompletableFuture.runAsync(() -> {
            try {
                // Determine handshake URL based on custom provider config
                String handshakeUrl;
                if (config.custom_provider_enabled && config.custom_provider_url != null && !config.custom_provider_url.isEmpty()) {
                    handshakeUrl = config.custom_provider_url;
                } else {
                    handshakeUrl = GatewayRequestHeaders.getGatewayUrl();
                }

                // Determine server name for handshake
                String serverName = "";
                Minecraft mc = Minecraft.getInstance();
                if (mc != null) {
                    if (mc.isLocalServer() && mc.getSingleplayerServer() != null) {
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
                        .uri(URI.create(handshakeUrl + "/v1/handshake"))
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

    private static void handleClientChat(Object source, String question) {
        boolean hasApiKey = (config.api_key != null && !config.api_key.isEmpty() && !config.api_key.equals("YOUR_API_KEY_HERE"));
        boolean hasCustomProvider = (config.custom_provider_enabled && config.custom_provider_url != null && !config.custom_provider_url.isEmpty());
        if (config.force_local_mode || (!hasApiKey && !hasCustomProvider)) {
            com.demonz.craftyai.common.LocalBrain localBrain = new com.demonz.craftyai.common.LocalBrain(
                net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get().toString(),
                msg -> LOGGER.info(msg)
            );
            String localAnswer = localBrain.generateOfflineResponse(question, Minecraft.getInstance().getUser().getName());
            if (localAnswer != null) {
                String prefix = config.force_local_mode ? "\u00A7e[FORCED-LOCAL]" : "\u00A7e[OFFLINE]";
                sendSystemMsg(null, Component.literal("\u00A7b[" + config.ai_name + "] " + prefix + " \u00A77> \u00A7f" + localAnswer));
                if (!config.force_local_mode) {
                    sendSystemMsg(null, Component.literal("\u00A78\u00A7oTip: Set your API key with /craftyclient apikey <key> to get online responses."));
                }
            } else {
                sendSystemMsg(null, Component.literal("\u00A7c[CraftyAI] API key not set or local brain failed. Use /craftyclient apikey <key> or press M."));
            }
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastChatTime < config.getCooldownMs()) {
            sendSystemMsg(null, Component.literal("\u00A7c[CraftyAI] Please wait before asking again."));
            return;
        }
        lastChatTime = now;

        String sid = com.demonz.craftyai.common.SessionManager.getSessionId(net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get());

        sendSystemMsg(null, Component.literal("\u00A7b[" + config.ai_name + "] \u00A77Thinking..."));

        UUID playerId = Minecraft.getInstance().player.getUUID();
        LinkedList<Map<String, String>> history = conversationCache.computeIfAbsent(playerId, k -> new LinkedList<>());
        String historyJson = buildHistoryJson(history);

        String context = buildContext(Minecraft.getInstance().player);

        CompletableFuture.supplyAsync(() -> sendAIRequest(question, Minecraft.getInstance().player.getName().getString(), context, historyJson))
            .thenAccept(responseBody -> {
                Minecraft.getInstance().execute(() -> {
                    if (responseBody != null && responseBody.startsWith("__ERROR__:")) {
                        String errorMsg = responseBody.substring("__ERROR__:".length());
                        sendSystemMsg(null, Component.literal("\u00A7c[CraftyAI] " + errorMsg));
                    } else if (responseBody != null) {
                        // Use Gson-based NeuralResponse parsing instead of manual JSON parsing
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
                                final String playerNameStr = Minecraft.getInstance().player.getName().getString();
                                final String bgCtx = buildContext(Minecraft.getInstance().player);
                                final String aiNameFinal = config.ai_name != null ? config.ai_name : "Crafty";
                                final String answerFinal = answer;
                                Thread fbThread = new Thread(() -> {
                                    try {
                                        String actionContext = "[Action Executed]\nAction: " + actionForFollowUp + "\nPlayer: " + playerNameStr;
                                        String fullQuestion = "The player asked: \"" + originalQuestion + "\". The action \"" + actionForFollowUp + "\" was executed. Respond naturally in 1-2 sentences confirming what was done. Be conversational and brief. Do NOT output action codes or technical details.\n\n" + actionContext;
                                        java.util.LinkedList<java.util.Map<String, String>> followUpHist = conversationCache.computeIfAbsent(playerId, k -> new java.util.LinkedList<>());
                                        String followUpHistoryJson = buildHistoryJson(followUpHist);
                                        String followUpResponse = sendAIRequest(fullQuestion, playerNameStr, bgCtx, followUpHistoryJson);
                                        if (followUpResponse != null && !followUpResponse.startsWith("__ERROR__:")) {
                                            NeuralResponse followUpRes = GSON.fromJson(followUpResponse, NeuralResponse.class);
                                            String followUp = followUpRes != null ? followUpRes.getAnswer() : null;
                                            if (followUp != null && !followUp.isEmpty()) {
                                                net.minecraft.client.Minecraft.getInstance().execute(() -> {
                                                    if (Minecraft.getInstance().player != null) {
                                                        sendSystemMsg(null, Component.literal("\u00A7b[" + aiNameFinal + "] \u00A77> \u00A7f" + followUp));
                                                        addToHistory(playerId, "(action follow-up)", followUp);
                                                    }
                                                });
                                            }
                                        } else {
                                            net.minecraft.client.Minecraft.getInstance().execute(() -> {
                                                if (Minecraft.getInstance().player != null) {
                                                    sendSystemMsg(null, Component.literal("\u00A7b[" + aiNameFinal + "] \u00A77> \u00A7f" + answerFinal));
                                                }
                                            });
                                        }
                                    } catch (Exception e) {
                                        LOGGER.warn("[CraftyAI] Action follow-up failed: " + e.getMessage());
                                        net.minecraft.client.Minecraft.getInstance().execute(() -> {
                                            if (Minecraft.getInstance().player != null) {
                                                sendSystemMsg(null, Component.literal("\u00A7b[" + aiNameFinal + "] \u00A77> \u00A7f" + answerFinal));
                                            }
                                        });
                                    }
                                }, "CraftyAI-ActionFeedback");
                                fbThread.setDaemon(true);
                                fbThread.start();
                            } else {
                                sendSystemMsg(null, Component.literal("\u00A7b[" + config.ai_name + "] \u00A77> \u00A7f" + answer));
                                if (hasAction) {
                                    executeAction(action);
                                }
                            }
                        } else {
                            sendSystemMsg(null, Component.literal("\u00A7c[CraftyAI] Got an empty response."));
                        }
                    } else {
                        com.demonz.craftyai.common.LocalBrain localBrain = new com.demonz.craftyai.common.LocalBrain(
                            net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get().toString(),
                            msg -> LOGGER.info(msg)
                        );
                        String playerName = Minecraft.getInstance().getUser().getName();
                        String localAnswer = localBrain.generateOfflineResponse(question, playerName);
                        if (localAnswer != null) {
                            sendSystemMsg(null, Component.literal("\u00A7b[" + config.ai_name + "] \u00A7e[OFFLINE] \u00A77> \u00A7f" + localAnswer));
                            localBrain.enqueueRequest(question, playerName, "Offline from Forge Client");
                        } else {
                            sendSystemMsg(null, Component.literal("\u00A7c[CraftyAI] Could not reach the AI. Connection is offline."));
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

            // OP status
            boolean hasOp = player.hasPermissions(2);
            ctx.append("OP Status: ").append(hasOp ? "YES — has operator permissions" : "NO — does NOT have OP permissions").append("\n");

            // World type & cheats
            boolean isSingleplayer = mc.isLocalServer();
            ctx.append("World Type: ").append(isSingleplayer ? "singleplayer" : "multiplayer").append("\n");

            boolean cheatsEnabled = hasOp;
            ctx.append("Cheats: ").append(cheatsEnabled ? "enabled" : "disabled").append("\n");

            // Flying
            ctx.append("Can Fly: ").append(player.getAbilities().mayfly ? "yes" : "no").append("\n");

            // Difficulty
            if (player.level() != null) {
                ctx.append("Difficulty: ").append(player.level().getDifficulty().getKey()).append("\n");
            }
            ctx.append("\n");

            // --- ENVIRONMENT ---
            ctx.append("[ENVIRONMENT]\n");
            if (player.level() != null) {
                ctx.append("Dimension: ").append(player.level().dimension().location().getPath()).append("\n");
                long time = player.level().getDayTime() % 24000;
                String timeOfDay = time < 6000 ? "Morning" : time < 12000 ? "Day" : time < 18000 ? "Evening" : "Night";
                ctx.append("Time: ").append(timeOfDay).append("\n");

                boolean raining = player.level().isRaining();
                boolean thundering = player.level().isThundering();
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
                    String effectName = String.valueOf(effect.getEffect());
                    if (effectName.contains(".")) {
                        effectName = effectName.substring(effectName.lastIndexOf('.') + 1);
                    }
                    if (effectName.contains(":")) {
                        effectName = effectName.substring(effectName.lastIndexOf(':') + 1);
                    }
                    int amp = effect.getAmplifier() + 1;
                    int dur = effect.getDuration() / 20;
                    ctx.append(effectName).append(" ").append(amp).append(" (").append(dur).append("s), ");
                });
                ctx.append("\n");
            }

        } catch (Exception ignored) {}
        return ctx.toString();
    }

    private static String sendAIRequest(String question, String playerName, String context, String historyJson) {
        try {
            final CraftyAIConfig cfg = config;
            String sid = com.demonz.craftyai.common.SessionManager.getSessionId(net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get());

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
                } catch (Exception ignored) {}

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
                String json = "{\"prompt\":" + jsonString(question) + "," +
                    "\"player_name\":" + jsonString(playerName) + "," +
                    "\"client_type\":" + jsonString(CLIENT_TYPE) + "," +
                    "\"version\":" + jsonString(GatewayRequestHeaders.MOD_VERSION) + "," +
                    "\"context\":" + jsonString(context) + "," +
                    "\"history\":" + historyJson + "," +
                    "\"server_id\":" + jsonString(sid) + "}";

                requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(effectiveUrl + "/v1/chat"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(json));

                GatewayHttpClientHelper.apply(requestBuilder, CLIENT_TYPE, sid);
                requestBuilder.setHeader("Authorization", "Bearer " + effectiveKey);
            }

            HttpRequest request = requestBuilder.build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) return response.body();
            int code = response.statusCode();
            LOGGER.warn("[CraftyAI] Gateway returned HTTP {}: {}", code, response.body());
            if (code == 401 || code == 403) {
                try {
                    String body = response.body();
                    if (body != null) {
                        if (body.contains("suspended") || body.contains("revoked")) {
                            return "__ERROR__:§c§lAccount Suspended. §r§7Create a ticket at §b§ndiscord.gg/zCkE44hsBR§r§7 to appeal.";
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
                } catch (Exception ignored) {}
                return "__ERROR__:API key invalid or expired. Check settings (M key).";
            }
            if (code == 429) {
                String body = response.body();
                if (body != null && body.contains("Daily request limit exceeded")) {
                    return "__ERROR__:\u00a7c\u00a7l[!] Daily Request Limit Reached. \u00a7r\u00a77Create a ticket at \u00a7b\u00a7ndiscord.gg/zCkE44hsBR\u00a7r\u00a77 to upgrade.";
                }
                if (body != null && body.contains("Daily token limit exceeded")) {
                    return "__ERROR__:\u00a7c\u00a7l[!] Daily Token Limit Reached. \u00a7r\u00a77Create a ticket at \u00a7b\u00a7ndiscord.gg/zCkE44hsBR\u00a7r\u00a77 to upgrade.";
                }
                return "__ERROR__:\u00a7e[Rate Limit] Too many requests. Slow down.";
            }
            if (code == 503) return "__ERROR__:AI service temporarily unavailable. Try again shortly.";
            return "__ERROR__:Server error (HTTP " + code + "). Try again later.";
        } catch (java.net.http.HttpTimeoutException e) {
            LOGGER.error("[CraftyAI] Request timed out: {}", e.getMessage());
            return "__ERROR__:Request timed out. Check your internet connection.";
        } catch (java.net.ConnectException e) {
            LOGGER.error("[CraftyAI] Connection refused: {}", e.getMessage());
            return "__ERROR__:Could not connect to CraftyAI servers.";
        } catch (Exception e) {
            LOGGER.error("[CraftyAI] Request failed: {}", e.getMessage());
            return null;
        }
    }

    private static void addToHistory(UUID playerId, String question, String answer) {
        LinkedList<Map<String, String>> history = conversationCache.computeIfAbsent(playerId, k -> new LinkedList<>());
        synchronized (history) {
            history.add(Map.of("role", "user", "content", question));
            history.add(Map.of("role", "assistant", "content", answer));
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

    private static String buildHistoryJson(LinkedList<Map<String, String>> history) {
        StringBuilder sb = new StringBuilder("[");
        synchronized (history) {
            for (int i = 0; i < history.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("{\"role\":").append(jsonString(history.get(i).get("role")))
                  .append(",\"content\":").append(jsonString(history.get(i).get("content"))).append("}");
            }
        }
        return sb.append("]").toString();
    }

    private static void executeAction(String actionString) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return;

        // Agentic tasks are FREE for all tiers.
        // No tier check. Only config + OP/cheats gates remain.

        // Check local configuration
        if (config == null || !config.agentic_tasks_enabled || !config.ai_enable_actions) {
            sendSystemMsg(mc.player, Component.literal("\u00A78[\u00A7bCraftyAI\u00A78] \u00A77Action suggested: \u00A7e" + actionString + " \u00A78(agentic tasks disabled in config)"));
            LOGGER.info("[CraftyAI] Skipped action {} — agentic tasks disabled in config", actionString);
            return;
        }

        // Client-side: actions require cheats/OP — warn if not available
        if (!mc.player.hasPermissions(2)) {
            sendSystemMsg(mc.player, Component.literal("\u00A78[\u00A7bCraftyAI\u00A78] \u00A77Action suggested: \u00A7e" + actionString + " \u00A78(requires OP/cheats)"));
            LOGGER.info("[CraftyAI] Skipped action {} — player lacks OP/cheats", actionString);
            return;
        }

        String playerKey = mc.player.getUUID().toString();
        if (!actionRateLimiter.tryAcquire(playerKey)) {
            long sec = actionRateLimiter.secondsUntilReset(playerKey);
            sendSystemMsg(mc.player, Component.literal("§c§o[Rate limit: try again in " + sec + "s]"));
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
                    sendSystemMsg(mc.player, Component.literal("§c§o⚠ Destructive action: §f" + upper + " §c§o— run §e/craftyclient confirm §c§owithin 30s to execute."));
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
                if (upper.startsWith("SCAN_BLOCKS:")) {
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
                        java.util.List<net.minecraft.world.entity.Entity> entities = mc.level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class, mc.player.getBoundingBox().inflate(radius), e -> true);
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
                            if (!name.equals(selfName) && !playerNames.contains(name)) {
                                playerNames.add(name);
                            }
                        }
                        playerCount = playerNames.size();
                    }
                    String aiName = config.ai_name != null ? config.ai_name : "Crafty";
                    StringBuilder scanMsg = new StringBuilder();
                    scanMsg.append("\u00A7b[").append(aiName).append("] \u00A77> \u00A7f");
                    if (blockCount == 0 && entityCount == 0 && playerCount == 0) {
                        scanMsg.append("It\u00A7cs quiet around here \u2014 nothing notable within \u00A7e").append(radius).append("\u00A7f blocks.");
                    } else {
                        scanMsg.append("Scan results within \u00A7e").append(radius).append("\u00A7f blocks:\n");
                        if (includeBlocks && !blockTypes.isEmpty()) {
                            scanMsg.append("\u00A77  [\u00A7eBlocks\u00A77] \u00A7f");
                            blockTypes.entrySet().stream()
                                .sorted((a, b) -> b.getValue() - a.getValue())
                                .limit(6)
                                .forEach(e -> scanMsg.append("\u00A77").append(e.getKey().replace('_', ' ')).append(" \u00A77x\u00A7e").append(e.getValue()).append("\u00A77, "));
                            if (scanMsg.charAt(scanMsg.length() - 2) == ',') scanMsg.setLength(scanMsg.length() - 2);
                            scanMsg.append("\n");
                        }
                        if (includeEntities && !entityTypes.isEmpty()) {
                            scanMsg.append("\u00A77  [\u00A7cMobs\u00A77] \u00A7f");
                            entityTypes.entrySet().stream()
                                .sorted((a, b) -> b.getValue() - a.getValue())
                                .forEach(e -> scanMsg.append("\u00A7c").append(e.getKey().replace('_', ' ')).append(" \u00A77x\u00A7c").append(e.getValue()).append("\u00A77, "));
                            if (scanMsg.charAt(scanMsg.length() - 2) == ',') scanMsg.setLength(scanMsg.length() - 2);
                            scanMsg.append("\n");
                        }
                        if (!playerNames.isEmpty()) {
                            scanMsg.append("\u00A77  [\u00A7bPlayers\u00A77] \u00A7f");
                            for (int i = 0; i < playerNames.size(); i++) {
                                scanMsg.append("\u00A7b").append(playerNames.get(i));
                                if (i < playerNames.size() - 1) scanMsg.append("\u00A77, ");
                            }
                            scanMsg.append("\n");
                        }
                        if (blockCount > 0 && includeBlocks) {
                            scanMsg.append("\u00A77  [\u00A7aSummary\u00A77] \u00A7f").append(blockCount).append(" blocks");
                            if (entityCount > 0) scanMsg.append(", ").append(entityCount).append(" mobs");
                            if (playerCount > 0) scanMsg.append(", ").append(playerCount).append(" player").append(playerCount > 1 ? "s" : "");
                            String topBlock = blockTypes.entrySet().stream().max(java.util.Map.Entry.comparingByValue()).map(e -> e.getKey().replace('_', ' ')).orElse("air");
                            scanMsg.append(". Mostly \u00A7e").append(topBlock).append("\u00A7f.");
                            boolean hasOre = blockTypes.keySet().stream().anyMatch(k -> k.contains("ore"));
                            if (hasOre) scanMsg.append(" \u00A7aOre deposits found!");
                            scanMsg.append("\n");
                        }
                    }
                    sendSystemMsg(mc.player, Component.literal(scanMsg.toString()));
                    LOGGER.info("[CraftyAI] SCAN_BLOCKS r={} — {} blocks, {} mobs, {} players", radius, blockCount, entityCount, playerCount);

                    // Send scan results back to AI so it can respond with informed decisions
                    StringBuilder scanCtx = new StringBuilder();
                    scanCtx.append("[Block Scan Results]\n");
                    scanCtx.append("Player position: ").append(playerPos.getX()).append(", ").append(playerPos.getY()).append(", ").append(playerPos.getZ()).append("\n");
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
                    // Send scan results back to AI — Forge doesn't have handleClientChatDirect,
                    // so call sendAIRequest directly with scan context prepended to question
                    if (mc.player != null) {
                        UUID scanPid = mc.player.getUUID();
                        LinkedList<Map<String, String>> scanHist = conversationCache.computeIfAbsent(scanPid, k -> new LinkedList<>());
                        String scanHistoryJson = buildHistoryJson(scanHist);
                        String scanQuestion = "Here are the scan results from my previous request. Based on this data, what do you recommend?";
                        String fullQuestion = scanQuestion + "\n\n" + scanContext;
                        String scanPlayerName = mc.player.getName().getString();
                        String bgCtx = buildContext(mc.player);
                        CompletableFuture.supplyAsync(() -> sendAIRequest(fullQuestion, scanPlayerName, bgCtx, scanHistoryJson))
                            .thenAccept(responseBody -> {
                                mc.execute(() -> {
                                    if (mc.player == null) return;
                                    if (responseBody != null && !responseBody.startsWith("__ERROR__:")) {
                                        NeuralResponse scanRes = GSON.fromJson(responseBody, NeuralResponse.class);
                                        String scanAnswer = scanRes != null ? scanRes.getAnswer() : null;
                                        if (scanAnswer != null && !scanAnswer.isEmpty()) {
                                            sendSystemMsg(mc.player, Component.literal("\u00A7b[" + config.ai_name + "] \u00A77> \u00A7f" + scanAnswer));
                                        }
                                    }
                                });
                            });
                    }
                    continue;
                }
                // Handle DELAYED_ACTION:<seconds>:<innerAction>
                if (upper.startsWith("DELAYED_ACTION:")) {
                    String[] parts = action.split(":", 3);
                    if (parts.length >= 3) {
                        int delaySec = Math.max(1, Math.min(300, Integer.parseInt(parts[1])));
                        String innerAction = parts[2];
                        final String fa = innerAction;
                        sendSystemMsg(mc.player, Component.literal("\u00A7e\u23F3 Action queued: " + innerAction + " in " + delaySec + "s"));
                        Thread delayedThread = new Thread(() -> {
                            try { Thread.sleep(delaySec * 1000L); } catch (InterruptedException e) { return; }
                            Minecraft.getInstance().execute(() -> {
                                if (Minecraft.getInstance().player != null && Minecraft.getInstance().player.isAlive()) {
                                    executeAction(fa);
                                }
                            });
                        }, "CraftyAI-DelayedAction");
                        delayedThread.setDaemon(true);
                        delayedThread.start();
                    }
                    continue;
                }
                // Handle SCHEDULE_TASK
                if (upper.startsWith("SCHEDULE_TASK:")) {
                    String[] schedParts = action.split(":", 4);
                    if (schedParts.length < 4) {
                        sendSystemMsg(mc.player, Component.literal("\u00A7c[CraftyAI] Invalid schedule format."));
                        continue;
                    }
                    String cronExpr = schedParts[1].trim();
                    String actionType = schedParts[2].trim().toLowerCase();
                    String message = schedParts[3].trim();
                    String taskName = message.length() > 40 ? message.substring(0, 40) + "..." : message;
                    final String fCron = cronExpr;
                    final String fType = actionType;
                    final String fName = taskName;
                    final String fMsg = message;
                    sendSystemMsg(mc.player, Component.literal("\u00A7e\u23F0 Scheduling task..."));
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
                            HttpRequest req = HttpRequest.newBuilder()
                                .uri(java.net.URI.create(targetUrl))
                                .header("Content-Type", "application/json")
                                .header("Authorization", "Bearer " + apiKeySnap)
                                .header("X-Server-ID", serverIdSnap)
                                .header("User-Agent", "CraftyAI-Minecraft/" + com.demonz.craftyai.common.GatewayRequestHeaders.MOD_VERSION)
                                .header("X-Client-Type", "minecraft-java")
                                .header("X-CraftyAI-Version", com.demonz.craftyai.common.GatewayRequestHeaders.MOD_VERSION)
                                .POST(HttpRequest.BodyPublishers.ofString(json))
                                .build();
                            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                            String respBody = resp.body();
                            if (resp.statusCode() == 200) {
                                Minecraft.getInstance().execute(() -> sendSystemMsg(null, Component.literal("\u00A7a\u2714 Task scheduled: " + fName + " (" + fCron + ")")));
                            } else {
                                String errMsg = respBody.contains("error") ? respBody.substring(respBody.indexOf("\"error\""), Math.min(respBody.indexOf("\"error\"") + 80, respBody.length())) : "HTTP " + resp.statusCode();
                                String finalErrMsg = errMsg;
                                Minecraft.getInstance().execute(() -> sendSystemMsg(null, Component.literal("\u00A7c\u2716 Schedule failed: " + finalErrMsg)));
                            }
                        } catch (Exception e) {
                            String msg = e.getMessage();
                            Minecraft.getInstance().execute(() -> sendSystemMsg(null, Component.literal("\u00A7c\u2716 Schedule error: " + msg)));
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
                        sendSystemMsg(mc.player, Component.literal("\u00A7c[CraftyAI] Blocked unsafe AI command. Only /locate is allowed."));
                        continue;
                    }
                    final String finalCmd = cmd;
                    sendSystemMsg(mc.player, Component.literal("\u00A77[CraftyAI] Running: /" + finalCmd));
                    mc.player.connection.sendCommand(finalCmd);
                    continue;
                }
                if (action.toUpperCase().startsWith("GIVE:")) {
                    String[] parts = action.split(":", 3);
                    if (parts.length >= 2) {
                        String item = parts[1].toLowerCase().trim();
                        if (GIVE_BLACKLIST.contains(item)) {
                            sendSystemMsg(mc.player, Component.literal("§cCannot give blacklisted item."));
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
            sendSystemMsg(null, Component.literal("\u00A73[CraftyAI] " + (feedback != null ? feedback : "Executing action...")));
            Minecraft.getInstance().getConnection().sendUnsignedCommand(command);
            LOGGER.info("[CraftyAI] Executed action: {} -> /{}", action, command);
        } else {
            LOGGER.warn("[CraftyAI] Unknown action trigger: {}", action);
        }
        } // End for loop
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

    /**
     * MOD-bus event subscriber — handles lifecycle events like key registration.
     * RegisterKeyMappingsEvent fires on the MOD bus, not the FORGE bus.
     */
    /**
     * Client-side action inference: when the LLM truncates the action field,
     * infer the action from the player's question and the AI's text response.
     */
    private static String inferActionFromText(String answer, String question) {
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

    public static void performAutoMintKey(java.util.function.Consumer<String> callback) {
        CompletableFuture.runAsync(() -> {
            try {
                String sid = com.demonz.craftyai.common.SessionManager.getSessionId(net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get());
                String gatewayUrl = com.demonz.craftyai.common.GatewayRequestHeaders.getGatewayUrl();
                if (config != null && config.custom_provider_enabled && config.custom_provider_url != null && !config.custom_provider_url.isEmpty()) {
                    gatewayUrl = config.custom_provider_url;
                }
                String playerName = net.minecraft.client.Minecraft.getInstance().getUser() != null ? net.minecraft.client.Minecraft.getInstance().getUser().getName() : "ForgePlayer";
                Map<String, String> payload = new HashMap<>();
                payload.put("name", playerName);
                payload.put("version", com.demonz.craftyai.common.GatewayRequestHeaders.MOD_VERSION);
                payload.put("client_type", CLIENT_TYPE);
                payload.put("server_id", sid);

                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(URI.create(gatewayUrl + "/v1/handshake-no-key"))
                        .header("Content-Type", "application/json")
                        .timeout(java.time.Duration.ofSeconds(10))
                        .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)));

                com.demonz.craftyai.common.GatewayHttpClientHelper.apply(builder, CLIENT_TYPE, sid);

                HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                LOGGER.info("[CraftyAI] Auto-mint response: {} -> {}", response.statusCode(), response.body());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    com.google.gson.JsonObject json = com.demonz.craftyai.common.JsonParserAdapter.parse(response.body()).getAsJsonObject();
                    String newKey = json.has("api_key") && !json.get("api_key").isJsonNull() ? json.get("api_key").getAsString() : "";
                    String newServerId = json.has("server_id") && !json.get("server_id").isJsonNull() ? json.get("server_id").getAsString() : "";
                    if (!newKey.isEmpty() && newKey.startsWith("cai_")) {
                        if (config != null) {
                            config.api_key = newKey;
                            if (newServerId != null && !newServerId.isEmpty()) {
                                config.server_id = newServerId;
                            }
                            ConfigLoader.saveConfig("config", "craftyai.json", config, LOGGER::info);
                        }
                        if (callback != null) callback.accept(newKey);
                        return;
                    }
                }
            } catch (Throwable t) {
                LOGGER.warn("[CraftyAI] Auto-mint key failed: {}", t.toString());
            }
            if (callback != null) callback.accept(null);
        });
    }

    @Mod.EventBusSubscriber(modid = CraftyAIForgeMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static class ModBusEvents {
        private static boolean initialized = false;

        @SubscribeEvent
        public static void onClientSetup(net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent event) {
            if (initialized) return;
            initialized = true;
            config = ConfigLoader.loadOrCreateConfig("config", "craftyai.json", LOGGER::info);
            performStartupHandshake();
            try {
                net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory factory =
                    new net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory((mc, parent) -> new CraftyAIForgeSettingsScreen(parent));
                net.minecraftforge.fml.ModLoadingContext.get().registerExtensionPoint(
                    net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory.class,
                    () -> factory
                );
            } catch (Throwable ignored) {}
        }

        @SubscribeEvent
        public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
            settingsKey = new KeyMapping("key.craftyai.settings", GLFW.GLFW_KEY_M, "category.craftyai");
            event.register(settingsKey);
            visionScanKey = new KeyMapping("key.craftyai.vision_scan", GLFW.GLFW_KEY_V, "category.craftyai");
            event.register(visionScanKey);
        }
    }
}
