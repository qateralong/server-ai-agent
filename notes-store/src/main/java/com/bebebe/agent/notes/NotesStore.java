package com.bebebe.agent.notes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public final class NotesStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NotesStore.class);
    private static final int PREVIEW_CHARS = 160;

    public sealed interface Resolution {

        record Found(NoteDocument document) implements Resolution { }

        record Ambiguous(List<NoteDocument> candidates) implements Resolution { }

        record NotFound() implements Resolution { }
    }

    private final NotesConfig config;
    private final Clock clock;
    private final NotesIndex index;
    private final NotesGit git;
    private final List<Runnable> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public NotesStore(NotesConfig config) {
        this(config, Clock.systemDefaultZone());
    }

    public NotesStore(NotesConfig config, Clock clock) {
        this.config = config;
        this.clock = clock;
        try {
            Files.createDirectories(config.dir().resolve(NoteKind.LIST.folder()));
            Files.createDirectories(config.dir().resolve(NoteKind.NOTE.folder()));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create notes directory " + config.dir(), e);
        }
        this.index = new NotesIndex(config.dir().resolve(".index.db"));
        this.git = new NotesGit(config.dir(), config.gitHistory());
        try {
            git.ensureRepository();
        } catch (IOException e) {
            log.warn("Git for notes failed to start: {}", e.getMessage());
        }
        int count = reindexAll();
        log.info("Notes: {} (documents: {}, git: {})", config.dir(), count, git.isAvailable() ? "yes" : "no");
    }

    public Path dir() {
        return config.dir();
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    private void notifyListeners() {
        for (Runnable l : listeners) {
            try {
                l.run();
            } catch (RuntimeException e) {
                log.warn("Notes listener failed: {}", e.toString());
            }
        }
    }

    public NotesGit git() {
        return git;
    }

    public List<NoteDocument> all() {
        syncWithDisk();
        List<NoteDocument> out = new ArrayList<>();
        for (NotesIndex.Row row : index.all()) {
            load(row).ifPresent(out::add);
        }
        return out;
    }

    public List<NoteDocument> all(NoteKind kind) {
        return all().stream().filter(d -> d.kind() == kind).toList();
    }

    public Optional<NoteDocument> byId(long id) {
        return index.byId(id).flatMap(this::load);
    }

    public Optional<NoteDocument> byTitle(String title) {
        String want = norm(title);
        return all().stream().filter(d -> norm(d.title()).equals(want)).findFirst();
    }

    public List<NotesSearch.Hit> search(String query) {
        return NotesSearch.search(query, all());
    }

    public Resolution resolve(String name, NoteKind kind) {
        List<NoteDocument> pool = kind == null ? all() : all(kind);
        record Scored(NoteDocument doc, double score) { }
        List<Scored> scored = new ArrayList<>();
        for (NotesSearch.TitleMatch match : NotesSearch.matchByName(name, pool)) {
            if (match.quality() == NotesSearch.Quality.EXACT) {
                return new Resolution.Found(match.document());
            }
            if (match.quality() == NotesSearch.Quality.NAME) {

                double score = Math.max(0.3, NotesSearch.titleScore(name, match.document().title()));
                scored.add(new Scored(match.document(), score));
            }
        }
        scored.sort((a, b) -> Double.compare(b.score, a.score));
        if (scored.isEmpty()) {
            return new Resolution.NotFound();
        }
        if (scored.size() == 1 || scored.get(0).score >= scored.get(1).score * 1.5) {
            return new Resolution.Found(scored.get(0).doc);
        }
        double top = scored.get(0).score;
        List<NoteDocument> close = scored.stream().filter(s -> s.score * 1.5 >= top).map(Scored::doc).toList();
        return new Resolution.Ambiguous(close);
    }

    public NoteDocument createList(String title, List<String> tags) {
        return create(title, NoteKind.LIST, "", tags);
    }

    public NoteDocument createNote(String title, String body, List<String> tags) {
        return create(title, NoteKind.NOTE, body == null ? "" : body, tags);
    }

    private NoteDocument create(String title, NoteKind kind, String body, List<String> tags) {
        String clean = title == null ? "" : title.strip();
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("Empty title");
        }
        Path path = freePath(kind, clean);
        Frontmatter meta = Frontmatter.of(clean, kind, LocalDate.now(clock), tags == null ? List.of() : tags);
        NoteDocument doc = new NoteDocument(0, path, meta, body, clock.instant());
        return write(doc, (kind == NoteKind.LIST ? "Created list «" : "Created note «") + clean + "»");
    }

    public NoteDocument addItem(long id, String text) {
        return addItem(id, text, null);
    }

    public NoteDocument addItem(long id, String text, Long jobId) {
        NoteDocument doc = require(id);
        ChecklistItem item = new ChecklistItem(doc.items().size(), false, clean(text), jobId);
        return write(doc.appendItem(item), "«" + doc.title() + "»: + " + item.text());
    }

    public NoteDocument setDone(long id, int itemIndex, boolean done) {
        NoteDocument doc = require(id);
        ChecklistItem item = itemAt(doc.items(), itemIndex);
        return write(doc.mapItems(it -> it.index() == itemIndex ? it.withDone(done) : it),
                "«" + doc.title() + "»: " + (done ? "✓ " : "↺ ") + item.text());
    }

    public NoteDocument linkItem(long id, int itemIndex, Long jobId) {
        NoteDocument doc = require(id);
        ChecklistItem item = itemAt(doc.items(), itemIndex);
        return write(doc.mapItems(it -> it.index() == itemIndex ? it.withJob(jobId) : it),
                "«" + doc.title() + "»: " + (jobId == null ? "reminder removed from " : "reminder for ") + item.text());
    }

    public NoteDocument removeItem(long id, int itemIndex) {
        NoteDocument doc = require(id);
        ChecklistItem item = itemAt(doc.items(), itemIndex);
        return write(doc.mapItems(it -> it.index() == itemIndex ? null : it),
                "«" + doc.title() + "»: − " + item.text());
    }

    public NoteDocument append(long id, String text) {
        NoteDocument doc = require(id);
        String body = doc.body().isBlank() ? clean(text) : NoteDocument.trimEdges(doc.body()) + "\n\n" + clean(text);
        return write(new NoteDocument(doc.id(), doc.path(), doc.meta(), body, doc.modified()),
                "«" + doc.title() + "»: appended");
    }

    public NoteDocument replaceBody(long id, String body) {
        NoteDocument doc = require(id);
        String clean = body == null ? "" : body.replace("\r", "");
        return write(new NoteDocument(doc.id(), doc.path(), doc.meta(), clean, doc.modified()),
                "«" + doc.title() + "»: text edited");
    }

    public NoteDocument setTags(long id, List<String> tags) {
        NoteDocument doc = require(id);
        return write(new NoteDocument(doc.id(), doc.path(), doc.meta().withTags(tags), doc.body(), doc.modified()),
                "«" + doc.title() + "»: tags");
    }

    public boolean delete(long id) {
        Optional<NoteDocument> doc = byId(id);
        if (doc.isEmpty()) {
            return false;
        }
        try {
            Files.deleteIfExists(doc.get().path());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        index.remove(relative(doc.get().path()));
        git.commit("Deleted: «" + doc.get().title() + "»");
        log.atInfo().addKeyValue("event", "notes.delete").addKeyValue("title", doc.get().title())
                .log("Document «{}» deleted", doc.get().title());
        notifyListeners();
        return true;
    }

    private void syncWithDisk() {
        Set<String> onDisk = new HashSet<>();
        for (NoteKind kind : NoteKind.values()) {
            Path folder = config.dir().resolve(kind.folder());
            try (Stream<Path> files = Files.list(folder)) {
                files.filter(p -> p.toString().endsWith(".md")).forEach(p -> onDisk.add(relative(p)));
            } catch (IOException e) {
                return;
            }
        }
        Set<String> indexed = new HashSet<>();
        for (NotesIndex.Row row : index.all()) {
            indexed.add(row.path());
        }
        if (!onDisk.equals(indexed)) {
            log.info("Notes file set changed externally -- reindexing");
            reindexAll();
        }
    }

    public synchronized int reindexAll() {
        Set<String> seen = new HashSet<>();
        int count = 0;
        for (NoteKind kind : NoteKind.values()) {
            Path folder = config.dir().resolve(kind.folder());
            if (!Files.isDirectory(folder)) {
                continue;
            }
            try (Stream<Path> files = Files.list(folder)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".md")).toList()) {
                    try {
                        NoteDocument doc = parse(0, file);
                        upsert(doc);
                        seen.add(relative(file));
                        count++;
                    } catch (IOException | RuntimeException e) {
                        log.warn("Skipping {}: {}", file, e.getMessage());
                    }
                }
            } catch (IOException e) {
                log.warn("Cannot read {}: {}", folder, e.getMessage());
            }
        }
        index.retainOnly(seen);
        return count;
    }

    private synchronized NoteDocument write(NoteDocument doc, String commitMessage) {
        try {
            Path tmp = doc.path().resolveSibling(doc.path().getFileName() + ".tmp");
            Files.writeString(tmp, doc.render(), StandardCharsets.UTF_8);
            Files.move(tmp, doc.path(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Files.setLastModifiedTime(doc.path(), FileTime.from(clock.instant()));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + doc.path(), e);
        }
        long id = upsert(new NoteDocument(doc.id(), doc.path(), doc.meta(), doc.body(), clock.instant()));
        boolean committed = git.commit(commitMessage);
        log.atInfo().addKeyValue("event", "notes.write").addKeyValue("id", id)
                .addKeyValue("title", doc.title()).addKeyValue("committed", committed)
                .log("Notes: {}", commitMessage);
        NoteDocument saved = byId(id).orElseThrow();
        notifyListeners();
        return saved;
    }

    private long upsert(NoteDocument doc) {
        String preview = doc.kind() == NoteKind.LIST
                ? doc.items().stream().map(ChecklistItem::text).limit(5).reduce((a, b) -> a + "; " + b).orElse("")
                : doc.body().strip();
        if (preview.length() > PREVIEW_CHARS) {
            preview = preview.substring(0, PREVIEW_CHARS - 1) + "…";
        }
        return index.upsert(relative(doc.path()), doc.title(), doc.kind(), doc.tags(), doc.modified(), preview);
    }

    private Optional<NoteDocument> load(NotesIndex.Row row) {
        Path file = config.dir().resolve(row.path());
        try {
            return Optional.of(parse(row.id(), file));
        } catch (IOException e) {
            log.warn("File from the index has disappeared: {}", file);
            index.remove(row.path());
            return Optional.empty();
        }
    }

    private NoteDocument parse(long id, Path file) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        String fallback = file.getFileName().toString().replaceAll("\\.md$", "");
        Frontmatter.Parsed parsed = Frontmatter.parse(content, fallback);
        Frontmatter meta = parsed.frontmatter();

        NoteKind byFolder = file.getParent().getFileName().toString().equals(NoteKind.NOTE.folder())
                ? NoteKind.NOTE : NoteKind.LIST;
        if (meta.kind() != byFolder) {
            meta = new Frontmatter(meta.title(), byFolder, meta.created(), meta.tags(), meta.extra());
        }
        return new NoteDocument(id, file, meta, NoteDocument.trimEdges(parsed.body()),
                Files.getLastModifiedTime(file).toInstant());
    }

    private NoteDocument require(long id) {
        return byId(id).orElseThrow(() -> new IllegalArgumentException("Document #" + id + " not found"));
    }

    private static ChecklistItem itemAt(List<ChecklistItem> items, int index) {
        if (index < 0 || index >= items.size()) {
            throw new IllegalArgumentException("No item #" + (index + 1) + " (total " + items.size() + ")");
        }
        return items.get(index);
    }

    private Path freePath(NoteKind kind, String title) {
        String slug = slug(title);
        Path folder = config.dir().resolve(kind.folder());
        Path candidate = folder.resolve(slug + ".md");
        int n = 2;
        while (Files.exists(candidate)) {
            candidate = folder.resolve(slug + "-" + n++ + ".md");
        }
        return candidate;
    }

    static String slug(String title) {
        String s = title.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{Nd}]+", "-")
                .replaceAll("^-+|-+$", "");
        if (s.length() > 60) {
            s = s.substring(0, 60).replaceAll("-+$", "");
        }
        return s.isEmpty() ? "untitled" : s;
    }

    private String relative(Path file) {
        return config.dir().relativize(file).toString().replace('\\', '/');
    }

    private static String clean(String text) {
        String t = text == null ? "" : text.replace("\r", "").replace('\n', ' ').strip();
        if (t.isEmpty()) {
            throw new IllegalArgumentException("Empty text");
        }
        return t;
    }

    private static String norm(String s) {
        return s == null ? "" : s.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    @Override
    public void close() {
        index.close();
    }
}
