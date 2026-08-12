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

/**
 * Unit tests for CraftyAIConfig
 */
public class CraftyAIConfigTest {

    @Test
    public void testDefaultValues() {
        CraftyAIConfig config = new CraftyAIConfig();
        

        assertEquals("YOUR_API_KEY_HERE", config.api_key);
        assertEquals("Crafty", config.ai_name);
        assertEquals("@", config.prefix);
        assertFalse(config.require_prefix);
        assertEquals(0, config.cooldown_seconds);
    }

    @Test
    public void testToJson() {
        CraftyAIConfig config = new CraftyAIConfig();
        config.api_key = "test_key_123";
        config.ai_name = "TestAI";
        
        String json = config.toJson();
        
        assertNotNull(json);
        assertTrue(json.contains("test_key_123"));
        assertTrue(json.contains("TestAI"));
    }

    @Test
    public void testFromJson() {
        String json = "{\"api_key\":\"key123\",\"ai_name\":\"MyAI\"}";
        
        CraftyAIConfig config = CraftyAIConfig.fromJson(json);
        

        assertEquals("key123", config.api_key);
        assertEquals("MyAI", config.ai_name);
    }

    @Test
    public void testGetCooldownMs() {
        CraftyAIConfig config = new CraftyAIConfig();
        config.cooldown_seconds = 5;
        
        assertEquals(5000, config.getCooldownMs());
    }

    @Test
    public void testAliasesArray() {
        CraftyAIConfig config = new CraftyAIConfig();
        config.aliases = new String[]{"bot", "assistant", "helper"};
        
        assertEquals(3, config.aliases.length);
        assertEquals("bot", config.aliases[0]);
    }

    @Test
    public void testApiKeyValidationAndAutoMint() {
        assertTrue(CraftyAIConfig.isValidApiKeyFormat("cai_1234567890abcdef123456"));
        assertTrue(CraftyAIConfig.isValidApiKeyFormat("cai_pro_abcdef1234567890"));
        assertFalse(CraftyAIConfig.isValidApiKeyFormat("cai-legacy-1234567890"));
        assertFalse(CraftyAIConfig.isValidApiKeyFormat("YOUR_API_KEY_HERE"));
        assertFalse(CraftyAIConfig.isValidApiKeyFormat(""));
        assertFalse(CraftyAIConfig.isValidApiKeyFormat(null));

        assertTrue(CraftyAIConfig.needsAutoMint("YOUR_API_KEY_HERE", false));
        assertTrue(CraftyAIConfig.needsAutoMint("cai-legacy-key", false));
        assertTrue(CraftyAIConfig.needsAutoMint("", false));
        assertTrue(CraftyAIConfig.needsAutoMint(null, false));
        assertFalse(CraftyAIConfig.needsAutoMint("cai_validKey1234567890123", false));
        assertFalse(CraftyAIConfig.needsAutoMint("cai-legacy-key", true)); // Custom provider overrides auto-mint
    }
}
