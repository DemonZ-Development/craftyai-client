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

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * VersionAdapter — Abstracts API differences across Minecraft versions.
 * Handles: chat messages, schedulers (Bukkit vs Folia), sounds, action bar.
 */
public class VersionAdapter {

    private final Plugin plugin;
    private final PlatformDetector platform;
    private final Logger logger;
    private final boolean hasAdventureAPI;
    private static Object cachedSerializer = null;

    public VersionAdapter(Plugin plugin, PlatformDetector platform) {
        this.plugin = plugin;
        this.platform = platform;
        this.logger = plugin.getLogger();
        this.hasAdventureAPI = platform.hasAdventureAPI();
    }

    // =====================================================================
    //  CHAT MESSAGES (Adventure API vs BungeeCord API vs Legacy)
    // =====================================================================

    /**
     * Sends a colored chat message to a player.
     * Uses the best available API for the platform.
     */
    public void sendMessage(Player player, String message) {
        // Translate color codes (& → §)
        String colored = translateColors(message);

        if (hasAdventureAPI) {
            try {
                Class<?> componentClass = Class.forName("net.kyori.adventure.text.Component");
                Object serializer = getLegacySectionSerializer();
                if (serializer != null) {
                    Object component = serializer.getClass().getMethod("deserialize", String.class).invoke(serializer, colored);
                    Method sendMsg = player.getClass().getMethod("sendMessage", componentClass);
                    sendMsg.invoke(player, component);
                    return;
                }
            } catch (Exception e) {
                // Fall through to legacy
            }
        }

        // Fallback: Standard Bukkit sendMessage (works on all versions)
        player.sendMessage(colored);
    }

    /**
     * Sends an action bar message to a player.
     */
    public void sendActionBar(Player player, String message) {
        String colored = translateColors(message);

        if (hasAdventureAPI) {
            try {
                Class<?> componentClass = Class.forName("net.kyori.adventure.text.Component");
                Object serializer = getLegacySectionSerializer();
                if (serializer != null) {
                    Object component = serializer.getClass().getMethod("deserialize", String.class).invoke(serializer, colored);
                    Method sendActionBar = player.getClass().getMethod("sendActionBar", componentClass);
                    sendActionBar.invoke(player, component);
                    return;
                }
            } catch (Exception e) {
                // Fall through
            }
        }

        // BungeeCord API (Spigot 1.8+)
        try {
            Class<?> chatMsgType = Class.forName("net.md_5.bungee.api.ChatMessageType");
            Class<?> textComponent = Class.forName("net.md_5.bungee.api.chat.TextComponent");
            Class<?> baseComponent = Class.forName("net.md_5.bungee.api.chat.BaseComponent");
            Class<?> baseComponentArray = java.lang.reflect.Array.newInstance(baseComponent, 0).getClass();

            Object actionBar = chatMsgType.getField("ACTION_BAR").get(null);
            Object component = textComponent.getConstructor(String.class).newInstance(colored);

            Object componentsArray = java.lang.reflect.Array.newInstance(baseComponent, 1);
            java.lang.reflect.Array.set(componentsArray, 0, component);

            Object spigot = player.getClass().getMethod("spigot").invoke(player);
            Method sendMsg = spigot.getClass().getMethod("sendMessage", chatMsgType, baseComponentArray);
            sendMsg.invoke(spigot, actionBar, componentsArray);
        } catch (Exception e) {
            // Last resort: just send as chat
            player.sendMessage(colored);
        }
    }

    // =====================================================================
    //  SCHEDULERS (Bukkit vs Folia)
    // =====================================================================

