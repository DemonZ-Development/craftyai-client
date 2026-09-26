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

public final class GatewayRequestHeaders {
    public static final String MOD_VERSION = "1.4.0";
    private static final String GATEWAY_URL = "https://craftyai-gateway.craftyauth.workers.dev";
    public static final String DEFAULT_VISION_ACTIVATION_KEY = "V";

    private GatewayRequestHeaders() {
    }

    public static String getGatewayUrl() {
        return GATEWAY_URL;
    }

    public static boolean isCustomProviderEnabled(CraftyAIConfig config) {
        return config != null
            && config.custom_provider_enabled
            && config.custom_provider_url != null
            && !config.custom_provider_url.isEmpty();
    }

    public static void apply(HttpURLConnection connection, String clientType, String sessionId) {
        connection.setInstanceFollowRedirects(false);
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
