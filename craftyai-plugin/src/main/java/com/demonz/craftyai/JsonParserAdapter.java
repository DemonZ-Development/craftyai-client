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

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.lang.reflect.Method;

public class JsonParserAdapter {
    private static Method parseStringMethod = null;
    private static Object parserInstance = null;
    private static Method parseMethod = null;

    static {
        try {

            parseStringMethod = JsonParser.class.getMethod("parseString", String.class);
        } catch (NoSuchMethodException e) {
            try {

                parserInstance = new JsonParser();
                parseMethod = JsonParser.class.getMethod("parse", String.class);
            } catch (Exception ignored) {}
        }
    }

    public static JsonElement parse(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            if (parseStringMethod != null) {
                return (JsonElement) parseStringMethod.invoke(null, json);
            } else if (parseMethod != null && parserInstance != null) {
                return (JsonElement) parseMethod.invoke(parserInstance, json);
            }
        } catch (Exception e) {

            try {
                @SuppressWarnings("deprecation")
                JsonElement result = new JsonParser().parse(json);
                return result;
            } catch (Exception ignored) {}
        }
        return null;
    }
}