    /**
     * Runs a task asynchronously. Uses Folia's async scheduler if available.
     */
    public void runAsync(Runnable task) {
        if (platform.hasFoliaScheduler()) {
            try {
                // Folia: Bukkit.getAsyncScheduler().runNow(plugin, t -> task.run())
                Object asyncScheduler = Bukkit.class.getMethod("getAsyncScheduler").invoke(null);
                // Use reflection to create a Consumer
                Class<?> consumerClass = Class.forName("java.util.function.Consumer");
                Object consumer = java.lang.reflect.Proxy.newProxyInstance(
                    consumerClass.getClassLoader(),
                    new Class[]{consumerClass},
                    (proxy, method, args) -> { task.run(); return null; }
                );
                asyncScheduler.getClass().getMethod("runNow", Plugin.class, consumerClass)
                    .invoke(asyncScheduler, plugin, consumer);
                return;
            } catch (Exception e) {
                logger.warning("[Folia] Async scheduler failed, falling back to Bukkit: " + e.getMessage());
            }
        }

        // Standard Bukkit scheduler
        Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    /**
     * Runs a task on the main thread (or region thread for Folia).
     */
    public void runSync(Runnable task) {
        if (platform.hasFoliaScheduler()) {
            try {
                Object globalScheduler = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
                Class<?> consumerClass = Class.forName("java.util.function.Consumer");
                Object consumer = java.lang.reflect.Proxy.newProxyInstance(
                    consumerClass.getClassLoader(),
                    new Class[]{consumerClass},
                    (proxy, method, args) -> { task.run(); return null; }
                );
                globalScheduler.getClass().getMethod("run", Plugin.class, consumerClass)
                    .invoke(globalScheduler, plugin, consumer);
                return;
            } catch (Exception e) {
                logger.warning("[Folia] Global scheduler failed, falling back to Bukkit: " + e.getMessage());
            }
        }

        Bukkit.getScheduler().runTask(plugin, task);
    }

    /**
     * Runs a task on the entity's region thread (for Folia) or the main thread (for Bukkit).
     * Essential for Folia compatibility when modifying entity state (teleport, health, etc.).
     */
    public void runEntitySync(org.bukkit.entity.Entity entity, Runnable task) {
        if (platform.hasFoliaScheduler()) {
            try {
                // entity.getScheduler().run(plugin, t -> task.run(), null)
                Object entityScheduler = entity.getClass().getMethod("getScheduler").invoke(entity);
                Class<?> consumerClass = Class.forName("java.util.function.Consumer");
                Object consumer = java.lang.reflect.Proxy.newProxyInstance(
                    consumerClass.getClassLoader(),
                    new Class[]{consumerClass},
                    (proxy, method, args) -> { task.run(); return null; }
                );
                entityScheduler.getClass().getMethod("run", Plugin.class, consumerClass, Runnable.class)
                    .invoke(entityScheduler, plugin, consumer, null);
                return;
            } catch (Exception e) {
                logger.warning("[Folia] Entity scheduler failed for " + entity.getName() + ", falling back to global: " + e.getMessage());
            }
        }
        
        // Fallback to standard sync (works on Bukkit/Spigot/Paper)
        runSync(task);
    }

    /**
     * Runs a task with delay (in ticks). 20 ticks = 1 second.
     */
    public void runAsyncLater(Runnable task, long delayTicks) {
        if (platform.hasFoliaScheduler()) {
            try {
                Object asyncScheduler = Bukkit.class.getMethod("getAsyncScheduler").invoke(null);
                Class<?> consumerClass = Class.forName("java.util.function.Consumer");
                Object consumer = java.lang.reflect.Proxy.newProxyInstance(
                    consumerClass.getClassLoader(),
                    new Class[]{consumerClass},
                    (proxy, method, args) -> { task.run(); return null; }
                );
                Class<?> timeUnitClass = Class.forName("java.util.concurrent.TimeUnit");
                Object millisUnit = timeUnitClass.getField("MILLISECONDS").get(null);
                long delayMs = delayTicks * 50; // Convert ticks to ms
                asyncScheduler.getClass().getMethod("runDelayed", Plugin.class, consumerClass, long.class, timeUnitClass)
                    .invoke(asyncScheduler, plugin, consumer, delayMs, millisUnit);
                return;
            } catch (Exception e) {
                logger.warning("[Folia] Delayed async failed, falling back: " + e.getMessage());
            }
        }

        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, delayTicks);
    }

    // =====================================================================
    //  SOUNDS (Handle renamed sounds across versions)
    // =====================================================================

    /**
     * Plays a sound by name with fallbacks for different versions.
     */
    public void playSound(Player player, String soundName) {
        playSound(player, soundName, 0.5f, 1.0f);
    }

    /**
     * Plays a sound by name with custom volume and pitch.
     */
    public void playSound(Player player, String soundName, float volume, float pitch) {
        try {
            Sound sound = Sound.valueOf(soundName);
            player.playSound(player.getLocation(), sound, volume, pitch);
        } catch (IllegalArgumentException e) {
            // Try common fallbacks
            String[] fallbacks = getSoundFallbacks(soundName);
            for (String fb : fallbacks) {
                try {
                    Sound sound = Sound.valueOf(fb);
                    player.playSound(player.getLocation(), sound, volume, pitch);
                    return;
                } catch (IllegalArgumentException ignored) {}
            }
            // Silently fail if no sound works
        }
    }

    // Cached serializer lookup (avoids reflection on every call)
    private Object getLegacySectionSerializer() {
        if (cachedSerializer == null) {
            try {
                Class<?> legacyClass = Class.forName("net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer");
                cachedSerializer = legacyClass.getMethod("legacySection").invoke(null);
            } catch (Exception e) {
                logger.warning("[CraftyAI] Failed to init LegacyComponentSerializer: " + e.getMessage());
            }
        }
        return cachedSerializer;
    }

    private String[] getSoundFallbacks(String name) {
        switch (name) {
            case "BLOCK_NOTE_BLOCK_CHIME":
                return new String[]{"BLOCK_NOTE_CHIME", "NOTE_PLING", "BLOCK_NOTE_BLOCK_PLING"};
            case "ENTITY_EXPERIENCE_ORB_PICKUP":
                return new String[]{"ENTITY_PLAYER_LEVELUP", "ORB_PICKUP"};
            case "BLOCK_AMETHYST_BLOCK_CHIME":
                return new String[]{"BLOCK_NOTE_BLOCK_CHIME", "BLOCK_AMETHYST_CLUSTER_BREAK"};
            default:
                return new String[]{"BLOCK_NOTE_BLOCK_PLING", "UI_BUTTON_CLICK"};
        }
    }

    // =====================================================================
    //  UTILITIES
    // =====================================================================

    /**
     * Translates & color codes to § for all versions.
     */
    public String translateColors(String text) {
        if (text == null) return "";
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length - 1; i++) {
            if (chars[i] == '&' && "0123456789AaBbCcDdEeFfKkLlMmNnOoRr".indexOf(chars[i + 1]) > -1) {
                chars[i] = '\u00A7'; // §
                chars[i + 1] = Character.toLowerCase(chars[i + 1]);
            }
        }
        return new String(chars);
    }
}
