package com.bebebe.agent.tools.notes;

import com.bebebe.agent.notes.ChecklistItem;
import com.bebebe.agent.notes.NoteDocument;
import com.bebebe.agent.notes.NoteKind;
import com.bebebe.agent.notes.NotesSearch;
import com.bebebe.agent.notes.NotesStore;
import com.bebebe.agent.tools.Tool;
import com.bebebe.agent.tools.ToolContext;
import com.bebebe.agent.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class NotesTool implements Tool {

    public static final String NAME = "notes_tool";

    private static final Logger log = LoggerFactory.getLogger(NotesTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter HUMAN = DateTimeFormatter.ofPattern("d MMMM, HH:mm", Locale.ENGLISH);

    public interface ReminderLink {

        long schedule(Instant fireAt, String prompt, String summary);

        boolean cancel(long jobId);
    }

    private final NotesStore store;
    private final ReminderLink reminders;
    private final Clock clock;

    public NotesTool(NotesStore store, ReminderLink reminders, Clock clock) {
        this.store = store;
        this.reminders = reminders;
        this.clock = clock;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "The user's notes and checklists (markdown files). You know their contents ONLY "
                + "through this tool: for any question about notes and lists -- \"is there a note/list X\", "
                + "\"find the note about Y\", \"what is on my list X\", \"show note X\", \"which lists do I have\", "
                + "\"where did I write about Z\" -- call it first, do not answer from conversation memory and "
                + "do not guess. \"Is there / find / where is it written\" -> action=find, query=title or topic; "
                + "\"what is on list X / show X\" -> read, name=X; \"which do I have\" -> list_all. The find "
                + "result names the match quality: exact, by title/tags, only similar, or nothing -- relay it "
                + "to the user honestly: if there is no exact match, say so and list the similar ones; if "
                + "nothing, say there is no such note. To write: \"add buy bread to the task list\" -> add_item, "
                + "name=\"задачи\", text=\"купить хлеб\" -- the list is created automatically if missing; "
                + "\"note: ...\" -> create_note. If the tool answered that there are several similar documents, "
                + "ask the user which one and repeat with the exact title; do not choose yourself. \"Remind me "
                + "tomorrow about item X\" -> add_item with remind_at (or check/remove for existing items).";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("action", Map.of("type", "string",
                "enum", List.of("create_list", "create_note", "add_item", "check_item", "uncheck_item",
                        "remove_item", "append", "read", "find", "list_all", "delete"),
                "description", "what to do"));
        p.put("name", Map.of("type", "string",
                "description", "title of the list or note as the user named it"));
        p.put("text", Map.of("type", "string",
                "description", "item text (add_item), note body (create_note) or what to append (append)"));
        p.put("item", Map.of("type", "string",
                "description", "which item: number from 1 or its text (check_item, uncheck_item, remove_item)"));
        p.put("tags", Map.of("type", "array", "items", Map.of("type", "string"),
                "description", "tags for a new document, optional"));
        p.put("remind_at", Map.of("type", "string",
                "description", "ISO-8601 with offset -- set a reminder for this item (add_item)"));
        p.put("query", Map.of("type", "string",
                "description", "what to search for (find): title, tag or topic as the user named it, "
                        + "without the words \"note\", \"list\", \"is there\""));
        return p;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        String action = str(arguments, "action").toLowerCase(Locale.ROOT);
        String name = str(arguments, "name");
        String text = str(arguments, "text");
        try {
            return switch (action) {
                case "create_list" -> created(store.createList(requireName(name), tags(arguments)));
                case "create_note" -> created(store.createNote(requireName(name), text, tags(arguments)));
                case "add_item" -> addItem(name, text, str(arguments, "remind_at"));
                case "check_item", "uncheck_item" -> withItem(name, str(arguments, "item"),
                        (doc, item) -> setDone(doc, item, action.equals("check_item")));
                case "remove_item" -> withItem(name, str(arguments, "item"), this::remove);
                case "append" -> withDocument(name, NoteKind.NOTE, doc ->
                        ToolResult.ok("Appended to «" + doc.title() + "».\n\n" + store.append(doc.id(), text).describe()));
                case "read" -> withDocument(name, null, doc -> ToolResult.ok(doc.describe()));
                case "find" -> find(str(arguments, "query").isEmpty() ? name : str(arguments, "query"), context);
                case "list_all" -> listAll();
                case "delete" -> withDocument(name, null, doc -> {
                    store.delete(doc.id());
                    return ToolResult.ok("Deleted: «" + doc.title() + "».");
                });
                default -> ToolResult.failure("Unknown action «" + action + "». Allowed: create_list, "
                        + "create_note, add_item, check_item, uncheck_item, remove_item, append, read, find, list_all, delete.");
            };
        } catch (IllegalArgumentException e) {
            return ToolResult.failure(e.getMessage());
        }
    }

    private ToolResult created(NoteDocument doc) {
        return ToolResult.ok((doc.kind() == NoteKind.LIST ? "Created list «" : "Created note «")
                + doc.title() + "». File: " + doc.path().getFileName());
    }

    private ToolResult addItem(String name, String text, String remindAt) {
        if (text.isEmpty()) {
            return ToolResult.failure("text is missing -- what to add to the list.");
        }
        NoteDocument target;
        switch (store.resolve(requireName(name), NoteKind.LIST)) {
            case NotesStore.Resolution.Found f -> target = f.document();
            case NotesStore.Resolution.Ambiguous a -> {
                return ambiguous(a.candidates());
            }
            case NotesStore.Resolution.NotFound n -> {
                target = store.createList(name.strip(), List.of());
                log.info("List «{}» did not exist -- created", name);
            }
        }

        Long jobId = null;
        String reminderNote = "";
        if (!remindAt.isEmpty()) {
            if (reminders == null) {
                return ToolResult.failure("Scheduler unavailable -- cannot set a reminder, item not added.");
            }
            Instant at = parseTime(remindAt);
            if (at == null || !at.isAfter(clock.instant())) {
                return ToolResult.failure("remind_at «" + remindAt + "» cannot be parsed or is in the past. "
                        + "ISO-8601 with offset in the future is required.");
            }
            jobId = reminders.schedule(at,
                    "Time to remind the user about the item «" + text.strip() + "» from the list «" + target.title() + "».",
                    text.strip());
            reminderNote = " Reminder set for " + ZonedDateTime.ofInstant(at, clock.getZone()).format(HUMAN) + ".";
        }
        NoteDocument doc = store.addItem(target.id(), text, jobId);
        return ToolResult.ok("Added to «" + doc.title() + "»: " + text.strip() + "." + reminderNote
                + "\n\n" + doc.describe());
    }

    private ToolResult setDone(NoteDocument doc, ChecklistItem item, boolean done) {
        NoteDocument updated = store.setDone(doc.id(), item.index(), done);
        String note = "";
        if (done && item.jobId() != null && reminders != null) {
            boolean cancelled = reminders.cancel(item.jobId());
            updated = store.linkItem(doc.id(), item.index(), null);
            note = cancelled ? " Reminder removed." : "";
        }
        return ToolResult.ok((done ? "Checked: " : "Unchecked: ") + item.text() + "." + note
                + "\n\n" + updated.describe());
    }

    private ToolResult remove(NoteDocument doc, ChecklistItem item) {
        if (item.jobId() != null && reminders != null) {
            reminders.cancel(item.jobId());
        }
        NoteDocument updated = store.removeItem(doc.id(), item.index());
        return ToolResult.ok("Removed item: " + item.text() + ".\n\n" + updated.describe());
    }

    private ToolResult listAll() {
        List<NoteDocument> all = store.all();
        if (all.isEmpty()) {
            return ToolResult.ok("No lists or notes yet.");
        }
        StringBuilder sb = new StringBuilder("Documents: ").append(all.size()).append('\n');
        for (NoteDocument doc : all) {
            sb.append("• ").append(doc.displayLine());
            if (!doc.tags().isEmpty()) {
                sb.append("  #").append(String.join(" #", doc.tags()));
            }
            sb.append('\n');
        }
        return ToolResult.ok(sb.toString().strip());
    }

    private ToolResult find(String query, ToolContext context) {
        if (query.isEmpty()) {
            return ToolResult.failure("query is missing -- what to search for.");
        }
        List<NoteDocument> all = store.all();
        if (all.isEmpty()) {
            return ToolResult.ok("No lists or notes yet.");
        }

        List<NoteDocument> exact = new ArrayList<>();
        List<NoteDocument> byName = new ArrayList<>();
        List<NoteDocument> similar = new ArrayList<>();
        Map<Long, List<String>> words = new LinkedHashMap<>();
        for (NotesSearch.TitleMatch m : NotesSearch.matchByName(query, all)) {
            switch (m.quality()) {
                case EXACT -> exact.add(m.document());
                case NAME -> byName.add(m.document());
                case WEAK -> similar.add(m.document());
            }
            words.put(m.document().id(), m.matchedWords());
        }
        for (NotesSearch.Hit hit : store.search(query)) {
            NoteDocument doc = hit.document();
            if (!exact.contains(doc) && !byName.contains(doc) && !similar.contains(doc)) {
                similar.add(doc);
            }
        }

        StringBuilder sb = new StringBuilder();
        List<NoteDocument> show;
        if (!exact.isEmpty()) {
            sb.append("Found, the title matches exactly: ").append(titles(exact)).append('\n');
            show = exact;
        } else if (!byName.isEmpty()) {
            sb.append("Found, matching by title/tags: ").append(titles(byName));
            if (byName.size() > 1) {
                sb.append(" -- several; ask the user which one if they meant a single one");
            }
            sb.append('\n');
            show = byName;
        } else if (!similar.isEmpty()) {
            sb.append("There is NO document «").append(query).append("». Only loosely similar ones, matching single words: ");
            List<String> parts = new ArrayList<>();
            for (NoteDocument doc : similar) {
                List<String> w = words.getOrDefault(doc.id(), List.of());
                parts.add("«" + doc.title() + "»" + (w.isEmpty() ? " (by text)" : " (word «" + String.join("», «", w) + "»)"));
            }
            sb.append(String.join(", ", parts))
                    .append(". Tell the user there is no such note and name the similar ones.\n");
            show = similar;
        } else {
            List<NoteDocument> semantic = context != null && context.llm() != null
                    ? semanticPick(query, all, context) : List.of();
            if (semantic.isEmpty()) {
                return ToolResult.ok("Nothing similar to «" + query + "» -- neither by title, nor by tags, "
                        + "nor by text. Available: " + titles(all) + ".");
            }
            sb.append("Nothing by title or words, but these may fit by meaning: ").append(titles(semantic)).append('\n');
            show = semantic;
        }
        if (show != exact && !similar.isEmpty() && show != similar) {
            sb.append("Also similar: ").append(titles(similar)).append('\n');
        }
        sb.append('\n');
        for (NoteDocument doc : show.subList(0, Math.min(3, show.size()))) {
            sb.append(doc.describe()).append("\n\n");
        }
        if (show.size() > 3) {
            sb.append("Others: ").append(titles(show.subList(3, show.size())));
        }
        return ToolResult.ok(sb.toString().strip());
    }

    private static String titles(List<NoteDocument> docs) {
        return String.join(", ", docs.stream().map(d -> "«" + d.title() + "»").toList());
    }

    private List<NoteDocument> semanticPick(String query, List<NoteDocument> all, ToolContext context) {
        StringBuilder catalog = new StringBuilder();
        for (NoteDocument doc : all) {
            catalog.append("[").append(doc.id()).append("] ").append(doc.kind().title()).append(" «")
                    .append(doc.title()).append("»");
            if (!doc.tags().isEmpty()) {
                catalog.append(" #").append(String.join(" #", doc.tags()));
            }
            String preview = doc.kind() == NoteKind.LIST
                    ? String.join("; ", doc.items().stream().map(ChecklistItem::text).limit(5).toList())
                    : doc.body().strip();
            if (preview.length() > 120) {
                preview = preview.substring(0, 119) + "…";
            }
            if (!preview.isEmpty()) {
                catalog.append(" — ").append(preview);
            }
            catalog.append('\n');
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("ids", Map.of("type", "array", "items", Map.of("type", "integer"))));
        schema.put("required", List.of("ids"));
        schema.put("additionalProperties", false);
        String answer = context.llm().ask(
                "You pick the user's notes and lists by the meaning of the query. Return JSON {\"ids\": [...]} "
                        + "with the ids of matching documents, best first; an empty list if nothing fits.",
                "Query: " + query + "\n\nDocuments:\n" + catalog, schema);
        List<NoteDocument> out = new ArrayList<>();
        try {
            JsonNode ids = JSON.readTree(answer).path("ids");
            for (JsonNode id : ids) {
                store.byId(id.asLong()).ifPresent(out::add);
            }
        } catch (Exception e) {
            log.warn("Model answer for note selection not parsed: {}", answer);
        }
        log.atInfo().addKeyValue("event", "notes.semantic").addKeyValue("query", query)
                .addKeyValue("found", out.size()).log("Notes selected by meaning: {}", out.size());
        return out;
    }

    @FunctionalInterface
    private interface DocAction {
        ToolResult apply(NoteDocument doc);
    }

    @FunctionalInterface
    private interface ItemAction {
        ToolResult apply(NoteDocument doc, ChecklistItem item);
    }

    private ToolResult withDocument(String name, NoteKind kind, DocAction action) {
        return switch (store.resolve(requireName(name), kind)) {
            case NotesStore.Resolution.Found f -> action.apply(f.document());
            case NotesStore.Resolution.Ambiguous a -> ambiguous(a.candidates());
            case NotesStore.Resolution.NotFound n -> notFound(name, kind);
        };
    }

    private ToolResult withItem(String name, String itemRef, ItemAction action) {
        return withDocument(name, NoteKind.LIST, doc -> {
            if (itemRef.isEmpty()) {
                return ToolResult.failure("item is missing -- which item. Items:\n" + doc.describe());
            }
            List<ChecklistItem> items = doc.items();
            Optional<ChecklistItem> byNumber = parseNumber(itemRef)
                    .filter(n -> n >= 1 && n <= items.size()).map(n -> items.get(n - 1));
            if (byNumber.isPresent()) {
                return action.apply(doc, byNumber.get());
            }
            List<ChecklistItem> matches = matchItems(items, itemRef);
            if (matches.size() == 1) {
                return action.apply(doc, matches.getFirst());
            }
            if (matches.isEmpty()) {
                return ToolResult.failure("«" + doc.title() + "» has no item «" + itemRef + "». Items:\n" + doc.describe());
            }
            return ToolResult.failure("Several items match «" + itemRef + "»: "
                    + String.join("; ", matches.stream().map(ChecklistItem::displayLine).toList())
                    + ". Ask the user for the number.");
        });
    }

    private static List<ChecklistItem> matchItems(List<ChecklistItem> items, String ref) {
        String want = ref.strip().toLowerCase(Locale.ROOT);
        List<ChecklistItem> exact = items.stream().filter(i -> i.text().toLowerCase(Locale.ROOT).equals(want)).toList();
        if (!exact.isEmpty()) {
            return exact;
        }
        List<ChecklistItem> contains = items.stream()
                .filter(i -> i.text().toLowerCase(Locale.ROOT).contains(want)
                        || i.text().length() >= 4 && want.contains(i.text().toLowerCase(Locale.ROOT)))
                .toList();
        if (!contains.isEmpty()) {
            return contains;
        }
        return items.stream().filter(i -> NotesSearch.titleScore(ref, i.text()) >= 0.5).toList();
    }

    private ToolResult ambiguous(Collection<NoteDocument> candidates) {
        return ToolResult.failure("Several similar documents: "
                + String.join(", ", candidates.stream().map(d -> "«" + d.title() + "»").toList())
                + ". Ask the user which one and repeat with the exact title.");
    }

    private ToolResult notFound(String name, NoteKind kind) {
        List<NoteDocument> pool = kind == null ? store.all() : store.all(kind);
        if (pool.isEmpty()) {
            return ToolResult.failure("«" + name + "» not found: there is no "
                    + (kind == null ? "document" : kind == NoteKind.LIST ? "list" : "note") + " yet.");
        }
        List<NoteDocument> similar = NotesSearch.matchByName(name, pool).stream()
                .map(NotesSearch.TitleMatch::document).toList();
        String hint = similar.isEmpty()
                ? "available: " + titles(pool)
                : "similar by title: " + titles(similar) + "; all available: " + titles(pool);
        return ToolResult.failure("«" + name + "» not found (" + hint + "). If the user meant "
                + "one of the similar ones, ask them; do not choose yourself.");
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is missing -- the title of the list or note.");
        }
        return name.strip();
    }

    private static String str(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v == null ? "" : String.valueOf(v).strip();
    }

    @SuppressWarnings("unchecked")
    private static List<String> tags(Map<String, Object> args) {
        Object v = args.get("tags");
        if (v == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        Iterable<Object> raw = v instanceof Collection<?> c ? (Collection<Object>) c : List.of(String.valueOf(v).split(","));
        for (Object o : raw) {
            String t = String.valueOf(o).strip().replaceFirst("^#", "").replaceAll("[\\s\\[\\]]+", "-");
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static Optional<Integer> parseNumber(String raw) {
        try {
            return Optional.of(Integer.parseInt(raw.strip().replaceAll("[.)]$", "")));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private Instant parseTime(String raw) {
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(raw).atZone(clock.getZone()).toInstant();
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }
}
