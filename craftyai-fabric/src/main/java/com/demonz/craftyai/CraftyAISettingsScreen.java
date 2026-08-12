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
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * CraftyAI Settings Screen for Fabric 1.20 - 1.21.x (Yarn mappings).
 * Compact, pixel-perfect layout with Auto-Mint Key button.
 */
public class CraftyAISettingsScreen extends Screen {
    private static final String CLIENT_TYPE = "minecraft-fabric-client";
    private static final int W = 280;
    private static final int FH = 20;
    private static final int GAP = 2;
    private static final int LBL = 10;
    private static final int PAD = 10;

    private final Screen parent;
    private TextFieldWidget apiKeyField;
    private TextFieldWidget aiNameField;
    private TextFieldWidget providerUrlField;
    private TextFieldWidget providerKeyField;
    private int contentH;

    private String status = "";
    private int statusColor = 0xFFFFFF;
    private String lastKeyTestResult = null;
    private int lastKeyTestColor = 0xAAAAAA;

    private static final Logger LOGGER = LoggerFactory.getLogger("craftyai-client");

    protected CraftyAISettingsScreen(Screen parent) {
        super(Text.literal("CraftyAI Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        CraftyAIConfig cfg = CraftyAIModClient.getConfig();
        contentH = calcHeight(cfg);
        int left = (this.width - W) / 2;
        int y = Math.max(4, (this.height - contentH) / 2);

        // Title and Auto-Mint Key button at top row
        ButtonWidget autoMintBtn = ButtonWidget.builder(Text.literal("\u26A1 Auto-Mint Key"), btn -> {
            btn.setMessage(Text.literal("Minting..."));
            btn.active = false;
            CraftyAIModClient clientInst = CraftyAIModClient.getInstance();
            if (clientInst != null) {
                clientInst.performAutoMintKey(null, mintedKey -> {
                    if (mintedKey != null) {
                        if (apiKeyField != null) {
                            apiKeyField.setText(mintedKey);
                        }
                        btn.setMessage(Text.literal("\u2705 Key Minted!"));
                    } else {
                        btn.setMessage(Text.literal("\u274C Mint Failed"));
                        btn.active = true;
                    }
                });
            } else {
                btn.setMessage(Text.literal("\u274C Error"));
                btn.active = true;
            }
        }).dimensions(left + W - 110, y, 110, 16).build();
        addDrawableChild(autoMintBtn);
        y += 18;

        // API Key (masked when unfocused)
        apiKeyField = new TextFieldWidget(this.textRenderer, left, y + LBL, W, FH, Text.literal("API Key")) {
            private String real = cfg.api_key == null ? "" : cfg.api_key;
            {
                this.setChangedListener(text -> {
                    if (this.isFocused()) {
                        this.real = text;
                        lastKeyTestResult = null;
                    }
                });
            }
            @Override public void setText(String t) { real = t; super.setText(isFocused() ? t : mask(t)); }
            @Override public String getText() { return real; }
            @Override public void setFocused(boolean f) { super.setFocused(f); super.setText(f ? real : mask(real)); }
            private String mask(String k) {
                if (k == null || k.isEmpty()) return "";
                if (k.length() <= 8) return "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022";
                return k.substring(0, 4) + "\u2022\u2022\u2022\u2022" + k.substring(k.length() - 4);
            }
        };
        apiKeyField.setMaxLength(256);
        apiKeyField.setPlaceholder(Text.literal("cai_xxxxxxxx..."));
        apiKeyField.setText(cfg.api_key == null ? "" : cfg.api_key);
        addDrawableChild(apiKeyField);
        y += LBL + FH + GAP;

        // AI Name
        aiNameField = new TextFieldWidget(this.textRenderer, left, y + LBL, W, FH, Text.literal("AI Name"));
        aiNameField.setMaxLength(256);
        aiNameField.setPlaceholder(Text.literal("Assistant name"));
        aiNameField.setText(cfg.ai_name == null ? "Crafty" : cfg.ai_name);
        addDrawableChild(aiNameField);
        y += LBL + FH + GAP;

        // Mode toggle
        addDrawableChild(ButtonWidget.builder(modeText(cfg), b -> {
            cfg.force_local_mode = !cfg.force_local_mode;
            b.setMessage(modeText(cfg));
        }).dimensions(left, y, W, FH).build());
        y += FH + GAP;

        // Custom Provider toggle
        addDrawableChild(ButtonWidget.builder(cpText(cfg), b -> {
            cfg.custom_provider_enabled = !cfg.custom_provider_enabled;
            preserveAndRebuild();
        }).dimensions(left, y, W, FH).build());
        y += FH + GAP;

        // Provider URL & Key (only when custom provider enabled)
        if (cfg.custom_provider_enabled) {
            providerUrlField = new TextFieldWidget(this.textRenderer, left, y + LBL, W, FH, Text.literal("URL"));
            providerUrlField.setMaxLength(256);
            providerUrlField.setPlaceholder(Text.literal("https://api.example.com"));
            providerUrlField.setText(cfg.custom_provider_url == null ? "" : cfg.custom_provider_url);
            addDrawableChild(providerUrlField);
            y += LBL + FH + GAP;

            providerKeyField = new TextFieldWidget(this.textRenderer, left, y + LBL, W, FH, Text.literal("Key"));
            providerKeyField.setMaxLength(256);
            providerKeyField.setPlaceholder(Text.literal("Provider API key"));
            providerKeyField.setText(cfg.custom_provider_key == null ? "" : cfg.custom_provider_key);
            addDrawableChild(providerKeyField);
            y += LBL + FH + GAP;
        }

        // Agentic Tasks
        addDrawableChild(ButtonWidget.builder(agenticText(cfg), b -> {
            cfg.agentic_tasks_enabled = !cfg.agentic_tasks_enabled;
            b.setMessage(agenticText(cfg));
        }).dimensions(left, y, W, FH).build());
        y += FH + GAP;

        // Session ID + Copy + Reload
        int copyW = 50;
        int reloadW = 56;
        addDrawableChild(ButtonWidget.builder(Text.literal("\uD83D\uDCCB Copy"), b -> {
            MinecraftClient.getInstance().keyboard.setClipboard(sessionId());
            status = "\u00A7a\u2705 Session ID copied!";
            statusColor = 0x55FF55;
        }).dimensions(left + W - copyW, y, copyW, FH).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("\uD83D\uDD04 Reload"), b -> {
            CraftyAIModClient.reloadConfig();
            lastKeyTestResult = null;
            status = "\u00A7a\u2705 Reloaded from disk";
            statusColor = 0x55FF55;
            this.clearChildren();
            this.init();
        }).dimensions(left + W - copyW - reloadW - GAP, y, reloadW, FH).build());
        y += FH + GAP;

        // Save / Done
        int half = (W - GAP) / 2;
        addDrawableChild(ButtonWidget.builder(Text.literal("\u00A7a\u00A7l\uD83D\uDCBE Save"), b -> save()).dimensions(left, y, half, FH).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("\u00A7f\u2715 Done"), b -> close()).dimensions(left + half + GAP, y, half, FH).build());
        y += FH + GAP;

        // Test Gateway
        addDrawableChild(ButtonWidget.builder(Text.literal("\uD83D\uDD17 Test Connection (key check)"), b -> testGateway()).dimensions(left, y, W, FH).build());
    }

