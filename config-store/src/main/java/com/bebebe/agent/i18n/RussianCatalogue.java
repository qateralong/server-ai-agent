package com.bebebe.agent.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * The Russian translations, kept as a resource rather than in code: it is data, it is long,
 * and a .properties file survives being edited by someone who does not write Java.
 *
 * <p>Keys are the English source strings, so the file reads as a two-column glossary and a
 * line that nobody translated yet is simply absent.
 */
final class RussianCatalogue {

    private static final String RESOURCE = "/i18n/messages_ru.properties";

    private static volatile Map<String, String> cached;

    private RussianCatalogue() {
    }

    static Map<String, String> entries() {
        Map<String, String> local = cached;
        if (local == null) {
            synchronized (RussianCatalogue.class) {
                local = cached;
                if (local == null) {
                    local = load();
                    cached = local;
                }
            }
        }
        return local;
    }

    private static Map<String, String> load() {
        Properties properties = new Properties();
        try (InputStream in = RussianCatalogue.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {

                return Map.of();
            }
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {

            return Map.of();
        }
        Map<String, String> entries = new HashMap<>();
        for (String key : properties.stringPropertyNames()) {
            String value = properties.getProperty(key);
            if (value != null && !value.isBlank()) {
                entries.put(key, value);
            }
        }
        return Map.copyOf(entries);
    }
}
