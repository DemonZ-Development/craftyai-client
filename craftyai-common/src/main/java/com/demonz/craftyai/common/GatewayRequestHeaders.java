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

import java.net.HttpURLConnection;

/**
 * Standardizes request headers sent to the CraftyAI Gateway.
 *
 * This class has TWO compilation targets:
 *   - Maven (Java 8): Only HttpURLConnection overload compiles.
 *     The HttpRequest.Builder overload is in GatewayHttpClientHelper (excluded from Maven).
 *   - Gradle (Java 17/21): Both overloads available via this class + helper.
 */
public final class GatewayRequestHeaders {
    public static final String MOD_VERSION = "1.3.1";
    private static final String GATEWAY_URL = "https://craftyai-gateway.craftyauth.workers.dev";
    public static final String DEFAULT_VISION_ACTIVATION_KEY = "V";

    private GatewayRequestHeaders() {
    }

    /**
     * Returns the CraftyAI gateway URL.
     * Encapsulated to prevent direct access in config files.
     */
    public static String getGatewayUrl() {
        return GATEWAY_URL;
    }

    /**
     * Check if a custom provider is configured and enabled.
     * @param config The CraftyAI configuration
     * @return true if custom provider is enabled with a non-empty URL
     */
    public static boolean isCustomProviderEnabled(CraftyAIConfig config) {
        return config != null
            && config.custom_provider_enabled
            && config.custom_provider_url != null
            && !config.custom_provider_url.isEmpty();
    }

    /**
     * Apply standard CraftyAI headers to an HttpURLConnection.
     * Works on Java 8+ (Spigot/Paper plugin, settings screen gateway tests).
     */
    public static void apply(HttpURLConnection connection, String clientType, String sessionId) {
        connection.setRequestProperty("User-Agent", userAgent(clientType));
        connection.setRequestProperty("X-Client-Type", clientType);
        connection.setRequestProperty("X-CraftyAI-Version", MOD_VERSION);
        connection.setRequestProperty("X-CraftyAI-Client", clientType);
        if (sessionId != null && !sessionId.isEmpty()) {
            connection.setRequestProperty("X-Session-Id", sessionId);
            connection.setRequestProperty("X-Server-ID", sessionId);
        }
    }

    static String userAgent(String clientType) {
        return "CraftyAI-Minecraft/" + MOD_VERSION + " (" + clientType + ")";
    }
}
