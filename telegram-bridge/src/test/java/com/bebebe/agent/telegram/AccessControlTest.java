package com.bebebe.agent.telegram;

import com.bebebe.agent.telegram.api.Dto.Chat;
import com.bebebe.agent.telegram.api.Dto.User;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessControlTest {

    private static User user(String username) {
        return new User(777L, false, "Иван", username);
    }

    private static Chat chat(long id) {
        return new Chat(id, "private", null, null);
    }

    @Test
    void allowsByUsernameRegardlessOfCaseAndAt() {
        AccessControl access = new AccessControl(Set.of("qateralong"), Set.of());

        assertTrue(access.isAllowed(user("QaterAlong"), chat(1)));
        assertTrue(access.isAllowed(user("qateralong"), chat(1)));
        assertTrue(access.isAllowed(user("QATERALONG"), chat(1)));
    }

    @Test
    void allowsByChatId() {
        AccessControl access = new AccessControl(Set.of(), Set.of(12345L));

        assertTrue(access.isAllowed(user("кто-то"), chat(12345L)));
        assertFalse(access.isAllowed(user("кто-то"), chat(99999L)));
    }

    @Test
    void deniesStrangers() {
        AccessControl access = new AccessControl(Set.of("qateralong"), Set.of(1L));

        assertFalse(access.isAllowed(user("stranger"), chat(2)));
        assertFalse(access.isAllowed(user(null), chat(2)));
        assertFalse(access.isAllowed(null, chat(2)));
    }

    @Test
    void emptyListDeniesEveryone() {

        AccessControl access = new AccessControl(Set.of(), Set.of());

        assertFalse(access.isConfigured());
        assertFalse(access.isAllowed(user("qateralong"), chat(1)));
    }

    @Test
    void normalisesName() {
        assertEquals("qateralong", AccessControl.normalize("@QaterAlong"));
        assertEquals("qateralong", AccessControl.normalize("  QaterAlong "));
    }
}
