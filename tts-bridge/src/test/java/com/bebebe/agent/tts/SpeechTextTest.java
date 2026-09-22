package com.bebebe.agent.tts;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeechTextTest {

    @Test
    void markupAndLinksAreNotReadAloud() {
        String prepared = SpeechText.prepare("**Готово!** Смотри https://example.com/x?y=1 и `код`.\n\n- пункт 🎉", 1000);

        assertEquals("Готово! Смотри ссылка и код.\nпункт", prepared);
    }

    @Test
    void codeBlockIsSkippedEntirely() {
        String prepared = SpeechText.prepare("Вот:\n```python\nprint(1)\n```\nГотово.", 1000);

        assertTrue(prepared.contains("фрагмент кода пропущен"), prepared);
        assertFalse(prepared.contains("print"));
    }

    @Test
    void longTextIsCutAtSentence() {
        String text = "Первое предложение. Второе предложение. Третье предложение, очень длинное и подробное.";

        String prepared = SpeechText.prepare(text, 45);

        assertEquals("Первое предложение. Второе предложение. Дальше — в тексте.", prepared);
    }

    @Test
    void shortTextIsLeftAlone() {
        assertEquals("Привет, как дела?", SpeechText.prepare("Привет, как дела?", 100));
    }
}
