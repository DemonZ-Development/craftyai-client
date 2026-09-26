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

import java.net.URI;

public final class SettingsDraft {
    public String apiKey, name, url, providerKey, model;
    public boolean local, provider, actions, confirmation;

    public SettingsDraft(CraftyAIConfig c) {
        apiKey = clean(c.api_key); name = clean(c.ai_name);
        if ("YOUR_API_KEY_HERE".equals(apiKey.trim())) apiKey = "";
        url = clean(c.custom_provider_url); providerKey = clean(c.custom_provider_key);
        model = clean(c.custom_provider_model); local = c.force_local_mode;
        provider = c.custom_provider_enabled; actions = c.agentic_tasks_enabled && c.ai_enable_actions;
        confirmation = c.require_confirmation;
    }

    private static String clean(String s) { return s == null ? "" : s; }

    public String validate() {
        if (name.trim().isEmpty() || name.length() > 32 || name.matches("(?s).*[\\p{Cntrl}\\u00A7].*")) return "Use an assistant name of 1-32 plain characters.";
        if (!local && !provider && !apiKey.trim().isEmpty() && !apiKey.trim().matches("^cai_[a-zA-Z0-9_-]{16,128}$")) return "Add a valid CraftyAI key in Connection, or leave it empty.";
        if (provider) {
            try { providerBaseUrl(url); }
            catch (IllegalArgumentException e) { return e.getMessage(); }
            if (model.trim().isEmpty()) return "Enter a model ID in Provider.";
        }
        return null;
    }

    public static String providerBaseUrl(String value) {
        try {
            URI uri = URI.create(value.trim());
            String host = uri.getHost();
            boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host) || "::1".equals(host);
            if (host == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null ||
                    !("https".equalsIgnoreCase(uri.getScheme()) || (loopback && "http".equalsIgnoreCase(uri.getScheme())))) {
                throw new IllegalArgumentException();
            }
            return value.trim().replaceAll("/+$", "").replaceAll("/v1/(chat/completions|models)$", "").replaceAll("/v1$", "");
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Use HTTPS, or HTTP for a local provider.");
        }
    }

    public CraftyAIConfig applyTo(CraftyAIConfig current) {
        CraftyAIConfig next = new CraftyAIConfig(); next.copyFrom(current);
        next.api_key = apiKey.trim(); next.ai_name = name.trim();
        next.force_local_mode = local; next.custom_provider_enabled = provider;
        next.custom_provider_url = provider ? providerBaseUrl(url) : url.trim();
        next.custom_provider_key = providerKey.trim(); next.custom_provider_model = model.trim();
        next.agentic_tasks_enabled = actions; next.ai_enable_actions = actions;
        next.require_confirmation = confirmation;
        return next;
    }
}
