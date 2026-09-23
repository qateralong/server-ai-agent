package com.bebebe.agent.core;

import com.bebebe.agent.memory.Entity;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Recognising a known person written slightly differently -- a typo, a different case, a Latin
 * letter that looks like a Cyrillic one -- before bothering the user with "is this the same
 * person?".
 *
 * <p>Deliberately timid. Merging two people who are not the same is a far worse outcome than one
 * extra question: the facts of one get attached to the other and nothing in the interface would
 * ever show that it happened. So the allowed edit distance grows with the length of the name --
 * "Саша" and "Маша" differ by one letter and must never be merged -- and a name that is close to
 * <b>two</b> known people is treated as no match at all and goes back to asking.
 */
final class NameMatch {

    private NameMatch() {
    }

    /**
     * @return the one known person this name is a near-miss of, or empty when there is none or
     *         when more than one would fit
     */
    static Optional<Entity> fuzzy(String name, List<Entity> known) {
        String needle = normalize(name);
        if (needle.isEmpty()) {
            return Optional.empty();
        }
        Entity found = null;
        for (Entity entity : known) {
            if (!matches(needle, entity)) {
                continue;
            }
            if (found != null) {

                return Optional.empty();
            }
            found = entity;
        }
        return Optional.ofNullable(found);
    }

    private static boolean matches(String needle, Entity entity) {
        if (close(needle, normalize(entity.canonicalName()))) {
            return true;
        }
        for (String alias : entity.aliases()) {
            if (close(needle, normalize(alias))) {
                return true;
            }
        }
        return false;
    }

    static boolean close(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        int allowed = allowedDistance(Math.min(a.length(), b.length()));
        if (allowed == 0 || Math.abs(a.length() - b.length()) > allowed) {
            return false;
        }
        return distance(a, b, allowed) <= allowed;
    }

    /**
     * Short names are left to exact matching: at four letters almost every Russian first name is
     * one edit away from another one.
     */
    private static int allowedDistance(int length) {
        if (length < 6) {
            return 0;
        }
        return length < 9 ? 1 : 2;
    }

    /** Lower case, ё as е, and Latin look-alikes folded onto their Cyrillic twins. */
    static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : raw.toLowerCase(Locale.ROOT).toCharArray()) {
            if (!Character.isLetterOrDigit(c)) {
                continue;
            }
            sb.append(switch (c) {
                case 'ё' -> 'е';
                case 'a' -> 'а';
                case 'e' -> 'е';
                case 'o' -> 'о';
                case 'p' -> 'р';
                case 'c' -> 'с';
                case 'x' -> 'х';
                case 'y' -> 'у';
                case 'k' -> 'к';
                case 'm' -> 'м';
                case 't' -> 'т';
                case 'h' -> 'н';
                case 'b' -> 'в';
                default -> c;
            });
        }
        return sb.toString();
    }

    /** Levenshtein, abandoned as soon as it is clear the answer exceeds {@code limit}. */
    static int distance(String a, String b, int limit) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            int best = current[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
                best = Math.min(best, current[j]);
            }
            if (best > limit) {
                return limit + 1;
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
