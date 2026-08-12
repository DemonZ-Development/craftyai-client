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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.util.*;
import java.util.logging.Level;

/**
 * KnowledgeBase — Loads and provides local knowledge entries for offline/fallback RAG.
 * Reads JSON files from plugins/CraftyAI/knowledge/ directory.
 * Uses Gson for proper JSON parsing.
 */
public class KnowledgeBase {

    private final CraftyAI plugin;
    private final List<KnowledgeEntry> entries = new ArrayList<KnowledgeEntry>();

    public KnowledgeBase(CraftyAI plugin) {
        this.plugin = plugin;
        loadKnowledge();
    }

    private void loadKnowledge() {
        File knowledgeDir = new File(plugin.getDataFolder(), "knowledge");
        if (!knowledgeDir.exists()) {
            knowledgeDir.mkdirs();
            // Save default knowledge files from resources
            saveDefaultKnowledge();
        }

        File[] files = knowledgeDir.listFiles(new java.io.FilenameFilter() {
            public boolean accept(File dir, String name) {
                return name.endsWith(".json");
            }
        });
        if (files == null) return;

        for (File file : files) {
            try {
                String content = readFile(file);
                List<KnowledgeEntry> fileEntries = parseKnowledgeFile(content, file.getName());
                entries.addAll(fileEntries);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to load knowledge file: " + file.getName(), e);
            }
        }

        plugin.getLogger().info("[Knowledge] Loaded " + entries.size() + " entries from " +
                files.length + " files.");
    }

    private void saveDefaultKnowledge() {
        // Save embedded knowledge files from jar resources
        String[] defaults = {"ai_memory_guidelines.json"};
        for (String name : defaults) {
            try {
                plugin.saveResource("knowledge/" + name, false);
            } catch (Exception ignored) {
                // Resource might not exist in jar
            }
        }
    }

    /**
     * Simple keyword-based search. Returns the best matching entry.
     */
    public String findAnswer(String question) {
        if (entries.isEmpty()) return null;

        String lower = question.toLowerCase();
        String[] words = lower.split("\\s+");

        KnowledgeEntry bestMatch = null;
        int bestScore = 0;

        for (KnowledgeEntry entry : entries) {
            int score = 0;
            String entryText = (entry.getQuestion() + " " + entry.getKeywords()).toLowerCase();

            for (String word : words) {
                if (word.length() < 3) continue; // Skip short words
                if (entryText.contains(word)) {
                    score += word.length(); // Longer word matches score higher
                }
            }

            if (score > bestScore) {
                bestScore = score;
                bestMatch = entry;
            }
        }

        // Require at least some relevance
        if (bestMatch != null && bestScore >= 6) {
            return bestMatch.getAnswer();
        }
        return null;
    }

    /**
     * Parse a knowledge JSON file using Gson.
     * Supports two formats:
     * 1. A JSON array of entry objects: [ { "question": "...", "answer": "...", "keywords": [...] }, ... ]
     * 2. A JSON object with "entries" key: { "entries": [ ... ] }
     * 3. A single JSON object: { "question": "...", "answer": "...", "keywords": "..." }
     */
    private List<KnowledgeEntry> parseKnowledgeFile(String json, String filename) {
        List<KnowledgeEntry> result = new ArrayList<KnowledgeEntry>();

        try {
            if (json == null || json.trim().isEmpty()) return result;

            JsonElement root = JsonParserAdapter.parse(json);
            if (root == null) return result;

            if (root.isJsonArray()) {
                // Format 1: JSON array of entries
                JsonArray array = root.getAsJsonArray();
                for (JsonElement element : array) {
                    KnowledgeEntry entry = parseEntry(element, filename);
                    if (entry != null) result.add(entry);
                }
            } else if (root.isJsonObject()) {
                JsonObject obj = root.getAsJsonObject();
                if (obj.has("entries") && obj.get("entries").isJsonArray()) {
                    // Format 2: Object with "entries" array
                    JsonArray array = obj.getAsJsonArray("entries");
                    for (JsonElement element : array) {
                        KnowledgeEntry entry = parseEntry(element, filename);
                        if (entry != null) result.add(entry);
                    }
                } else if (obj.has("question") && obj.has("answer")) {
                    // Format 3: Single entry object
                    KnowledgeEntry entry = parseEntry(obj, filename);
                    if (entry != null) result.add(entry);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to parse " + filename + ": " + e.getMessage());
        }

        return result;
    }

    /**
     * Parse a single knowledge entry from a JsonElement.
     */
    private KnowledgeEntry parseEntry(JsonElement element, String filename) {
        if (element == null || !element.isJsonObject()) return null;
        try {
            JsonObject obj = element.getAsJsonObject();
            String question = obj.has("question") ? obj.get("question").getAsString() : null;
            String answer = obj.has("answer") ? obj.get("answer").getAsString() : null;
            if (question == null || answer == null) return null;

            // Keywords can be a string or an array of strings
            String keywords = "";
            if (obj.has("keywords")) {
                JsonElement kwElement = obj.get("keywords");
                if (kwElement.isJsonArray()) {
                    StringBuilder sb = new StringBuilder();
                    JsonArray kwArray = kwElement.getAsJsonArray();
                    for (int i = 0; i < kwArray.size(); i++) {
                        if (i > 0) sb.append(" ");
                        sb.append(kwArray.get(i).getAsString());
                    }
                    keywords = sb.toString();
                } else if (kwElement.isJsonPrimitive()) {
                    keywords = kwElement.getAsString();
                }
            }

            return new KnowledgeEntry(question, answer, keywords, filename);
        } catch (Exception e) {
            plugin.getLogger().fine("Skipping invalid entry in " + filename + ": " + e.getMessage());
            return null;
        }
    }

    private String readFile(File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    public List<KnowledgeEntry> getEntries() { return entries; }
    public int size() { return entries.size(); }
}
