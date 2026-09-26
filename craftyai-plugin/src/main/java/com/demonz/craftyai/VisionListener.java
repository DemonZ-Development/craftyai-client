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

    @EventHandler
    public void onBlockInteract(PlayerInteractEvent e) {

        try {
            if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        } catch (Throwable ignored) {

        }
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        if (!shouldActivateScan(e.getPlayer())) return;

        Player p = e.getPlayer();
        if (!p.hasPermission("crafty.vision") || isOnCooldown(p)) return;

        Block b = e.getClickedBlock();
        if (b == null || b.getType() == Material.AIR) return;

        scanBlock(p, b);
    }

    @EventHandler
    public void onEntityInteract(PlayerInteractEntityEvent e) {

        try {
            if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        } catch (Throwable ignored) {

        }

        if (!shouldActivateScan(e.getPlayer())) return;

        Player p = e.getPlayer();
        if (!p.hasPermission("crafty.vision") || isOnCooldown(p)) return;

        scanEntity(p, e.getRightClicked());
    }

    public void scanBlock(Player player, Block block) {
        if (!enabled) {
            adapter.sendMessage(player, "&7[CraftyAI] Vision scanning is disabled by this server.");
            return;
        }
        StringBuilder ctx = new StringBuilder();
        ctx.append("[VISION SCAN]\n");
        ctx.append("Target Type: block\n");
        ctx.append("Target Name: ").append(getMaterialName(block.getType())).append("\n");
        if (block.getState() instanceof org.bukkit.block.Container) {
            try {
                org.bukkit.inventory.Inventory inv = ((org.bukkit.block.Container) block.getState()).getInventory();
                Map<String, Integer> contents = new LinkedHashMap<String, Integer>();
                for (org.bukkit.inventory.ItemStack item : inv.getContents()) {
                    if (item != null && item.getType() != Material.AIR) {
                        String itemName = getMaterialName(item.getType());
                        contents.merge(itemName, item.getAmount(), Integer::sum);
                    }
                }
                if (!contents.isEmpty()) {
                    ctx.append("Contents: ");
                    int n = 0;
                    for (Map.Entry<String, Integer> e : contents.entrySet()) {
                        if (n++ >= 8) { ctx.append("\u2026"); break; }
                        if (n > 1) ctx.append(", ");
                        ctx.append(e.getKey()).append(" x").append(e.getValue());
                    }
                    ctx.append("\n");
                }
            } catch (Throwable ignored) {}
        }
        ctx.append(com.demonz.craftyai.common.ScanFlow.privacyRules());

        processScan(player, "Analyze this block: " + getMaterialName(block.getType()), ctx.toString());
    }

    public void scanEntity(Player player, Entity entity) {
        if (!enabled) {
            adapter.sendMessage(player, "&7[CraftyAI] Vision scanning is disabled by this server.");
            return;
        }
        String health = "N/A";
        boolean hostile = false;
        boolean tameable = false;
        if (entity instanceof LivingEntity) {
            LivingEntity living = (LivingEntity) entity;
            health = String.valueOf((int) living.getHealth());
            hostile = living instanceof org.bukkit.entity.Monster;
            tameable = living instanceof org.bukkit.entity.Tameable;
        }

        StringBuilder ctx = new StringBuilder();
        ctx.append("[VISION SCAN]\n");
        ctx.append("Target Type: entity\n");
        ctx.append("Target Name: ").append(getEntityName(entity.getType())).append("\n");
        ctx.append("Display Name: ").append(entity.getCustomName() != null ? entity.getCustomName() : entity.getName()).append("\n");
        ctx.append("Health: ").append(health).append("\n");
        ctx.append("Temperament: ").append(hostile ? "hostile" : tameable ? "tameable" : "passive").append("\n");
        ctx.append(com.demonz.craftyai.common.ScanFlow.privacyRules());

        processScan(player, "Analyze this entity: " + getEntityName(entity.getType()), ctx.toString());
    }

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
