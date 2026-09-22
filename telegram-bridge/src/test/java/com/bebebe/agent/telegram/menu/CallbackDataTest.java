package com.bebebe.agent.telegram.menu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallbackDataTest {

    @Test
    void encodesSegmentsWithColon() {
        assertEquals("menu:settings", CallbackData.of("menu", "settings").encode());
        assertEquals("pwr:set:on", CallbackData.power(true).encode());
        assertEquals("mem:person:42:delete",
                CallbackData.of("mem", "person", "42", "delete").encode());
    }

    @Test
    void decodesWhatItEncoded() {
        CallbackData original = CallbackData.of("mem", "person", "42", "delete");

        CallbackData decoded = CallbackData.decode(original.encode());

        assertEquals(original, decoded);
        assertEquals("mem", decoded.namespace());
        assertEquals("person", decoded.action());
        assertEquals("42", decoded.arg(1).orElseThrow());
        assertEquals("delete", decoded.arg(2).orElseThrow());
    }

    @Test
    void returnsEmptyActionWithoutSegments() {
        CallbackData data = CallbackData.decode("nav");

        assertEquals("nav", data.namespace());
        assertEquals("", data.action());
        assertTrue(data.arg(0).isEmpty());
    }

    @Test
    void rejectsExceeding64Bytes() {

        String tooLong = "x".repeat(CallbackData.MAX_BYTES);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CallbackData.of("menu", tooLong));

        assertTrue(e.getMessage().contains("64"), e.getMessage());
    }

    @Test
    void limitIsCountedInBytesNotChars() {

        String cyrillic = "я".repeat(40);
        assertTrue(cyrillic.length() < CallbackData.MAX_BYTES);
        assertTrue(cyrillic.getBytes(StandardCharsets.UTF_8).length > CallbackData.MAX_BYTES);

        assertThrows(IllegalArgumentException.class, () -> CallbackData.of("m", cyrillic));
    }

    @Test
    void rejectsSeparatorInsideSegment() {
        assertThrows(IllegalArgumentException.class, () -> CallbackData.of("menu", "a:b"));
    }

    @Test
    void rejectsEmptySegments() {
        assertThrows(IllegalArgumentException.class, () -> CallbackData.of("menu", ""));
        assertThrows(IllegalArgumentException.class, () -> CallbackData.decode(""));
        assertThrows(IllegalArgumentException.class, () -> CallbackData.decode("menu:"));
    }

    @ParameterizedTest
    @EnumSource(MenuSection.class)
    void allSectionsFitBudgetWithMargin(MenuSection section) {
        int size = CallbackData.section(section).byteSize();

        assertTrue(size <= CallbackData.WARN_BYTES,
                "Section " + section + " takes " + size + " bytes, threshold " + CallbackData.WARN_BYTES);
    }

    @Test
    void sectionIdsAreAsciiOnly() {

        for (MenuSection section : MenuSection.values()) {
            assertTrue(section.id().chars().allMatch(c -> c >= 'a' && c <= 'z'),
                    "Section id must consist of lowercase latin letters: " + section.id());
        }
    }

    @Test
    void comparesNamespaceAndAction() {
        CallbackData data = CallbackData.power(false);

        assertTrue(data.is(CallbackData.NS_POWER, "set"));
        assertTrue(data.inNamespace(CallbackData.NS_POWER));
        assertFalse(data.is(CallbackData.NS_MENU, "set"));
    }
}
