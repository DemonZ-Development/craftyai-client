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

import com.google.gson.annotations.SerializedName;

/**
 * Shared Response Model for CraftyAI Neural Engine
 * ===============================================
 * Used across Spigot, Fabric, and Forge to parse AI responses consistently.
 */
public class NeuralResponse {
    public String answer;
    public String response;
    
    @SerializedName("action_trigger")
    public String actionTrigger;
    public String action;
    
    public String source;
    
    @SerializedName("tokens_used")
    public int tokensUsed;

    /**
     * Gets the textual response from the AI, prioritizing the 'response' field.
     */
    public String getAnswer() {
        if (response != null && !response.isEmpty()) return response;
        return answer;
    }

    /**
     * Gets the action trigger, prioritizing 'action_trigger'.
     */
    public String getAction() {
        if (actionTrigger != null && !actionTrigger.isEmpty() && !"null".equalsIgnoreCase(actionTrigger)) return actionTrigger;
        if (action != null && !action.isEmpty() && !"null".equalsIgnoreCase(action)) return action;
        return null;
    }
}
