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

import com.demonz.craftyai.common.ActionHarness;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

final class CommandFeedbackBridge {
    private CommandFeedbackBridge() {}
    static CompletableFuture<ActionHarness.Feedback> execute(MinecraftClient client, String command, String action, BooleanSupplier current) {
        CompletableFuture<ActionHarness.Feedback> result = new CompletableFuture<>();
        client.execute(() -> {
            if (!current.getAsBoolean() || client.player == null || client.getNetworkHandler() == null) {
                result.complete(new ActionHarness.Feedback(action, ActionHarness.Status.CANCELLED, "The task is no longer active."));
                return;
            }
            var server = client.getServer();
            if (server == null) {
                client.getNetworkHandler().sendChatCommand(command);
                result.complete(new ActionHarness.Feedback(action, ActionHarness.Status.SUBMITTED,
                        "Command sent. This multiplayer connection does not provide verified command results to Crafty; check the server response."));
                return;
            }
            var playerId = client.player.getUuid();
            server.execute(() -> {
                if (!current.getAsBoolean()) {
                    result.complete(new ActionHarness.Feedback(action, ActionHarness.Status.CANCELLED, "The task was cancelled before execution."));
                    return;
                }
                var player = server.getPlayerManager().getPlayer(playerId);
                if (player == null) {
                    result.complete(new ActionHarness.Feedback(action, ActionHarness.Status.CANCELLED, "The player left the world."));
                    return;
                }
                StringBuilder output = new StringBuilder();
                boolean[] succeeded = {false};
                try {
                    var source = player.getCommandSource().withOutput(new net.minecraft.server.command.CommandOutput() {
                        @Override public void sendMessage(Text message) {
                            if (output.length() < 4096) {
                                if (output.length() > 0) output.append('\n');
                                String text = message.getString();
                                output.append(text, 0, Math.min(text.length(), 4096 - output.length()));
                            }
                            player.sendMessage(message);
                        }
                        @Override public boolean shouldReceiveFeedback() { return true; }
                        @Override public boolean shouldTrackOutput() { return true; }
                        @Override public boolean shouldBroadcastConsoleToOps() { return false; }
                    }).withReturnValueConsumer((success, value) -> succeeded[0] = success);

                    server.getCommandManager().executeWithPrefix(source, command);
                    result.complete(new ActionHarness.Feedback(action,
                            succeeded[0] ? ActionHarness.Status.SUCCEEDED : ActionHarness.Status.FAILED,
                            output.length() > 0 ? output.toString() : succeeded[0] ? "Command completed successfully." : "The server did not report a successful result."));
                } catch (Exception failure) {
                    result.complete(new ActionHarness.Feedback(action, ActionHarness.Status.FAILED,
                            output.length() > 0 ? output.toString() : "The server rejected the command."));
                }
            });
        });
        return result;
    }
}
