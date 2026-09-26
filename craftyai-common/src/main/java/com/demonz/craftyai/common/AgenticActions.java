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

import java.util.Objects;
import java.util.Set;
import java.util.HashSet;
public final class AgenticActions {

    public enum Risk { SAFE, MODERATE, DESTRUCTIVE }

    public static final class ActionDef {
        private final String key;
        private final String permission;
        private final Risk risk;
        public ActionDef(String key, String permission, Risk risk) {
            this.key = key;
            this.permission = permission;
            this.risk = risk;
        }
        public String key() { return key; }
        public String permission() { return permission; }
        public Risk risk() { return risk; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof ActionDef)) return false;
            ActionDef other = (ActionDef) o;
            return Objects.equals(key, other.key) && Objects.equals(permission, other.permission) && risk == other.risk;
        }
        @Override public int hashCode() { return Objects.hash(key, permission, risk); }
    }

    private static final java.util.Map<String, ActionDef> DEFS = new java.util.HashMap<>();
    private static final java.util.Map<String, String> ALIASES = new java.util.HashMap<>();

    static {
        register("TIME_DAY",       "crafty.agentic.time",     Risk.SAFE);
        register("TIME_NIGHT",     "crafty.agentic.time",     Risk.SAFE);
        register("WEATHER_CLEAR",  "crafty.agentic.weather",  Risk.SAFE);
        register("WEATHER_RAIN",   "crafty.agentic.weather",  Risk.SAFE);
        register("WEATHER_THUNDER","crafty.agentic.weather",  Risk.SAFE);
        register("HEAL",           "crafty.agentic.heal",     Risk.SAFE);
        register("FEED",           "crafty.agentic.feed",     Risk.SAFE);
        register("GIVE",           "crafty.agentic.give",     Risk.MODERATE);
        register("EFFECT",         "crafty.agentic.effect",   Risk.MODERATE);
        register("ENCHANT",        "crafty.agentic.enchant",  Risk.MODERATE);
        register("TP",             "crafty.agentic.teleport", Risk.MODERATE);
        register("TELEPORT_SPAWN", "crafty.agentic.teleport", Risk.MODERATE);
        register("KILL_MOBS",      "crafty.agentic.kill",     Risk.DESTRUCTIVE);
        register("GAMEMODE_CREATIVE", "crafty.agentic.gamemode", Risk.DESTRUCTIVE);
        register("GAMEMODE_SURVIVAL", "crafty.agentic.gamemode", Risk.DESTRUCTIVE);
        register("GAMEMODE_SPECTATOR","crafty.agentic.gamemode", Risk.DESTRUCTIVE);
        register("CHAT",             "crafty.agentic.commands", Risk.DESTRUCTIVE);
        register("SCAN_BLOCKS",      "crafty.agentic.scan",     Risk.SAFE);
        register("SCHEDULE_TASK",    "crafty.agentic.schedule", Risk.SAFE);
        register("DELAYED_ACTION",   "crafty.agentic.delayed",  Risk.SAFE);

        ALIASES.put("TIME_DAY", "TIME_DAY");
        ALIASES.put("TIME_NIGHT", "TIME_NIGHT");
        ALIASES.put("WEATHER_CLEAR", "WEATHER_CLEAR");
        ALIASES.put("WEATHER_RAIN", "WEATHER_RAIN");
        ALIASES.put("WEATHER_THUNDER", "WEATHER_THUNDER");
        ALIASES.put("HEAL", "HEAL");
        ALIASES.put("FEED", "FEED");
        ALIASES.put("KILL_MOBS", "KILL_MOBS");
        ALIASES.put("TELEPORT_SPAWN", "TELEPORT_SPAWN");
        ALIASES.put("GAMEMODE_CREATIVE", "GAMEMODE_CREATIVE");
        ALIASES.put("GAMEMODE_SURVIVAL", "GAMEMODE_SURVIVAL");
        ALIASES.put("GAMEMODE_SPECTATOR", "GAMEMODE_SPECTATOR");
    }

    private static void register(String key, String permission, Risk risk) {
        DEFS.put(key, new ActionDef(key, permission, risk));
    }
    public static ActionDef get(String key) {
        if (key == null) return null;
        return DEFS.get(key.toUpperCase());
    }
    public static String canonicalize(String rawAction) {
        if (rawAction == null || rawAction.isEmpty()) return null;
        String upper = rawAction.trim().toUpperCase();
        int colon = upper.indexOf(':');
        String head = colon >= 0 ? upper.substring(0, colon) : upper;
        return ALIASES.getOrDefault(head, head);
    }

    public static Risk riskFor(String rawAction) {
        String canonical = canonicalize(rawAction);
        ActionDef def = get(canonical);
        if (def == null) return Risk.MODERATE;
        if ("CHAT".equals(canonical) && isAllowedChatCommand(rawAction.substring(rawAction.indexOf(':') + 1))) {
            return Risk.SAFE;
        }
        if ("GIVE".equals(canonical)) {
            int count = parseCountParam(rawAction);
            if (count > 16) return Risk.DESTRUCTIVE;
        }
        if ("ENCHANT".equals(canonical)) {
            int lvl = parseLevelParam(rawAction);
            if (lvl >= 5) return Risk.DESTRUCTIVE;
        }
        return def.risk();
    }
    public static String permissionFor(String rawAction) {
        String canonical = canonicalize(rawAction);
        ActionDef def = get(canonical);
        if (def == null) return "crafty.agentic";
        return def.permission();
    }

    public static Set<String> allKeys() {
        return new HashSet<>(DEFS.keySet());
    }

    public static boolean isAllowedChatCommand(String command) {
        if (command == null) return false;
        String normalized = command.trim();
        if (normalized.startsWith("/")) normalized = normalized.substring(1).trim();
        if (normalized.length() == 0 || normalized.length() > 160
                || normalized.indexOf('\n') >= 0 || normalized.indexOf('\r') >= 0
                || normalized.indexOf(';') >= 0) return false;
        return normalized.matches("(?i)^locate\\s+(structure|biome|poi)\\s+#?(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+$");
    }

    public static String normalizeLocateCommand(String command) {
        if (!isAllowedChatCommand(command)) return command;
        String value = command.trim().replaceFirst("^/", "").toLowerCase(java.util.Locale.ROOT);
        String[] parts = value.split("\\s+", 3);
        boolean tag = parts[2].startsWith("#");
        String id = tag ? parts[2].substring(1) : parts[2];
        if (!id.contains(":")) id = "minecraft:" + id;
        return parts[0] + " " + parts[1] + " " + (tag ? "#" : "") + id;
    }

    public static String description(String action) {
        String kind = canonicalize(action);
        if (kind == null) return "game action";
        switch (kind) {
            case "CHAT": return "find a location";
            case "SCAN_BLOCKS": return "scan the surroundings";
            case "TP": case "TELEPORT_SPAWN": return "teleport";
            case "GIVE": return "give items";
            case "EFFECT": return "apply an effect";
            case "ENCHANT": return "enchant an item";
            case "KILL_MOBS": return "remove nearby mobs";
            case "HEAL": return "restore health";
            case "FEED": return "restore hunger";
            case "TIME_DAY": case "TIME_NIGHT": return "change the time";
            case "WEATHER_CLEAR": case "WEATHER_RAIN": case "WEATHER_THUNDER": return "change the weather";
            case "GAMEMODE_CREATIVE": case "GAMEMODE_SURVIVAL": case "GAMEMODE_SPECTATOR": return "change game mode";
            default: return "game action";
        }
    }

    private static int parseCountParam(String action) {
        if (action == null) return 0;
        String[] parts = action.split(":");
        if (parts.length < 3) return 0;
        try { return Integer.parseInt(parts[2].trim()); } catch (NumberFormatException e) { return 0; }
    }

    private static int parseLevelParam(String action) {
        if (action == null) return 1;
        String[] parts = action.split(":");
        if (parts.length < 3) return 1;
        try { return Integer.parseInt(parts[2].trim()); } catch (NumberFormatException e) { return 1; }
    }
}
