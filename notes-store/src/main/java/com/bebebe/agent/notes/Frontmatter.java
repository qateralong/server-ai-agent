package com.bebebe.agent.notes;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record Frontmatter(String title, NoteKind kind, LocalDate created, List<String> tags,
                          Map<String, String> extra) {

    public static Frontmatter of(String title, NoteKind kind, LocalDate created, List<String> tags) {
        return new Frontmatter(title, kind, created, List.copyOf(tags), Map.of());
    }

    public record Parsed(Frontmatter frontmatter, String body) { }

    public static Parsed parse(String content, String fallbackTitle) {
        String[] lines = content.split("\n", -1);
        if (lines.length == 0 || !lines[0].strip().equals("---")) {
            return new Parsed(of(fallbackTitle, guessKind(content), null, List.of()), content);
        }
        int end = -1;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].strip().equals("---")) {
                end = i;
                break;
            }
        }
        if (end < 0) {
            return new Parsed(of(fallbackTitle, guessKind(content), null, List.of()), content);
        }

        String title = fallbackTitle;
        NoteKind kind = null;
        LocalDate created = null;
        List<String> tags = List.of();
        Map<String, String> extra = new LinkedHashMap<>();
        for (int i = 1; i < end; i++) {
            String line = lines[i];
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).strip();
            String value = line.substring(colon + 1).strip();
            switch (key) {
                case "title" -> title = unquote(value);
                case "kind" -> kind = NoteKind.fromWire(unquote(value));
                case "created" -> created = parseDate(value);
                case "tags" -> tags = parseList(value);
                default -> extra.put(key, value);
            }
        }
        String body = String.join("\n", java.util.Arrays.copyOfRange(lines, end + 1, lines.length));
        if (body.startsWith("\n")) {
            body = body.substring(1);
        }
        if (kind == null) {
            kind = guessKind(body);
        }
        return new Parsed(new Frontmatter(title, kind, created, tags, extra), body);
    }

    public String render() {
        StringBuilder sb = new StringBuilder("---\n");
        sb.append("title: ").append(quoteIfNeeded(title)).append('\n');
        sb.append("kind: ").append(kind.wire()).append('\n');
        if (created != null) {
            sb.append("created: ").append(created).append('\n');
        }
        sb.append("tags: [").append(String.join(", ", tags)).append("]\n");
        extra.forEach((k, v) -> sb.append(k).append(": ").append(v).append('\n'));
        return sb.append("---\n").toString();
    }

    public Frontmatter withTags(List<String> newTags) {
        return new Frontmatter(title, kind, created, List.copyOf(newTags), extra);
    }

    private static NoteKind guessKind(String body) {
        for (String line : body.split("\n")) {
            if (ChecklistItem.isItemLine(line)) {
                return NoteKind.LIST;
            }
        }
        return NoteKind.NOTE;
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(unquote(value).substring(0, Math.min(10, unquote(value).length())));
        } catch (RuntimeException e) {
            return null;
        }
    }

    static List<String> parseList(String value) {
        String v = value.strip();
        if (v.startsWith("[") && v.endsWith("]")) {
            v = v.substring(1, v.length() - 1);
        }
        List<String> out = new ArrayList<>();
        for (String part : v.split(",")) {
            String tag = unquote(part.strip());
            if (tag.startsWith("#")) {
                tag = tag.substring(1);
            }
            if (!tag.isEmpty()) {
                out.add(tag);
            }
        }
        return List.copyOf(out);
    }

    private static String unquote(String v) {
        String s = v.strip();
        if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String quoteIfNeeded(String v) {
        return v.contains(":") || v.contains("#") || v.startsWith("[") ? "\"" + v.replace("\"", "\\\"") + "\"" : v;
    }
}
