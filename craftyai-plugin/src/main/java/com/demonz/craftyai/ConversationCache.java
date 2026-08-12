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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ConversationCache — Stores per-player conversation history for context.
 * Thread-safe, with a configurable max history size.
 * Private commands (e.g. /crafty ask) are stored separately from public chat to prevent context leakage.
 */
public class ConversationCache {

    private static final int MAX_HISTORY = 10; // Max messages per player (5 Q+A pairs) // TODO: extract to craftyai-common
    private static final String PRIVATE_PREFIX = "p_";
    private static final int MAX_CACHE_SIZE = 500; // Prevent unbounded memory growth

    private final ConcurrentHashMap<String, LinkedList<Map<String, String>>> cache = new ConcurrentHashMap<>();

    public void addInteraction(UUID playerId, String question, String answer) {
        addInteraction(playerId, question, answer, false);
    }

    public void addInteraction(UUID playerId, String question, String answer, boolean isPrivate) {
        String key = isPrivate ? PRIVATE_PREFIX + playerId.toString() : playerId.toString();
        LinkedList<Map<String, String>> history = cache.computeIfAbsent(key, k -> new LinkedList<>());

        synchronized (history) {
            Map<String, String> userMsg = new HashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", question);
            history.add(userMsg);

            Map<String, String> assistantMsg = new HashMap<>();
            assistantMsg.put("role", "assistant");
            assistantMsg.put("content", answer);
            history.add(assistantMsg);

            while (history.size() > MAX_HISTORY) {
                history.removeFirst();
            }
        }

        // Evict oldest cache entries if total cache exceeds limit
        if (cache.size() > MAX_CACHE_SIZE) {
            Iterator<String> it = cache.keySet().iterator();
            while (cache.size() > MAX_CACHE_SIZE && it.hasNext()) {
                it.next();
                it.remove();
            }
        }
    }

    public List<Map<String, String>> getFormattedHistory(UUID playerId) {
        return getFormattedHistory(playerId, false);
    }

    public List<Map<String, String>> getFormattedHistory(UUID playerId, boolean isPrivate) {
        String key = isPrivate ? PRIVATE_PREFIX + playerId.toString() : playerId.toString();
        LinkedList<Map<String, String>> history = cache.get(key);
        if (history == null) return Collections.emptyList();

        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    public void removePlayer(UUID playerId) {
        cache.remove(playerId.toString());
        cache.remove(PRIVATE_PREFIX + playerId.toString());
    }

    public void clearPlayer(UUID playerId) {
        removePlayer(playerId);
    }

    public void clearAll() {
        cache.clear();
    }
}
