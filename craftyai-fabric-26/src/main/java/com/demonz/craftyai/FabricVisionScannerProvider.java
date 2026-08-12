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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fabric-specific implementation of Vision Scanner (Mojang Mappings for MC 26.x)
 */
public class FabricVisionScannerProvider implements VisionScanner.VisionScannerProvider {

    @Override
    public VisionScanner.ScanResult scan(Object playerObj, Object worldObj) {
        if (!(playerObj instanceof Player) || !(worldObj instanceof ServerLevel)) {
            return new VisionScanner.ScanResult();
        }

        Player player = (Player) playerObj;
        ServerLevel world = (ServerLevel) worldObj;
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
        result.foodLevel = player.getFoodData().getFoodLevel();

        // Scan inventory
        scanInventory(player, result);

        // --- Permission & World Context ---
        result.hasOp = isOp(player, world);
        result.canFly = player.getAbilities().mayfly;
        result.cheatsEnabled = result.hasOp;
        result.difficulty = world.getDifficulty().name().toLowerCase();
        result.pvpEnabled = world.isPvpAllowed();
        result.dimension = world.dimension().identifier().getPath();
        result.serverBrand = "fabric";
        result.worldType = world.getServer().isDedicatedServer() ? "dedicated" : "singleplayer";

        // Gamemode
        if (player instanceof ServerPlayer) {
            ServerPlayer sp = (ServerPlayer) player;
            result.gameMode = sp.gameMode.getGameModeForPlayer().getName();
        }

        // Active potion effects
        player.getActiveEffects().forEach(effect -> {
            String effectName = effect.getEffect().value().getDisplayName().getString();
            int amplifier = effect.getAmplifier() + 1;
            int duration = effect.getDuration() / 20; // ticks to seconds
            result.activeEffects.add(effectName + " " + amplifier + " (" + duration + "s)");
        });

        return result;
    }

    private void scanNearbyBlocks(Player player, ServerLevel world, VisionScanner.ScanResult result) {
        BlockPos playerPos = player.blockPosition();
        int radius = 2; // 5x5x5 area

        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    if (x == 0 && y == 0 && z == 0) continue; 

                    BlockPos pos = playerPos.offset(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    Block block = state.getBlock();

                    Identifier blockId = BuiltInRegistries.BLOCK.getKey(block);
                    String blockName = blockId != null ? blockId.getPath() : "unknown";

                    int distance = Math.abs(x) + Math.abs(y) + Math.abs(z);

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

    private void scanNearbyEntities(Player player, ServerLevel world, VisionScanner.ScanResult result) {
        List<Entity> entities = world.getEntities(player, player.getBoundingBox().inflate(16.0));

        for (Entity entity : entities) {
            EntityType<?> type = entity.getType();
            Identifier typeId = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            String entityName = typeId != null ? typeId.getPath() : "unknown";
            String displayName = entity.getName().getString();

            double distance = entity.position().distanceTo(player.position());

            boolean isHostile = false;
            if (entity instanceof Mob && !(entity instanceof Animal)) {
                isHostile = true;
            }

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

    private VisionScanner.BiomeInfo getBiomeName(Player player, ServerLevel world) {
        try {
            String biomeName = world.getBiome(player.blockPosition()).unwrapKey().map(key -> key.identifier().getPath()).orElse("unknown");
            return new VisionScanner.BiomeInfo(biomeName);
        } catch (Exception e) {
            return new VisionScanner.BiomeInfo("unknown");
        }
    }

    private String getTimeOfDay(ServerLevel world) {
        long time = world.getDefaultClockTime() % 24000;
        if (time < 6000) return "morning";
        if (time < 12000) return "day";
        if (time < 18000) return "evening";
        return "night";
    }

    private String getWeather(ServerLevel world) {
        if (world.isRaining()) {
            return world.isThundering() ? "thunderstorm" : "rain";
        }
        return "clear";
    }

    private void scanInventory(Player player, VisionScanner.ScanResult result) {
        Map<String, Integer> inventory = new HashMap<>();

        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                String itemName = stack.getItem().getName(stack).getString();
                inventory.put(itemName, inventory.getOrDefault(itemName, 0) + stack.getCount());
            }
        }

        addEquipmentItem(player.getItemBySlot(EquipmentSlot.HEAD), inventory);
        addEquipmentItem(player.getItemBySlot(EquipmentSlot.CHEST), inventory);
        addEquipmentItem(player.getItemBySlot(EquipmentSlot.LEGS), inventory);
        addEquipmentItem(player.getItemBySlot(EquipmentSlot.FEET), inventory);
        addEquipmentItem(player.getItemBySlot(EquipmentSlot.OFFHAND), inventory);

        result.inventory = inventory;
    }

    private boolean isOp(Player player, ServerLevel world) {
        return player instanceof ServerPlayer serverPlayer && world.getServer().getPlayerList().isOp(serverPlayer.nameAndId());
    }

    private void addEquipmentItem(ItemStack stack, Map<String, Integer> inventory) {
        if (!stack.isEmpty()) {
            String itemName = stack.getItem().getName(stack).getString();
            inventory.put(itemName, inventory.getOrDefault(itemName, 0) + stack.getCount());
        }
    }
}
