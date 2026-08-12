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

import org.bukkit.Bukkit;
import java.util.logging.Logger;

/**
 * PlatformDetector — Identifies server software and Minecraft version at runtime.
 * Supports: Spigot, Paper, Folia, Purpur, and unknown/generic Bukkit.
 */
public class PlatformDetector {

    public enum ServerSoftware {
        FOLIA,    // Paper fork with region-based threading
        PURPUR,   // Paper fork with extra gameplay features
        PAPER,    // Performance-focused Spigot fork
        SPIGOT,   // Standard Bukkit implementation
        UNKNOWN   // Generic Bukkit or unrecognized
    }

    public enum VersionRange {
        LEGACY_1_8_1_12,    // 1.8 - 1.12.2 (old API, no Adventure)
        MODERN_1_13_1_15,   // 1.13 - 1.15.2 (flattening, new materials)
        MODERN_1_16_1_19,   // 1.16 - 1.19.4 (Adventure API on Paper)
        LATEST_1_20_PLUS    // 1.20+ (latest APIs, renamed materials)
    }

    private final ServerSoftware software;
    private final VersionRange versionRange;
    private final int majorVersion;
    private final int minorVersion;
    private final boolean hasAdventureAPI;
    private final boolean hasFoliaScheduler;

    public PlatformDetector(Logger logger) {
        this.software = detectSoftware();
        int[] ver = detectVersion();
        this.majorVersion = ver[0];
        this.minorVersion = ver[1];
        this.versionRange = classifyVersion(majorVersion, minorVersion);
        this.hasAdventureAPI = checkAdventureAPI();
        this.hasFoliaScheduler = checkFoliaScheduler();

        logger.info("[Platform] Software: " + software.name());
        String verStr = (majorVersion < 20 ? "1." + majorVersion : majorVersion) + "." + minorVersion;
        logger.info("[Platform] Version: " + verStr + " (" + versionRange.name() + ")");
        logger.info("[Platform] Adventure API: " + (hasAdventureAPI ? "YES" : "NO"));
        logger.info("[Platform] Folia Scheduler: " + (hasFoliaScheduler ? "YES" : "NO"));
    }

    private ServerSoftware detectSoftware() {
        String version = Bukkit.getVersion().toLowerCase();
        String name = Bukkit.getName().toLowerCase();

        // Check Folia first (it extends Paper)
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return ServerSoftware.FOLIA;
        } catch (ClassNotFoundException ignored) {}

        // Check Purpur (extends Paper)
        try {
            Class.forName("org.purpurmc.purpur.PurpurConfig");
            return ServerSoftware.PURPUR;
        } catch (ClassNotFoundException ignored) {}

        // Check Paper
        try {
            Class.forName("com.destroystokyo.paper.PaperConfig");
            return ServerSoftware.PAPER;
        } catch (ClassNotFoundException ignored) {
            try {
                Class.forName("io.papermc.paper.configuration.PaperConfigurations");
                return ServerSoftware.PAPER;
            } catch (ClassNotFoundException ignored2) {}
        }

        // Check Spigot
        try {
            Class.forName("org.spigotmc.SpigotConfig");
            return ServerSoftware.SPIGOT;
        } catch (ClassNotFoundException ignored) {}

        return ServerSoftware.UNKNOWN;
    }

    private int[] detectVersion() {
        String version = Bukkit.getBukkitVersion(); // e.g. "1.20.4-R0.1-SNAPSHOT" or "26.1.2-R0.1-SNAPSHOT"
        try {
            String[] parts = version.split("-")[0].split("\\.");
            int major, minor;
            
            if (parts[0].equals("1")) {
                // Legacy format: 1.X.Y
                major = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
                minor = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
            } else {
                // Modern format: X.Y.Z
                major = Integer.parseInt(parts[0]);
                minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            }
            return new int[]{major, minor};
        } catch (Exception e) {
            return new int[]{20, 0}; // Default to 1.20 if parsing fails
        }
    }

    private VersionRange classifyVersion(int major, int minor) {
        if (major <= 12) return VersionRange.LEGACY_1_8_1_12;
        if (major <= 15) return VersionRange.MODERN_1_13_1_15;
        if (major <= 19) return VersionRange.MODERN_1_16_1_19;
        return VersionRange.LATEST_1_20_PLUS;
    }

    private boolean checkAdventureAPI() {
        try {
            Class.forName("net.kyori.adventure.text.Component");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private boolean checkFoliaScheduler() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    // --- GETTERS ---
    public ServerSoftware getSoftware() { return software; }
    public VersionRange getVersionRange() { return versionRange; }
    public int getMajorVersion() { return majorVersion; }
    public int getMinorVersion() { return minorVersion; }
    public boolean hasAdventureAPI() { return hasAdventureAPI; }
    public boolean hasFoliaScheduler() { return hasFoliaScheduler; }
    public boolean isPaper() { return software == ServerSoftware.PAPER || software == ServerSoftware.FOLIA || software == ServerSoftware.PURPUR; }
    public boolean isLegacy() { return versionRange == VersionRange.LEGACY_1_8_1_12; }

    /**
     * Returns the formatted version string (e.g., "1.20.4" for legacy format, "26.1" for modern format).
     * Handles both the legacy "1.X.Y" format and the modern "X.Y" format used by newer Minecraft releases.
     */
    public String getVersionString() {
        return (majorVersion < 20 ? "1." + majorVersion : String.valueOf(majorVersion)) + "." + minorVersion;
    }
}
