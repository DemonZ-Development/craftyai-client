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
import com.demonz.craftyai.common.GatewayRequestHeaders;
import com.demonz.craftyai.common.SessionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * CraftyAI Settings Screen for Forge 1.20+ (Mojang mappings).
 * Compact layout designed to fit at GUI scale 2 on 1080p.
 * v1.1.1-beta: refresh button, decorative emoji, key validation, tier badge.
 */
public class CraftyAIForgeSettingsScreen extends Screen {
    private static final String CLIENT_TYPE = "minecraft-forge-client";
    private static final int W = 280;
    private static final int FH = 20;
    private static final int GAP = 2;
    private static final int LBL = 10;
    private static final int PAD = 10;

    private final Screen parent;
    private EditBox apiKeyField;
    private EditBox aiNameField;
    private EditBox providerUrlField;
    private EditBox providerKeyField;
    private volatile String status = "";
    private volatile int statusColor = 0x55FFFF;
    private int contentH;
    private String lastKeyTestResult = null;
    private int lastKeyTestColor = 0xAAAAAA;

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("craftyai-client");

    public CraftyAIForgeSettingsScreen(Screen parent) {
        super(Component.literal("CraftyAI Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        CraftyAIConfig cfg = CraftyAIForgeModClient.getConfig();
        contentH = calcHeight(cfg);
        int left = (this.width - W) / 2;
        int y = Math.max(4, (this.height - contentH) / 2);

        // Title with tier and Auto-Mint button
        int accent = tierColor(cfg.tier);
        addRenderableWidget(new StringWidget(left, y + 4, W - 110, 10,
                Component.literal("\u00A7b\u2726 CraftyAI \u00A78\u2014 \u00A7r" + cap(cfg.tier))
                        .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(accent))), this.font));

        Button autoMintBtn = Button.builder(Component.literal("\u26A1 Auto-Mint Key"), btn -> {
            btn.setMessage(Component.literal("Minting..."));
            btn.active = false;
            CraftyAIForgeModClient.performAutoMintKey(mintedKey -> {
                if (mintedKey != null) {
                    if (apiKeyField != null) {
                        apiKeyField.setValue(mintedKey);
                    }
                    btn.setMessage(Component.literal("\u2705 Key Minted!"));
                } else {
                    btn.setMessage(Component.literal("\u274C Mint Failed"));
                    btn.active = true;
                }
            });
        }).bounds(left + W - 110, y, 110, 16).build();
        addRenderableWidget(autoMintBtn);
        y += 18;

        // API Key (masked when unfocused)
        apiKeyField = new EditBox(this.font, left, y + LBL, W, FH, Component.literal("API Key")) {
            private String real = cfg.api_key == null ? "" : cfg.api_key;
            {
                this.setResponder(text -> {
                    if (this.isFocused()) {
                        this.real = text;
                        lastKeyTestResult = null;
                    }
                });
            }
            @Override public void setValue(String t) { real = t; super.setValue(isFocused() ? t : mask(t)); }
            @Override public String getValue() { return real; }
            @Override public void setFocused(boolean f) { super.setFocused(f); super.setValue(f ? real : mask(real)); }
            private String mask(String k) {
                if (k == null || k.isEmpty()) return "";
                if (k.length() <= 8) return "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022";
                return k.substring(0, 4) + "\u2022\u2022\u2022\u2022" + k.substring(k.length() - 4);
            }
        };
        apiKeyField.setMaxLength(256);
        apiKeyField.setHint(Component.literal("cai_xxxxxxxx..."));
        apiKeyField.setValue(cfg.api_key == null ? "" : cfg.api_key);
        addRenderableWidget(apiKeyField);
        y += LBL + FH + GAP;

        // AI Name
        aiNameField = new EditBox(this.font, left, y + LBL, W, FH, Component.literal("AI Name"));
        aiNameField.setMaxLength(256);
        aiNameField.setHint(Component.literal("Assistant name"));
        aiNameField.setValue(cfg.ai_name == null ? "Crafty" : cfg.ai_name);
        addRenderableWidget(aiNameField);
        y += LBL + FH + GAP;

        // Mode toggle
        addRenderableWidget(Button.builder(modeText(cfg), b -> {
            cfg.force_local_mode = !cfg.force_local_mode;
            b.setMessage(modeText(cfg));
        }).bounds(left, y, W, FH).build());
        y += FH + GAP;

        // Custom Provider toggle
        addRenderableWidget(Button.builder(cpText(cfg), b -> {
            cfg.custom_provider_enabled = !cfg.custom_provider_enabled;
            preserveAndRebuild();
        }).bounds(left, y, W, FH).build());
        y += FH + GAP;

        // Provider URL & Key (only when enabled)
        if (cfg.custom_provider_enabled) {
            providerUrlField = new EditBox(this.font, left, y + LBL, W, FH, Component.literal("URL"));
            providerUrlField.setMaxLength(256);
            providerUrlField.setHint(Component.literal("https://api.example.com"));
            providerUrlField.setValue(cfg.custom_provider_url == null ? "" : cfg.custom_provider_url);
            addRenderableWidget(providerUrlField);
            y += LBL + FH + GAP;

            providerKeyField = new EditBox(this.font, left, y + LBL, W, FH, Component.literal("Key"));
            providerKeyField.setMaxLength(256);
            providerKeyField.setHint(Component.literal("Provider API key"));
            providerKeyField.setValue(cfg.custom_provider_key == null ? "" : cfg.custom_provider_key);
            addRenderableWidget(providerKeyField);
            y += LBL + FH + GAP;
        }

        // Agentic Tasks (free for all tiers — only config toggle)
        addRenderableWidget(Button.builder(agenticText(cfg), b -> {
            cfg.agentic_tasks_enabled = !cfg.agentic_tasks_enabled;
            b.setMessage(agenticText(cfg));
        }).bounds(left, y, W, FH).build());
        y += FH + GAP;

        // Session ID + Copy + Refresh
        int copyW = 50;
        int refreshW = 56;
        addRenderableWidget(Button.builder(Component.literal("\uD83D\uDCCB Copy"), b -> {
            Minecraft.getInstance().keyboardHandler.setClipboard(sessionId());
            status = "\u00A7a\u2705 Session ID copied!";
            statusColor = 0x55FF55;
        }).bounds(left + W - copyW, y, copyW, FH).build());
        addRenderableWidget(Button.builder(Component.literal("\uD83D\uDD04 Reload"), b -> {
            CraftyAIForgeModClient.reloadConfig();
            lastKeyTestResult = null;
            status = "\u00A7a\u2705 Reloaded from disk";
            statusColor = 0x55FF55;
            this.clearWidgets();
            this.init();
        }).bounds(left + W - copyW - refreshW - GAP, y, refreshW, FH).build());
        y += FH + GAP;

        // Save / Done
        int half = (W - GAP) / 2;
        addRenderableWidget(Button.builder(Component.literal("\u00A7a\u00A7l\uD83D\uDCBE Save"), b -> save()).bounds(left, y, half, FH).build());
        addRenderableWidget(Button.builder(Component.literal("\u00A7f\u2715 Done"), b -> onClose()).bounds(left + half + GAP, y, half, FH).build());
        y += FH + GAP;

        // Test Connection
        if (cfg.custom_provider_enabled) {
            addRenderableWidget(Button.builder(Component.literal("\uD83D\uDD0C Test Provider"), b -> testProvider()).bounds(left, y, W, FH).build());
        } else {
            addRenderableWidget(Button.builder(Component.literal("\uD83D\uDD17 Test Connection \u00A77(\u00A7fkey check\u00A77)"), b -> testGateway()).bounds(left, y, W, FH).build());
        }
    }

