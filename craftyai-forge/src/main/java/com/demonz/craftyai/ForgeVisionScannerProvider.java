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

import com.demonz.craftyai.common.VisionScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Forge-specific implementation of Vision Scanner
 * Scans the player's surroundings and provides context to the AI
 */
public class ForgeVisionScannerProvider implements VisionScanner.VisionScannerProvider {

    @Override
    public VisionScanner.ScanResult scan(Object playerObj, Object worldObj) {
        if (!(playerObj instanceof Player) || !(worldObj instanceof Level)) {
            return new VisionScanner.ScanResult();
        }

        Player player = (Player) playerObj;
        Level world = (Level) worldObj;
        VisionScanner.ScanResult result = new VisionScanner.ScanResult();

        // Scan blocks around the player (5x5x5 area)
        scanNearbyBlocks(player, world, result);

        // Scan entities around the player (16 block radius)
        scanNearbyEntities(player, world, result);

        // Get biome information
        result.biome = getBiomeName(player, world);

        // Get time of day
        result.timeOfDay = getTimeOfDay(world);

        // Get weather
        result.weather = getWeather(world);

        // Get player status
        result.health = (int) player.getHealth();
        result.maxHealth = (int) player.getMaxHealth();
        result.foodLevel = player.getFoodData().getFoodLevel();

        // Scan inventory
        scanInventory(player, result);

        // --- Permission & World Context ---
        result.canFly = player.getAbilities().mayfly;
        result.difficulty = world.getDifficulty().getKey();
        result.dimension = world.dimension().location().getPath();
        result.serverBrand = "forge";

        if (player instanceof net.minecraft.server.level.ServerPlayer) {
            net.minecraft.server.level.ServerPlayer sp = (net.minecraft.server.level.ServerPlayer) player;
            result.hasOp = sp.hasPermissions(2);
            result.cheatsEnabled = sp.hasPermissions(2);
            result.gameMode = sp.gameMode.getGameModeForPlayer().getName();
            result.pvpEnabled = sp.server.isPvpAllowed();
            result.worldType = sp.server.isDedicatedServer() ? "dedicated" : "singleplayer";

            // Active potion effects
            sp.getActiveEffects().forEach(effect -> {
                String effectName = String.valueOf(effect.getEffect());
                if (effectName.contains(".")) {
                    effectName = effectName.substring(effectName.lastIndexOf('.') + 1);
                }
                if (effectName.contains(":")) {
                    effectName = effectName.substring(effectName.lastIndexOf(':') + 1);
                }
                int amplifier = effect.getAmplifier() + 1;
                int duration = effect.getDuration() / 20;
                result.activeEffects.add(effectName + " " + amplifier + " (" + duration + "s)");
            });
        }

        return result;
    }

    private void scanNearbyBlocks(Player player, Level world, VisionScanner.ScanResult result) {
        BlockPos playerPos = player.blockPosition();
        int radius = 2; // 5x5x5 area

        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    if (x == 0 && y == 0 && z == 0) continue; // Skip player position

                    BlockPos pos = playerPos.offset(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    Block block = state.getBlock();

                    // Get block name from registry
                    ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(block);
                    String blockName = blockId != null ? blockId.getPath() : "unknown";

                    // Calculate distance
                    int distance = Math.abs(x) + Math.abs(y) + Math.abs(z);

                    // Only include interesting blocks (not air, grass, dirt)
                    if (!blockName.equals("air") && !blockName.equals("grass_block") 
                        && !blockName.equals("dirt") && !blockName.equals("stone")
                        && !blockName.equals("cave_air")) {
                        result.nearbyBlocks.add(new VisionScanner.BlockInfo(
                            blockName,
                            x + "," + y + "," + z,
                            distance
                        ));
                    }
                }
            }
        }
    }

    private void scanNearbyEntities(Player player, Level world, VisionScanner.ScanResult result) {
        List<Entity> entities = new ArrayList<>();
        world.getEntities(player, player.getBoundingBox().inflate(16)).forEach(entity -> {
            if (entity != player) {
                entities.add(entity);
            }
        });

        for (Entity entity : entities) {
            EntityType<?> type = entity.getType();
            ResourceLocation typeId = ForgeRegistries.ENTITY_TYPES.getKey(type);
            String entityName = typeId != null ? typeId.getPath() : "unknown";
            String displayName = entity.getName().getString();

            // Calculate distance
            double distance = entity.position().distanceTo(player.position());

            // Determine if hostile
            boolean isHostile = entity instanceof Monster;

            // Get relative position
            int relX = (int) (entity.getX() - player.getX());
            int relY = (int) (entity.getY() - player.getY());
            int relZ = (int) (entity.getZ() - player.getZ());

            result.nearbyEntities.add(new VisionScanner.EntityInfo(
                entityName,
                displayName,
                relX + "," + relY + "," + relZ,
                (int) distance,
                isHostile
            ));
        }
    }

    private VisionScanner.BiomeInfo getBiomeName(Player player, Level world) {
        try {
            Biome biome = world.getBiome(player.blockPosition()).value();
            ResourceLocation biomeId = world.registryAccess().registryOrThrow(Registries.BIOME).getKey(biome);
            return new VisionScanner.BiomeInfo(biomeId != null ? biomeId.getPath() : "unknown");
        } catch (Exception e) {
            return new VisionScanner.BiomeInfo("unknown");
        }
    }

    private String getTimeOfDay(Level world) {
        long time = world.getDayTime() % 24000;
        if (time < 6000) return "morning";
        if (time < 12000) return "day";
        if (time < 18000) return "evening";
        return "night";
    }

    private String getWeather(Level world) {
        if (world.isRaining()) {
            return world.isThundering() ? "thunderstorm" : "rain";
        }
        return "clear";
    }

    private void scanInventory(Player player, VisionScanner.ScanResult result) {
        Map<String, Integer> inventory = new HashMap<>();

        // Main inventory
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                String itemName = stack.getHoverName().getString();
                inventory.put(itemName, inventory.getOrDefault(itemName, 0) + stack.getCount());
            }
        }

        // Armor
        for (ItemStack armor : player.getInventory().armor) {
            if (!armor.isEmpty()) {
                String itemName = armor.getHoverName().getString();
                inventory.put(itemName, inventory.getOrDefault(itemName, 0) + armor.getCount());
            }
        }

        // Offhand
        ItemStack offhand = player.getInventory().offhand.get(0);
        if (!offhand.isEmpty()) {
            String itemName = offhand.getHoverName().getString();
            inventory.put(itemName, inventory.getOrDefault(itemName, 0) + offhand.getCount());
        }

        result.inventory = inventory;
    }
}
