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

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class ModernScreenAccess {
    private ModernScreenAccess() {}

    private static final ClassValue<Access> ACCESS = new ClassValue<Access>() {
        @Override protected Access computeValue(Class<?> type) {
            try {
                try {
                    Field screen = type.getField("screen");
                    return new Access(null, screen, null, type.getMethod("setScreen", screen.getType()));
                } catch (NoSuchFieldException oldApiAbsent) {
                    Field gui = type.getField("gui");
                    Method screen = gui.getType().getMethod("screen");
                    return new Access(gui, null, screen,
                            gui.getType().getMethod("setScreen", screen.getReturnType()));
                }
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Unsupported Minecraft screen API", e);
            }
        }
    };

    public static Object current(Object client) {
        try {
            Access access = ACCESS.get(client.getClass());
            Object owner = access.owner(client);
            return access.field != null ? access.field.get(owner) : access.get.invoke(owner);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not read Minecraft screen", e);
        }
    }

    public static void set(Object client, Object screen) {
        try {
            Access access = ACCESS.get(client.getClass());
            access.set.invoke(access.owner(client), screen);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not change Minecraft screen", e);
        }
    }

    public static void closeIfCurrent(Object client, Object expected, Object parent) {
        if (current(client) == expected) set(client, parent);
    }

    private static final class Access {
        final Field gui, field;
        final Method get, set;
        Access(Field gui, Field field, Method get, Method set) {
            this.gui = gui; this.field = field; this.get = get; this.set = set;
        }
        Object owner(Object client) throws IllegalAccessException {
            return gui == null ? client : gui.get(client);
        }
    }
}
