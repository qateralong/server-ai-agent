package com.bebebe.agent.tools.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FreshnessHintsTest {

    @Test
    void catchesRatesWeatherNewsAndNow() {
        assertTrue(FreshnessHints.looksTimeSensitive("какой курс доллара?"));
        assertTrue(FreshnessHints.looksTimeSensitive("Что с погодой в Казани?"));
        assertTrue(FreshnessHints.looksTimeSensitive("какие новости про Arch"));
        assertTrue(FreshnessHints.looksTimeSensitive("что сейчас происходит на рынке"));
        assertTrue(FreshnessHints.looksTimeSensitive("сколько стоит биткоин сегодня"));
        assertTrue(FreshnessHints.looksTimeSensitive("вышла ли новая версия Python"));
    }

    @Test
    void doesNotCatchTimeless() {
        assertFalse(FreshnessHints.looksTimeSensitive("столица Франции?"));
        assertFalse(FreshnessHints.looksTimeSensitive("объясни, как работает TCP"));
        assertFalse(FreshnessHints.looksTimeSensitive("напиши скрипт, который считает файлы"));
        assertFalse(FreshnessHints.looksTimeSensitive(""));
        assertFalse(FreshnessHints.looksTimeSensitive(null));
    }

    @Test
    void hintMentionsTool() {
        assertTrue(FreshnessHints.hintFor("курс евро").contains("web_search"));
        assertTrue(FreshnessHints.hintFor("столица Франции").isEmpty());
    }
}
