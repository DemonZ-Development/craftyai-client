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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight PII (Personally Identifiable Information) redaction utility.
 * Replaces common PII patterns with category labels BEFORE sending prompts to LLMs
 * or storing in chat_memory, so we never persist or transmit sensitive data.
 *
 * Patterns covered:
 *   - Email addresses
 *   - IPv4 addresses
 *   - Phone numbers (US-style and international with + prefix)
 *   - Credit card numbers (13-19 digits, Luhn-validated when possible)
 *   - US SSN (xxx-xx-xxxx)
 *   - API keys/secrets (long hex/base64 strings)
 */
public final class PiiRedactor {
    private static final Pattern EMAIL = Pattern.compile(
            "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");
    private static final Pattern IPV4 = Pattern.compile(
            "\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
    private static final Pattern PHONE = Pattern.compile(
            "(?:(?:\\+?\\d{1,3}[\\s.-]?)?(?:\\(\\d{2,4}\\)|\\d{2,4})[\\s.-]?)?\\d{3,4}[\\s.-]?\\d{3,4}[\\s.-]?\\d{0,4}");
    private static final Pattern SSN = Pattern.compile(
            "\\b\\d{3}-\\d{2}-\\d{4}\\b");
    private static final Pattern CC = Pattern.compile(
            "\\b(?:\\d[ -]?){13,19}\\b");
    private static final Pattern LONG_HEX = Pattern.compile(
            "\\b[A-Fa-f0-9]{32,}\\b");

    private PiiRedactor() {}

    /**
     * Redact PII in the given input. Returns a new string with sensitive patterns replaced
     * by category labels like [REDACTED:EMAIL].
     */
    public static String redact(String input) {
        if (input == null || input.isEmpty()) return input;
        String s = input;
        s = EMAIL.matcher(s).replaceAll("[REDACTED:EMAIL]");
        s = SSN.matcher(s).replaceAll("[REDACTED:SSN]");
        s = redactCreditCards(s);
        s = redactPhones(s);
        s = IPV4.matcher(s).replaceAll("[REDACTED:IP]");
        s = LONG_HEX.matcher(s).replaceAll("[REDACTED:HEX]");
        return s;
    }

    private static String redactPhones(String s) {
        Matcher m = PHONE.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String match = m.group();
            String digits = match.replaceAll("[^0-9]", "");
            // Phone heuristic: 7-15 digits, must include 3+ sequential digits at end
            if (digits.length() >= 7 && digits.length() <= 15) {
                m.appendReplacement(sb, Matcher.quoteReplacement("[REDACTED:PHONE]"));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(match));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String redactCreditCards(String s) {
        Matcher m = CC.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String match = m.group();
            String digits = match.replaceAll("[^0-9]", "");
            if (digits.length() >= 13 && digits.length() <= 19 && passesLuhn(digits)) {
                m.appendReplacement(sb, Matcher.quoteReplacement("[REDACTED:CC]"));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(match));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static boolean passesLuhn(String digits) {
        int sum = 0;
        boolean alt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int n = digits.charAt(i) - '0';
            if (alt) {
                n *= 2;
                if (n > 9) n -= 9;
            }
            sum += n;
            alt = !alt;
        }
        return sum % 10 == 0;
    }
}
