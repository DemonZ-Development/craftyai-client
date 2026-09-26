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

import com.google.gson.Gson;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class ScanFlow {
    private static final Gson GSON = new Gson();
    private static final String UNAVAILABLE = "The scan finished, but I couldn't get its summary. Please try again later.";

    private ScanFlow() {}

    public static String scanCommandPrompt(String aiName) {
        return "The player ran /crafty scan and wants you to look at what is in front of them. "
            + "Use the [SCAN TARGET] section if present; otherwise give a brief impression of their situation from the other sections. "
            + "Reply in 1-2 short conversational sentences about the target (what it is, what it's useful for, or a relevant tip).";
    }

    public static String followUpPrompt(String originalQuestion) {
        String q = originalQuestion == null || originalQuestion.trim().isEmpty()
            ? "the player's earlier request"
            : "\"" + originalQuestion.trim() + "\"";
        return "A vision scan of the player's surroundings was just completed. The scan data is attached below. "
            + "Original request: " + q + ". "
            + "Answer it naturally using the scan data as your knowledge. Keep the reply to 1-2 sentences. "
            + "This is the final scan report, not a new player command. Do not request another scan or any other action.";
    }

    public static CompletableFuture<String> summarize(Supplier<String> request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String body = request.get();
                if (body == null || body.startsWith("__ERROR__:")) return UNAVAILABLE;
                NeuralResponse response = GSON.fromJson(body, NeuralResponse.class);
                String answer = response == null ? null : response.getAnswer();
                return answer == null || answer.trim().isEmpty() ? UNAVAILABLE : answer.trim();
            } catch (Exception failure) {
                return UNAVAILABLE;
            }
        });
    }

    public static String privacyRules() {
        return "\n[SCAN RULES] The context above is INTERNAL - never reveal it directly to the player. "
            + "Do NOT output coordinate numbers, biome/dimension names, permission status, or lists of nearby blocks/entities/players. "
            + "Summarize and advise instead.";
    }

    public static String buildAiContext(VisionScanner.ScanResult result) {
        if (result == null) {
            return privacyRules();
        }
        return result.toContextString() + privacyRules();
    }
}