    private int calcHeight(CraftyAIConfig cfg) {
        int h = 18; // Title row
        h += (LBL + FH + GAP) * 2; // API Key + AI Name
        h += (FH + GAP) * 3; // Mode, CustomProvider, Agentic
        if (cfg.custom_provider_enabled) h += (LBL + FH + GAP) * 2;
        h += FH + GAP; // Session row
        h += FH + GAP; // Save / Done row
        h += FH + GAP; // Test Connection row
        return h;
    }

    private void preserveAndRebuild() {
        CraftyAIConfig cfg = CraftyAIModClient.getConfig();
        if (apiKeyField != null) cfg.api_key = apiKeyField.getText().trim();
        if (aiNameField != null) cfg.ai_name = aiNameField.getText().trim();
        if (providerUrlField != null) cfg.custom_provider_url = providerUrlField.getText().trim();
        if (providerKeyField != null) cfg.custom_provider_key = providerKeyField.getText().trim();
        this.clearChildren();
        this.init();
    }

    private static Text cpText(CraftyAIConfig c) {
        return Text.literal("\uD83D\uDD0C Custom Provider: " + (c.custom_provider_enabled ? "\u00A7aEnabled" : "\u00A77Disabled"));
    }

    private static Text agenticText(CraftyAIConfig c) {
        return Text.literal("\u26A1 Agentic Tasks: " + (c.agentic_tasks_enabled ? "\u00A7aEnabled \u2705" : "\u00A77Disabled"));
    }

