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
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.Base64;

/**
 * Local Brain for CraftyAI
 * Provides offline capability and request queue when Gateway is unavailable
 * Enhanced with NLP capabilities for intelligent offline responses
 * Queue data is encrypted at rest for security
 * Uses connection pooling for HTTP requests
 */
public class LocalBrain {

    private static final Gson GSON = new GsonBuilder().create();
    private static final int MAX_QUEUE_SIZE = 50;
    private static final String QUEUE_FILE = "craftyai_queue.enc";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;
    
    // Shared HTTP client with connection pooling (for gateway requests)
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(10)) // Reduced from 30s for game responsiveness
        .build();

    // Separate HTTP client for custom provider requests
    private static final HttpClient CUSTOM_PROVIDER_HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(10)) // Reduced from 30s for game responsiveness
        .build();

    public static HttpClient getHttpClient() {
        return HTTP_CLIENT;
    }

    /**
     * Get the appropriate HTTP client based on whether a custom provider is being used.
     * @param config The CraftyAI configuration to check for custom provider settings
     * @return The custom provider HTTP client if custom provider is enabled, otherwise the default gateway client
     */
    public static HttpClient getHttpClient(CraftyAIConfig config) {
        if (GatewayRequestHeaders.isCustomProviderEnabled(config)) {
            return CUSTOM_PROVIDER_HTTP_CLIENT;
        }
        return HTTP_CLIENT;
    }

    private final ConcurrentLinkedQueue<QueuedRequest> requestQueue = new ConcurrentLinkedQueue<>();
    private final String configDir;
    private final Consumer<String> logger;
    private final KnowledgeBase knowledgeBase;
    private final String encryptionKey;

    public LocalBrain(String configDir, Consumer<String> logger) {
        this.configDir = configDir;
        this.logger = logger;
        this.knowledgeBase = new KnowledgeBase();
        // Generate or load encryption key from config
        this.encryptionKey = generateEncryptionKey();
        loadQueue();
    }

    private static final Object KEY_LOCK = new Object();

    /**
     * Generate or retrieve encryption key.
     * Uses a persistent random key stored in a local file for security.
     */
    private String generateEncryptionKey() {
        synchronized (KEY_LOCK) {
            Path keyPath = Paths.get(configDir, "internal_vault.key");
            try {
                if (Files.exists(keyPath)) {
                    return new String(Files.readAllBytes(keyPath), StandardCharsets.UTF_8).trim();
                }

                // Generate a new 256-bit key
                SecureRandom random = new SecureRandom();
                byte[] keyBytes = new byte[32];
                random.nextBytes(keyBytes);
                String newKey = Base64.getEncoder().encodeToString(keyBytes);

                // Store it securely
                if (!Files.exists(Paths.get(configDir))) {
                    Files.createDirectories(Paths.get(configDir));
                }
                Files.write(keyPath, newKey.getBytes(StandardCharsets.UTF_8));
                setOwnerOnlyPermissions(keyPath); // Restrict key file to owner-only
                return newKey;
            } catch (Exception e) {
                logger.accept("[LocalBrain] Critical: Failed to manage encryption key: " + e.getMessage());
                // Fail safely rather than use a predictable key
                throw new RuntimeException("Cannot initialize encryption key - file I/O failed");
            }
        }
    }

    /**
     * CRIT-NEW-15: Set POSIX file permissions to owner-only (rw-------).
     * Silently skips on non-POSIX filesystems (e.g. Windows).
     */
    private static void setOwnerOnlyPermissions(Path path) {
        try {
            if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(path,
                    java.util.EnumSet.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE
                    ));
            }
        } catch (Exception e) {
            // Non-POSIX filesystem or permission denied — skip silently
        }
    }

    /**
     * Represents a queued request for later processing
     */
    public static class QueuedRequest {
        public String prompt;
        public String playerName;
        public String context;
        public long timestamp;
        public int attempts;
        public String intent;

        public QueuedRequest(String prompt, String playerName, String context) {
            this.prompt = prompt;
            this.playerName = playerName;
            this.context = context;
            this.timestamp = System.currentTimeMillis();
            this.attempts = 0;
            this.intent = null;
        }
    }

    /**
     * Knowledge base for offline NLP responses
     */
    private static class KnowledgeBase {
        private final Map<String, String> responses = new HashMap<>();
        private final Map<String, List<String>> keywords = new HashMap<>();

        public KnowledgeBase() {
            initializeResponses();
            initializeKeywords();
        }

        private void initializeResponses() {
            // Greetings
            responses.put("greeting", "Hello {player}! I'm currently running on my local backup brain. I can't do complex tasks right now, but I'm here to chat!");
            
            // Help
            responses.put("help", "I'm offline, so I can't execute advanced server commands right now. I can still give you basic tips, tell jokes, or just keep you company. Your complex requests are queued! (Queue: {queue})");
            
            // Status
            responses.put("status", "My neural uplink is offline! I'm running in local mode. Your unanswerable messages are safely queued in my memory banks. (Queue: {queue})");
            
            // Game-related
            responses.put("game", "Minecraft is a game about placing blocks and going on adventures. Even though I'm offline, I know you should always bring a water bucket and never dig straight down!");
            
            // Technical
            responses.put("technical", "I've logged your technical question. Once my connection to the gateway is restored, I'll give you a proper answer!");

            // Jokes
            responses.put("joke", "Why can't you trust a creeper? Because they're always bursting with excitement! (I know more when I'm online...)");

            // Lore
            responses.put("lore", "The ancient builders left behind ruins and strongholds long ago. Some say Endermen are all that remains of them. Creepy, right?");

            // Banter
            responses.put("banter", "I might be offline, {player}, but I'm still the smartest AI in this server! Just... locally smart for now.");

            // Unknown
            responses.put("unknown", "I'm not quite sure how to answer that while offline, {player}. I've saved your question for later! (Queue: {queue})");
        }

        private void initializeKeywords() {
            keywords.put("greeting", Arrays.asList("hello", "hi", "hey", "greetings", "howdy", "morning", "evening"));
            keywords.put("help", Arrays.asList("help", "assist", "support", "what can you do", "commands"));
            keywords.put("status", Arrays.asList("status", "are you online", "working", "available", "why offline", "connection"));
            keywords.put("game", Arrays.asList("minecraft", "crafting", "blocks", "items", "mobs", "biome", "survival"));
            keywords.put("technical", Arrays.asList("error", "bug", "issue", "problem", "crash", "install", "setup", "lag"));
            keywords.put("joke", Arrays.asList("joke", "funny", "laugh", "tell me something funny"));
            keywords.put("lore", Arrays.asList("lore", "story", "enderman", "herobrine", "ancient", "history"));
            keywords.put("banter", Arrays.asList("stupid", "smart", "ai", "bot", "robot", "who are you"));
        }

        public String detectIntent(String prompt) {
            String lowerPrompt = prompt.toLowerCase();
            
            for (Map.Entry<String, List<String>> entry : keywords.entrySet()) {
                for (String keyword : entry.getValue()) {
                    if (lowerPrompt.contains(keyword)) {
                        return entry.getKey();
                    }
                }
            }
            
            return "unknown";
        }

        public String getResponse(String intent, String playerName, int queueSize) {
            String response = responses.getOrDefault(intent, responses.get("unknown"));
            return response
                .replace("{player}", playerName)
                .replace("{queue}", String.valueOf(queueSize));
        }
    }

    /**
     * Add a request to the queue
     */
    public boolean enqueueRequest(String prompt, String playerName, String context) {
        if (requestQueue.size() >= MAX_QUEUE_SIZE) {
            logger.accept("[LocalBrain] Queue full, cannot add request");
            return false;
        }

        QueuedRequest request = new QueuedRequest(prompt, playerName, context);
        request.intent = knowledgeBase.detectIntent(prompt);
        requestQueue.add(request);
        saveQueue();
        logger.accept("[LocalBrain] Request queued for " + playerName + " (queue size: " + requestQueue.size() + ", intent: " + request.intent + ")");
        return true;
    }

    /**
     * Get the next request from the queue
     */
    public QueuedRequest dequeueRequest() {
        QueuedRequest request = requestQueue.poll();
        if (request != null) {
            saveQueue();
        }
        return request;
    }

    /**
     * Get queue size
     */
    public int getQueueSize() {
        return requestQueue.size();
    }

    /**
     * Clear the queue (in-memory only — does not immediately destroy disk backup)
     */
    public void clearQueue() {
        requestQueue.clear();
        logger.accept("[LocalBrain] Queue cleared (in-memory). Call saveQueue() to persist the empty state.");
    }

    /**
     * Generate an intelligent offline response based on NLP intent recognition
     */
    public String generateOfflineResponse(String prompt, String playerName) {
        String intent = knowledgeBase.detectIntent(prompt);
        String response = knowledgeBase.getResponse(intent, playerName, requestQueue.size());
        
        // Extract entities from prompt for more contextual responses
        String entities = extractEntities(prompt);
        if (!entities.isEmpty()) {
            response += " I noticed you're asking about: " + entities;
        }
        
        return response;
    }

    /**
     * Extract key entities from prompt using simple pattern matching
     */
    private String extractEntities(String prompt) {
        List<String> entities = new ArrayList<>();
        
        // Extract numbers
        Pattern numberPattern = Pattern.compile("\\b\\d+\\b");
        Matcher numberMatcher = numberPattern.matcher(prompt);
        while (numberMatcher.find()) {
            entities.add(numberMatcher.group());
        }
        
        // Extract quoted text
        Pattern quotePattern = Pattern.compile("\"([^\"]+)\"");
        Matcher quoteMatcher = quotePattern.matcher(prompt);
        while (quoteMatcher.find()) {
            entities.add(quoteMatcher.group(1));
        }
        
        return String.join(", ", entities);
    }

    /**
     * Save queue to disk (encrypted)
     */
    private synchronized void saveQueue() {
        try {
            Path queuePath = Paths.get(configDir, QUEUE_FILE);
            if (!Files.exists(Paths.get(configDir))) {
                Files.createDirectories(Paths.get(configDir));
            }

            List<QueuedRequest> queueList = new ArrayList<>(requestQueue);
            String json = GSON.toJson(queueList);
            byte[] encrypted = encrypt(json, encryptionKey);
            Files.write(queuePath, encrypted);
        } catch (Exception e) {
            logger.accept("[LocalBrain] Failed to save queue: " + e.getMessage());
        }
    }

    /**
     * Encrypt data using AES-GCM with random IV
     */
    private byte[] encrypt(String data, String key) throws Exception {
        SecureRandom random = new SecureRandom();
        byte[] iv = new byte[GCM_IV_LENGTH];
        random.nextBytes(iv);

        byte[] keyBytes = Base64.getDecoder().decode(key);
        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);
        
        byte[] encrypted = cipher.doFinal(data.getBytes(StandardCharsets.UTF_8));
        
        // Combine IV and encrypted data
        byte[] combined = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
        
        return combined;
    }

    /**
     * Decrypt data using AES-GCM
     */
    private String decrypt(byte[] encrypted, String key) throws Exception {
        if (encrypted.length < GCM_IV_LENGTH) {
            throw new IllegalArgumentException("Encrypted data too short");
        }
        
        byte[] iv = new byte[GCM_IV_LENGTH];
        byte[] cipherText = new byte[encrypted.length - GCM_IV_LENGTH];
        
        System.arraycopy(encrypted, 0, iv, 0, GCM_IV_LENGTH);
        System.arraycopy(encrypted, GCM_IV_LENGTH, cipherText, 0, cipherText.length);

        byte[] keyBytes = Base64.getDecoder().decode(key);
        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);
        
        byte[] decrypted = cipher.doFinal(cipherText);
        return new String(decrypted, StandardCharsets.UTF_8);
    }

    /**
     * Load queue from disk (decrypted)
     */
    private synchronized void loadQueue() {
        try {
            Path queuePath = Paths.get(configDir, QUEUE_FILE);
            if (!Files.exists(queuePath)) {
                return;
            }

            byte[] encrypted = Files.readAllBytes(queuePath);
            String content = decrypt(encrypted, encryptionKey);
            List<QueuedRequest> loadedQueue = GSON.fromJson(content, 
                new TypeToken<List<QueuedRequest>>(){}.getType());
            
            if (loadedQueue != null) {
                requestQueue.addAll(loadedQueue);
                logger.accept("[LocalBrain] Loaded " + requestQueue.size() + " queued requests from disk");
            }
        } catch (Exception e) {
            logger.accept("[LocalBrain] Failed to load queue (may be corrupted or using old format): " + e.getMessage());
            // Try to load as unencrypted for backward compatibility
            try {
                Path queuePath = Paths.get(configDir, "craftyai_queue.json");
                if (Files.exists(queuePath)) {
                    String content = new String(Files.readAllBytes(queuePath), StandardCharsets.UTF_8);
                    List<QueuedRequest> loadedQueue = GSON.fromJson(content, 
                        new TypeToken<List<QueuedRequest>>(){}.getType());
                    if (loadedQueue != null) {
                        requestQueue.addAll(loadedQueue);
                        logger.accept("[LocalBrain] Loaded " + requestQueue.size() + " queued requests from old format");
                        // Migrate to encrypted format
                        saveQueue();
                        Files.delete(queuePath);
                    }
                }
            } catch (Exception ex) {
                logger.accept("[LocalBrain] Failed to load queue from old format: " + ex.getMessage());
            }
        }
    }

    /**
     * Process queued requests (call this when connection is restored)
     */
    public int processQueuedRequests(RequestProcessor processor) {
        int processed = 0;
        List<QueuedRequest> toProcess = new ArrayList<>();
        
        // Drain queue to avoid concurrent modification
        while (!requestQueue.isEmpty()) {
            QueuedRequest req = requestQueue.poll();
            if (req != null) {
                toProcess.add(req);
            }
        }
        
        for (QueuedRequest req : toProcess) {
            try {
                processor.process(req);
                processed++;
                logger.accept("[LocalBrain] Processed queued request: " + req.prompt.substring(0, Math.min(50, req.prompt.length())));
            } catch (Exception e) {
                logger.accept("[LocalBrain] Failed to process queued request: " + e.getMessage());
                // Re-queue if failed
                req.attempts++;
                if (req.attempts < 3) {
                    requestQueue.add(req);
                }
            }
        }
        saveQueue();
        return processed;
    }

    /**
     * Interface for processing queued requests
     */
    public interface RequestProcessor {
        void process(QueuedRequest request);
    }
}
