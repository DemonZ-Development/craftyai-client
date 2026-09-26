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

public class FabricVisionScannerProvider implements VisionScanner.VisionScannerProvider {

    @Override
    public VisionScanner.ScanResult scan(Object playerObj, Object worldObj) {
        if (!(playerObj instanceof PlayerEntity) || !(worldObj instanceof World)) {
            return new VisionScanner.ScanResult();
        }

        PlayerEntity player = (PlayerEntity) playerObj;
        World world = (World) worldObj;
        VisionScanner.ScanResult result = new VisionScanner.ScanResult();

        result.scanTarget = getScanTarget(player, world);

        scanNearbyBlocks(player, world, result);

        scanNearbyEntities(player, world, result);

        result.biome = getBiomeName(player, world);

        result.timeOfDay = getTimeOfDay(world);

        result.weather = getWeather(world);

        result.health = (int) player.getHealth();
        result.foodLevel = player.getHungerManager().getFoodLevel();

        scanInventory(player, result);

        result.hasOp = player.hasPermissionLevel(2);
        result.canFly = player.getAbilities().allowFlying;
        result.cheatsEnabled = player.hasPermissionLevel(2);
        result.difficulty = world.getDifficulty().getName();
        result.pvpEnabled = world instanceof ServerWorld && world.getServer().isPvpEnabled();
        result.dimension = world.getRegistryKey().getValue().getPath();
        result.serverBrand = "fabric";
        result.worldType = world.getServer() == null ? "client" : (world.getServer().isDedicated() ? "dedicated" : "singleplayer");

        if (player instanceof net.minecraft.server.network.ServerPlayerEntity) {
            net.minecraft.server.network.ServerPlayerEntity sp = (net.minecraft.server.network.ServerPlayerEntity) player;
            result.gameMode = sp.interactionManager.getGameMode().getName();
        }

        player.getStatusEffects().forEach(effect -> {
            String effectName = effect.getEffectType().getName().getString();
            int amplifier = effect.getAmplifier() + 1;
            int duration = effect.getDuration() / 20;
            result.activeEffects.add(effectName + " " + amplifier + " (" + duration + "s)");
        });

        return result;
    }