    // --- Layout ---

    private int calcHeight(CraftyAIConfig cfg) {
        int h = 16 + (LBL + FH + GAP) * 2 + (FH + GAP) * 5 + FH + 2;
        if (cfg.custom_provider_enabled) h += (LBL + FH + GAP) * 2;
        return h;
    }

    private void preserveAndRebuild() {
        CraftyAIConfig cfg = CraftyAIForgeModClient.getConfig();
        if (apiKeyField != null) cfg.api_key = apiKeyField.getValue().trim();
        if (aiNameField != null) cfg.ai_name = aiNameField.getValue().trim();
        if (providerUrlField != null) cfg.custom_provider_url = providerUrlField.getValue().trim();
        if (providerKeyField != null) cfg.custom_provider_key = providerKeyField.getValue().trim();
        this.clearWidgets();
        this.init();
    }

    // --- Text helpers ---

    private static boolean isProTier(CraftyAIConfig c) {
        return "pro".equalsIgnoreCase(c.tier) || "enterprise".equalsIgnoreCase(c.tier);
    }

    private static boolean isEnterpriseTier(CraftyAIConfig c) {
        return "enterprise".equalsIgnoreCase(c.tier);
    }

    private static Component modeText(CraftyAIConfig c) {
        return Component.literal((c.force_local_mode ? "\uD83C\uDF24" : "\u2601\uFE0F") + " Mode: " + (c.force_local_mode ? "\u00A7eLocal Only" : "\u00A7aCloud"));
    }

