package com.bebebe.agent.script.library;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ScriptSearch {

    private static final int MIN_TOKEN_LENGTH = 3;

    private static final int STEM_LENGTH = 4;

    private static final double NAME_WEIGHT = 3.0;
    private static final double TAG_WEIGHT = 2.0;
    private static final double DESCRIPTION_WEIGHT = 1.0;

    private static final double MIN_SCORE = 2.0;

    private ScriptSearch() {
    }

    public static List<ScriptEntry> find(String query, List<ScriptEntry> entries, int limit) {
        Set<String> queryStems = stems(query);
        if (queryStems.isEmpty() || entries.isEmpty()) {
            return List.of();
        }

        record Scored(ScriptEntry entry, double score) {
        }

        List<Scored> scored = new ArrayList<>();
        for (ScriptEntry entry : entries) {
            double score = score(queryStems, entry);
            if (score >= MIN_SCORE) {
                scored.add(new Scored(entry, score));
            }
        }

        return scored.stream()
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(limit)
                .map(Scored::entry)
                .toList();
    }

    static double score(Set<String> queryStems, ScriptEntry entry) {
        double score = 0;
        score += NAME_WEIGHT * overlap(queryStems, stems(entry.name()));
        score += TAG_WEIGHT * overlap(queryStems, stems(String.join(" ", entry.tags())));
        score += DESCRIPTION_WEIGHT * overlap(queryStems, stems(entry.description()));

        if (score == 0) {
            return 0;
        }

        return score * (0.75 + 0.5 * entry.successRate());
    }

    private static int overlap(Set<String> left, Set<String> right) {
        int count = 0;
        for (String stem : left) {
            if (right.contains(stem)) {
                count++;
            }
        }
        return count;
    }

    static Set<String> stems(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }

        String normalized = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFC);

        Set<String> result = new LinkedHashSet<>();
        for (String raw : normalized.split("[^\\p{L}\\p{N}_]+")) {
            if (raw.length() < MIN_TOKEN_LENGTH) {
                continue;
            }
            result.add(raw.length() <= STEM_LENGTH ? raw : raw.substring(0, STEM_LENGTH));
        }
        return result;
    }
}
