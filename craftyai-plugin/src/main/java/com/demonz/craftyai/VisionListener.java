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

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * VisionListener V1.1 — Block & Entity Scanner
 * Supports multiple activation methods:
 *   - item_right_click: Hold configured item (default COMPASS) + right-click (no conflict with block placing)
 *   - command_only: Only /crafty scan triggers scans
 *   - shift_scan (legacy): Shift+Right-Click (causes conflict with block placing while sneaking)
 * Compatible with Minecraft 1.8 - 1.21+.
 */
public class VisionListener implements Listener {

    private final CraftyAI plugin;
    private final VersionAdapter adapter;
    private CraftyEngine engine;
    private final ConcurrentHashMap<UUID, Long> cooldowns = new ConcurrentHashMap<UUID, Long>();
    private final Map<Material, String> materialCache = new EnumMap<Material, String>(Material.class);
    private final Map<EntityType, String> entityCache = new EnumMap<EntityType, String>(EntityType.class);

    private boolean shiftScanEnabled;
    private String activationMode;
    private String activationItemName;
    private int scanCooldownSec;
    private boolean enabled;

    public VisionListener(CraftyAI plugin, VersionAdapter adapter, CraftyEngine engine) {
        this.plugin = plugin;
        this.adapter = adapter;
        this.engine = engine;
        reload();
    }

    public void reload() {
        this.enabled = plugin.getConfig().getBoolean("ai.allow_block_scanning", true);
        this.shiftScanEnabled = plugin.getConfig().getBoolean("vision.shift_scan_enabled", false);
        this.activationMode = plugin.getConfig().getString("vision.activation", "item_right_click");
        this.activationItemName = plugin.getConfig().getString("vision.activation_item", "COMPASS");
        this.scanCooldownSec = plugin.getConfig().getInt("vision.cooldown", 5);
    }

    public void setEngine(CraftyEngine engine) {
        this.engine = engine;
    }

    // =====================================================================
    //  ACTIVATION CHECK — Configurable activation method
    // =====================================================================

