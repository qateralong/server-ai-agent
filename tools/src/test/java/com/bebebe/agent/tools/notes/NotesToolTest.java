package com.bebebe.agent.tools.notes;

import com.bebebe.agent.notes.NoteDocument;
import com.bebebe.agent.notes.NotesConfig;
import com.bebebe.agent.notes.NotesStore;
import com.bebebe.agent.tools.ToolContext;
import com.bebebe.agent.tools.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotesToolTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-09-19T12:00:00Z"), ZoneId.of("Europe/Moscow"));
    private static final ToolContext NO_LLM = new ToolContext(
            (s, u, schema) -> { throw new AssertionError("the model must not be called"); }, "");

    @TempDir
    Path temp;

    private NotesStore store;
    private NotesTool tool;
    private final List<String> scheduled = new ArrayList<>();
    private final List<Long> cancelled = new ArrayList<>();

    @BeforeEach
    void setUp() {
        store = new NotesStore(new NotesConfig(temp.resolve("n"), false), FIXED);
        tool = new NotesTool(store, new NotesTool.ReminderLink() {
            @Override
            public long schedule(Instant fireAt, String prompt, String summary) {
                scheduled.add(fireAt + " " + prompt);
                return 77L;
            }

            @Override
            public boolean cancel(long jobId) {
                cancelled.add(jobId);
                return true;
            }
        }, FIXED);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private ToolResult run(Object... kv) {
        Map<String, Object> args = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            args.put((String) kv[i], kv[i + 1]);
        }
        return tool.execute(args, NO_LLM);
    }

    @Test
    void addToTaskListCreatesListIfMissing() {
        ToolResult r = run("action", "add_item", "name", "задачи", "text", "купить хлеб");

        assertTrue(r.success(), r.content());
        NoteDocument doc = store.byTitle("задачи").orElseThrow();
        assertEquals("купить хлеб", doc.items().getFirst().text());
        assertTrue(r.content().contains("☐ купить хлеб"), r.content());
    }

    @Test
    void similarListIsFoundByInexactName() {
        store.createList("Список покупок", List.of());

        run("action", "add_item", "name", "покупки", "text", "молоко");

        assertEquals(1, store.all().size(), "no new list must be created");
        assertEquals("молоко", store.byTitle("Список покупок").orElseThrow().items().getFirst().text());
    }

    @Test
    void ambiguityIsQuestionToUserNotGuess() {
        store.createList("Задачи по дому", List.of());
        store.createList("Задачи по работе", List.of());

        ToolResult r = run("action", "add_item", "name", "задачи", "text", "x");

        assertFalse(r.success());
        assertTrue(r.content().contains("«Задачи по дому»"), r.content());
        assertTrue(r.content().contains("«Задачи по работе»"), r.content());
        assertTrue(r.content().contains("Ask the user"), r.content());
        assertEquals(2, store.all().size(), "nothing created or added");
        assertTrue(store.all().stream().allMatch(d -> d.items().isEmpty()));
    }

    @Test
    void checkByNumberAndByText() {
        run("action", "add_item", "name", "дела", "text", "позвонить маме");
        run("action", "add_item", "name", "дела", "text", "сдать отчёт");

        assertTrue(run("action", "check_item", "name", "дела", "item", "2").success());
        assertTrue(run("action", "check_item", "name", "дела", "item", "маме").success());

        NoteDocument doc = store.byTitle("дела").orElseThrow();
        assertTrue(doc.items().stream().allMatch(i -> i.done()));
        assertTrue(run("action", "uncheck_item", "name", "дела", "item", "1").success());
        assertFalse(store.byTitle("дела").orElseThrow().items().getFirst().done());
    }

    @Test
    void missingItemIsErrorWithItemList() {
        run("action", "add_item", "name", "дела", "text", "а");

        ToolResult r = run("action", "check_item", "name", "дела", "item", "варить борщ");

        assertFalse(r.success());
        assertTrue(r.content().contains("has no item"), r.content());
        assertTrue(r.content().contains("1. ☐ а"), r.content());
    }

    @Test
    void removeItem() {
        run("action", "add_item", "name", "дела", "text", "а");
        run("action", "add_item", "name", "дела", "text", "б");

        ToolResult r = run("action", "remove_item", "name", "дела", "item", "а");

        assertTrue(r.success());
        assertEquals(List.of("б"), store.byTitle("дела").orElseThrow().items().stream().map(i -> i.text()).toList());
    }

    @Test
    void itemWithReminderSchedulesJobAndCheckCancelsIt() {
        ToolResult r = run("action", "add_item", "name", "дела", "text", "сдать отчёт",
                "remind_at", "2026-09-20T09:00:00+03:00");

        assertTrue(r.success(), r.content());
        assertEquals(1, scheduled.size());
        assertTrue(scheduled.getFirst().startsWith("2026-09-20T06:00:00Z"), scheduled.getFirst());
        assertTrue(scheduled.getFirst().contains("«сдать отчёт» from the list «дела»"));
        assertEquals(77L, store.byTitle("дела").orElseThrow().items().getFirst().jobId());
        assertTrue(r.content().contains("Reminder set for 20 September, 09:00"), r.content());

        run("action", "check_item", "name", "дела", "item", "1");

        assertEquals(List.of(77L), cancelled);
        assertEquals(null, store.byTitle("дела").orElseThrow().items().getFirst().jobId());
    }

    @Test
    void reminderInPastDoesNotAddItem() {
        ToolResult r = run("action", "add_item", "name", "дела", "text", "x", "remind_at", "2026-09-19T10:00:00+03:00");

        assertFalse(r.success());
        assertTrue(scheduled.isEmpty());
        assertTrue(store.byTitle("дела").orElseThrow().items().isEmpty());
    }

    @Test
    void noteIsCreatedAppendedAndRead() {
        assertTrue(run("action", "create_note", "name", "Идея", "text", "Сделать бота", "tags", List.of("работа")).success());
        assertTrue(run("action", "append", "name", "идея", "text", "И ещё").success());

        ToolResult r = run("action", "read", "name", "идея");

        assertTrue(r.content().contains("Сделать бота"), r.content());
        assertTrue(r.content().contains("И ещё"), r.content());
        assertTrue(r.content().contains("#работа"), r.content());
    }

    @Test
    void wordSearchDoesNotCallModel() {
        store.createList("Покупки", List.of("еда"));
        store.createNote("Идея", "про бота", List.of());

        ToolResult r = run("action", "find", "query", "покупки");

        assertTrue(r.content().contains("matches exactly: «Покупки»"), r.content());
        assertFalse(r.content().contains("«Идея»"), r.content());
    }

    @Test
    void directQuestionFindsNoteByTitleThroughFillerWords() {
        store.createNote("Рецепт борща", "свёкла, капуста", List.of("еда"));
        store.createList("Покупки", List.of());

        for (String q : List.of("есть ли у меня заметка с именем рецепт борща", "заметка про борщ",
                "борщ", "Рецепт Борща", "еда", "что у меня в списке покупок")) {
            ToolResult r = run("action", "find", "query", q);
            assertTrue(r.success(), q);
            assertTrue(r.content().startsWith("Found,"), q + " → " + r.content());
            assertTrue(r.content().contains(q.contains("покуп") ? "«Покупки»" : "«Рецепт борща»"), q + " → " + r.content());
            assertFalse(r.content().contains("NO document"), q + " → " + r.content());
        }
        assertTrue(run("action", "find", "query", "рецепт борща").content().contains("matches exactly"));
    }

    @Test
    void singleWordMatchIsNoWithSimilarNotFound() {
        store.createNote("Рецепт борща", "свёкла", List.of());
        store.createList("Покупки", List.of());
        store.addItem(store.byTitle("Покупки").orElseThrow().id(), "молоко", null);

        ToolResult r = run("action", "find", "query", "рецепт пирога");
        assertTrue(r.content().contains("NO document «рецепт пирога»"), r.content());
        assertTrue(r.content().contains("«Рецепт борща» (word «рецепт»)"), r.content());
        assertTrue(r.content().contains("no such note"), r.content());

        ToolResult body = run("action", "find", "query", "молоко");
        assertTrue(body.content().contains("NO document «молоко»") && body.content().contains("«Покупки» (by text)"), body.content());

        ToolResult none = tool.execute(Map.of("action", "find", "query", "фильмы"),
                new ToolContext((s, u, schema) -> "{\"ids\": []}", ""));
        assertTrue(none.content().startsWith("Nothing similar to «фильмы»"), none.content());
        assertTrue(none.content().contains("Available: «Покупки», «Рецепт борща»") || none.content().contains("Available: «Рецепт борща», «Покупки»"), none.content());
    }

    @Test
    void severalTitleMatchesAreAllListed() {
        store.createList("Задачи по дому", List.of());
        store.createList("Задачи по работе", List.of());

        ToolResult r = run("action", "find", "query", "есть ли у меня список задач");

        assertTrue(r.content().contains("«Задачи по работе»") && r.content().contains("«Задачи по дому»"), r.content());
        assertTrue(r.content().contains("several"), r.content());
    }

    @Test
    void hyphenAndCaseDoNotPreventTitleMatch() {
        store.createNote("Пароль от wifi", "qwerty", List.of());

        assertTrue(run("action", "find", "query", "wi-fi").content().contains("«Пароль от wifi»"));
        assertTrue(run("action", "find", "query", "WIFI").content().contains("«Пароль от wifi»"));
    }

    @Test
    void readBySingleCommonWordDoesNotSubstituteAnotherNote() {
        store.createNote("Рецепт борща", "свёкла", List.of());

        ToolResult r = run("action", "read", "name", "рецепт пирога");

        assertFalse(r.success(), r.content());
        assertTrue(r.content().contains("«рецепт пирога» not found"), r.content());
        assertTrue(r.content().contains("similar by title: «Рецепт борща»"), r.content());
        assertTrue(r.content().contains("ask them"), r.content());
    }

    @Test
    void meaningSearchAsksModelOnceWhenWordsDoNotMatch() {
        NoteDocument food = store.createList("Покупки", List.of());
        store.createNote("Идея", "про бота", List.of());
        List<String> asked = new ArrayList<>();
        ToolContext llm = new ToolContext((s, u, schema) -> {
            asked.add(u);
            return "{\"ids\": [" + food.id() + "]}";
        }, "");

        ToolResult r = tool.execute(Map.of("action", "find", "query", "что взять в магазине"), llm);

        assertEquals(1, asked.size());
        assertTrue(asked.getFirst().contains("[" + food.id() + "] 📋 List «Покупки»"), asked.getFirst());
        assertTrue(r.content().contains("may fit by meaning: «Покупки»"), r.content());
    }

    @Test
    void readingMissingListsWhatExists() {
        store.createList("Покупки", List.of());

        ToolResult r = run("action", "read", "name", "фильмы");

        assertFalse(r.success());
        assertTrue(r.content().contains("available: «Покупки»"), r.content());
    }

    @Test
    void listAllAndDelete() {
        store.createList("Покупки", List.of());
        store.createNote("Идея", "", List.of());

        assertTrue(run("action", "list_all").content().contains("Documents: 2"));
        assertTrue(run("action", "delete", "name", "идея").success());
        assertEquals(1, store.all().size());
    }

    @Test
    void withoutActionOrNamePoliteError() {
        assertFalse(run("action", "fly").success());
        assertFalse(run("action", "add_item", "text", "x").success());
        assertFalse(run("action", "add_item", "name", "дела").success());
    }
}
