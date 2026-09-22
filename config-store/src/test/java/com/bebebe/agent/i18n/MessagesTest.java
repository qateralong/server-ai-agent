package com.bebebe.agent.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessagesTest {

    @AfterEach
    void resetLanguage() {
        Messages.setLanguage(Language.EN);
    }

    @Test
    void englishIsReturnedAsIsWhenThereIsNoTranslation() {
        Messages.setLanguage(Language.RU);

        assertEquals("нет такой строки в словаре", Messages.t("нет такой строки в словаре"),
                "a miss must return the source, not an empty string or a placeholder");
    }

    @Test
    void russianComesFromTheCatalogue() {
        Messages.setLanguage(Language.RU);
        assertEquals("⚡ Питание", Messages.t("⚡ Power"));

        Messages.setLanguage(Language.EN);
        assertEquals("⚡ Power", Messages.t("⚡ Power"), "English asks the catalogue for nothing");
    }

    @Test
    void formatPlaceholdersSurviveTranslation() {

        Messages.setLanguage(Language.RU);
        String greeting = Messages.t("Deleted: ") + "x";
        assertFalse(greeting.isBlank());
        assertEquals("Запускался 3 раз, успешно 2.",
                Messages.t("Ran %d times before, %d successfully.", 3, 2));
    }

    /**
     * A translation that loses or reorders a %-specifier would not be a typo but a crash in
     * String.formatted at the moment the screen is drawn, so the whole catalogue is checked.
     */
    @Test
    void everyTranslationKeepsTheSameFormatSpecifiers() {
        Pattern specifier = Pattern.compile("%(?:\\d+\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z%]");
        List<String> broken = new ArrayList<>();
        for (Map.Entry<String, String> entry : RussianCatalogue.entries().entrySet()) {
            if (!specifiers(specifier, entry.getKey()).equals(specifiers(specifier, entry.getValue()))) {
                broken.add(entry.getKey());
            }
        }
        assertTrue(broken.isEmpty(), "the placeholders differ from the English source in: " + broken);
    }

    private static List<String> specifiers(Pattern pattern, String text) {
        List<String> found = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    @Test
    void theCatalogueIsNotEmpty() {
        assertFalse(RussianCatalogue.entries().isEmpty(), "the resource did not load");
    }

    @Test
    void unknownAndEmptyCodesFallBackSensibly() {
        assertSame(Language.RU, Language.from("ru"));
        assertSame(Language.RU, Language.from(" RU "));
        assertSame(Language.EN, Language.from("klingon"));
        assertSame(Language.RU, Language.ofLocale(Locale.of("ru", "RU")));
        assertSame(Language.EN, Language.ofLocale(Locale.US));
    }
}
