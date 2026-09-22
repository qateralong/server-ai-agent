package com.bebebe.agent.core;

import com.bebebe.agent.memory.Entity;
import com.bebebe.agent.memory.MemoryStore;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class EntityResolver {

    private static final int MIN_STEMMED = 4;

    private static final int MAX_SUFFIX = 3;

    private static final int MIN_LENGTH = 2;

    private final MemoryStore memory;

    public EntityResolver(MemoryStore memory) {
        this.memory = memory;
    }

    public List<Entity> resolve(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Entity> known = memory.entities();
        if (known.isEmpty()) {
            return List.of();
        }

        List<String> words = words(text);
        Map<Long, Entity> found = new LinkedHashMap<>();
        for (String word : words) {
            for (Entity entity : known) {
                if (found.containsKey(entity.id())) {
                    continue;
                }
                if (matches(word, entity)) {
                    found.put(entity.id(), entity);
                }
            }
        }
        return new ArrayList<>(found.values());
    }

    static boolean matches(String word, Entity entity) {
        for (String name : entity.allNamesLower()) {
            if (name.equals(word)) {
                return true;
            }
            if (name.length() < MIN_STEMMED) {
                continue;
            }
            String stem = name.substring(0, name.length() - 1);
            if (word.startsWith(stem) && word.length() <= name.length() + MAX_SUFFIX) {
                return true;
            }
        }
        return false;
    }

    static List<String> words(String text) {
        String normalized = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFC);
        List<String> words = new ArrayList<>();
        for (String raw : normalized.split("[^\\p{L}\\p{N}]+")) {
            if (raw.length() >= MIN_LENGTH) {
                words.add(raw);
            }
        }
        return words;
    }
}
