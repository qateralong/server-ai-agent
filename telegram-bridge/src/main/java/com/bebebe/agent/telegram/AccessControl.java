package com.bebebe.agent.telegram;

import com.bebebe.agent.telegram.api.Dto.Chat;
import com.bebebe.agent.telegram.api.Dto.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Set;

public final class AccessControl {

    private static final Logger log = LoggerFactory.getLogger(AccessControl.class);

    private volatile Set<String> usernames;

    private final Set<Long> chatIds;

    public AccessControl(Set<String> usernames, Set<Long> chatIds) {
        this.usernames = Set.copyOf(usernames);
        this.chatIds = Set.copyOf(chatIds);
    }

    public void updateUsernames(java.util.Collection<String> updated) {
        this.usernames = updated.stream()
                .map(AccessControl::normalize)
                .filter(name -> !name.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        log.info("Telegram allow-list updated: {} usernames", usernames.size());
    }

    public boolean isConfigured() {
        return !usernames.isEmpty() || !chatIds.isEmpty();
    }

    public boolean isAllowed(User user, Chat chat) {
        if (chat != null && chatIds.contains(chat.id())) {
            return true;
        }
        return user != null && user.username() != null && usernames.contains(normalize(user.username()));
    }

    public static String normalize(String username) {
        String trimmed = username.trim();
        if (trimmed.startsWith("@")) {
            trimmed = trimmed.substring(1);
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    public void logSummary() {
        if (!isConfigured()) {
            log.warn("Telegram allow-list is empty: the bot will answer no one. "
                    + "Fill in telegram.allowed_usernames or telegram.allowed_chat_ids");
        } else {
            log.info("Telegram allow-list: {} usernames, {} chat_ids", usernames.size(), chatIds.size());
        }
    }
}
