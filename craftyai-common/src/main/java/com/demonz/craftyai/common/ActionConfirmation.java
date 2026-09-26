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

import java.util.concurrent.ConcurrentHashMap;

public final class ActionConfirmation {
    public static final long EXPIRY_MS = 30_000L;
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
    public void request(String playerKey, String action) {
        if (playerKey == null || playerKey.isEmpty()) return;
        pending.put(playerKey, new Pending(action, System.currentTimeMillis()));
    }
    public void cancel(String playerKey) {
        if (playerKey == null) return;
        pending.remove(playerKey);
    }
    public String peek(String playerKey) {
        if (playerKey == null) return null;
        Pending p = pending.get(playerKey);
        if (p == null) return null;
        if (isExpired(p)) {
            pending.remove(playerKey, p);
            return null;
        }
        return p.action;
    }
    public String confirm(String playerKey) {
        if (playerKey == null) return null;
        Pending p = pending.get(playerKey);
        if (p == null) return null;
        if (isExpired(p)) {
            pending.remove(playerKey, p);
            return null;
        }
        return pending.remove(playerKey, p) ? p.action : null;
    }
    public void purgeExpired() {
        long now = System.currentTimeMillis();
        pending.entrySet().removeIf(e -> now - e.getValue().createdAt >= EXPIRY_MS);
    }

    public boolean hasPending(String playerKey) {
        return peek(playerKey) != null;
    }

    private static boolean isExpired(Pending p) {
        return System.currentTimeMillis() - p.createdAt >= EXPIRY_MS;
    }

    private static final class Pending {
        final String action;
        final long createdAt;
        Pending(String action, long createdAt) {
            this.action = action;
            this.createdAt = createdAt;
        }
    }
}