    private VisionScanner.TargetInfo getScanTarget(PlayerEntity player, World world) {
        try {
            net.minecraft.util.hit.HitResult hit = player.raycast(6.0D, 1.0F, false);
            net.minecraft.util.math.Vec3d start = player.getEyePos();
            net.minecraft.util.math.Vec3d direction = player.getRotationVec(1.0F).multiply(6.0D);
            double limit = hit == null ? 36.0D : start.squaredDistanceTo(hit.getPos());
            net.minecraft.util.hit.EntityHitResult entityHit = net.minecraft.entity.projectile.ProjectileUtil.raycast(
                    player, start, start.add(direction), player.getBoundingBox().stretch(direction).expand(1.0D),
                    entity -> !entity.isSpectator() && entity.canHit(), limit);
            if (entityHit != null) hit = entityHit;
            if (hit == null || hit.getType() == net.minecraft.util.hit.HitResult.Type.MISS) return null;

            BlockPos playerPos = player.getBlockPos();
            int dist = (int) Math.round(player.getEyePos().distanceTo(hit.getPos()));

            if (hit instanceof net.minecraft.util.hit.BlockHitResult) {
                BlockPos pos = ((net.minecraft.util.hit.BlockHitResult) hit).getBlockPos();
                BlockState state = world.getBlockState(pos);
                Identifier blockId = Registries.BLOCK.getId(state.getBlock());
                String name = blockId != null ? blockId.getPath() : "unknown";
                VisionScanner.TargetInfo target = new VisionScanner.TargetInfo(
                        "block", name, dist,
                        (pos.getX() - playerPos.getX()) + "," + (pos.getY() - playerPos.getY()) + "," + (pos.getZ() - playerPos.getZ()));
                for (Map.Entry<net.minecraft.state.property.Property<?>, Comparable<?>> entry : state.getEntries().entrySet()) {
                    try {
                        target.properties.put(entry.getKey().getName(), String.valueOf(entry.getValue()));
                    } catch (Exception ignored) {}
                }
                return target;
            }
            if (hit instanceof net.minecraft.util.hit.EntityHitResult) {
                Entity entity = ((net.minecraft.util.hit.EntityHitResult) hit).getEntity();
                Identifier typeId = Registries.ENTITY_TYPE.getId(entity.getType());
                boolean hostile = entity instanceof MobEntity && !(entity instanceof PassiveEntity);
                VisionScanner.TargetInfo target = new VisionScanner.TargetInfo(
                        "entity",
                        entity.getName().getString(),
                        dist,
                        (int) (entity.getX() - player.getX()) + "," + (int) (entity.getY() - player.getY()) + "," + (int) (entity.getZ() - player.getZ()));
                target.properties.put("type", typeId != null ? typeId.getPath() : "unknown");
                target.properties.put("temperament", hostile ? "hostile" : "passive");
                if (entity instanceof LivingEntity) {
                    LivingEntity living = (LivingEntity) entity;
                    target.properties.put("health", String.valueOf((int) living.getHealth()) + "/" + String.valueOf((int) living.getMaxHealth()));
                }
                return target;
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private void scanNearbyBlocks(PlayerEntity player, World world, VisionScanner.ScanResult result) {
        BlockPos playerPos = player.getBlockPos();
        int radius = 2;

        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    if (x == 0 && y == 0 && z == 0) continue;

                    BlockPos pos = playerPos.add(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    Block block = state.getBlock();

                    Identifier blockId = Registries.BLOCK.getId(block);
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

    private void scanNearbyEntities(PlayerEntity player, World world, VisionScanner.ScanResult result) {
        List<Entity> entities = world.getOtherEntities(player, player.getBoundingBox().expand(16.0));

        for (Entity entity : entities) {
            EntityType<?> type = entity.getType();
            Identifier typeId = Registries.ENTITY_TYPE.getId(type);
            String entityName = typeId != null ? typeId.getPath() : "unknown";
            String displayName = entity.getName().getString();

            double distance = entity.getPos().distanceTo(player.getPos());

            boolean isHostile = false;
            if (entity instanceof MobEntity && !(entity instanceof PassiveEntity)) {
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

    private VisionScanner.BiomeInfo getBiomeName(PlayerEntity player, World world) {
        try {
            Biome biome = world.getBiome(player.getBlockPos()).value();
            Identifier biomeId = world.getRegistryManager().get(RegistryKeys.BIOME).getId(biome);
            return new VisionScanner.BiomeInfo(biomeId != null ? biomeId.getPath() : "unknown");
        } catch (Exception e) {
            return new VisionScanner.BiomeInfo("unknown");
        }
    }

    private String getTimeOfDay(World world) {
        long time = world.getTimeOfDay() % 24000;
        if (time < 6000) return "morning";
        if (time < 12000) return "day";
        if (time < 18000) return "evening";
        return "night";
    }

    private String getWeather(World world) {
        if (world.isRaining()) {
            return world.isThundering() ? "thunderstorm" : "rain";
        }
        return "clear";
    }

    private void scanInventory(PlayerEntity player, VisionScanner.ScanResult result) {
        Map<String, Integer> inventory = new HashMap<>();

        for (int i = 0; i < Math.min(36, player.getInventory().size()); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isEmpty()) {
                String itemName = stack.getItem().getName().getString();
                inventory.put(itemName, inventory.getOrDefault(itemName, 0) + stack.getCount());
            }
        }

        for (ItemStack armor : player.getInventory().armor) {
            if (!armor.isEmpty()) {
                String itemName = armor.getItem().getName().getString();
                inventory.put(itemName, inventory.getOrDefault(itemName, 0) + armor.getCount());
            }
        }

        ItemStack offhand = player.getInventory().offHand.get(0);
        if (!offhand.isEmpty()) {
            String itemName = offhand.getItem().getName().getString();
            inventory.put(itemName, inventory.getOrDefault(itemName, 0) + offhand.getCount());
        }

        result.inventory = inventory;
    }
}