    private static Component cpText(CraftyAIConfig c) {
        return Component.literal("\uD83D\uDD0C Custom Provider: " + (c.custom_provider_enabled ? "\u00A7aEnabled" : "\u00A77Disabled"));
    }

    private static Component agenticText(CraftyAIConfig c) {
        return Component.literal("\u26A1 Agentic Tasks: " + (c.agentic_tasks_enabled ? "\u00A7aEnabled \u2705" : "\u00A77Disabled"));
    }

    private static String cap(String s) {
        if (s == null || s.isEmpty()) return "Free";
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    private static int tierColor(String tier) {
        if (tier == null) return 0xAAAAAA;
        return switch (tier.toLowerCase()) {
            case "enterprise" -> 0xC77DFF; // purple
            case "pro" -> 0xFFD700;        // gold
            case "starter" -> 0x55C7FF;    // cyan
            default -> 0xAAAAAA;           // gray (free/trial/unknown)
        };
    }

    private String shortId(String s) {
        return s == null || s.isBlank() ? "N/A" : s.length() <= 18 ? s : s.substring(0, 8) + "..." + s.substring(s.length() - 6);
    }

    private String sessionId() { return SessionManager.getSessionId(FMLPaths.CONFIGDIR.get()); }

    // --- Actions ---

    private void save() {
        CraftyAIConfig cfg = CraftyAIForgeModClient.getConfig();
        String key = apiKeyField.getValue().trim();
        if (key.isEmpty()) { status = "\u00A7c\u274C API key required"; statusColor = 0xFF5555; return; }
        if (!key.matches("^cai_[a-zA-Z0-9_-]{16,128}$")) {
            status = "\u00A7c\u274C Invalid API key format (must start with cai_)";
            statusColor = 0xFF5555;
            return;
        }

        if (cfg.custom_provider_enabled && providerUrlField != null) {
            String url = providerUrlField.getValue().trim();
            if (!url.isEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
                status = "\u00A7c\u274C URL must start with http:// or https://"; statusColor = 0xFF5555; return;
            }
        }

        cfg.api_key = key;
        cfg.ai_name = aiNameField.getValue().trim().isEmpty() ? "Crafty" : aiNameField.getValue().trim();
        if (providerUrlField != null) cfg.custom_provider_url = providerUrlField.getValue().trim();
        if (providerKeyField != null) cfg.custom_provider_key = providerKeyField.getValue().trim();

        try {
            CraftyAIForgeModClient.saveConfig();
            if (CraftyAIForgeMod.INSTANCE != null) {
                CraftyAIForgeMod.INSTANCE.reloadConfig();
            }
            LOGGER.info("[CraftyAI] Saved config: agentic_tasks_enabled={}, force_local_mode={}, custom_provider_enabled={}, tier={}",
                    cfg.agentic_tasks_enabled, cfg.force_local_mode, cfg.custom_provider_enabled, cfg.tier);
            status = "\u00A7a\u2705 \u00A7lSaved successfully!";
            statusColor = 0x55FF55;
        } catch (Exception e) {
            LOGGER.error("[CraftyAI] Save failed: {}", e.getMessage());
            status = "\u00A7c\u274C Save failed: " + e.getMessage();
            statusColor = 0xFF5555;
        }
    }

    private void testGateway() {
        String key = apiKeyField.getValue().trim();
        if (key.isEmpty()) { status = "\u00A7c\u274C API key required for validation"; statusColor = 0xFF5555; return; }
        if (!key.matches("^cai_[a-zA-Z0-9_-]{16,128}$")) {
            lastKeyTestResult = "\u00A7c\u274C Bad format";
            lastKeyTestColor = 0xFF5555;
            status = "\u00A7c\u274C API key must start with cai_"; statusColor = 0xFF5555;
            return;
        }
        status = "\u00A7e\u23F3 Validating key..."; statusColor = 0xFFAA00;
        Thread testThread = new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) URI.create(GatewayRequestHeaders.getGatewayUrl() + "/v1/handshake").toURL().openConnection();
                conn.setRequestMethod("POST"); conn.setDoOutput(true);
                conn.setConnectTimeout(5000); conn.setReadTimeout(5000);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + key);
                GatewayRequestHeaders.apply(conn, CLIENT_TYPE, sessionId());
                String body = "{\"version\":\"" + GatewayRequestHeaders.MOD_VERSION + "\",\"client_type\":\"" + CLIENT_TYPE + "\",\"server_id\":\"" + sessionId() + "\"}";
                conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) {
                    String resBody = "";
                    try (java.io.InputStream in = conn.getInputStream()) {
                        resBody = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    }
                    String tier = "unknown";
                    boolean suspended = false;
                    String warnMsg = null;
                    try {
                        com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(resBody).getAsJsonObject();
                        if (json.has("tier")) tier = json.get("tier").getAsString();
                        if (json.has("status") && "suspended".equalsIgnoreCase(json.get("status").getAsString())) suspended = true;
                        if (json.has("warnings") && json.getAsJsonArray("warnings").size() > 0) {
                            warnMsg = json.getAsJsonArray("warnings").get(0).getAsJsonObject().get("message").getAsString();
                        }
                    } catch (Exception ignored) {}
                    CraftyAIForgeModClient.getConfig().tier = tier;
                    if (suspended) {
                        lastKeyTestResult = "\u00A7c\u274C Suspended";
                        lastKeyTestColor = 0xFF5555;
                        status = "\u00A7c\u274C \u00A7lAccount Suspended. \u00A7r\u00A77Open a ticket: \u00A7bdiscord.gg/zCkE44hsBR";
                        statusColor = 0xFF5555;
                    } else {
                        lastKeyTestResult = "\u00A7a\u2705 Valid";
                        lastKeyTestColor = 0x55FF55;
                        String tierEmoji = isEnterpriseTier(CraftyAIForgeModClient.getConfig()) ? "\uD83D\uDC8E" : (isProTier(CraftyAIForgeModClient.getConfig()) ? "\uD83D\uDC8E" : "\u2728");
                        String extra = warnMsg != null ? " \u00A77\u00A7o\u26A0 " + warnMsg : "";
                        status = "\u00A7a\u2705 \u00A7lKey Valid! \u00A7r\u00A7aTier: \u00A7f" + tierEmoji + " " + tier + extra;
                        statusColor = 0x55FF55;
                    }
                } else if (code == 401 || code == 403) {
                    String errBody = "";
                    try (java.io.InputStream in = conn.getErrorStream()) {
                        if (in != null) errBody = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    } catch (Exception ignored) {}
                    String errMsg = "Unauthorized";
                    try {
                        com.google.gson.JsonObject errJson = com.google.gson.JsonParser.parseString(errBody).getAsJsonObject();
                        if (errJson.has("error")) errMsg = errJson.get("error").getAsString();
                    } catch (Exception ignored) {}
                    lastKeyTestResult = "\u00A7c\u274C Invalid";
                    lastKeyTestColor = 0xFF5555;
                    status = "\u00A7c\u274C \u00A7lKey Invalid! \u00A7r\u00A77" + errMsg;
                    statusColor = 0xFF5555;
                } else {
                    lastKeyTestResult = "\u00A7e\u26A0 HTTP " + code;
                    lastKeyTestColor = 0xFFAA00;
                    status = "\u00A7e\u26A0 Gateway returned HTTP " + code;
                    statusColor = 0xFFAA00;
                }
            } catch (IOException e) {
                lastKeyTestResult = "\u00A7c\u274C Unreachable";
                lastKeyTestColor = 0xFF5555;
                status = "\u00A7c\u274C Gateway unreachable. Check your internet.";
                statusColor = 0xFF5555;
            }
            finally { if (conn != null) conn.disconnect(); }
        }, "CraftyAI-Test");
        testThread.setDaemon(true);
        testThread.start();
    }

    private void testProvider() {
        if (providerUrlField == null) return;
        String url = providerUrlField.getValue().trim();
        String key = providerKeyField != null ? providerKeyField.getValue().trim() : "";
        if (url.isEmpty()) { status = "\u00A7c\u274C Provider URL required"; statusColor = 0xFF5555; return; }
        status = "\u00A7e\u23F3 Testing provider..."; statusColor = 0xFFAA00;
        Thread providerThread = new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) URI.create(url + "/v1/models").toURL().openConnection();
                conn.setRequestMethod("GET"); conn.setConnectTimeout(5000); conn.setReadTimeout(5000);
                if (!key.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + key);
                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) {
                    status = "\u00A7a\u2705 \u00A7lProvider online!";
                    statusColor = 0x55FF55;
                } else {
                    status = "\u00A7c\u274C Provider returned HTTP " + code;
                    statusColor = 0xFF5555;
                }
            } catch (IOException e) {
                status = "\u00A7c\u274C Provider unreachable";
                statusColor = 0xFF5555;
            }
            finally { if (conn != null) conn.disconnect(); }
        }, "CraftyAI-Provider-Test");
        providerThread.setDaemon(true);
        providerThread.start();
    }

    // --- Render ---

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float d) {
        super.renderBackground(g, mx, my, d);
        CraftyAIConfig cfg = CraftyAIForgeModClient.getConfig();
        int left = (this.width - W) / 2;
        int y = Math.max(4, (this.height - contentH) / 2);
        // Clean dark rounded-feel background container
        g.fillGradient(left - PAD, y - 8, left + W + PAD, y + contentH + 8, 0xE0101828, 0xE0182438);
        int accent = tierColor(cfg.tier);
        g.fill(left - PAD, y - 8, left + W + PAD, y - 6, accent);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float d) {
        super.render(g, mx, my, d);
        CraftyAIConfig cfg = CraftyAIForgeModClient.getConfig();
        int left = (this.width - W) / 2;
        int y = Math.max(4, (this.height - contentH) / 2);

        int accent = tierColor(cfg.tier);
        String titleText = "\u00A7b\u2726 CraftyAI Settings \u00A78\u2014 \u00A7r" + cap(cfg.tier);
        g.drawCenteredString(this.font, Component.literal(titleText), this.width / 2, y + 2, accent);
        y += 16;

        // API Key status badge
        if (lastKeyTestResult != null) {
            int badgeX = left + W - 56;
            int badgeY = y + LBL + 2;
            g.drawString(this.font, Component.literal(lastKeyTestResult), badgeX, badgeY, lastKeyTestColor);
        }

        // Labels
        g.drawString(this.font, Component.literal("\uD83D\uDD11 \u00A7fAPI Key"), left, y, 0xFFFFFF);
        y += LBL + FH + GAP;
        g.drawString(this.font, Component.literal("\uD83E\uDD16 \u00A7fAI Name"), left, y, 0xFFFFFF);
        y += LBL + FH + GAP;

        y += FH + GAP; // Mode
        y += FH + GAP; // Custom Provider

        if (cfg.custom_provider_enabled) {
            g.drawString(this.font, Component.literal("\uD83C\uDF10 \u00A7fProvider URL"), left, y, 0xFFFFFF);
            y += LBL + FH + GAP;
            g.drawString(this.font, Component.literal("\uD83D\uDDDD\uFE0F \u00A7fProvider Key"), left, y, 0xFFFFFF);
            y += LBL + FH + GAP;
        }

        y += FH + GAP; // Agentic

        // Session ID
        g.drawString(this.font, Component.literal("\uD83D\uDD17 \u00A77Session: \u00A7f" + shortId(sessionId())), left, y + 6, 0xDDDDDD);

        // Multiplayer hint
        if (Minecraft.getInstance() != null && Minecraft.getInstance().getCurrentServer() != null) {
            g.drawCenteredString(this.font,
                    Component.literal("\u00A77\u26A0 Multiplayer: agentic tasks controlled by \u00A7fplugins/CraftyAI/config.yml"),
                    this.width / 2, this.height - 50, 0xAAAAAA);
        }

        // Status (color-coded)
        if (!status.isEmpty()) {
            g.drawCenteredString(this.font, Component.literal(status), this.width / 2, this.height - 32, statusColor);
        }

        g.drawCenteredString(this.font, Component.literal("\u00A77Guides & Support: \u00A7bcraftyai.pages.dev"),
                this.width / 2, this.height - 18, 0x55FFFF);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            CraftyAIForgeModClient.safeSetScreen(this.minecraft, this.parent);
        }
    }
}
