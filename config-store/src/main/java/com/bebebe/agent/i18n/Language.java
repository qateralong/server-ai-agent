package com.bebebe.agent.i18n;

import java.util.Locale;

/**
 * The language of everything the user reads: the window, the Telegram menu and the bot's
 * own replies. Log files are deliberately not affected -- they are read by whoever runs
 * the agent, and keeping them in one language makes them greppable.
 */
public enum Language {

    EN("en", "English"),
    RU("ru", "Русский");

    private final String code;
    private final String title;

    Language(String code, String title) {
        this.code = code;
        this.title = title;
    }

    public String code() {
        return code;
    }

    /** The name of the language in that language -- it has to be readable before you switch. */
    public String title() {
        return title;
    }

    /**
     * @param raw the value from the config: a code, "auto", or nonsense
     * @return the matching language; "auto" follows the system locale, anything unknown is English
     */
    public static Language from(String raw) {
        if (raw == null || raw.isBlank() || raw.strip().equalsIgnoreCase("auto")) {
            return ofLocale(Locale.getDefault());
        }
        String value = raw.strip().toLowerCase(Locale.ROOT);
        for (Language language : values()) {
            if (language.code.equals(value) || language.name().toLowerCase(Locale.ROOT).equals(value)) {
                return language;
            }
        }
        return EN;
    }

    static Language ofLocale(Locale locale) {
        return locale != null && "ru".equalsIgnoreCase(locale.getLanguage()) ? RU : EN;
    }
}
