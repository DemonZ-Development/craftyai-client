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
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.LightType;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fabric-specific implementation of Vision Scanner
 * Scans the player's surroundings and provides context to the AI
 */
public class FabricVisionScannerProvider implements VisionScanner.VisionScannerProvider {

    @Override
    public VisionScanner.ScanResult scan(Object playerObj, Object worldObj) {
        if (!(playerObj instanceof PlayerEntity) || !(worldObj instanceof ServerWorld)) {
            return new VisionScanner.ScanResult();
        }

        PlayerEntity player = (PlayerEntity) playerObj;
        ServerWorld world = (ServerWorld) worldObj;
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
        result.foodLevel = player.getHungerManager().getFoodLevel();

        // Scan inventory
        scanInventory(player, result);

        // --- Permission & World Context ---
        result.hasOp = player.hasPermissionLevel(2);
        result.canFly = player.getAbilities().allowFlying;
        result.cheatsEnabled = player.hasPermissionLevel(2); // On server, OP = cheats
        result.difficulty = world.getDifficulty().getName();
        result.pvpEnabled = world.getServer().isPvpEnabled();
        result.dimension = world.getRegistryKey().getValue().getPath();
        result.serverBrand = "fabric";
        result.worldType = world.getServer().isDedicated() ? "dedicated" : "singleplayer";

        // Gamemode
        if (player instanceof net.minecraft.server.network.ServerPlayerEntity) {
            net.minecraft.server.network.ServerPlayerEntity sp = (net.minecraft.server.network.ServerPlayerEntity) player;
            result.gameMode = sp.interactionManager.getGameMode().getName();
        }

        // Active potion effects
        player.getStatusEffects().forEach(effect -> {
            String effectName = effect.getEffectType().getName().getString();
            int amplifier = effect.getAmplifier() + 1;
            int duration = effect.getDuration() / 20; // ticks to seconds
            result.activeEffects.add(effectName + " " + amplifier + " (" + duration + "s)");
        });

        return result;
    }

    private void scanNearbyBlocks(PlayerEntity player, ServerWorld world, VisionScanner.ScanResult result) {
        BlockPos playerPos = player.getBlockPos();
        int radius = 2; // 5x5x5 area

        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    if (x == 0 && y == 0 && z == 0) continue; // Skip player position

                    BlockPos pos = playerPos.add(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    Block block = state.getBlock();

                    // Get block name from registry
                    Identifier blockId = Registries.BLOCK.getId(block);
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

    private void scanNearbyEntities(PlayerEntity player, ServerWorld world, VisionScanner.ScanResult result) {
        List<Entity> entities = world.getOtherEntities(player, player.getBoundingBox().expand(16.0));

        for (Entity entity : entities) {
            EntityType<?> type = entity.getType();
            Identifier typeId = Registries.ENTITY_TYPE.getId(type);
            String entityName = typeId != null ? typeId.getPath() : "unknown";
            String displayName = entity.getName().getString();

            // Calculate distance
            double distance = entity.getPos().distanceTo(player.getPos());

            // Determine if hostile
            boolean isHostile = false;
            if (entity instanceof MobEntity && !(entity instanceof PassiveEntity)) {
                isHostile = true;
            }

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

    private VisionScanner.BiomeInfo getBiomeName(PlayerEntity player, ServerWorld world) {
        try {
            Biome biome = world.getBiome(player.getBlockPos()).value();
            Identifier biomeId = world.getRegistryManager().get(RegistryKeys.BIOME).getId(biome);
            return new VisionScanner.BiomeInfo(biomeId != null ? biomeId.getPath() : "unknown");
        } catch (Exception e) {
            return new VisionScanner.BiomeInfo("unknown");
        }
    }

    private String getTimeOfDay(ServerWorld world) {
        long time = world.getTimeOfDay() % 24000;
        if (time < 6000) return "morning";
        if (time < 12000) return "day";
        if (time < 18000) return "evening";
        return "night";
    }

    private String getWeather(ServerWorld world) {
        if (world.isRaining()) {
            return world.isThundering() ? "thunderstorm" : "rain";
        }
        return "clear";
    }

    private void scanInventory(PlayerEntity player, VisionScanner.ScanResult result) {
        Map<String, Integer> inventory = new HashMap<>();

        // Main inventory
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isEmpty()) {
                String itemName = stack.getItem().getName().getString();
                inventory.put(itemName, inventory.getOrDefault(itemName, 0) + stack.getCount());
            }
        }

        // Armor
        for (ItemStack armor : player.getInventory().armor) {
            if (!armor.isEmpty()) {
                String itemName = armor.getItem().getName().getString();
                inventory.put(itemName, inventory.getOrDefault(itemName, 0) + armor.getCount());
            }
        }

        // Offhand
        ItemStack offhand = player.getInventory().offHand.get(0);
        if (!offhand.isEmpty()) {
            String itemName = offhand.getItem().getName().getString();
            inventory.put(itemName, inventory.getOrDefault(itemName, 0) + offhand.getCount());
        }

        result.inventory = inventory;
    }
}
