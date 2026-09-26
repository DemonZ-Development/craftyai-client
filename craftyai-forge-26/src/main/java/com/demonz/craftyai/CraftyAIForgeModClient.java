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
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientChatEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.demonz.craftyai.common.ActionHarness;

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
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@EventBusSubscriber(modid = CraftyAIForgeMod.MOD_ID, value = Dist.CLIENT)
public class CraftyAIForgeModClient {

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
    private static final ScheduledThreadPoolExecutor TIMEOUTS = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "craftyai-forge-timeouts");
        thread.setDaemon(true);
        return thread;
    });
    static { TIMEOUTS.setRemoveOnCancelPolicy(true); }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        checkActionWorld(Minecraft.getInstance());
        Minecraft mc = Minecraft.getInstance();
        if (settingsKey != null && settingsKey.consumeClick()) {
            boolean isScreenNull = com.demonz.craftyai.common.ModernScreenAccess.current(mc) == null;
                if (isScreenNull) {
                safeSetScreen(mc, new CraftyAIForgeSettingsScreen(null));
            }
        }
        if (!shownWelcome && mc.player != null && mc.level != null) {
            shownWelcome = true;
            if (config != null && config.op_welcome_message) {
                sendClientMessage(mc.player, Component.literal(""));
                sendClientMessage(mc.player, Component.literal("\u00A7b\u00A7l\u2726 CraftyAI \u00A77v" + GatewayRequestHeaders.MOD_VERSION + " \u00A78\u2014 \u00A77AI Companion"));
                String prefixVal = config.prefix != null ? config.prefix : "@";
                sendClientMessage(mc.player, Component.literal("\u00A77  Type \u00A7e" + prefixVal + config.ai_name + " <question>\u00A77 in chat to talk to your AI."));
                sendClientMessage(mc.player, Component.literal("\u00A77  Press \u00A7eM\u00A77 to open settings. Press \u00A7eV\u00A77 to vision-scan. Use \u00A7e/crafty status\u00A77 to check status."));
                boolean hasApiKey = (config.api_key != null && !config.api_key.isEmpty() && !config.api_key.equals("YOUR_API_KEY_HERE"));
                boolean hasCustomProvider = (config.custom_provider_enabled && config.custom_provider_url != null && !config.custom_provider_url.isEmpty());
                if (!hasApiKey && !hasCustomProvider) {
                    sendClientMessage(mc.player, Component.literal("\u00A7c  \u26A0 No API key or custom provider set! Press M or use /crafty apikey <key>"));
                }
                sendClientMessage(mc.player, Component.literal("\u00A78  Hide this: set op_welcome_message to false in config/craftyai.json"));
                sendClientMessage(mc.player, Component.literal(""));
            }
        }

        if (visionScanKey != null && visionScanKey.consumeClick() && mc.player != null) {
            long now = System.currentTimeMillis();
            if (now - lastVisionScanTime < VISION_SCAN_COOLDOWN_MS) {
                sendClientMessage(mc.player, Component.literal("\u00A7c[CraftyAI] Please wait before scanning again."));
                return;
            }
            lastVisionScanTime = now;
            VisionScanner.ScanResult scanResult = VisionScanner.scan(mc.player, mc.player.level());

            String visionPrompt = com.demonz.craftyai.common.ScanFlow.scanCommandPrompt(config.ai_name != null ? config.ai_name : "Crafty")
                    + "\n\n" + com.demonz.craftyai.common.ScanFlow.buildAiContext(scanResult);
            sendClientMessage(mc.player, Component.literal("\u00A7b\u00A7l[CraftyAI] \u00A7fScanning... analyzing what you're looking at."));
            handleClientChat(null, visionPrompt);
        }
    }

    private static boolean shownWelcome = false;

    @SubscribeEvent
    public static void onClientLogout(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        shownWelcome = false;
        conversationCache.clear();
        LOGGER.info("[CraftyAI] Cleared client conversation cache and welcome flag on disconnect.");
    }

    @SubscribeEvent
    public static void onClientChat(ClientChatEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.getCurrentServer() != null && !mc.isLocalServer()) return;
        if (event.getMessage() == null || event.getMessage().trim().isEmpty()) return;
        String msg = event.getMessage().trim();
        String lower = msg.toLowerCase();

        if (config == null) {
            config = ConfigLoader.loadOrCreateConfig("config", "craftyai.json", LOGGER::info);
        }

        String prefixVal = config != null && config.prefix != null ? config.prefix : "@";
        boolean requirePrefix = config != null && config.require_prefix;
        List<String> aliasesList = config != null && config.aliases != null ? Arrays.asList(config.aliases) : Arrays.asList("crafty", "craftyai", "ai", "helper");

        for (String alias : aliasesList) {
            String prefixed = prefixVal.toLowerCase() + alias;
            if (lower.startsWith(prefixed + " ")) {
                event.setCanceled(true);
                if (mc != null && mc.player != null) {
                    sendClientMessage(mc.player, Component.literal("<" + mc.player.getName().getString() + "> " + msg));
                }
                String question = msg.substring(prefixed.length()).trim();
                if (!question.isEmpty()) {
                    handleClientChat(null, question);
                }
                return;
            }
            if (!requirePrefix && lower.startsWith(alias + " ")) {
                event.setCanceled(true);
                if (mc != null && mc.player != null) {
                    sendClientMessage(mc.player, Component.literal("<" + mc.player.getName().getString() + "> " + msg));
                }
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
            Commands.literal("craftyclient")
                .executes(ctx -> {
                    ctx.getSource().sendSystemMessage(Component.literal("\u00A7b\u00A7lCraftyAI \u00A77v" + GatewayRequestHeaders.MOD_VERSION + " \u00A78- \u00A77DemonZ Development"));
                    ctx.getSource().sendSystemMessage(Component.literal("\u00A77Platform: \u00A7fForge Client"));
                    ctx.getSource().sendSystemMessage(Component.literal("\u00A77Usage: \u00A7f/craftyclient <status|apikey|ask|settings>"));
                    return 1;
                })
                .then(Commands.literal("status")
                    .executes(ctx -> {
                        ctx.getSource().sendSystemMessage(Component.literal("\u00A7b[CraftyAI] \u00A77Client Status:"));
                        ctx.getSource().sendSystemMessage(Component.literal("\u00A77  API Key: \u00A7f" + (config.api_key != null && config.api_key.startsWith("cai_") ? "configured" : "not set")));
                        String sid = com.demonz.craftyai.common.SessionManager.getSessionId(net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get());
                        ctx.getSource().sendSystemMessage(Component.literal("\u00A77  Session ID: \u00A7f" + sid));
                        ctx.getSource().sendSystemMessage(Component.literal("\u00A77  Press \u00A7eM \u00A77to open settings."));
                        return 1;
                    })
                )
                .then(Commands.literal("apikey")
                    .then(Commands.argument("key", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String key = StringArgumentType.getString(ctx, "key").trim();
                            if (!key.matches("^cai_[a-zA-Z0-9_-]{16,128}$")) {
                                ctx.getSource().sendSystemMessage(Component.literal("\u00A7cInvalid API key format. Keys start with cai_."));
                                return 0;
                            }
                            config.api_key = key;
                            saveConfig();
                            ctx.getSource().sendSystemMessage(Component.literal("\u00A7a[CraftyAI] API key saved."));
                            return 1;
                        })
                    )
                )
                .then(Commands.literal("settings")
                    .executes(ctx -> {
                        Minecraft.getInstance().execute(() ->
                            com.demonz.craftyai.common.ModernScreenAccess.set(Minecraft.getInstance(), new CraftyAIForgeSettingsScreen(null))
                        );
                        return 1;
                    })
                )
                .then(Commands.literal("ask")
                    .then(Commands.argument("question", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String question = StringArgumentType.getString(ctx, "question");
                            handleClientChat(ctx.getSource(), question);
                            return 1;
                        })
                    )
                )
                .then(Commands.literal("cancel")
                    .executes(ctx -> {
                        chatGeneration++;
                        cancelActiveRun();
                        var client = Minecraft.getInstance();
                        if (client.player != null) {
                            actionConfirmations.cancel(client.player.getUUID().toString());
                            sendClientMessage(client.player, Component.literal("[CraftyAI] Task cancelled. No further actions will run."));
                        }
                        return 1;
                    })
                )
                .then(Commands.literal("confirm")
                    .executes(ctx -> {
                        Minecraft client = Minecraft.getInstance();
                        if (client.player == null) return 0;
                        String playerKey = client.player.getUUID().toString();
                        String pending = actionConfirmations.confirm(playerKey);
                        if (pending == null) {
                            ctx.getSource().sendSystemMessage(Component.literal("\u00A77[CraftyAI] No destructive action pending."));
                        } else {
                            resumeConfirmedAction(pending);
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
        if (config == null || config.force_local_mode || !config.telemetry_enabled) return;
        String sid = com.demonz.craftyai.common.SessionManager.getSessionId(
                net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get());

        CompletableFuture.runAsync(() -> {
            try {

                String handshakeUrl;
                if (config.custom_provider_enabled && config.custom_provider_url != null && !config.custom_provider_url.isEmpty()) {
                    handshakeUrl = config.custom_provider_url;
                } else {
                    handshakeUrl = GatewayRequestHeaders.getGatewayUrl();
                }

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
                                        sendClientMessage(net.minecraft.client.Minecraft.getInstance().player,
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

    private static void handleClientChat(Object source, String question) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) return;
        boolean hasApiKey = (config != null && config.api_key != null && !config.api_key.isEmpty() && !config.api_key.equals("YOUR_API_KEY_HERE"));
        boolean hasCustomProvider = (config != null && config.custom_provider_enabled && config.custom_provider_url != null && !config.custom_provider_url.isEmpty());
        if (config == null || config.force_local_mode || (!hasApiKey && !hasCustomProvider)) {
            com.demonz.craftyai.common.LocalBrain localBrain = new com.demonz.craftyai.common.LocalBrain(
                net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().toString(),
                msg -> LOGGER.info(msg)
            );
            String playerName = mc.getUser() != null ? mc.getUser().getName() : "Player";
            String localAnswer = localBrain.generateOfflineResponse(question, playerName);
            String aiName = config != null && config.ai_name != null ? config.ai_name : "Crafty";
            if (localAnswer != null) {
                String prefix = (config != null && config.force_local_mode) ? "\u00A7e[FORCED-LOCAL]" : "\u00A7e[OFFLINE]";
                sendClientMessage(mc.player, Component.literal("\u00A7b[" + aiName + "] " + prefix + " \u00A77> \u00A7f" + localAnswer));
                if (config == null || !config.force_local_mode) {
                    sendClientMessage(mc.player, Component.literal("\u00A78\u00A7oTip: Set your API key with /craftyclient apikey <key> to get online responses."));
                }
            } else {
                sendClientMessage(mc.player, Component.literal("\u00A7c[CraftyAI] API key not set or local brain failed. Use /craftyclient apikey <key> or press M."));
            }
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastChatTime < config.getCooldownMs()) {
            sendClientMessage(mc.player, Component.literal("\u00A7c[CraftyAI] Please wait before asking again."));
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

        String sid = com.demonz.craftyai.common.SessionManager.getSessionId(net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get());

        sendClientMessage(Minecraft.getInstance().player, Component.literal("\u00A7b[" + config.ai_name + "] \u00A77Thinking..."));

        UUID playerId = Minecraft.getInstance().player.getUUID();
        LinkedList<Map<String, String>> history = conversationCache.computeIfAbsent(playerId, k -> new LinkedList<>());
        String historyJson = buildHistoryJson(history);

        String context = buildContext(Minecraft.getInstance().player);

        CompletableFuture.supplyAsync(() -> sendAIRequest(question, requestPlayerName, context, historyJson))
            .thenAccept(responseBody -> {
                Minecraft.getInstance().execute(() -> {
                    if (requestGeneration != chatGeneration || requestClient.player != requestPlayer || requestClient.level != requestWorld) return;
                    if (responseBody != null && responseBody.startsWith("__ERROR__:")) {
                        String errorMsg = responseBody.substring("__ERROR__:".length());
                        sendClientMessage(Minecraft.getInstance().player, Component.literal("\u00A7c[CraftyAI] " + errorMsg));
                    } else if (responseBody != null) {

                        NeuralResponse res;
                        try { res = GSON.fromJson(responseBody, NeuralResponse.class); }
                        catch (Exception invalid) { sendClientMessage(requestPlayer, Component.literal("[CraftyAI] Invalid AI response. No action was run.")); return; }
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
                            runActionHarness(action, question, answer, context, historyJson, false);
                        } else if (answer != null && !answer.isEmpty()) {
                            addToHistory(playerId, question, answer);
                            sendClientMessage(Minecraft.getInstance().player, Component.literal("\u00A7b[" + config.ai_name + "] \u00A77> \u00A7f" + answer));
                        } else {
                            sendClientMessage(Minecraft.getInstance().player, Component.literal("\u00A7c[CraftyAI] Got an empty response."));
                        }
                    } else {
                        com.demonz.craftyai.common.LocalBrain localBrain = new com.demonz.craftyai.common.LocalBrain(
                            net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().toString(),
                            msg -> LOGGER.info(msg)
                        );
                        String playerName = Minecraft.getInstance().getUser().getName();
                        String localAnswer = localBrain.generateOfflineResponse(question, playerName);
                        if (localAnswer != null) {
                            sendClientMessage(Minecraft.getInstance().player, Component.literal("\u00A7b[" + config.ai_name + "] \u00A7e[OFFLINE] \u00A77> \u00A7f" + localAnswer));
                            localBrain.enqueueRequest(question, playerName, "Offline from Forge Client");
                        } else {
                            sendClientMessage(Minecraft.getInstance().player, Component.literal("\u00A7c[CraftyAI] Could not reach the AI. Connection is offline."));
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

            boolean isSingleplayer = mc.isLocalServer();
            ctx.append("World Type: ").append(isSingleplayer ? "singleplayer" : "multiplayer").append("\n");

            boolean cheatsEnabled = hasOp;
            ctx.append("Cheats: ").append(cheatsEnabled ? "enabled" : "disabled").append("\n");

            ctx.append("Can Fly: ").append(player.getAbilities().mayfly ? "yes" : "no").append("\n");

            if (player.level() != null) {
                ctx.append("Difficulty: ").append(player.level().getDifficulty().name().toLowerCase()).append("\n");
            }
            ctx.append("\n");

            ctx.append("[ENVIRONMENT]\n");
            if (player.level() != null) {
                ctx.append("Dimension: ").append(player.level().dimension().identifier().getPath()).append("\n");
                long time = player.level().getDefaultClockTime() % 24000;
                String timeOfDay = time < 6000 ? "Morning" : time < 12000 ? "Day" : time < 18000 ? "Evening" : "Night";
                ctx.append("Time: ").append(timeOfDay).append("\n");

                boolean raining = player.level().isRaining();
                boolean thundering = player.level().isThundering();
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
                    if (effectName.contains(".")) {
                        effectName = effectName.substring(effectName.lastIndexOf('.') + 1);
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
            if (cfg.force_local_mode) return null;
            String sid = com.demonz.craftyai.common.SessionManager.getSessionId(net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get());

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
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8));

                GatewayHttpClientHelper.applyCustomProvider(requestBuilder, effectiveUrl, effectiveKey, CLIENT_TYPE, sid);
            } else {

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
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));

                GatewayHttpClientHelper.apply(requestBuilder, CLIENT_TYPE, sid);
                requestBuilder.setHeader("Authorization", "Bearer " + effectiveKey);
            }

            HttpRequest request = requestBuilder.build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 200 && response.statusCode() < 300) return response.body();
            int code = response.statusCode();
            LOGGER.warn("[CraftyAI] Gateway returned HTTP {}", code);
            if (code == 401 || code == 403) {
                try {
                    String body = response.body();
                    if (body != null) {
                        if (body.contains("suspended") || body.contains("revoked")) {
                            return "__ERROR__:\u00A7c\u00A7lAccount Suspended. \u00A7r\u00A77Create a ticket at \u00A7b\u00A7ndiscord.gg/zCkE44hsBR\u00A7r\u00A77 to appeal.";
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

    private static volatile RunScope activeRun;
    private static long chatGeneration;

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

    private static void cancelActiveRun() {
        RunScope previous = activeRun;
        activeRun = null;
        if (previous != null) {
            previous.valid = false;
            previous.harness.cancel();
            if (previous.waiting != null) previous.waiting.complete(new ActionHarness.Feedback(previous.waitingAction,
                    ActionHarness.Status.CANCELLED, "Task cancelled."));
        }
    }

    private static void checkActionWorld(Minecraft mc) {
        RunScope run = activeRun;
        if (run != null && (!run.inWorld(mc) || config == null || config.force_local_mode || !config.ai_enable_actions || !config.agentic_tasks_enabled)) { chatGeneration++; cancelActiveRun(); }
    }

    private static void resumeConfirmedAction(String action) {
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

    public static void runActionHarness(String initialAction, String question, String initialAnswer, String context, String historyJson, boolean confirmedByUser) {
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
                    sendClientMessage(mc.player, Component.literal("\u00A7b[" + config.ai_name + "] \u00A77> \u00A7f" + answer));
                }
            }));
        });
    }

    private static CompletableFuture<ActionHarness.Feedback> executeSingleActionAsync(String action, RunScope run, boolean confirmed) {
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

    private static CompletableFuture<ActionHarness.Feedback> executeActionOnClient(String action, RunScope run, boolean confirmed) {
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
            sendClientMessage(mc.player, Component.literal("\u00A7e[CraftyAI] Confirm " + AgenticActions.description(action)
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
            sendClientMessage(mc.player, Component.literal("[CraftyAI] Waiting " + seconds + " seconds before the next action."));
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

    private static String performLocalBlockScan(String action) {
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
            java.util.List<net.minecraft.world.entity.Entity> entities = mc.level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class, mc.player.getBoundingBox().inflate(radius), e -> true);
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

    private static CompletableFuture<ActionHarness.Feedback> executeScheduleTaskAsync(String fName, String fCron, String fType, String fMsg, String action) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            sendClientMessage(mc.player, Component.literal("\u00A7e\u23F0 Scheduling task..."));
        }
        final String apiKeySnap = config != null ? config.api_key : null;
        final String serverIdSnap = config != null ? config.server_id : null;
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

    private static void executeAction(String actionString) {
        runActionHarness(actionString, "Action", null, buildContext(Minecraft.getInstance().player), "[]", false);
    }

    private static void executeAction(String actionString, String originalQuestion) {
        runActionHarness(actionString, originalQuestion != null ? originalQuestion : "Action", null, buildContext(Minecraft.getInstance().player), "[]", false);
    }

    private static void executeAction(String actionString, String originalQuestion, boolean confirmedByUser) {
        runActionHarness(actionString, originalQuestion != null ? originalQuestion : "Action", null, buildContext(Minecraft.getInstance().player), "[]", confirmedByUser);
    }

    private static String jsonString(String value) {
        if (value == null) return "\"\"";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\"";
    }

    private static String normalizeLocateCommand(String cmd) {
        return AgenticActions.normalizeLocateCommand(cmd);
    }

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
                String sid = com.demonz.craftyai.common.SessionManager.getSessionId(net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get());
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
                LOGGER.info("[CraftyAI] Auto-mint response: HTTP {}", response.statusCode());
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

    @EventBusSubscriber(modid = CraftyAIForgeMod.MOD_ID, value = Dist.CLIENT)
    public static class ModBusEvents {
        private static boolean initialized = false;

        @SubscribeEvent
        public static void onClientSetup(net.neoforged.fml.event.lifecycle.FMLClientSetupEvent event) {
            if (initialized) return;
            initialized = true;
            config = ConfigLoader.loadOrCreateConfig("config", "craftyai.json", LOGGER::info);
            performStartupHandshake();
            try {
                net.neoforged.fml.ModLoadingContext.get().registerExtensionPoint(
                    net.neoforged.neoforge.client.gui.IConfigScreenFactory.class,
                    () -> (container, parent) -> new CraftyAIForgeSettingsScreen(parent)
                );
            } catch (Throwable t) {
                LOGGER.warn("[CraftyAI] Could not register IConfigScreenFactory: {}", t.toString());
            }
        }

        @SubscribeEvent
        public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
            settingsKey = new KeyMapping("key.craftyai.settings", GLFW.GLFW_KEY_M, KeyMapping.Category.MISC);
            event.register(settingsKey);
            visionScanKey = new KeyMapping("key.craftyai.vision_scan", GLFW.GLFW_KEY_V, KeyMapping.Category.MISC);
            event.register(visionScanKey);
        }
    }

    public static void safeSetScreen(Object client, Object screen) {
        if (client != null) com.demonz.craftyai.common.ModernScreenAccess.set(client, screen);
    }

    public static void sendClientMessage(net.minecraft.client.player.LocalPlayer player, Component message) {
        if (player != null) player.sendSystemMessage(message);
    }
}
