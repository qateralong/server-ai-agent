package com.bebebe.agent.script.library;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptSearchTest {

    private static ScriptEntry entry(long id, String name, String description, List<String> tags) {
        return entry(id, name, description, tags, 0, 0);
    }

    private static ScriptEntry entry(long id, String name, String description,
                                     List<String> tags, int ok, int fail) {
        return new ScriptEntry(id, name, description, tags, Path.of("/tmp/x.py"), 1, id,
                ok, fail, true, Instant.now(), null);
    }

    private static final List<ScriptEntry> LIBRARY = List.of(
            entry(1, "Свободное место на диске", "Показывает свободные гигабайты", List.of("диск", "место")),
            entry(2, "Список процессов", "Показывает запущенные процессы", List.of("процессы")),
            entry(3, "Текущее время", "Дата и день недели", List.of("время", "дата")));

    @Test
    void findsByWordFromName() {
        List<ScriptEntry> found = ScriptSearch.find("сколько места на диске?", LIBRARY, 3);

        assertFalse(found.isEmpty());
        assertEquals(1, found.getFirst().id());
    }

    @Test
    void findsByTag() {
        assertEquals(2, ScriptSearch.find("покажи процессы", LIBRARY, 3).getFirst().id());
    }

    @Test
    void handlesWordEndings() {

        assertEquals(3, ScriptSearch.find("какая дата сегодня", LIBRARY, 3).getFirst().id());
        assertEquals(1, ScriptSearch.find("места на дисках", LIBRARY, 3).getFirst().id());
    }

    @Test
    void offersNothingForUnrelatedQuery() {

        assertTrue(ScriptSearch.find("расскажи анекдот про пингвинов", LIBRARY, 3).isEmpty());
        assertTrue(ScriptSearch.find("", LIBRARY, 3).isEmpty());
        assertTrue(ScriptSearch.find("столица Франции", LIBRARY, 3).isEmpty());
    }

    @Test
    void shortWordsAreIgnored() {

        assertTrue(ScriptSearch.find("на и до", LIBRARY, 3).isEmpty());
    }

    @Test
    void reliableScriptRanksAboveEquallySimilarFailingOne() {
        List<ScriptEntry> two = List.of(
                entry(10, "Место на диске", "вариант A", List.of("диск"), 0, 5),
                entry(11, "Место на диске", "вариант B", List.of("диск"), 5, 0));

        assertEquals(11, ScriptSearch.find("место на диске", two, 2).getFirst().id());
    }

    @Test
    void respectsLimit() {
        List<ScriptEntry> many = List.of(
                entry(1, "Диск один", "место", List.of("диск")),
                entry(2, "Диск два", "место", List.of("диск")),
                entry(3, "Диск три", "место", List.of("диск")));

        assertEquals(2, ScriptSearch.find("диск место", many, 2).size());
    }

    @Test
    void caseAndSeparatorsDoNotMatter() {
        assertEquals(1, ScriptSearch.find("СВОБОДНОЕ МЕСТО, диск!", LIBRARY, 3).getFirst().id());
    }

    @Test
    void stemsAreCutToFourLetters() {
        assertTrue(ScriptSearch.stems("файлами").contains("файл"));
        assertTrue(ScriptSearch.stems("файлы").contains("файл"));
        assertTrue(ScriptSearch.stems("файл").contains("файл"));
        assertFalse(ScriptSearch.stems("на").contains("на"));
    }
}