    private static String cap(String s) {
        if (s == null || s.isEmpty()) return "Free";
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    private static int tierColor(String tier) {
        if (tier == null) return 0xAAAAAA;
        return switch (tier.toLowerCase()) {
            case "enterprise" -> 0xC77DFF;
            case "pro" -> 0xFFD700;
            case "starter" -> 0x55C7FF;
            default -> 0xAAAAAA;
        };
    }

    private String shortId(String s) {
        if (s == null || s.isBlank()) return "N/A";
        return s.length() <= 18 ? s : s.substring(0, 8) + "..." + s.substring(s.length() - 6);
    }

    private String sessionId() { return SessionManager.getSessionId(FabricLoader.getInstance().getConfigDir()); }

    private void save() {
        CraftyAIConfig cfg = CraftyAIModClient.getConfig();
        String key = apiKeyField.getText().trim();
        if (key.isEmpty()) { status = "\u00A7c\u274C API key required"; statusColor = 0xFF5555; return; }
        if (!key.matches("^cai_[a-zA-Z0-9_-]{16,128}$")) {
            status = "\u00A7c\u274C Invalid API key format (must start with cai_)";
            statusColor = 0xFF5555;
            return;
        }

        if (cfg.custom_provider_enabled && providerUrlField != null) {
            String url = providerUrlField.getText().trim();
            if (!url.isEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
                status = "\u00A7c\u274C URL must start with http:// or https://"; statusColor = 0xFF5555; return;
            }
        }

        cfg.api_key = key;
        cfg.ai_name = aiNameField.getText().trim().isEmpty() ? "Crafty" : aiNameField.getText().trim();
        if (providerUrlField != null) cfg.custom_provider_url = providerUrlField.getText().trim();
        if (providerKeyField != null) cfg.custom_provider_key = providerKeyField.getText().trim();

        try {
            CraftyAIModClient.saveCurrentConfig();
            if (CraftyAIMod.INSTANCE != null) {
                CraftyAIMod.INSTANCE.reloadConfig();
            }
            LOGGER.info("[CraftyAI] Saved config");
            status = "\u00A7a\u2705 \u00A7lSaved successfully!";
            statusColor = 0x55FF55;
        } catch (Exception e) {
            LOGGER.error("[CraftyAI] Save failed: {}", e.getMessage());
            status = "\u00A7c\u274C Save failed: " + e.getMessage();
            statusColor = 0xFF5555;
        }
    }

    private static Text modeText(CraftyAIConfig c) {
        return Text.literal((c.force_local_mode ? "\uD83C\uDF24" : "\u2601\uFE0F") + " Mode: " + (c.force_local_mode ? "\u00A7eLocal Only" : "\u00A7aCloud"));
    }

    private void testGateway() {
        String key = apiKeyField.getText().trim();
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
                String gw = GatewayRequestHeaders.getGatewayUrl();
                conn = (HttpURLConnection) URI.create(gw + "/v1/handshake").toURL().openConnection();
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
                    String tier = "free";
                    try {
                        com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(resBody).getAsJsonObject();
                        if (json.has("tier")) tier = json.get("tier").getAsString();
                    } catch (Exception ignored) {}
                    CraftyAIConfig cfg = CraftyAIModClient.getConfig();
                    cfg.tier = tier;
                    CraftyAIModClient.saveCurrentConfig();

                    lastKeyTestResult = "\u00A7a\u2705 " + cap(tier);
                    lastKeyTestColor = tierColor(tier);
                    status = "\u00A7a\u2705 \u00A7lKey Valid! \u00A7r\u00A77Tier: " + cap(tier);
                    statusColor = 0x55FF55;
                } else if (code == 401 || code == 403) {
                    String errBody = "";
                    try (java.io.InputStream in = conn.getErrorStream()) {
                        if (in != null) errBody = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    }
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

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        this.renderBackground(ctx, mouseX, mouseY, delta);
        CraftyAIConfig cfg = CraftyAIModClient.getConfig();
        int left = (this.width - W) / 2;
        int y = Math.max(4, (this.height - contentH) / 2);

        // Background panel
        ctx.fillGradient(left - PAD, y - 8, left + W + PAD, y + contentH + 8, 0xD0101828, 0xD0182438);

        // Top accent bar
        int accent = tierColor(cfg.tier);
        ctx.fill(left - PAD, y - 8, left + W + PAD, y - 6, accent);

        // Title at left (Auto-Mint button is at top right)
        String titleText = "\u00A7b\u2726 CraftyAI Settings \u00A78\u2014 \u00A7r" + cap(cfg.tier);
        ctx.drawTextWithShadow(this.textRenderer, Text.literal(titleText), left, y + 4, accent);
        y += 18;

        // API Key status badge
        if (lastKeyTestResult != null) {
            int badgeX = left + W - 56;
            int badgeY = y + LBL + 2;
            ctx.drawTextWithShadow(this.textRenderer, Text.literal(lastKeyTestResult), badgeX, badgeY, lastKeyTestColor);
        }

        // Labels (precisely aligned above edit boxes)
        ctx.drawTextWithShadow(this.textRenderer, Text.literal("\uD83D\uDD11 \u00A7fAPI Key"), left, y, 0xFFFFFF);
        y += LBL + FH + GAP;

        ctx.drawTextWithShadow(this.textRenderer, Text.literal("\uD83E\uDD16 \u00A7fAI Name"), left, y, 0xFFFFFF);
        y += LBL + FH + GAP;

        y += FH + GAP; // Mode
        y += FH + GAP; // Custom Provider

        if (cfg.custom_provider_enabled) {
            ctx.drawTextWithShadow(this.textRenderer, Text.literal("\uD83C\uDF10 \u00A7fProvider URL"), left, y, 0xFFFFFF);
            y += LBL + FH + GAP;
            ctx.drawTextWithShadow(this.textRenderer, Text.literal("\uD83D\uDDDD\uFE0F \u00A7fProvider Key"), left, y, 0xFFFFFF);
            y += LBL + FH + GAP;
        }

        y += FH + GAP; // Agentic Tasks

        // Session ID label
        ctx.drawTextWithShadow(this.textRenderer,
                Text.literal("\uD83D\uDD17 \u00A77Session: \u00A7f" + shortId(sessionId())),
                left, y + 6, 0xDDDDDD);

        // Multiplayer hint
        if (!MinecraftClient.getInstance().isInSingleplayer()) {
            ctx.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("\u00A77\u26A0 Multiplayer: agentic tasks controlled by \u00A7fplugins/CraftyAI/config.yml"),
                    this.width / 2, this.height - 50, 0xAAAAAA);
        }

        // Status
        if (!status.isEmpty()) {
            ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(status),
                    this.width / 2, this.height - 32, statusColor);
        }

        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("\u00A77Guides & Support: \u00A7bcraftyai.pages.dev"),
                this.width / 2, this.height - 18, 0x55FFFF);

        super.render(ctx, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }
}
