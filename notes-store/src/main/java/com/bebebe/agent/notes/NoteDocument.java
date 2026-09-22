package com.bebebe.agent.notes;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public record NoteDocument(long id, Path path, Frontmatter meta, String body, Instant modified) {

    public String title() {
        return meta.title();
    }

    public NoteKind kind() {
        return meta.kind();
    }

    public List<String> tags() {
        return meta.tags();
    }

    public List<ChecklistItem> items() {
        List<ChecklistItem> out = new ArrayList<>();
        for (String line : body.split("\n")) {
            ChecklistItem item = ChecklistItem.parse(out.size(), line);
            if (item != null) {
                out.add(item);
            }
        }
        return out;
    }

    NoteDocument mapItems(java.util.function.UnaryOperator<ChecklistItem> op) {
        StringBuilder sb = new StringBuilder();
        int k = 0;
        for (String line : body.split("\n", -1)) {
            ChecklistItem item = ChecklistItem.parse(k, line);
            if (item == null) {
                sb.append(line).append('\n');
                continue;
            }
            k++;
            ChecklistItem replaced = op.apply(item);
            if (replaced != null) {
                sb.append(replaced.render()).append('\n');
            }
        }
        return new NoteDocument(id, path, meta, trimEdges(sb.toString()), modified);
    }

    NoteDocument appendItem(ChecklistItem item) {
        String b = trimEdges(body);
        return new NoteDocument(id, path, meta, (b.isEmpty() ? "" : b + "\n") + item.render(), modified);
    }

    static String trimEdges(String text) {
        String t = text.replaceAll("^\\s*\\n", "").stripTrailing();
        return t;
    }

    public String render() {
        String b = trimEdges(body);
        return meta.render() + (b.isEmpty() ? "" : "\n" + b + "\n");
    }

    public String describe() {
        StringBuilder sb = new StringBuilder(kind().title()).append(" «").append(title()).append("»");
        if (!tags().isEmpty()) {
            sb.append("  #").append(String.join(" #", tags()));
        }
        sb.append('\n');
        if (kind() == NoteKind.LIST) {
            List<ChecklistItem> items = items();
            if (items.isEmpty()) {
                sb.append("(empty)");
            }
            for (ChecklistItem item : items) {
                sb.append(item.displayLine()).append('\n');
            }
        } else {
            sb.append(body.isBlank() ? "(empty)" : body.strip());
        }
        return sb.toString().strip();
    }

    public String displayLine() {
        if (kind() == NoteKind.LIST) {
            List<ChecklistItem> items = items();
            long done = items.stream().filter(ChecklistItem::done).count();
            return "📋 " + title() + " (" + done + "/" + items.size() + ")";
        }
        return "📝 " + title();
    }
}
