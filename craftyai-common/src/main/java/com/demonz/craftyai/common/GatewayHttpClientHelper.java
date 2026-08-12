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

import java.net.http.HttpRequest;

/**
 * Java 11+ helper for applying CraftyAI gateway headers to HttpRequest.Builder.
 * Used by Fabric and Forge mods (compiled with Gradle on Java 17/21).
 *
 * EXCLUDED from Maven build (Java 8 plugin) via pom.xml compiler excludes.
 */
public final class GatewayHttpClientHelper {

    private GatewayHttpClientHelper() {
    }

    /**
     * Apply standard CraftyAI headers to an HttpRequest.Builder.
     * Delegates to GatewayRequestHeaders for header values.
     */
    public static HttpRequest.Builder apply(HttpRequest.Builder builder, String clientType, String sessionId) {
        builder.setHeader("User-Agent", GatewayRequestHeaders.userAgent(clientType));
        builder.setHeader("X-Client-Type", clientType);
        builder.setHeader("X-CraftyAI-Version", GatewayRequestHeaders.MOD_VERSION);
        builder.setHeader("X-CraftyAI-Client", clientType);
        if (sessionId != null && !sessionId.isBlank()) {
            builder.setHeader("X-Session-Id", sessionId);
            builder.setHeader("X-Server-ID", sessionId);
        }
        return builder;
    }

    /**
     * Apply headers for custom provider connections.
     * Uses the custom provider URL and key instead of the standard gateway.
     *
     * @param builder    The HttpRequest.Builder to apply headers to
     * @param customUrl  The custom provider base URL
     * @param customKey  The custom provider API key
     * @param clientType The client type identifier (e.g., "Fabric", "Forge", "Bukkit")
     * @param sessionId  The session ID for request tracking
     * @return The same builder with custom provider headers applied
     */
    public static HttpRequest.Builder applyCustomProvider(HttpRequest.Builder builder, String customUrl, String customKey, String clientType, String sessionId) {
        builder.setHeader("User-Agent", GatewayRequestHeaders.userAgent(clientType));
        builder.setHeader("X-Client-Type", clientType);
        builder.setHeader("X-CraftyAI-Version", GatewayRequestHeaders.MOD_VERSION);
        builder.setHeader("X-CraftyAI-Client", clientType);
        builder.setHeader("X-Custom-Provider", "true");
        if (customKey != null && !customKey.isBlank()) {
            builder.setHeader("Authorization", "Bearer " + customKey);
        }
        if (sessionId != null && !sessionId.isBlank()) {
            builder.setHeader("X-Session-Id", sessionId);
            builder.setHeader("X-Server-ID", sessionId);
        }
        return builder;
    }
}
