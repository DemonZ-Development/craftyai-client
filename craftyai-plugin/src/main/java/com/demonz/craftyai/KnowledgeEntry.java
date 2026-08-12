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
 * KnowledgeEntry — A single Q&A knowledge item for the local RAG system.
 */
public class KnowledgeEntry {

    private final String question;
    private final String answer;
    private final String keywords;
    private final String source;

    public KnowledgeEntry(String question, String answer, String keywords, String source) {
        this.question = question;
        this.answer = answer;
        this.keywords = keywords;
        this.source = source;
    }

    public String getQuestion() { return question; }
    public String getAnswer() { return answer; }
    public String getKeywords() { return keywords; }
    public String getSource() { return source; }
}