    /**
     * Checks if the vision scan should be triggered based on config settings.
     * Returns true if the interaction should trigger a scan, false otherwise.
     *
     * Priority:
     * 1. If shift_scan_enabled is true, use legacy shift+right-click behavior
     * 2. If activation is "item_right_click", check for held item + right-click (no sneaking required)
     * 3. If activation is "command_only", never trigger from events
     */
    private boolean shouldActivateScan(Player player) {
        if (!enabled) return false;
        if (shiftScanEnabled) {
            return player.isSneaking();
        }
        if ("item_right_click".equals(activationMode)) {
            Material requiredItem = Material.matchMaterial(activationItemName);
            if (requiredItem == null) return false;
            try {
                org.bukkit.inventory.ItemStack mainHand = player.getInventory().getItemInMainHand();
                if (mainHand != null && mainHand.getType() == requiredItem) {
                    return true;
                }
            } catch (NoSuchMethodError e) {
                org.bukkit.inventory.ItemStack handItem = player.getItemInHand();
                if (handItem != null && handItem.getType() == requiredItem) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    // =====================================================================
    //  EVENT HANDLERS
    // =====================================================================

    @EventHandler
    public void onBlockInteract(PlayerInteractEvent e) {
        // EquipmentSlot.HAND doesn't exist on 1.8 — guard with try-catch
        try {
            if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        } catch (Throwable ignored) {
            // 1.8: no dual-wield, all interactions are main hand
        }
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        // Check activation method from config
        if (!shouldActivateScan(e.getPlayer())) return;

        Player p = e.getPlayer();
        if (!p.hasPermission("crafty.vision") || isOnCooldown(p)) return;

        Block b = e.getClickedBlock();
        if (b == null || b.getType() == Material.AIR) return;

        scanBlock(p, b);
    }

    @EventHandler
    public void onEntityInteract(PlayerInteractEntityEvent e) {
        // EquipmentSlot.HAND doesn't exist on 1.8 — guard with try-catch
        try {
            if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        } catch (Throwable ignored) {
            // 1.8: no dual-wield
        }

        // Check activation method from config
        if (!shouldActivateScan(e.getPlayer())) return;

        Player p = e.getPlayer();
        if (!p.hasPermission("crafty.vision") || isOnCooldown(p)) return;

        scanEntity(p, e.getRightClicked());
    }

    // =====================================================================
    //  PUBLIC SCAN METHODS — callable from /crafty scan command
    // =====================================================================

    /**
     * Scans a block and sends the AI analysis to the player.
     * Can be called from the /crafty scan command.
     */
    public void scanBlock(Player player, Block block) {
        if (!enabled) {
            adapter.sendMessage(player, "&7[CraftyAI] Vision scanning is disabled by this server.");
            return;
        }
        String context = String.format(
                "Vision Scan | Type: Block | Material: %s | Location: %d,%d,%d | Biome: %s",
                getMaterialName(block.getType()),
                block.getX(), block.getY(), block.getZ(),
                block.getBiome().name()
        );

        processScan(player, "Analyze this block: " + getMaterialName(block.getType()), context);
    }

    /**
     * Scans an entity and sends the AI analysis to the player.
     * Can be called from the /crafty scan command.
     */
    public void scanEntity(Player player, Entity entity) {
        if (!enabled) {
            adapter.sendMessage(player, "&7[CraftyAI] Vision scanning is disabled by this server.");
            return;
        }
        String health = "N/A";
        if (entity instanceof LivingEntity) {
            health = String.valueOf((int) ((LivingEntity) entity).getHealth());
        }

        String context = String.format(
                "Vision Scan | Type: Entity | Entity: %s | Name: %s | Health: %s",
                getEntityName(entity.getType()),
                entity.getCustomName() != null ? entity.getCustomName() : entity.getName(),
                health
        );

        processScan(player, "Analyze this entity: " + getEntityName(entity.getType()), context);
    }

    // =====================================================================
    //  INTERNAL SCAN PROCESSING
    // =====================================================================

    private void processScan(final Player p, final String question, String context) {
        adapter.sendActionBar(p, "&b&lSCANNING TARGET...");
        adapter.playSound(p, plugin.getConfig().getString("chat.sounds.thinking", "BLOCK_NOTE_BLOCK_CHIME"));

        if (engine == null) {
            adapter.sendMessage(p, "&c[" + plugin.getAiName() + "] Neural engine not available.");
            adapter.sendActionBar(p, "&c&lSCAN FAILED");
            return;
        }

        List<Map<String, String>> history = plugin.getConversations().getFormattedHistory(p.getUniqueId());

        engine.ask(p, question, context, history, new CraftyEngine.Callback() {
            @Override
            public void onSuccess(String response) {
                adapter.runSync(new Runnable() {
                    public void run() {
                        if (!p.isOnline()) return;
                        String answer = engine.parseAnswer(response);
                        if (answer != null && !answer.isEmpty()) {
                            String format = plugin.getConfig().getString("chat.format", "&b[{name}] &7> &f{response}");
                            adapter.sendMessage(p, format
                                    .replace("{name}", plugin.getAiName())
                                    .replace("{response}", answer));
                            plugin.getConversations().addInteraction(p.getUniqueId(), question, answer);
                        }
                        adapter.sendActionBar(p, "&a&lSCAN COMPLETE");
                        adapter.playSound(p, plugin.getConfig().getString("chat.sounds.success", "ENTITY_EXPERIENCE_ORB_PICKUP"));
                    }
                });
            }

            @Override
            public void onFailure(String error) {
                adapter.runSync(new Runnable() {
                    public void run() {
                        if (!p.isOnline()) return;
                        adapter.sendActionBar(p, "&c&lSCAN FAILED");
                        adapter.sendMessage(p, "&c[" + plugin.getAiName() + "] " + error);
                        adapter.playSound(p, plugin.getConfig().getString("chat.sounds.error", "BLOCK_NOTE_BLOCK_CHIME"));
                    }
                });
            }
        });
    }

    private boolean isOnCooldown(Player p) {
        long cooldownMs = scanCooldownSec * 1000L;
        long now = System.currentTimeMillis();
        final long[] result = {0};
        cooldowns.compute(p.getUniqueId(), (key, last) -> {
            if (last == null || (now - last) >= cooldownMs) {
                result[0] = 0;
                return now;
            }
            result[0] = last;
            return last;
        });
        if (result[0] != 0 && (now - result[0]) < cooldownMs) {
            adapter.sendActionBar(p, "&cScanner recharging...");
            return true;
        }
        return false;
    }

    private String getMaterialName(Material m) {
        String cached = materialCache.get(m);
        if (cached != null) return cached;
        String name = m.name().toLowerCase().replace("_", " ");
        materialCache.put(m, name);
        return name;
    }

    private String getEntityName(EntityType t) {
        String cached = entityCache.get(t);
        if (cached != null) return cached;
        String name = t.name().toLowerCase().replace("_", " ");
        entityCache.put(t, name);
        return name;
    }
}
