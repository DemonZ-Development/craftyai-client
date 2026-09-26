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
import java.util.concurrent.atomic.AtomicInteger;
public final class ActionRateLimiter {
    private final long windowMs;
    private final int maxPerWindow;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private static final int MAX_TRACKED_KEYS = 10_000;

    public ActionRateLimiter() {
        this(5, 60_000L);
    }

    public ActionRateLimiter(int maxPerWindow, long windowMs) {
        this.maxPerWindow = Math.max(1, maxPerWindow);
        this.windowMs = Math.max(1_000L, windowMs);
    }
    private int purgeCounter = 0;

    public boolean tryAcquire(String playerKey) {
        if (playerKey == null || playerKey.isEmpty()) return true;
        long now = System.currentTimeMillis();
        Window w = windows.computeIfAbsent(playerKey, k -> new Window(now));
        synchronized (w) {
            if (now - w.windowStart.get() >= windowMs) {
                w.windowStart.set(now);
                w.count.set(0);
            }
            if (w.count.get() >= maxPerWindow) {
                return false;
            }
            w.count.incrementAndGet();
            if (++purgeCounter % 100 == 0) {
                purgeExpired();
            }
            return true;
        }
    }
    public int remaining(String playerKey) {
        if (playerKey == null || playerKey.isEmpty()) return maxPerWindow;
        long now = System.currentTimeMillis();
        Window w = windows.get(playerKey);
        if (w == null) return maxPerWindow;
        synchronized (w) {
            if (now - w.windowStart.get() >= windowMs) return maxPerWindow;
            return Math.max(0, maxPerWindow - w.count.get());
        }
    }

    public long secondsUntilReset(String playerKey) {
        if (playerKey == null || playerKey.isEmpty()) return 0;
        Window w = windows.get(playerKey);
        if (w == null) return 0;
        synchronized (w) {
            long elapsed = System.currentTimeMillis() - w.windowStart.get();
            long remaining = windowMs - elapsed;
            return Math.max(0, (remaining + 999) / 1000);
        }
    }

    public int getMaxPerWindow() { return maxPerWindow; }
    public long getWindowMs() { return windowMs; }
    public void purgeExpired() {
        long now = System.currentTimeMillis();
        if (windows.size() > MAX_TRACKED_KEYS) {
            windows.entrySet().removeIf(e -> now - e.getValue().windowStart.get() >= windowMs);
        }
        if (windows.size() > MAX_TRACKED_KEYS * 2) {
            windows.entrySet().removeIf(e -> true);
            windows.clear();
        }
    }

    private static final class Window {
        final java.util.concurrent.atomic.AtomicLong windowStart;
        final AtomicInteger count;
        Window(long now) {
            this.windowStart = new java.util.concurrent.atomic.AtomicLong(now);
            this.count = new AtomicInteger(0);
        }
    }
}
