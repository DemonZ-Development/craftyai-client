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

public class ForgeVisionScannerProvider implements VisionScanner.VisionScannerProvider {

    @Override
    public VisionScanner.ScanResult scan(Object playerObj, Object worldObj) {
        if (!(playerObj instanceof Player) || !(worldObj instanceof Level)) {
            return new VisionScanner.ScanResult();
        }

        Player player = (Player) playerObj;
        Level world = (Level) worldObj;
        VisionScanner.ScanResult result = new VisionScanner.ScanResult();

        result.scanTarget = getScanTarget(player, world);

        scanNearbyBlocks(player, world, result);

        scanNearbyEntities(player, world, result);

        result.biome = getBiomeName(player, world);

        result.timeOfDay = getTimeOfDay(world);

        result.weather = getWeather(world);

        result.health = (int) player.getHealth();
        result.maxHealth = (int) player.getMaxHealth();
        result.foodLevel = player.getFoodData().getFoodLevel();

        scanInventory(player, result);

        result.canFly = player.getAbilities().mayfly;
        result.difficulty = world.getDifficulty().getKey();
        result.dimension = world.dimension().location().getPath();
        result.serverBrand = "forge";

        if (player instanceof net.minecraft.server.level.ServerPlayer) {
            net.minecraft.server.level.ServerPlayer sp = (net.minecraft.server.level.ServerPlayer) player;
            result.hasOp = sp.hasPermissions(2);
            result.cheatsEnabled = sp.hasPermissions(2);
            result.gameMode = sp.gameMode.getGameModeForPlayer().getName();
            result.pvpEnabled = sp.getServer().isPvpAllowed();
            result.worldType = sp.getServer().isDedicatedServer() ? "dedicated" : "singleplayer";

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

    private VisionScanner.TargetInfo getScanTarget(Player player, Level world) {
        try {
            net.minecraft.world.phys.HitResult hit = player.pick(6.0D, 1.0F, false);
            net.minecraft.world.phys.Vec3 start = player.getEyePosition();
            net.minecraft.world.phys.Vec3 direction = player.getViewVector(1.0F).scale(6.0D);
            double limit = hit == null ? 36.0D : start.distanceToSqr(hit.getLocation());
            net.minecraft.world.phys.EntityHitResult entityHit = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(
                    player, start, start.add(direction), player.getBoundingBox().expandTowards(direction).inflate(1.0D),
                    entity -> !entity.isSpectator() && entity.isPickable(), limit);
            if (entityHit != null) hit = entityHit;
            if (hit == null || hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS) return null;

            BlockPos playerPos = player.blockPosition();
            int dist = (int) Math.round(player.getEyePosition().distanceTo(hit.getLocation()));

            if (hit instanceof net.minecraft.world.phys.BlockHitResult) {
                BlockPos pos = ((net.minecraft.world.phys.BlockHitResult) hit).getBlockPos();
                BlockState state = world.getBlockState(pos);
                ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(state.getBlock());
                VisionScanner.TargetInfo target = new VisionScanner.TargetInfo(
                        "block", blockId != null ? blockId.getPath() : "unknown", dist,
                        (pos.getX() - playerPos.getX()) + "," + (pos.getY() - playerPos.getY()) + "," + (pos.getZ() - playerPos.getZ()));
                for (Map.Entry<net.minecraft.world.level.block.state.properties.Property<?>, Comparable<?>> entry : state.getValues().entrySet()) {
                    try {
                        target.properties.put(entry.getKey().getName(), String.valueOf(entry.getValue()));
                    } catch (Exception ignored) {}
                }
                return target;
            }
            if (hit instanceof net.minecraft.world.phys.EntityHitResult) {
                Entity entity = ((net.minecraft.world.phys.EntityHitResult) hit).getEntity();
                EntityType<?> type = entity.getType();
                ResourceLocation typeId = ForgeRegistries.ENTITY_TYPES.getKey(type);
                boolean hostile = entity instanceof Monster;
                VisionScanner.TargetInfo target = new VisionScanner.TargetInfo(
                        "entity",
                        entity.getName().getString(),
                        dist,
                        (int) (entity.getX() - player.getX()) + "," + (int) (entity.getY() - player.getY()) + "," + (int) (entity.getZ() - player.getZ()));
                target.properties.put("type", typeId != null ? typeId.getPath() : "unknown");
                target.properties.put("temperament", hostile ? "hostile" : "passive");
                if (entity instanceof LivingEntity living) {
                    target.properties.put("health", (int) living.getHealth() + "/" + (int) living.getMaxHealth());
                }
                return target;
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private void scanNearbyBlocks(Player player, Level world, VisionScanner.ScanResult result) {
        BlockPos playerPos = player.blockPosition();
        int radius = 2;

        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    if (x == 0 && y == 0 && z == 0) continue;

                    BlockPos pos = playerPos.offset(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    Block block = state.getBlock();

                    ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(block);
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

            double distance = entity.position().distanceTo(player.position());

            boolean isHostile = entity instanceof Monster;

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
            String biomeName = world.getBiome(player.blockPosition())
                    .unwrapKey().map(key -> key.location().getPath()).orElse("unknown");
            return new VisionScanner.BiomeInfo(biomeName);
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

        for (int i = 0; i < Math.min(36, player.getInventory().getContainerSize()); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                String itemName = stack.getHoverName().getString();
                inventory.put(itemName, inventory.getOrDefault(itemName, 0) + stack.getCount());
            }
        }

        for (net.minecraft.world.entity.EquipmentSlot slot : new net.minecraft.world.entity.EquipmentSlot[]{
                net.minecraft.world.entity.EquipmentSlot.HEAD,
                net.minecraft.world.entity.EquipmentSlot.CHEST,
                net.minecraft.world.entity.EquipmentSlot.LEGS,
                net.minecraft.world.entity.EquipmentSlot.FEET,
                net.minecraft.world.entity.EquipmentSlot.OFFHAND}) {
            ItemStack equip = player.getItemBySlot(slot);
            if (!equip.isEmpty()) {
                String itemName = equip.getHoverName().getString();
                inventory.put(itemName, inventory.getOrDefault(itemName, 0) + equip.getCount());
            }
        }

        result.inventory = inventory;
    }
}
