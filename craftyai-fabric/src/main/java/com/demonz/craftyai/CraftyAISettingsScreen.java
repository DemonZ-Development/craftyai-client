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

import com.demonz.craftyai.common.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.fabricmc.loader.api.FabricLoader;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

public class CraftyAISettingsScreen extends Screen {
    private final Screen parent;
    private SettingsDraft draft;
    private int page;
    private boolean reveal, busy, closed;
    private String status = "Changes apply when you save.";
    private int left, top, w, h;
    private static final int ACCENT = 0xFFE2DFD5;

    public CraftyAISettingsScreen(Screen parent) {
        super(Text.literal("CraftyAI Settings"));
        this.parent = parent;
    }

    @Override protected void init() {
        if (draft == null) draft = new SettingsDraft(CraftyAIModClient.getConfig());
        w = Math.min(380, this.width - 32);
        h = Math.min(272, this.height - 12);
        left = (this.width - w) / 2; top = (this.height - h) / 2;
        addDrawable((g, mx, my, delta) -> {
            g.fill(0, 0, this.width, this.height, 0x99121110);
            g.fill(left - 10, top, left + w + 10, top + h, 0xF0242320);
        });
        label(left, top + 10, "CRAFTY AI", ACCENT, w / 2);
        label(left + w / 2, top + 10, "Settings / " + new String[]{"General", "Connection", "Provider"}[page], 0xFFAFBDCC, w / 2);
        int tabW = (w - 8) / 3;
        String[] tabs = {"General", "Connection", "Provider"};
        for (int i = 0; i < tabs.length; i++) {
            final int tab = i;
            button(left + i * (tabW + 4), top + 28, tabW, (page == i ? "> " : "") + tabs[i], () -> {
                page = tab; reveal = false; rebuild();
            }, true);
        }
        int y = top + 56;
        if (page == 0) general(y);
        else if (page == 1) connection(y);
        else provider(y);

        label(left, top + h - 46, status, 0xFFB7CBD7, w);
        int third = (w - 8) / 3;
        button(left, top + h - 30, third, "Reset edits", () -> {
            draft = new SettingsDraft(CraftyAIModClient.getConfig()); reveal = false;
            status = "Unsaved edits reset."; rebuild();
        }, !busy);
        button(left + third + 4, top + h - 30, third, "Cancel", this::close, true);
        button(left + 2 * (third + 4), top + h - 30, third, "Save & close", this::save, !busy);
    }

    private void general(int y) {
        label(left, y, "Assistant name", 0xFFAFBDCC, w);
        field(left, y + 12, w, draft.name, 32, false, value -> draft.name = value);
        button(left, y + 40, w, "Connection mode: " + (draft.local ? "Offline" : "Online"), () -> {
            draft.local = !draft.local; rebuild();
        }, !busy);
        int half = (w - 4) / 2;
        button(left, y + 66, half, "Game actions: " + (draft.actions ? "On" : "Off"), () -> {
            draft.actions = !draft.actions; rebuild();
        }, !busy);
        button(left + half + 4, y + 66, half, "Ask before risky: " + (draft.confirmation ? "Yes" : "No"), () -> {
            draft.confirmation = !draft.confirmation; rebuild();
        }, !busy);
        label(left, y + 99, "Multiplayer servers control their own actions.", 0xFF8D9DAE, w);
        label(left, y + 111, "Cloud access and custom providers need internet.", 0xFF8D9DAE, w);
    }

    private void connection(int y) {
        label(left, y, "CraftyAI API key", 0xFFAFBDCC, w);
        field(left, y + 12, w - 74, draft.apiKey, 256, !reveal, value -> draft.apiKey = value);
        button(left + w - 70, y + 12, 70, reveal ? "Hide" : "Show / edit", () -> { reveal = !reveal; rebuild(); }, !busy);
        int half = (w - 4) / 2;
        button(left, y + 40, half, "Create free key", () -> {
            String session = sessionId();
            run(() -> SettingsConnection.gateway("", "minecraft-fabric-client", session, true), key -> {
                draft.apiKey = key; status = "Key created. Save to keep it.";
            });
        }, !busy);
        button(left + half + 4, y + 40, half, "Test connection", () -> {
            String key = draft.apiKey.trim(); String session = sessionId();
            if (!key.matches("^cai_[a-zA-Z0-9_-]{16,128}$")) { status = "Enter a valid CraftyAI key first."; rebuild(); return; }
            run(() -> SettingsConnection.gateway(key, "minecraft-fabric-client", session, false), message -> status = message);
        }, !busy);
        button(left, y + 66, w, "Copy session ID", () -> {
            MinecraftClient.getInstance().keyboard.setClipboard(sessionId());
            status = "Session ID copied."; rebuild();
        }, true);
        label(left, y + 99, "Keys are hidden by default. Keep them private.", 0xFF8D9DAE, w);
        label(left, y + 111, "Support: craftyai.pages.dev", 0xFF8D9DAE, w);
    }

