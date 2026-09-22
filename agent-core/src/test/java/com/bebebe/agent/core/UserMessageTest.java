package com.bebebe.agent.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserMessageTest {

    @Test
    void telegramMessageKnowsWhereToReply() {
        UserMessage message = UserMessage.telegram("Привет", 12345L);

        assertEquals(MessageSource.TELEGRAM, message.source());
        assertEquals("Привет", message.text());
        assertEquals("12345", message.replyTarget().orElseThrow());
    }

    @Test
    void voiceMessageHasNowhereToReplyYet() {

        UserMessage message = UserMessage.voice("включи музыку");

        assertEquals(MessageSource.VOICE, message.source());
        assertTrue(message.replyTarget().isEmpty());
    }

    @Test
    void clipboardFactoryExists() {
        assertEquals(MessageSource.CLIPBOARD, UserMessage.clipboard("текст").source());
    }

    @Test
    void textIsTrimmed() {
        assertEquals("Привет", UserMessage.voice("  Привет \n").text());
    }

    @Test
    void emptyMessageIsDetected() {
        assertTrue(UserMessage.voice("   ").isEmpty());
        assertTrue(UserMessage.voice(null).isEmpty());
        assertFalse(UserMessage.voice("текст").isEmpty());
    }

    @Test
    void sourceIsRequired() {
        assertThrows(IllegalArgumentException.class,
                () -> new UserMessage(null, "текст", Instant.now(), null));
    }

    @Test
    void toStringDoesNotBloatLog() {
        String longText = "а".repeat(500);

        String rendered = UserMessage.voice(longText).toString();

        assertTrue(rendered.length() < 150, rendered.length() + " chars");
        assertTrue(rendered.startsWith("[Voice]"));
        assertTrue(rendered.endsWith("…"));
    }
}
