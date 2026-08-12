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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgenticActionsTest {
    @Test
    void allowsOnlyReadOnlyLocateCommands() {
        assertTrue(AgenticActions.isAllowedChatCommand("/locate structure minecraft:ancient_city"));
        assertTrue(AgenticActions.isAllowedChatCommand("locate biome minecraft:desert"));
        assertTrue(AgenticActions.isAllowedChatCommand("locate structure #minecraft:village"));
        assertTrue(AgenticActions.isAllowedChatCommand("locate poi minecraft:bee_nest"));
        assertTrue(AgenticActions.isAllowedChatCommand("/locate poi #minecraft:nether_portal"));
    }

    @Test
    void rejectsAdministrativeAndChainedCommands() {
        assertFalse(AgenticActions.isAllowedChatCommand("tp @a 0 100 0"));
        assertFalse(AgenticActions.isAllowedChatCommand("locate structure village; op Player"));
        assertFalse(AgenticActions.isAllowedChatCommand("locate structure village\nop Player"));
        assertFalse(AgenticActions.isAllowedChatCommand("execute as @a run locate structure village"));
    }
}
