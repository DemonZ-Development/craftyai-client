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

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Unit tests for VisionScanner
 */
public class VisionScannerTest {

    @Test
    public void testBlockInfoCreation() {
        VisionScanner.BlockInfo block = new VisionScanner.BlockInfo("stone", "1,2,3", 3);
        
        assertEquals("stone", block.blockType);
        assertEquals("1,2,3", block.position);
        assertEquals(3, block.distance);
    }

    @Test
    public void testEntityInfoCreation() {
        VisionScanner.EntityInfo entity = new VisionScanner.EntityInfo("zombie", "Zombie", "5,0,5", 5, true);
        
        assertEquals("zombie", entity.entityType);
        assertEquals("Zombie", entity.name);
        assertEquals("5,0,5", entity.position);
        assertEquals(5, entity.distance);
        assertTrue(entity.isHostile);
    }

    @Test
    public void testScanResultCreation() {
        VisionScanner.ScanResult result = new VisionScanner.ScanResult();
        
        assertNotNull(result.nearbyBlocks);
        assertNotNull(result.nearbyEntities);
        assertNotNull(result.inventory);
        assertTrue(result.nearbyBlocks.isEmpty());
        assertTrue(result.nearbyEntities.isEmpty());
        assertTrue(result.inventory.isEmpty());
    }

    @Test
    public void testScanResultWithBlocks() {
        VisionScanner.ScanResult result = new VisionScanner.ScanResult();
        result.nearbyBlocks.add(new VisionScanner.BlockInfo("cobblestone", "1,0,0", 1));
        result.nearbyBlocks.add(new VisionScanner.BlockInfo("oak_log", "0,1,0", 1));
        
        assertEquals(2, result.nearbyBlocks.size());
        assertEquals("cobblestone", result.nearbyBlocks.get(0).blockType);
    }

    @Test
    public void testScanResultWithEntities() {
        VisionScanner.ScanResult result = new VisionScanner.ScanResult();
        result.nearbyEntities.add(new VisionScanner.EntityInfo("pig", "Pig", "2,0,2", 2, false));
        result.nearbyEntities.add(new VisionScanner.EntityInfo("skeleton", "Skeleton", "-3,0,-3", 3, true));
        
        assertEquals(2, result.nearbyEntities.size());
        assertFalse(result.nearbyEntities.get(0).isHostile);
        assertTrue(result.nearbyEntities.get(1).isHostile);
    }

    @Test
    public void testScanResultWithInventory() {
        VisionScanner.ScanResult result = new VisionScanner.ScanResult();
        Map<String, Integer> inventory = new HashMap<>();
        inventory.put("Stone", 64);
        inventory.put("Dirt", 32);
        result.inventory = inventory;
        
        assertEquals(2, result.inventory.size());
        assertEquals(64, result.inventory.get("Stone"));
        assertEquals(32, result.inventory.get("Dirt"));
    }

    @Test
    public void testToContextString() {
        VisionScanner.ScanResult result = new VisionScanner.ScanResult();
        result.timeOfDay = "day";
        result.weather = "clear";
        result.biome = new VisionScanner.BiomeInfo("plains");
        result.health = 20;
        result.foodLevel = 20;
        result.nearbyBlocks.add(new VisionScanner.BlockInfo("crafting_table", "1,0,0", 1));
        result.nearbyEntities.add(new VisionScanner.EntityInfo("cow", "Cow", "3,0,3", 3, false));
        
        Map<String, Integer> inventory = new HashMap<>();
        inventory.put("Apple", 5);
        result.inventory = inventory;
        
        String context = result.toContextString();
        
        assertNotNull(context);
        assertTrue(context.contains("day"));
        assertTrue(context.contains("clear"));
        assertTrue(context.contains("plains"));
        assertTrue(context.contains("Health: 20/20"));
        assertTrue(context.contains("Hunger: 20/20"));
        assertTrue(context.contains("crafting_table"));
        assertTrue(context.contains("Cow"));
        assertTrue(context.contains("Apple"));
    }

    @Test
    public void testToContextStringEmpty() {
        VisionScanner.ScanResult result = new VisionScanner.ScanResult();
        result.timeOfDay = "night";
        result.weather = "rain";
        result.biome = new VisionScanner.BiomeInfo("unknown");
        result.health = 10;
        result.foodLevel = 15;
        
        String context = result.toContextString();
        
        assertNotNull(context);
        assertTrue(context.contains("night"));
        assertTrue(context.contains("rain"));
        assertTrue(context.contains("Health: 10/20"));
        assertTrue(context.contains("Hunger: 15/20"));
    }
}
