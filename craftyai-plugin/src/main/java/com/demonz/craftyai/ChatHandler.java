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

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class ChatHandler implements Listener {

    private final CraftyAI plugin;
    private final VersionAdapter adapter;
    private final ConversationCache conversations;
    private final LocalBrain localBrain;

    private String aiName;
    private List<String> aliases;
    private String prefix;
    private boolean requirePrefix;
    private boolean caseSensitive;
    private boolean anywhereInMessage;
    private boolean fuzzyMatch;
    private int fuzzyThreshold;
    private int chatCooldownMs;
    private String responseVisibility;
    private List<Pattern> activationPatterns;
    private final ConcurrentHashMap<UUID, Long> chatCooldowns = new ConcurrentHashMap<UUID, Long>();

    public ChatHandler(CraftyAI plugin, VersionAdapter adapter, ConversationCache conversations, LocalBrain localBrain) {
        this.plugin = plugin;
        this.adapter = adapter;
        this.conversations = conversations;
        this.localBrain = localBrain;
        loadActivationConfig();
    }

    public void reload() {
        loadActivationConfig();
    }

    public String getAiName() { return aiName; }
    public List<String> getAliases() { return aliases; }
    public String getPrefix() { return prefix; }
    public boolean isFuzzyMatch() { return fuzzyMatch; }

    private void loadActivationConfig() {
        aiName = plugin.getConfig().getString("ai.name", "Crafty");
        aliases = plugin.getConfig().getStringList("ai.aliases");
        if (aliases.isEmpty()) {
            aliases = new ArrayList<String>();
            aliases.add(aiName.toLowerCase());
            aliases.add("crafty");
            aliases.add("craftyai");
            aliases.add("ai");
        }
        prefix = plugin.getConfig().getString("ai.activation.prefix", "@");
        requirePrefix = plugin.getConfig().getBoolean("ai.activation.require-prefix", false);
        caseSensitive = plugin.getConfig().getBoolean("ai.activation.case-sensitive", false);
        anywhereInMessage = plugin.getConfig().getBoolean("ai.activation.anywhere-in-message", false);
        fuzzyMatch = plugin.getConfig().getBoolean("ai.activation.fuzzy-match", true);
        fuzzyThreshold = plugin.getConfig().getInt("ai.activation.fuzzy-threshold", 2);
        chatCooldownMs = plugin.getConfig().getInt("ai.activation.cooldown", 3) * 1000;
        responseVisibility = plugin.getConfig().getString("ai.activation.response-visibility", "default");

        activationPatterns = new ArrayList<Pattern>();
        for (String alias : aliases) {
            String escaped = Pattern.quote(alias);
            int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
            String prefixEscaped = Pattern.quote(prefix);
            if (requirePrefix) {
                if (anywhereInMessage) {
                    activationPatterns.add(Pattern.compile(prefixEscaped + escaped + "\\s+(.+)", flags));
                } else {
                    activationPatterns.add(Pattern.compile("^" + prefixEscaped + escaped + "\\s+(.+)", flags));
                }
            } else {
                if (anywhereInMessage) {
                    activationPatterns.add(Pattern.compile("(?:" + prefixEscaped + ")?" + escaped + "[,:]?\\s+(.+)", flags));
                } else {
                    activationPatterns.add(Pattern.compile("^(?:" + prefixEscaped + ")?" + escaped + "[,:]?\\s+(.+)", flags));
                }
            }
        }

        plugin.getLogger().info("[Activation] Name: " + aiName + " | Aliases: " + aliases);
        plugin.getLogger().info("[Activation] Prefix: '" + prefix + "' | Required: " + requirePrefix);
        plugin.getLogger().info("[Activation] Fuzzy: " + fuzzyMatch + " (threshold: " + fuzzyThreshold + ")");
    }

    public String extractQuestion(String message) {
        if (message == null || message.trim().isEmpty()) return null;
        String trimmed = message.trim();
        for (Pattern pattern : activationPatterns) {
            java.util.regex.Matcher matcher = pattern.matcher(trimmed);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        }
        if (fuzzyMatch) {
            return fuzzyExtract(trimmed);
        }
        return null;
    }

    private String fuzzyExtract(String message) {
        String lower = caseSensitive ? message : message.toLowerCase();
        String[] words = lower.split("\\s+", 2);
        if (words.length < 2) return null;
        String firstWord = words[0];
        if (firstWord.startsWith(prefix.toLowerCase())) {
            firstWord = firstWord.substring(prefix.length());
        }
        firstWord = firstWord.replaceAll("[,:!?]+$", "");
        for (String alias : aliases) {
            String compareAlias = caseSensitive ? alias : alias.toLowerCase();

            if (compareAlias.length() < 3) continue;
            int distance = levenshteinDistance(firstWord, compareAlias);
            if (distance <= fuzzyThreshold && distance < compareAlias.length() / 2) {
                return words[1].trim();
            }
        }
        return null;
    }

    private int levenshteinDistance(String s1, String s2) {
        if (s1.equals(s2)) return 0;
        if (s1.isEmpty()) return s2.length();
        if (s2.isEmpty()) return s1.length();
        int[] prev = new int[s2.length() + 1];
        int[] curr = new int[s2.length() + 1];
        for (int j = 0; j <= s2.length(); j++) prev[j] = j;
        for (int i = 1; i <= s1.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= s2.length(); j++) {
                int cost = s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] temp = prev;
            prev = curr;
            curr = temp;
        }
        return prev[s2.length()];
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        final Player player = event.getPlayer();
        if (!player.hasPermission("crafty.chat")) return;

        final String rawMsg = event.getMessage().trim();
        final String visibility = responseVisibility != null ? responseVisibility : "default";
        boolean privateFlag = visibility.equalsIgnoreCase("always-private") ||
                (visibility.equalsIgnoreCase("default") && rawMsg.startsWith(prefix));
        if (visibility.equalsIgnoreCase("always-public")) {
            privateFlag = false;
        }
        final boolean isPrivate = privateFlag;

        final String question = extractQuestion(event.getMessage());
        if (question == null) return;

        event.setCancelled(true);
        String eventFormat = event.getFormat();
        String formattedMsg = String.format(eventFormat, player.getDisplayName(), event.getMessage());
        if (isPrivate) {
            player.sendMessage(formattedMsg);
        } else {
            broadcastChatMessage(formattedMsg);
        }

        if (isOnCooldown(player)) {
            adapter.sendActionBar(player, "&c" + aiName + " is thinking... please wait.");
            return;
        }

        adapter.sendActionBar(player, "&b&l" + aiName.toUpperCase() + " IS THINKING...");

        final String context = buildContext(player);
        final List<Map<String, String>> history = conversations.getFormattedHistory(player.getUniqueId(), isPrivate);

        if (plugin.getConfig().getBoolean("ai.force-local-mode", false)) {
            String localAnswer = localBrain.tryAnswer(question);
            if (localAnswer != null) {
                String format = plugin.getConfig().getString("chat.format", "&b[{name}] &7\u27A6 &f{response}");
                String formatted = format.replace("{name}", aiName).replace("{response}", localAnswer);
                if (isPrivate) {
                    formatted = "&8[Private] " + formatted;
                    adapter.sendMessage(player, formatted);
                } else {
                    broadcastChatMessage(formatted);
                }
                conversations.addInteraction(player.getUniqueId(), question, localAnswer, isPrivate);
                adapter.sendActionBar(player, "&e&lFORCED LOCAL MODE");
                adapter.playSound(player, plugin.getConfig().getString("chat.sounds.success", "ENTITY_EXPERIENCE_ORB_PICKUP"), 1.0f, 1.2f);
            } else {
                adapter.sendMessage(player, "&c[" + aiName + "] &7Local brain failed to generate response.");
            }
            return;
        }

        CraftyEngine engine = plugin.getEngine();
        if (engine == null) {
            adapter.sendMessage(player, "&c[" + aiName + "] &7Neural engine not available.");
            return;
        }

        final String aiNameFinal = aiName;
        engine.ask(player, question, context, history, new CraftyEngine.Callback() {
            public void onSuccess(final String response) {
                plugin.updateTierFromResponse(response);
                adapter.runEntitySync(player, new Runnable() {
                    public void run() {
                        if (!player.isOnline()) return;
                        String answer = engine.parseAnswer(response);
                        if (answer != null && !answer.isEmpty()) {
                            String format = plugin.getConfig().getString("chat.format", "&b[{name}] &7\u27A6 &f{response}");

                            String actionRaw = engine.parseAction(response);
                            if (actionRaw == null || actionRaw.isEmpty() || "null".equalsIgnoreCase(actionRaw)) {
                                actionRaw = inferActionFromText(answer, question);
                            } else if (actionRaw.toUpperCase().startsWith("SCHEDULE_TASK:") && question != null && question.toLowerCase().matches(".*\\b(in|after|wait)\\s+\\d+\\s*(seconds?|sec|minutes?|min)\\b.*")) {
                                actionRaw = inferActionFromText(answer, question);
                            }
                            final String action = actionRaw;

                            boolean agenticEnabled = plugin.getConfig().getBoolean("ai.enable_actions", true) || plugin.getConfig().getBoolean("ai_agentic_tasks", false);
                            boolean hasAction = action != null && agenticEnabled;
                            String actionUpper = hasAction ? action.toUpperCase().trim() : "";

                            boolean isSelfFeedback = actionUpper.startsWith("SCAN_BLOCKS")
                                || actionUpper.startsWith("DELAYED_ACTION")
                                || actionUpper.startsWith("SCHEDULE_TASK");

                            if (hasAction && !isSelfFeedback) {
                                conversations.addInteraction(player.getUniqueId(), question, answer, isPrivate);
                                plugin.getActionHandler().handleAction(plugin, player, action, plugin.getTier(), false, question);

                                final String followUpFormat = format;
                                final String originalQuestion = question;
                                final String actionName = action;
                                adapter.runSync(new Runnable() {
                                    public void run() {
                                        if (!player.isOnline()) return;
                                        String actionContext = "[Action Executed]\nAction: " + actionName
                                            + "\nPlayer: " + player.getName()
                                            + "\nWorld: " + player.getWorld().getName()
                                            + "\nOriginal Question: " + originalQuestion;
                                        String followUpPrompt = "The player asked: \"" + originalQuestion
                                            + "\". The action \"" + actionName + "\" was executed. Respond naturally in 1-2 sentences confirming what was done. Be conversational and brief. Do NOT output action codes or technical details.";
                                        engine.ask(player,
                                            followUpPrompt, actionContext, null, new CraftyEngine.Callback() {
                                                public void onSuccess(String followUpResponse) {
                                                    adapter.runEntitySync(player, new Runnable() {
                                                        public void run() {
                                                            if (!player.isOnline()) return;
                                                            String followUp = engine.parseAnswer(followUpResponse);
                                                            if (followUp != null && !followUp.isEmpty()) {
                                                                String msg = followUpFormat.replace("{name}", aiNameFinal).replace("{response}", followUp);
                                                                if (isPrivate) msg = "&8[Private] " + msg;
                                                                adapter.sendMessage(player, msg);
                                                                conversations.addInteraction(player.getUniqueId(), "(action follow-up)", followUp, isPrivate);
                                                            }
                                                            adapter.playSound(player, plugin.getConfig().getString("chat.sounds.success", "ENTITY_EXPERIENCE_ORB_PICKUP"), 1.0f, 1.2f);
                                                        }
                                                    });
                                                }
                                                public void onFailure(String error) {
                                                    adapter.runEntitySync(player, new Runnable() {
                                                        public void run() {
                                                            if (!player.isOnline()) return;
                                                            String msg = followUpFormat.replace("{name}", aiNameFinal).replace("{response}", answer);
                                                            if (isPrivate) msg = "&8[Private] " + msg;
                                                            adapter.sendMessage(player, msg);
                                                            adapter.playSound(player, plugin.getConfig().getString("chat.sounds.success", "ENTITY_EXPERIENCE_ORB_PICKUP"), 1.0f, 1.2f);
                                                        }
                                                    });
                                                    plugin.getLogger().warning("[CraftyAI] Action follow-up failed: " + error);
                                                }
                                            });
                                    }
                                });
                            } else {
                                String formatted = format.replace("{name}", aiNameFinal).replace("{response}", answer);
                                if (isPrivate) {
                                    formatted = "&8[Private] " + formatted;
                                    adapter.sendMessage(player, formatted);
                                } else {
                                    broadcastChatMessage(formatted);
                                }
                                conversations.addInteraction(player.getUniqueId(), question, answer, isPrivate);
                                if (hasAction) {
                                    plugin.getActionHandler().handleAction(plugin, player, action, plugin.getTier(), false, question);
                                }
                                adapter.playSound(player, plugin.getConfig().getString("chat.sounds.success", "ENTITY_EXPERIENCE_ORB_PICKUP"), 1.0f, 1.2f);
                            }
                        } else {
                            adapter.sendMessage(player, ChatColor.RED + "\u2716 " + ChatColor.DARK_RED + "[" + aiNameFinal + "] " + ChatColor.RED + "Neural link offline.");
                        }
                    }
                });
            }
            public void onFailure(final String error) {
                adapter.runSync(new Runnable() {
                    public void run() {
                        if (!player.isOnline()) return;
                        String localAnswer = localBrain.tryAnswer(question);
                        if (localAnswer != null) {
                            String format = plugin.getConfig().getString("chat.format", "&b[{name}] &7\u27A6 &f{response}");
                            String formatted = format.replace("{name}", aiNameFinal).replace("{response}", localAnswer);
                            if (isPrivate) {
                                formatted = "&8[Private] " + formatted;
                                adapter.sendMessage(player, formatted);
                            } else {
                                broadcastChatMessage(formatted);
                            }
                            conversations.addInteraction(player.getUniqueId(), question, localAnswer, isPrivate);
                            if (error != null && error.contains("API key not configured")) {
                                adapter.sendActionBar(player, "&e&lOFFLINE MODE &7- Configure API Key for Online features");
                                adapter.sendMessage(player, "&8&oTip: Set your API key in config.yml to access full AI capabilities.");
                            } else {
                                adapter.sendActionBar(player, "&e&lLOCAL MODE");
                            }
                        } else {
                            adapter.sendMessage(player, "&c[" + aiNameFinal + "] &7" + error);
                            adapter.sendActionBar(player, "&c&lERROR");
                        }
                        adapter.playSound(player, plugin.getConfig().getString("chat.sounds.error", "BLOCK_NOTE_BLOCK_CHIME"));
                    }
                });
            }
        });
    }

    private void broadcastChatMessage(String formatted) {
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            adapter.sendMessage(online, formatted);
        }
    }

    private boolean isOnCooldown(Player player) {
        long now = System.currentTimeMillis();
        final long[] previousTime = {0};
        chatCooldowns.compute(player.getUniqueId(), (key, prev) -> {
            if (prev == null || (now - prev) >= chatCooldownMs) {
                previousTime[0] = 0;
                return now;
            }
            previousTime[0] = prev;
            return prev;
        });
        return previousTime[0] != 0 && (now - previousTime[0]) < chatCooldownMs;
    }

    String buildContext(Player player) {
        StringBuilder ctx = new StringBuilder();
        try {
            ctx.append("[PERMISSIONS]\n");
            ctx.append("GameMode: ").append(player.getGameMode().name().toLowerCase()).append("\n");
            ctx.append("OP Status: ").append(player.isOp() ? "YES \u2014 has operator permissions" : "NO \u2014 does NOT have OP permissions").append("\n");
            ctx.append("Cheats: ").append(player.isOp() || player.hasPermission("crafty.actions") ? "enabled" : "disabled").append("\n");
            ctx.append("Can Fly: ").append(player.getAllowFlight() ? "yes" : "no").append("\n");
            ctx.append("World Type: ").append("dedicated").append("\n");
            ctx.append("Difficulty: ").append(player.getWorld().getDifficulty().name().toLowerCase()).append("\n");
            ctx.append("PVP: ").append(player.getWorld().getPVP() ? "enabled" : "disabled").append("\n");

            String serverBrand = "bukkit";
            try {
                String version = org.bukkit.Bukkit.getVersion().toLowerCase();
                if (version.contains("paper")) serverBrand = "paper";
                else if (version.contains("purpur")) serverBrand = "purpur";
                else if (version.contains("folia")) serverBrand = "folia";
                else if (version.contains("spigot")) serverBrand = "spigot";
            } catch (Exception ignored) {}
            ctx.append("Server: ").append(serverBrand).append("\n\n");

            ctx.append("[ENVIRONMENT]\n");
            ctx.append("World: ").append(player.getWorld().getName()).append("\n");
            ctx.append("Biome: ").append(player.getLocation().getBlock().getBiome().name().toLowerCase()).append("\n");
            ctx.append("Dimension: ").append(player.getWorld().getEnvironment().name().toLowerCase()).append("\n");
            long time = player.getWorld().getTime();
            String timeOfDay = time < 6000 ? "Morning" : time < 12000 ? "Day" : time < 18000 ? "Evening" : "Night";
            ctx.append("Time: ").append(timeOfDay).append("\n");
            boolean raining = player.getWorld().hasStorm();
            boolean thundering = player.getWorld().isThundering();
            ctx.append("Weather: ").append(thundering ? "thunderstorm" : raining ? "rain" : "clear").append("\n\n");

            ctx.append("[PLAYER STATUS]\n");
            ctx.append("Health: ").append((int) player.getHealth()).append("/").append((int) player.getMaxHealth()).append("\n");
            ctx.append("Food: ").append(player.getFoodLevel()).append("/20\n");
            ctx.append("XP Level: ").append(player.getLevel()).append("\n");
            ctx.append("Coords: ").append(player.getLocation().getBlockX())
                    .append(",").append(player.getLocation().getBlockY())
                    .append(",").append(player.getLocation().getBlockZ()).append("\n");

            if (!player.getActivePotionEffects().isEmpty()) {
                ctx.append("Active Effects: ");
                for (org.bukkit.potion.PotionEffect effect : player.getActivePotionEffects()) {
                    ctx.append(getPotionEffectTypeName(effect.getType()).toLowerCase())
                       .append(" ").append(effect.getAmplifier() + 1)
                       .append(" (").append(effect.getDuration() / 20).append("s), ");
                }
                ctx.append("\n");
            }
        } catch (Exception ignored) {}
        return ctx.toString();
    }

    private String getPotionEffectTypeName(org.bukkit.potion.PotionEffectType type) {
        if (type == null) return "unknown";
        try {
            Object key = type.getClass().getMethod("getKey").invoke(type);
            return (String) key.getClass().getMethod("getKey").invoke(key);
        } catch (Throwable t) {
            return type.getName();
        }
    }

    public String inferActionFromText(String answer, String question) {
        if (answer == null || question == null) return null;
        String la = answer.toLowerCase();
        String lq = question.toLowerCase();

        if (la.contains("scanning") || la.contains("looking around") || la.contains("checking surroundings")) {
            return "SCAN_BLOCKS:8:0:1:1";
        }

        if (lq.matches(".*\\b(in|after|wait)\\s+\\d+\\s*(seconds?|sec|minutes?|min)\\b.*")) {
            java.util.regex.Matcher secM = java.util.regex.Pattern.compile("(\\d+)\\s*(?:second|sec)").matcher(lq);
            java.util.regex.Matcher minM = java.util.regex.Pattern.compile("(\\d+)\\s*minute").matcher(lq);
            int delaySec = 10;
            if (secM.find()) delaySec = Integer.parseInt(secM.group(1));
            else if (minM.find()) delaySec = Integer.parseInt(minM.group(1)) * 60;
            delaySec = Math.max(1, Math.min(300, delaySec));

            String innerAction = "TELEPORT_SPAWN";
            if (lq.contains("time") && lq.contains("day")) innerAction = "TIME_DAY";
            else if (lq.contains("time") && lq.contains("night")) innerAction = "TIME_NIGHT";
            else if (lq.contains("heal")) innerAction = "HEAL";
            else if (lq.contains("feed")) innerAction = "FEED";
            else if (lq.contains("weather") && lq.contains("clear")) innerAction = "WEATHER_CLEAR";
            else if (lq.contains("weather") && lq.contains("rain")) innerAction = "WEATHER_RAIN";
            else if (lq.contains("weather") && lq.contains("thunder")) innerAction = "WEATHER_THUNDER";
            else if (lq.contains("kill") && lq.contains("mob")) innerAction = "KILL_MOBS";
            else if (lq.contains("teleport") || lq.contains(" tp ")) {
                java.util.regex.Matcher coordM = java.util.regex.Pattern.compile("tp\\s+(?:me\\s+)?(?:to\\s+)?(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)").matcher(lq);
                if (coordM.find()) innerAction = "TP:" + coordM.group(1) + ":" + coordM.group(2) + ":" + coordM.group(3);
                else innerAction = "TELEPORT_SPAWN";
            }
            return "DELAYED_ACTION:" + delaySec + ":" + innerAction;
        }

        if (la.contains("scheduled") || la.contains("recurring") || lq.contains("every day") || lq.contains("daily") || lq.contains("every hour") || lq.contains("remind me")) {
            String cron = "0 9 * * *";
            if (lq.contains("every hour")) cron = "0 * * * *";
            else if (lq.contains("every minute")) cron = "* * * * *";
            else {
                java.util.regex.Matcher hm = java.util.regex.Pattern.compile("at\\s+(\\d+)\\s*(am|pm)?").matcher(lq);
                if (hm.find()) {
                    int h = Integer.parseInt(hm.group(1));
                    if ("pm".equalsIgnoreCase(hm.group(2)) && h < 12) h += 12;
                    if ("am".equalsIgnoreCase(hm.group(2)) && h == 12) h = 0;
                    cron = "0 " + h + " * * *";
                }
            }
            java.util.regex.Matcher mm = java.util.regex.Pattern.compile("(?:remind(?:\\s+me)?|say|tell|announce)\\s+(?:me\\s+)?(.+)").matcher(lq);
            String msg = mm.find() ? mm.group(1).substring(0, Math.min(100, mm.group(1).length())) : "Reminder!";
            return "SCHEDULE_TASK:" + cron + ":chat:" + msg;
        }

        return null;
    }

    public void removeCooldown(UUID uuid) {
        chatCooldowns.remove(uuid);
    }
}
