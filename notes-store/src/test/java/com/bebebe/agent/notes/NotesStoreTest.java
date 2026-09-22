package com.bebebe.agent.notes;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotesStoreTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-09-19T12:00:00Z"), ZoneId.of("Europe/Moscow"));

    @TempDir
    Path temp;

    private Path dir;
    private NotesStore store;

    @BeforeEach
    void setUp() {
        dir = temp.resolve("notes");
        store = new NotesStore(new NotesConfig(dir, true), FIXED);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void firstRunCreatesDirectoryAndGitRepository() {
        assertTrue(Files.isDirectory(dir.resolve("lists")));
        assertTrue(Files.isDirectory(dir.resolve("notes")));
        assertTrue(Files.isDirectory(dir.resolve(".git")), "git init on first run");
        assertTrue(Files.exists(dir.resolve(".gitignore")));
        assertTrue(Files.exists(dir.resolve(".index.db")));
    }

    @Test
    void reopeningKeepsRepositoryAndIds() {
        long id = store.createList("Покупки", List.of()).id();
        store.close();

        store = new NotesStore(new NotesConfig(dir, true), FIXED);

        assertEquals("Покупки", store.byId(id).orElseThrow().title());
        assertEquals(1, store.git().recentLog(10).stream().filter(l -> l.contains("created")).count());
    }

    @Test
    void listIsWrittenAsMarkdownWithFrontmatterAndCheckboxes() throws IOException {
        NoteDocument doc = store.createList("Покупки", List.of("дом", "еда"));
        doc = store.addItem(doc.id(), "молоко");
        doc = store.addItem(doc.id(), "хлеб");
        store.setDone(doc.id(), 0, true);

        String file = Files.readString(dir.resolve("lists/покупки.md"));
        assertEquals("""
                ---
                title: Покупки
                kind: list
                created: 2026-09-19
                tags: [дом, еда]
                ---

                - [x] молоко
                - [ ] хлеб
                """, file);
    }

    @Test
    void noteIsWrittenWithFrontmatterAndBody() throws IOException {
        NoteDocument doc = store.createNote("Идея", "Сделать бота", List.of("работа"));
        store.append(doc.id(), "И ещё абзац");

        String file = Files.readString(dir.resolve("notes/идея.md"));
        assertTrue(file.startsWith("---\ntitle: Идея\nkind: note\n"), file);
        assertTrue(file.endsWith("Сделать бота\n\nИ ещё абзац\n"), file);
    }

    @Test
    void everyChangeIsACommit() {
        NoteDocument doc = store.createList("Дела", List.of());
        store.addItem(doc.id(), "позвонить");
        store.setDone(doc.id(), 0, true);
        store.removeItem(doc.id(), 0);

        List<String> log = store.git().recentLog(20);
        assertEquals(5, log.size(), log.toString());
        assertTrue(log.get(0).contains("− позвонить"), log.get(0));
        assertTrue(log.get(1).contains("✓ позвонить"), log.get(1));
    }

    @Test
    void externallyEditedFileIsPickedUpOnReindex() throws IOException {
        NoteDocument doc = store.createList("Дела", List.of());
        Files.writeString(doc.path(), Files.readString(doc.path()) + "- [ ] дописано в Obsidian\n");
        Files.writeString(dir.resolve("notes/чужая.md"), "# Без шапки\n\nпросто текст\n");

        store.reindexAll();

        assertEquals("дописано в Obsidian", store.byId(doc.id()).orElseThrow().items().getFirst().text());
        NoteDocument foreign = store.byTitle("чужая").orElseThrow();
        assertEquals(NoteKind.NOTE, foreign.kind());
        assertTrue(foreign.body().contains("просто текст"));
    }

    @Test
    void fileDeletedFromDiskLeavesIndex() throws IOException {
        NoteDocument doc = store.createList("Временный", List.of());
        Files.delete(doc.path());

        assertTrue(store.all().isEmpty(), "disk check on read, without explicit reindex");
        assertTrue(store.byId(doc.id()).isEmpty());
    }

    @Test
    void externallyCreatedFileIsVisibleWithoutRestart() throws IOException {
        Files.writeString(dir.resolve("lists/из-obsidian.md"), "- [ ] пункт\n");

        List<NoteDocument> all = store.all();

        assertEquals(1, all.size());
        assertEquals("из-obsidian", all.getFirst().title());
        assertEquals(NoteKind.LIST, all.getFirst().kind());
    }

    @Test
    void identicalTitlesDoNotOverwriteEachOther() {
        NoteDocument a = store.createList("Дела", List.of());
        NoteDocument b = store.createList("Дела", List.of());

        assertFalse(a.path().equals(b.path()));
        assertTrue(b.path().getFileName().toString().equals("дела-2.md"));
    }

    @Test
    void titleFromModelDoesNotBecomePath() {
        assertEquals("etc-passwd", NotesStore.slug("../../etc/passwd"));
        assertEquals("список-дел", NotesStore.slug("Список дел!"));
        assertEquals("untitled", NotesStore.slug("///"));
    }

    @Test
    void reminderLinkIsStoredInLine() throws IOException {
        NoteDocument doc = store.createList("Дела", List.of());
        doc = store.addItem(doc.id(), "сдать отчёт", 42L);

        assertTrue(Files.readString(doc.path()).contains("- [ ] сдать отчёт <!-- job:42 -->"));
        assertEquals(42L, doc.items().getFirst().jobId());
        assertEquals("сдать отчёт", doc.items().getFirst().text());

        doc = store.linkItem(doc.id(), 0, null);
        assertNull(doc.items().getFirst().jobId());
        assertFalse(Files.readString(doc.path()).contains("job:"));
    }

    @Test
    void removingItemKeepsOthersAndSurroundingText() throws IOException {
        NoteDocument doc = store.createList("Дела", List.of());
        Files.writeString(doc.path(), Files.readString(doc.path()) + "## Утро\n- [ ] а\n- [ ] б\n\n## Вечер\n- [ ] в\n");
        store.reindexAll();

        doc = store.removeItem(doc.id(), 1);

        String file = Files.readString(doc.path());
        assertTrue(file.contains("## Утро\n- [ ] а\n\n## Вечер\n- [ ] в"), file);
        assertEquals(List.of("а", "в"), doc.items().stream().map(ChecklistItem::text).toList());
    }

    @Test
    void wordSearchWeightsTitleTagsBody() {
        store.createList("Покупки", List.of("еда"));
        store.createNote("Рецепт борща", "Купить свёклу и капусту", List.of("еда", "кухня"));
        NoteDocument work = store.createNote("Работа", "Созвон в понедельник", List.of());

        List<NotesSearch.Hit> hits = store.search("список покупок еда");

        assertEquals(2, hits.size());
        assertEquals("Покупки", hits.get(0).document().title(), "title weighs more than body");
        assertTrue(store.search("созвон").getFirst().document().id() == work.id());
        assertTrue(store.search("квантовая физика").isEmpty());
    }

    @Test
    void nameResolutionExactMatchWins() {
        store.createList("Задачи", List.of());
        store.createList("Задачи по дому", List.of());

        NotesStore.Resolution r = store.resolve("задачи", NoteKind.LIST);

        assertInstanceOf(NotesStore.Resolution.Found.class, r);
        assertEquals("Задачи", ((NotesStore.Resolution.Found) r).document().title());
    }

    @Test
    void nameResolutionSeveralSimilarIsAmbiguous() {
        store.createList("Задачи по дому", List.of());
        store.createList("Задачи по работе", List.of());

        NotesStore.Resolution r = store.resolve("задачи", NoteKind.LIST);

        assertInstanceOf(NotesStore.Resolution.Ambiguous.class, r);
        assertEquals(2, ((NotesStore.Resolution.Ambiguous) r).candidates().size());
    }

    @Test
    void nameResolutionSingleSimilarTakenNothingNotFound() {
        store.createList("Список покупок", List.of());

        assertInstanceOf(NotesStore.Resolution.Found.class, store.resolve("покупки", NoteKind.LIST));
        assertInstanceOf(NotesStore.Resolution.NotFound.class, store.resolve("фильмы", NoteKind.LIST));
    }

    @Test
    void nameResolutionBySingleCommonWordIsNotFound() {
        store.createNote("Рецепт борща", "свёкла", List.of());
        store.createNote("Рецепт пирога", "яблоки", List.of());

        assertInstanceOf(NotesStore.Resolution.NotFound.class, store.resolve("рецепт оливье", NoteKind.NOTE));
        assertEquals("Рецепт пирога",
                ((NotesStore.Resolution.Found) store.resolve("пирог", NoteKind.NOTE)).document().title());
        assertEquals("Рецепт борща",
                ((NotesStore.Resolution.Found) store.resolve("заметка про борщ", NoteKind.NOTE)).document().title());

        assertInstanceOf(NotesStore.Resolution.Ambiguous.class, store.resolve("рецепт", NoteKind.NOTE));
    }

    @Test
    void titleMatchDistinguishesExactNameAndWeak() {
        store.createNote("Пароль от wifi", "qwerty", List.of("дом"));
        store.createList("Покупки", List.of("дом"));

        List<NotesSearch.TitleMatch> exact = NotesSearch.matchByName("пароль от WiFi", store.all());
        assertEquals(NotesSearch.Quality.EXACT, exact.getFirst().quality());

        List<NotesSearch.TitleMatch> hyphen = NotesSearch.matchByName("wi-fi", store.all());
        assertEquals(1, hyphen.size());
        assertEquals(NotesSearch.Quality.NAME, hyphen.getFirst().quality());

        List<NotesSearch.TitleMatch> tag = NotesSearch.matchByName("дом", store.all());
        assertEquals(2, tag.size(), "tag matched for both");
        assertTrue(tag.stream().allMatch(m -> m.quality() == NotesSearch.Quality.NAME));

        List<NotesSearch.TitleMatch> weak = NotesSearch.matchByName("пароль от почты", store.all());
        assertEquals(NotesSearch.Quality.WEAK, weak.getFirst().quality());
        assertEquals(List.of("пароль"), weak.getFirst().matchedWords(), "the user's word, not a stem stub");

        assertTrue(NotesSearch.matchByName("есть ли у меня", store.all()).isEmpty(), "no common words -- empty");
    }

    @Test
    void missingItemIsErrorNotIndexCrash() {
        NoteDocument doc = store.createList("Дела", List.of());

        assertThrows(IllegalArgumentException.class, () -> store.setDone(doc.id(), 3, true));
        assertThrows(IllegalArgumentException.class, () -> store.addItem(doc.id(), "  "));
    }

    @Test
    void deletingDocumentRemovesFileAndCommits() {
        NoteDocument doc = store.createList("Дела", List.of());

        assertTrue(store.delete(doc.id()));

        assertFalse(Files.exists(doc.path()));
        assertTrue(store.byId(doc.id()).isEmpty());
        assertTrue(store.git().recentLog(1).getFirst().contains("Deleted"));
    }

    @Test
    void storageWorksWithoutGit() {
        store.close();
        Path plain = temp.resolve("plain");
        store = new NotesStore(new NotesConfig(plain, false), FIXED);

        NoteDocument doc = store.createList("Дела", List.of());

        assertFalse(Files.exists(plain.resolve(".git")));
        assertTrue(Files.exists(doc.path()));
    }
}
