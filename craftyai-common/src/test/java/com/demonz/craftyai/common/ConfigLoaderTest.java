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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assertions;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for ConfigLoader
 */
public class ConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    public void testLoadOrCreateConfigCreatesDefault() {
        List<String> logMessages = new ArrayList<>();
        CraftyAIConfig config = ConfigLoader.loadOrCreateConfig(
            tempDir.toString(), 
            "test-config.json", 
            logMessages::add
        );
        
        assertNotNull(config);

        assertEquals("YOUR_API_KEY_HERE", config.api_key);
        
        // Check that config file was created
        Path configFile = tempDir.resolve("test-config.json");
        Assertions.assertTrue(Files.exists(configFile));
    }

    @Test
    public void testLoadOrCreateConfigLoadsExisting() throws IOException {
        // Create an existing config file
        Path configFile = tempDir.resolve("test-config.json");
        String existingJson = "{\"api_key\":\"cai_1234567890abcdef1234567890abcdef1234567890abcdef\"}";
        Files.write(configFile, existingJson.getBytes());
        
        List<String> logMessages = new ArrayList<>();
        CraftyAIConfig config = ConfigLoader.loadOrCreateConfig(
            tempDir.toString(), 
            "test-config.json", 
            logMessages::add
        );
        
        assertNotNull(config);

        assertEquals("cai_1234567890abcdef1234567890abcdef1234567890abcdef", config.api_key);
    }

    @Test
    public void testLoadOrCreateConfigHandlesInvalidJson() throws IOException {
        // Create an invalid JSON file
        Path configFile = tempDir.resolve("test-config.json");
        Files.write(configFile, "invalid json content".getBytes());
        
        List<String> logMessages = new ArrayList<>();
        CraftyAIConfig config = ConfigLoader.loadOrCreateConfig(
            tempDir.toString(), 
            "test-config.json", 
            logMessages::add
        );
        
        // Should return default config on error
        assertNotNull(config);

    }

    @Test
    public void testSaveConfig() throws IOException {
        CraftyAIConfig config = new CraftyAIConfig();
        config.api_key = "cai_1234567890abcdef1234567890abcdef1234567890abcdef";
        config.ai_name = "SavedAI";
        
        List<String> logMessages = new ArrayList<>();
        ConfigLoader.saveConfig(
            tempDir.toString(), 
            "test-save.json", 
            config, 
            logMessages::add
        );
        
        // Verify file was created
        Path configFile = tempDir.resolve("test-save.json");
        Assertions.assertTrue(Files.exists(configFile));
        
        // Verify content
        String content = new String(Files.readAllBytes(configFile));
        Assertions.assertTrue(content.contains("cai_1234567890abcdef1234567890abcdef1234567890abcdef"));
        Assertions.assertTrue(content.contains("SavedAI"));
    }

    @Test
    public void testSaveConfigCreatesDirectory() {
        Path subDir = tempDir.resolve("subdirectory");
        
        CraftyAIConfig config = new CraftyAIConfig();
        config.api_key = "cai_1234567890abcdef1234567890abcdef1234567890abcdef";
        
        List<String> logMessages = new ArrayList<>();
        ConfigLoader.saveConfig(
            subDir.toString(), 
            "test.json", 
            config, 
            logMessages::add
        );
        
        // Verify directory was created
        Assertions.assertTrue(Files.exists(subDir));
        
        // Verify file was created
        Path configFile = subDir.resolve("test.json");
        Assertions.assertTrue(Files.exists(configFile));
    }
}
