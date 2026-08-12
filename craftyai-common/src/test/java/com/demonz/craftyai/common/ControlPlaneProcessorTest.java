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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlPlaneProcessorTest {
    @Test
    void appliesVersionedSafeConfig() {
        CraftyAIConfig config = new CraftyAIConfig();
        RecordingHandler handler = new RecordingHandler();
        String response = "{\"control_plane\":{\"config_revision\":7,\"config\":{\"ai.name\":\"Atlas\",\"ai.cooldown_seconds\":5,\"vision.enabled\":false},\"commands\":[]}}";

        ControlPlaneProcessor.Result result = ControlPlaneProcessor.process(response, config, handler);

        assertEquals("Atlas", config.ai_name);
        assertEquals(5, config.cooldown_seconds);
        assertFalse(config.allow_block_scanning);
        assertEquals(7, config.control_revision);
        assertTrue(result.hasAcknowledgements());
        assertTrue(handler.persisted);
        assertTrue(handler.reloaded);
    }

    @Test
    void rejectsUnknownSettingsWithoutMutatingRevision() {
        CraftyAIConfig config = new CraftyAIConfig();
        RecordingHandler handler = new RecordingHandler();
        String response = "{\"control_plane\":{\"config_revision\":2,\"config\":{\"ai.name\":\"ShouldNotStick\",\"server.secret\":\"stolen\"}}}";

        ControlPlaneProcessor.Result result = ControlPlaneProcessor.process(response, config, handler);

        assertEquals(0, config.control_revision);
        assertEquals("Crafty", config.ai_name);
        assertFalse(result.hasAcknowledgements());
        assertFalse(handler.persisted);
    }

    @Test
    void reportsRuntimeSettingFailureWhenAtomicSaveFails() {
        CraftyAIConfig config = new CraftyAIConfig();
        RecordingHandler handler = new RecordingHandler();
        handler.saveSucceeds = false;
        String response = "{\"control_plane\":{\"commands\":[{\"id\":\"123e4567-e89b-42d3-a456-426614174000\",\"command_type\":\"set_runtime_setting\",\"payload\":{\"key\":\"ai.cooldown_seconds\",\"value\":9}}]}}";

        ControlPlaneProcessor.Result result = ControlPlaneProcessor.process(response, config, handler);

        assertEquals(0, config.cooldown_seconds);
        assertFalse(handler.reloaded);
        assertTrue(result.toAckJson("srv_test").contains("Configuration could not be persisted"));
    }

    @Test
    void reAcknowledgesDuplicateCommandWithoutExecutingItTwice() {
        CraftyAIConfig config = new CraftyAIConfig();
        RecordingHandler handler = new RecordingHandler();
        String response = "{\"control_plane\":{\"commands\":[{\"id\":\"223e4567-e89b-42d3-a456-426614174001\",\"command_type\":\"show_message\",\"payload\":{\"title\":\"Notice\",\"message\":\"Hello\",\"severity\":\"info\"}}]}}";

        ControlPlaneProcessor.process(response, config, handler);
        ControlPlaneProcessor.Result duplicate = ControlPlaneProcessor.process(response, config, handler);

        assertEquals(1, handler.messageCount);
        assertTrue(duplicate.hasAcknowledgements());
        assertTrue(duplicate.toAckJson("srv_test").contains("\"duplicate\":true"));
    }

    private static final class RecordingHandler implements ControlPlaneProcessor.Handler {
        boolean persisted;
        boolean reloaded;
        boolean saveSucceeds = true;
        int messageCount;

        public void showMessage(String title, String message, String severity) { messageCount++; }
        public boolean persistConfiguration(CraftyAIConfig config) { persisted = true; return saveSucceeds; }
        public void reloadConfiguration() { reloaded = true; }
        public void log(String message) { }
    }
}
