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
package com.demonz.craftyai.common;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class VisionScanner {

    private static VisionScannerProvider provider;

    public static void registerProvider(VisionScannerProvider provider) {
        VisionScanner.provider = provider;
    }

    public static ScanResult scan(Object player, Object world) {
        if (provider == null) {
            return new ScanResult();
        }
        return provider.scan(player, world);
    }

    public static class BlockInfo {
        public String blockType;
        public String position;
        public int distance;
        public Map<String, String> properties = new HashMap<>();

        public BlockInfo(String blockType, String position, int distance) {
            this.blockType = blockType;
            this.position = position;
            this.distance = distance;
        }
    }

    public static class EntityInfo {
        public String entityType;
        public String name;
        public String position;
        public int distance;
        public boolean isHostile;
        public int health;
        public int maxHealth;
        public List<String> effects = new ArrayList<>();

        public EntityInfo(String entityType, String name, String position, int distance, boolean isHostile) {
            this.entityType = entityType;
            this.name = name;
            this.position = position;
            this.distance = distance;
            this.isHostile = isHostile;
            this.health = -1;
            this.maxHealth = -1;
        }
    }

    public static class PlayerInfo {
        public String name;
        public String position;
        public int distance;
        public int health;
        public int maxHealth;
        public String gameMode;

        public PlayerInfo(String name, String position, int distance, String gameMode) {
            this.name = name;
            this.position = position;
            this.distance = distance;
            this.gameMode = gameMode;
            this.health = -1;
            this.maxHealth = -1;
        }
    }

    public static class TargetInfo {
        public String targetType;
        public String name;
        public int distance;
        public String position;
        public Map<String, String> properties = new HashMap<>();

        public TargetInfo(String targetType, String name, int distance, String position) {
            this.targetType = targetType;
            this.name = name;
            this.distance = distance;
            this.position = position;
        }
    }

    public static class BiomeInfo {
        public String name;
        public String temperature;
        public String humidity;
        public List<String> features = new ArrayList<>();

        public BiomeInfo(String name) {
            this.name = name;
            this.temperature = "unknown";
            this.humidity = "unknown";
        }
    }

    public static class ScanResult {
        public List<BlockInfo> nearbyBlocks = new ArrayList<>();
        public List<EntityInfo> nearbyEntities = new ArrayList<>();
        public List<PlayerInfo> nearbyPlayers = new ArrayList<>();
        public Map<String, Integer> inventory = new HashMap<>();
        public Map<String, Integer> armor = new HashMap<>();
        public Map<String, Integer> heldItem = new HashMap<>();
        public String timeOfDay = "unknown";
        public String weather = "unknown";
        public BiomeInfo biome = new BiomeInfo("unknown");
        public int health = 20;
        public int maxHealth = 20;
        public int foodLevel = 20;
        public int maxFoodLevel = 20;
        public float experienceLevel = 0;
        public int experiencePoints = 0;
        public String dimension = "overworld";

        public TargetInfo scanTarget;

        public String gameMode = "unknown";
        public boolean hasOp = false;
        public boolean cheatsEnabled = false;
        public String difficulty = "unknown";
        public boolean pvpEnabled = true;
        public boolean canFly = false;
        public String worldType = "unknown";
        public List<String> activeEffects = new ArrayList<>();
        public String serverBrand = "unknown";

        public String toContextString() {
            StringBuilder sb = new StringBuilder();

            sb.append("[PERMISSIONS]\n");
            sb.append("GameMode: ").append(gameMode).append("\n");
            sb.append("OP Status: ").append(hasOp ? "YES \u2014 has operator permissions" : "NO \u2014 does NOT have OP permissions").append("\n");
            sb.append("Cheats: ").append(cheatsEnabled ? "enabled" : "disabled").append("\n");
            sb.append("Can Fly: ").append(canFly ? "yes" : "no").append("\n");
            sb.append("World Type: ").append(worldType).append("\n");
            sb.append("Difficulty: ").append(difficulty).append("\n");
            sb.append("PVP: ").append(pvpEnabled ? "enabled" : "disabled").append("\n");
            if (!serverBrand.equals("unknown")) {
                sb.append("Server: ").append(serverBrand).append("\n");
            }
            sb.append("\n");

            if (scanTarget != null && !"none".equals(scanTarget.targetType)) {
                sb.append("[SCAN TARGET]\n");
                sb.append("Type: ").append(scanTarget.targetType).append("\n");
                sb.append("Name: ").append(scanTarget.name).append("\n");
                if (scanTarget.distance >= 0) {
                    sb.append("Distance: ").append(scanTarget.distance).append(" block(s)\n");
                }
                if (!scanTarget.properties.isEmpty()) {
                    sb.append("Properties: ");
                    int n = 0;
                    for (Map.Entry<String, String> e : scanTarget.properties.entrySet()) {
                        if (n++ > 0) sb.append(", ");
                        sb.append(e.getKey()).append("=").append(e.getValue());
                    }
                    sb.append("\n");
                }
                sb.append("\n");
            }

            sb.append("[ENVIRONMENT]\n");
            sb.append("Biome: ").append(biome.name);
            if (!biome.temperature.equals("unknown") || !biome.humidity.equals("unknown")) {
                sb.append(" (Temp: ").append(biome.temperature).append(", Humidity: ").append(biome.humidity).append(")");
            }
            sb.append("\n");
            sb.append("Dimension: ").append(dimension).append("\n");
            sb.append("Time: ").append(timeOfDay).append(", Weather: ").append(weather).append("\n");

            sb.append("\n[PLAYER STATUS]\n");
            sb.append("Health: ").append(health).append("/").append(maxHealth).append(", Hunger: ").append(foodLevel).append("/").append(maxFoodLevel).append("\n");
            sb.append("Experience: Level ").append(experienceLevel).append(" (").append(experiencePoints).append(" XP)\n");

            if (!activeEffects.isEmpty()) {
                sb.append("Active Effects: ").append(String.join(", ", activeEffects)).append("\n");
            }

            if (!heldItem.isEmpty()) {
                sb.append("Held item: ");
                for (Map.Entry<String, Integer> entry : heldItem.entrySet()) {
                    sb.append(entry.getKey()).append(" x").append(entry.getValue());
                }
                sb.append("\n");
            }

            if (!armor.isEmpty()) {
                sb.append("Armor: ");
                for (Map.Entry<String, Integer> entry : armor.entrySet()) {
                    sb.append(entry.getKey()).append(" x").append(entry.getValue()).append(", ");
                }
                sb.append("\n");
            }

            if (!nearbyBlocks.isEmpty()) {
                sb.append("\n[NEARBY BLOCKS] (").append(nearbyBlocks.size()).append("): ");
                int count = 0;
                for (BlockInfo block : nearbyBlocks) {
                    if (count++ < 10) {
                        sb.append(block.blockType);
                        if (block.distance >= 0) sb.append(" d").append(block.distance);
                        sb.append(", ");
                    }
                }
                if (nearbyBlocks.size() > 10) {
                    sb.append("... and ").append(nearbyBlocks.size() - 10).append(" more");
                }
                sb.append("\n");
            }

            if (!nearbyEntities.isEmpty()) {
                sb.append("[NEARBY ENTITIES] (").append(nearbyEntities.size()).append("): ");
                int count = 0;
                for (EntityInfo entity : nearbyEntities) {
                    if (count++ < 10) {
                        sb.append(entity.name).append(" (").append(entity.entityType).append(")");
                        if (entity.health > 0) {
                            sb.append(" HP:").append(entity.health).append("/").append(entity.maxHealth);
                        }
                        if (entity.distance >= 0) {
                            sb.append(" d").append(entity.distance);
                            sb.append(entity.isHostile ? " [hostile]" : "");
                        }
                        sb.append(", ");
                    }
                }
                if (nearbyEntities.size() > 10) {
                    sb.append("... and ").append(nearbyEntities.size() - 10).append(" more");
                }
                sb.append("\n");
            }

            if (!nearbyPlayers.isEmpty()) {
                sb.append("[NEARBY PLAYERS] (").append(nearbyPlayers.size()).append("): ");
                for (PlayerInfo player : nearbyPlayers) {
                    sb.append(player.name).append(" (").append(player.gameMode).append(")");
                    if (player.health > 0) {
                        sb.append(" HP:").append(player.health).append("/").append(player.maxHealth);
                    }
                    sb.append(", ");
                }
                sb.append("\n");
            }

            if (!inventory.isEmpty()) {
                sb.append("\n[INVENTORY]: ");
                int count = 0;
                for (Map.Entry<String, Integer> entry : inventory.entrySet()) {
                    if (count > 0) sb.append(", ");
                    if (count >= 8) {
                        sb.append("...");
                        break;
                    }
                    sb.append(entry.getKey()).append(" x").append(entry.getValue());
                    count++;
                }
                sb.append("\n");
            }

            return sb.toString();
        }
    }

    public interface VisionScannerProvider {
        ScanResult scan(Object player, Object world);
    }
}
