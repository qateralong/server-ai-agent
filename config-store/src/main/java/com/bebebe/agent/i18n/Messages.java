package com.bebebe.agent.i18n;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Translation of interface text. The English string itself is the key, so a line that has
 * no translation yet simply stays English instead of turning into a missing-key placeholder.
 *
 * <p>That property is what makes it safe to translate at the output boundary -- a button
 * label goes through {@link #t} whether it is our own wording or a name the user typed, and
 * anything not in the catalogue comes back untouched.
 *
 * <p>The language is process-wide state on purpose: the agent serves one person, and both
 * the window and the Telegram menu must never disagree about it.
 */
public final class Messages {

    private static final AtomicReference<Language> CURRENT = new AtomicReference<>(Language.EN);
    private static final AtomicReference<Map<String, String>> CATALOGUE =
            new AtomicReference<>(Map.of());

    private Messages() {
    }

    public static Language language() {
        return CURRENT.get();
    }

    public static void setLanguage(Language language) {
        Language target = language == null ? Language.EN : language;
        CURRENT.set(target);
        CATALOGUE.set(target == Language.RU ? RussianCatalogue.entries() : Map.of());
    }

    /** @return the translation of {@code english}, or {@code english} itself if there is none */
    public static String t(String english) {
        if (english == null || english.isEmpty()) {
            return english;
        }
        String translated = CATALOGUE.get().get(english);
        return translated == null ? english : translated;
    }

    /** {@link #t} plus {@link String#formatted}: translate the template, then fill it in. */
    public static String t(String english, Object... args) {
        return t(english).formatted(args);
    }
}
