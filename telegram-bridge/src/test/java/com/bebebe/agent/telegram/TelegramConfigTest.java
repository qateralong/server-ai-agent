package com.bebebe.agent.telegram;

import com.bebebe.agent.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramConfigTest {

    private static TelegramConfig parse(String toml) {
        return TelegramConfig.from(AppConfig.fromToml(toml).section(TelegramConfig.SECTION));
    }

    @Test
    void readsFullSection() {
        TelegramConfig config = parse("""
                [telegram]
                enabled = true
                bot_token = "123456:AAbbcc"
                allowed_usernames = ["@QaterAlong", "Second"]
                allowed_chat_ids = [111, 222]
                poll_timeout_seconds = 45
                """);

        assertTrue(config.enabled());
        assertEquals("123456:AAbbcc", config.botToken());
        assertEquals(Set.of("qateralong", "second"), config.allowedUsernames());
        assertEquals(Set.of(111L, 222L), config.allowedChatIds());
        assertEquals(Duration.ofSeconds(45), config.pollTimeout());
        assertTrue(config.isUsable());
    }

    @Test
    void substitutesDefaults() {
        TelegramConfig config = parse("""
                [telegram]
                bot_token = "1:x"
                """);

        assertTrue(config.enabled());
        assertEquals(TelegramConfig.DEFAULT_POLL_TIMEOUT, config.pollTimeout());
        assertTrue(config.allowedUsernames().isEmpty());
    }

    @Test
    void withoutTokenBridgeDoesNotStart() {
        assertFalse(parse("[telegram]\n").isUsable());
        assertFalse(parse("""
                [telegram]
                enabled = false
                bot_token = "1:x"
                """).isUsable());
    }

    @Test
    void rejectsNonAsciiToken() {

        assertThrows(IllegalArgumentException.class, () -> parse("""
                [telegram]
                bot_token = "токен"
                """));
    }

    @Test
    void doesNotPrintTokenInToString() {
        String text = parse("""
                [telegram]
                bot_token = "123456:SECRET-VALUE"
                """).toString();

        assertFalse(text.contains("SECRET-VALUE"));
        assertTrue(text.contains("<set>"));
    }
}
