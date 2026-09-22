package com.bebebe.agent.notes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class NotesSearch {

    public record Hit(NoteDocument document, double score) { }

    public enum Quality {

        EXACT,

        NAME,

        WEAK
    }

    public record TitleMatch(NoteDocument document, Quality quality, List<String> matchedWords) { }

    private static final Pattern SPLIT = Pattern.compile("[^\\p{L}\\p{Nd}]+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final int STEM = 4;

    private static final java.util.Set<String> GENERIC = java.util.Set.of(
            "спис", "заме", "запи", "лист", "доку", "чекл",
            "про", "для", "мой", "моя", "мои", "моё", "мое", "меня", "мне", "у", "в", "на", "с", "о", "об",
            "от", "из", "по", "до", "за", "к", "и", "а", "не", "же", "то",
            "есть", "ли", "что", "где", "имен", "назв", "под", "как", "како", "каки", "кака", "назы");

    private NotesSearch() {
    }

    public static List<String> nameStems(String text) {
        List<String> all = stems(text);
        List<String> meaningful = all.stream().filter(s -> !GENERIC.contains(s)).toList();
        return meaningful.isEmpty() ? all : meaningful;
    }

    private static List<String> nameWords(String text) {
        List<String> words = new ArrayList<>();
        for (String word : SPLIT.split(text == null ? "" : text.strip().toLowerCase(Locale.ROOT))) {
            if (word.length() >= 2) {
                words.add(word);
            }
        }
        List<String> meaningful = words.stream().filter(w -> !GENERIC.contains(stem(w))).toList();
        return meaningful.isEmpty() ? words : meaningful;
    }

    private static String stem(String word) {
        return word.length() > STEM ? word.substring(0, STEM) : word;
    }

    static String norm(String text) {
        return String.join(" ", SPLIT.split(text == null ? "" : text.strip().toLowerCase(Locale.ROOT))).strip();
    }

    private static String compact(String norm) {
        return norm.replace(" ", "");
    }

    public static List<TitleMatch> matchByName(String query, List<NoteDocument> documents) {
        String wantNorm = norm(query);
        List<String> want = nameWords(query);
        if (wantNorm.isEmpty() || want.isEmpty()) {
            return List.of();
        }
        String wantCompact = compact(wantNorm);
        List<TitleMatch> out = new ArrayList<>();
        for (NoteDocument doc : documents) {
            String titleNorm = norm(doc.title());
            String titleCompact = compact(titleNorm);
            List<String> titleStems = stems(doc.title());
            List<String> tagStems = stems(String.join(" ", doc.tags()));
            List<String> matched = new ArrayList<>();
            for (String w : want) {
                String s = stem(w);
                if (titleStems.contains(s) || tagStems.contains(s)) {
                    matched.add(w);
                }
            }
            Quality quality;
            if (titleNorm.equals(wantNorm) || titleCompact.equals(wantCompact)) {
                quality = Quality.EXACT;
            } else if (matched.size() == want.size()
                    || wantCompact.length() >= 3 && titleCompact.contains(wantCompact)
                    || titleCompact.length() >= 4 && wantCompact.contains(titleCompact)
                    || want.size() >= 3 && matched.size() * 3 >= want.size() * 2) {
                quality = Quality.NAME;
            } else if (!matched.isEmpty()) {
                quality = Quality.WEAK;
            } else {
                continue;
            }
            out.add(new TitleMatch(doc, quality, matched));
        }
        out.sort(Comparator.comparing(TitleMatch::quality)
                .thenComparing(m -> -m.matchedWords().size())
                .thenComparing(m -> m.document().modified(), Comparator.reverseOrder()));
        return out;
    }

    public static List<Hit> search(String query, List<NoteDocument> documents) {
        List<String> q = stems(query);
        if (q.isEmpty()) {
            return List.of();
        }
        List<Hit> hits = new ArrayList<>();
        for (NoteDocument doc : documents) {
            double score = overlap(q, stems(doc.title())) * 3
                    + overlap(q, stems(String.join(" ", doc.tags()))) * 2
                    + overlap(q, stems(doc.body()));
            if (score > 0) {
                hits.add(new Hit(doc, score));
            }
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed()
                .thenComparing(h -> h.document().modified(), Comparator.reverseOrder()));
        return hits;
    }

    public static double titleScore(String query, String title) {
        List<String> q = stems(query);
        List<String> t = stems(title);
        if (q.isEmpty() || t.isEmpty()) {
            return 0;
        }
        double hit = overlap(q, t);

        return hit == 0 ? 0 : (hit / q.size() + hit / t.size()) / 2;
    }

    static List<String> stems(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String word : SPLIT.split(text.toLowerCase(Locale.ROOT))) {
            if (word.length() < 2) {
                continue;
            }
            out.add(word.length() > STEM ? word.substring(0, STEM) : word);
        }
        return out;
    }

    private static double overlap(List<String> query, List<String> target) {
        if (target.isEmpty()) {
            return 0;
        }
        int hits = 0;
        for (String q : query) {
            if (target.contains(q)) {
                hits++;
            }
        }
        return hits;
    }
}
