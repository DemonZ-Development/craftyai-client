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

/**
 * LocalBrain — Offline fallback AI using the local knowledge base.
 * Used when the cloud API is unreachable.
 */
public class LocalBrain {

    private final CraftyAI plugin;
    private final KnowledgeBase knowledge;

    public LocalBrain(CraftyAI plugin, KnowledgeBase knowledge) {
        this.plugin = plugin;
        this.knowledge = knowledge;
    }

    /**
     * Attempts to answer a question using the local knowledge base.
     * Returns null if no relevant answer found.
     */
    public String tryAnswer(String question) {
        if (knowledge == null || knowledge.size() == 0) return null;
        return knowledge.findAnswer(question);
    }
}
