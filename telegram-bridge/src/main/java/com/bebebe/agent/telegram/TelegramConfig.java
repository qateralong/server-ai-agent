package com.bebebe.agent.telegram;

import com.bebebe.agent.config.ConfigSection;

import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

public record TelegramConfig(
        boolean enabled,
        String botToken,
        Set<String> allowedUsernames,
        Set<Long> allowedChatIds,
        Duration pollTimeout
) {

    public static final String SECTION = "telegram";

    public static final Duration DEFAULT_POLL_TIMEOUT = Duration.ofSeconds(30);

    public TelegramConfig {
        botToken = botToken == null ? "" : botToken.trim();

        if (!botToken.isEmpty() && !botToken.chars().allMatch(c -> c >= 0x21 && c <= 0x7E)) {
            throw new IllegalArgumentException(
                    "telegram.bot_token contains invalid characters: only printable ASCII is expected");
        }
        if (pollTimeout == null || pollTimeout.isNegative() || pollTimeout.isZero()) {
            throw new IllegalArgumentException("telegram.poll_timeout_seconds must be > 0");
        }
        allowedUsernames = allowedUsernames.stream()
                .map(AccessControl::normalize)
                .collect(Collectors.toUnmodifiableSet());
        allowedChatIds = Set.copyOf(allowedChatIds);
    }

    public static TelegramConfig from(ConfigSection section) {
        return new TelegramConfig(
                section.bool("enabled", true),
                section.string("bot_token", ""),
                section.lowercaseSet("allowed_usernames"),
                section.longSet("allowed_chat_ids"),
                section.seconds("poll_timeout_seconds", DEFAULT_POLL_TIMEOUT));
    }

    public boolean isUsable() {
        return enabled && !botToken.isBlank();
    }

    public AccessControl accessControl() {
        return new AccessControl(allowedUsernames, allowedChatIds);
    }

    @Override
    public String toString() {
        return "TelegramConfig[enabled=%s, token=%s, usernames=%d, chatIds=%d, poll=%ds]"
                .formatted(enabled, botToken.isBlank() ? "<empty>" : "<set>",
                        allowedUsernames.size(), allowedChatIds.size(), pollTimeout.toSeconds());
    }
}
