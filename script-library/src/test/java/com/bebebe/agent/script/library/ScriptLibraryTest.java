package com.bebebe.agent.script.library;

import com.bebebe.agent.config.AppConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptLibraryTest {

    @TempDir
    Path temp;

    private ScriptLibrary library;

    @BeforeEach
    void setUp() {
        library = open();
    }

    @AfterEach
    void tearDown() {
        library.close();
    }

    private ScriptLibrary open() {
        return new ScriptLibrary(LibraryConfig.from(AppConfig.fromToml("""
                [library]
                db_path = "%s"
                scripts_dir = "%s"
                """.formatted(temp.resolve("library.db"), temp.resolve("scripts")))
                .section(LibraryConfig.SECTION)));
    }

    @Test
    void savesScriptAndCode() {
        ScriptEntry entry = library.add("Свободное место", "Показывает место на диске",
                List.of("диск", "место"), "print('ok')");

        assertTrue(entry.id() > 0);
        assertEquals("Свободное место", entry.name());
        assertEquals(List.of("диск", "место"), entry.tags());
        assertEquals(1, entry.version());
        assertEquals("print('ok')", library.codeOf(entry).orElseThrow());
        assertTrue(Files.isReadable(entry.path()));
    }

    @Test
    void newScriptRequiresConfirmation() {

        assertTrue(library.add("Что-то", "", List.of(), "print(1)").requiresConfirmation());
        assertTrue(ScriptLibrary.DEFAULT_REQUIRES_CONFIRMATION);
    }

    @Test
    void confirmationFlagToggles() {
        ScriptEntry entry = library.add("Скрипт", "", List.of(), "print(1)");

        library.setRequiresConfirmation(entry.id(), false);
        assertFalse(library.byId(entry.id()).orElseThrow().requiresConfirmation());

        library.setRequiresConfirmation(entry.id(), true);
        assertTrue(library.byId(entry.id()).orElseThrow().requiresConfirmation());
    }

    @Test
    void countsSuccessesAndFailures() {
        ScriptEntry entry = library.add("Скрипт", "", List.of(), "print(1)");

        library.recordRun(entry.id(), true);
        library.recordRun(entry.id(), true);
        library.recordRun(entry.id(), false);

        ScriptEntry updated = library.byId(entry.id()).orElseThrow();
        assertEquals(2, updated.successCount());
        assertEquals(1, updated.failureCount());
        assertEquals(3, updated.totalRuns());
        assertEquals(2.0 / 3, updated.successRate(), 0.001);
        assertNotEquals(null, updated.lastUsedAt());
    }

    @Test
    void newVersionKeepsOldOneAndItsStats() {

        ScriptEntry first = library.add("Скрипт", "описание", List.of("тег"), "print(1)");
        library.recordRun(first.id(), false);

        ScriptEntry second = library.addVersion(first, "print(2)");

        assertEquals(2, second.version());
        assertEquals(first.rootId(), second.rootId());
        assertEquals("Скрипт", second.name());
        assertEquals(List.of("тег"), second.tags());
        assertEquals("print(2)", library.codeOf(second).orElseThrow());

        ScriptEntry reloaded = library.byId(first.id()).orElseThrow();
        assertEquals(1, reloaded.failureCount());
        assertEquals("print(1)", library.codeOf(reloaded).orElseThrow());
        assertEquals(0, second.failureCount());
    }

    @Test
    void versionsAreNumberedInOrder() {
        ScriptEntry v1 = library.add("Скрипт", "", List.of(), "1");
        ScriptEntry v2 = library.addVersion(v1, "2");
        ScriptEntry v3 = library.addVersion(v2, "3");

        assertEquals(List.of(1, 2, 3),
                library.allVersionsOf(v1.rootId()).stream().map(ScriptEntry::version).toList());
        assertEquals(v1.rootId(), v3.rootId());
    }

    @Test
    void listHasOnlyLatestVersions() {
        ScriptEntry v1 = library.add("Первый", "", List.of(), "1");
        library.addVersion(v1, "2");
        library.add("Второй", "", List.of(), "x");

        List<ScriptEntry> latest = library.latestVersions();

        assertEquals(2, latest.size());
        assertEquals(2, latest.stream().filter(e -> e.name().equals("Первый"))
                .findFirst().orElseThrow().version());
    }

    @Test
    void dataSurvivesRestart() {
        ScriptEntry entry = library.add("Скрипт", "описание", List.of("тег"), "print(1)");
        library.recordRun(entry.id(), true);
        library.setRequiresConfirmation(entry.id(), false);
        library.close();

        library = open();

        ScriptEntry reloaded = library.byId(entry.id()).orElseThrow();
        assertEquals("описание", reloaded.description());
        assertEquals(List.of("тег"), reloaded.tags());
        assertEquals(1, reloaded.successCount());
        assertFalse(reloaded.requiresConfirmation());
    }

    @Test
    void fileNameIsSanitised() {

        assertEquals("svobodnoe-mesto", ScriptLibrary.sanitizeName("svobodnoe/mesto"));
        assertEquals("etc-passwd", ScriptLibrary.sanitizeName("../../etc/passwd"));
        assertEquals("script", ScriptLibrary.sanitizeName(""));
        assertEquals("script", ScriptLibrary.sanitizeName("///"));
        assertTrue(ScriptLibrary.sanitizeName("a".repeat(200)).length() <= 48);

        for (String hostile : List.of("../../etc/passwd", "a/b/c", "..", "~/secret")) {
            String cleaned = ScriptLibrary.sanitizeName(hostile);
            assertFalse(cleaned.contains("/"), cleaned);
            assertFalse(cleaned.contains("."), cleaned);
        }
    }

    @Test
    void scriptFileStaysInsideDirectory() {
        ScriptEntry entry = library.add("../побег", "", List.of(), "print(1)");

        assertTrue(entry.path().normalize().startsWith(temp.resolve("scripts").normalize()),
                "file escaped the directory: " + entry.path());
    }

    @Test
    void descriptionForModelContainsIdAndStats() {
        ScriptEntry entry = library.add("Диск", "Свободное место", List.of("диск"), "print(1)");
        library.recordRun(entry.id(), true);

        String described = library.byId(entry.id()).orElseThrow().describeForModel();

        assertTrue(described.startsWith("[" + entry.id() + "]"), described);
        assertTrue(described.contains("Свободное место"));
        assertTrue(described.contains("диск"));
        assertTrue(described.contains("success 1/1"));
    }

    @Test
    void newVersionAsksConfirmationAgainEvenIfOldWasTrusted() {

        ScriptEntry v1 = library.add("Диск", "место", List.of("диск"), "print(1)");
        library.setRequiresConfirmation(v1.id(), false);
        assertFalse(library.byId(v1.id()).orElseThrow().requiresConfirmation());

        ScriptEntry v2 = library.addVersion(library.byId(v1.id()).orElseThrow(), "print(2)");

        assertTrue(v2.requiresConfirmation());
        assertEquals(v1.rootId(), v2.rootId());
        assertEquals(2, v2.version());
        assertEquals(List.of(1, 2), library.allVersionsOf(v1.rootId()).stream().map(ScriptEntry::version).toList());
    }

    @Test
    void runUpdatesLastUsedTime() throws InterruptedException {
        ScriptEntry entry = library.add("Диск", "место", List.of("диск"), "print(1)");
        assertEquals(null, entry.lastUsedAt(), "empty before the first run");

        Thread.sleep(5);
        library.recordRun(entry.id(), false);

        ScriptEntry used = library.byId(entry.id()).orElseThrow();
        assertTrue(used.lastUsedAt() != null && !used.lastUsedAt().isBefore(entry.createdAt()));
        assertEquals(1, used.failureCount());
        assertEquals(0, used.successCount());
    }

    @Test
    void descriptionUpdateChangesSearchNotCode() {
        ScriptEntry entry = library.add("Диск", "место", List.of("диск"), "print('disk')");
        assertTrue(library.search("свободно в tmp").isEmpty());

        library.updateDescription(entry.id(), "Свободно в /tmp", "сколько свободно места в каталоге tmp", List.of("tmp", "свободно"));

        List<ScriptEntry> found = library.search("сколько свободно в tmp");
        assertEquals(1, found.size());
        assertEquals(entry.id(), found.getFirst().id());
        assertEquals("print('disk')", library.codeOf(found.getFirst()).orElseThrow(), "code untouched");
    }

    @Test
    void missingIdAndVanishedFileDoNotCrashCatalog() throws java.io.IOException {
        assertTrue(library.byId(9999).isEmpty());

        ScriptEntry entry = library.add("Диск", "место", List.of("диск"), "print(1)");
        Files.delete(entry.path());

        assertTrue(library.codeOf(entry).isEmpty(), "no file -- empty, not an exception");
        assertTrue(library.byId(entry.id()).isPresent(), "the catalog entry remains");
    }

    @Test
    void identicalNamesDoNotOverwriteEachOthersFiles() {
        ScriptEntry a = library.add("Диск", "первый", List.of(), "print('a')");
        ScriptEntry b = library.add("Диск", "второй", List.of(), "print('b')");

        assertNotEquals(a.path(), b.path());
        assertNotEquals(a.rootId(), b.rootId(), "same-named scripts are different roots, not versions");
        assertEquals("print('a')", library.codeOf(a).orElseThrow());
        assertEquals("print('b')", library.codeOf(b).orElseThrow());
        assertEquals(2, library.latestVersions().size());
    }
}
