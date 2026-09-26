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

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class ActionCommands {
    private ActionCommands() {}
    private static final Set<String> BLOCKED = new HashSet<>(Arrays.asList("barrier", "command_block",
            "chain_command_block", "repeating_command_block", "command_block_minecart", "structure_block",
            "structure_void", "bedrock", "end_portal_frame", "spawner"));
    public static String command(String action) {
        String[] p = action.trim().split(":", -1);
        String kind = p[0].toUpperCase(Locale.ROOT);
        if (kind.equals("CHAT")) {
            String cmd = action.substring(action.indexOf(':') + 1);
            if (!AgenticActions.isAllowedChatCommand(cmd)) throw new IllegalArgumentException("Only read-only /locate commands are allowed.");
            return AgenticActions.normalizeLocateCommand(cmd);
        }
        if (kind.equals("TP") && p.length == 4) {
            for (int i = 1; i < 4; i++) {
                p[i] = p[i].trim();
                if (!p[i].matches("[~^]?(?:-?\\d+(?:\\.\\d+)?)?" ) || p[i].isEmpty()) throw new IllegalArgumentException("Teleport requires three valid coordinates.");
                String number = p[i].replace("~", "").replace("^", "");
                if (!number.isEmpty() && Math.abs(Double.parseDouble(number)) > 30000000) throw new IllegalArgumentException("Coordinate outside world bounds.");
            }
            return "tp @s " + p[1] + " " + p[2] + " " + p[3];
        }
        if ((kind.equals("GIVE") || kind.equals("EFFECT") || kind.equals("ENCHANT")) && (p.length == 2 || p.length == 3)) {
            String id = p[1].trim().toLowerCase(Locale.ROOT);
            if (!id.matches("[a-z0-9_]+") || (kind.equals("GIVE") && BLOCKED.contains(id))) throw new IllegalArgumentException("Item or effect is not allowed.");
            int value;
            try { value = p.length == 3 ? Integer.parseInt(p[2].trim()) : kind.equals("EFFECT") ? 60 : 1; }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Amount must be a whole number."); }
            int max = kind.equals("GIVE") ? 64 : kind.equals("EFFECT") ? 3600 : 5;
            if (value < 1 || value > max) throw new IllegalArgumentException("Amount must be between 1 and " + max + ".");
            return (kind.equals("GIVE") ? "give" : kind.equals("EFFECT") ? "effect give" : "enchant") + " @s minecraft:" + id + " " + value;
        }
        if (p.length != 1) throw new IllegalArgumentException("Invalid action parameters.");
        switch (kind) {
            case "TIME_DAY": return "time set day";
            case "TIME_NIGHT": return "time set night";
            case "WEATHER_CLEAR": return "weather clear";
            case "WEATHER_RAIN": return "weather rain";
            case "WEATHER_THUNDER": return "weather thunder";
            case "HEAL": return "effect give @s minecraft:instant_health 1 255";
            case "FEED": return "effect give @s minecraft:saturation 1 255";
            case "KILL_MOBS": return "kill @e[type=!player,distance=..50,type=!item,type=!experience_orb]";
            case "GAMEMODE_CREATIVE": return "gamemode creative";
            case "GAMEMODE_SURVIVAL": return "gamemode survival";
            case "GAMEMODE_SPECTATOR": return "gamemode spectator";
            case "TELEPORT_SPAWN": throw new IllegalArgumentException("Spawn coordinates have not been verified. No teleport was performed.");
            default: throw new IllegalArgumentException("Unsupported action.");
        }
    }
}