    private void provider(int y) {
        button(left, y, w, "Custom provider: " + (draft.provider ? "Enabled" : "Disabled"), () -> {
            draft.provider = !draft.provider; rebuild();
        }, !busy);
        label(left, y + 26, "Endpoint URL", 0xFFAFBDCC, w);
        field(left, y + 38, w, draft.url, 512, false, value -> draft.url = value);
        int half = (w - 4) / 2;
        label(left, y + 62, "Provider key", 0xFFAFBDCC, half);
        label(left + half + 4, y + 62, "Model ID", 0xFFAFBDCC, half);
        field(left, y + 74, half, draft.providerKey, 512, !reveal, value -> draft.providerKey = value);
        field(left + half + 4, y + 74, half, draft.model, 256, false, value -> draft.model = value);
        button(left, y + 100, half, reveal ? "Hide key" : "Show / edit key", () -> { reveal = !reveal; rebuild(); }, !busy);
        button(left + half + 4, y + 100, half, "Test provider", () -> {
            String url = draft.url.trim(), key = draft.providerKey.trim();
            try { SettingsDraft.providerBaseUrl(url); }
            catch (IllegalArgumentException e) { status = e.getMessage(); rebuild(); return; }
            run(() -> SettingsConnection.provider(url, key), message -> status = message);
        }, !busy);
    }

    private void field(int x, int y, int width, String value, int limit, boolean hidden, Consumer<String> change) {
        TextFieldWidget input = new TextFieldWidget(this.textRenderer, x, y, width, 20, Text.literal(hidden ? "Hidden API key" : "Setting"));
        input.setMaxLength(limit);
        input.setText(hidden ? (value.isEmpty() ? "No key configured" : "********") : value);
        input.setEditable(!hidden && !busy); input.active = !hidden && !busy;

        if (!hidden) input.setChangedListener(change);
        addDrawableChild(input);
    }

    private void button(int x, int y, int width, String text, Runnable action, boolean enabled) {
        ButtonWidget b = ButtonWidget.builder(Text.literal(text), ignored -> action.run()).dimensions(x, y, width, 20).build();
        b.active = enabled; addDrawableChild(b);
    }

    private void label(int x, int y, String text, int color, int width) {
        addDrawable((g, mx, my, delta) -> g.drawTextWithShadow(this.textRenderer, this.textRenderer.trimToWidth(text, width), x, y, color));
    }
    private void rebuild() {

        MinecraftClient.getInstance().execute(() -> {
            if (closed) return;
            this.setFocused(null);
            this.clearChildren(); this.init();
        });
    }
    private String sessionId() { return SessionManager.getSessionId(FabricLoader.getInstance().getConfigDir()); }

    private void run(Callable<String> operation, Consumer<String> success) {
        if (busy) return;
        busy = true; status = "Connecting..."; rebuild();
        SettingsConnection.run(operation).whenComplete((result, failure) -> MinecraftClient.getInstance().execute(() -> {
            if (closed) return;
            busy = false;
            if (failure == null) success.accept(result);
            else status = "Connection failed. Check settings and try again.";
            rebuild();
        }));
    }

    private void save() {
        String problem = draft.validate();
        if (problem != null) { status = problem; rebuild(); return; }
        CraftyAIConfig live = CraftyAIModClient.getConfig();
        CraftyAIConfig next = draft.applyTo(live);
        if (!ConfigLoader.saveConfig(FabricLoader.getInstance().getConfigDir().toString(), "craftyai.json", next, message -> {})) {
            status = "Could not save. Check config folder permissions."; rebuild(); return;
        }
        live.copyFrom(next);
        if (MinecraftClient.getInstance().getServer() != null && CraftyAIMod.INSTANCE != null) {
            MinecraftClient.getInstance().getServer().execute(() -> {
                if (CraftyAIMod.INSTANCE != null) CraftyAIMod.INSTANCE.reloadConfig();
            });
        }
        close();
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            if (client.currentScreen == this) client.setScreen(parent);
        });
    }
    @Override public void removed() { closed = true; super.removed(); }
    @Override public boolean shouldPause() { return false; }
}
