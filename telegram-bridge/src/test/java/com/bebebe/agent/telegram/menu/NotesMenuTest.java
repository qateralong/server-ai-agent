package com.bebebe.agent.telegram.menu;

import com.bebebe.agent.config.AppConfig;
import com.bebebe.agent.config.AppSettings;
import com.bebebe.agent.core.AgentSwitch;
import com.bebebe.agent.memory.MemoryStore;
import com.bebebe.agent.notes.NoteDocument;
import com.bebebe.agent.notes.NotesConfig;
import com.bebebe.agent.notes.NotesStore;
import com.bebebe.agent.script.library.ScriptLibrary;
import com.bebebe.agent.telegram.TestLibrary;
import com.bebebe.agent.telegram.input.InputOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotesMenuTest {

    private static final String CHAT = "TELEGRAM:1";

    @TempDir
    Path temp;

    private ScriptLibrary library;
    private MemoryStore memory;
    private NotesStore notes;
    private MenuController controller;

    @BeforeEach
    void setUp() {
        library = TestLibrary.inDirectory(temp);
        memory = TestLibrary.memoryIn(temp);
        notes = new NotesStore(new NotesConfig(temp.resolve("notes"), false));
        controller = new MenuController(new AgentSwitch(false), AppSettings.from(AppConfig.fromToml("")),
                List::of, library, TestLibrary.NO_CONFIRM, memory, TestLibrary.NO_MEMORY_ACTIONS);
        controller.attachNotes(notes);
    }

    @AfterEach
    void tearDown() {
        notes.close();
        library.close();
        memory.close();
    }

    private static String keyboard(MenuScreen screen) {
        return screen.keyboard().inlineKeyboard().toString();
    }

    private MenuResponse press(CallbackData data) {
        return controller.handle(data, CHAT);
    }

    @Test
    void rootScreenIsSharedWithCreateAndOpen() {
        notes.createList("Покупки", List.of());
        notes.createNote("Идея", "", List.of());

        MenuScreen screen = controller.screenFor(MenuSection.NOTES);

        assertTrue(screen.text().contains("Lists: 1"), screen.text());
        assertTrue(screen.text().contains("Notes: 1"), screen.text());
        assertTrue(screen.text().contains("Obsidian"));
        assertTrue(keyboard(screen).contains(CallbackData.notesCreate().encode()));
        assertTrue(keyboard(screen).contains(CallbackData.notesList(0).encode()));
    }

    @Test
    void withoutStoreSectionExplainsWhy() {
        MenuController bare = new MenuController(new AgentSwitch(false), AppSettings.from(AppConfig.fromToml("")),
                List::of, library, TestLibrary.NO_CONFIRM, memory, TestLibrary.NO_MEMORY_ACTIONS);

        assertEquals("Notes are off", bare.handle(CallbackData.notesList(0), CHAT).toast());
    }

    @Test
    void listCreationViaPendingTextInput() {
        MenuResponse choice = press(CallbackData.notesCreate());
        assertTrue(keyboard(choice.screen()).contains(CallbackData.notesCreate("list").encode()));

        MenuResponse await = press(CallbackData.notesCreate("list"));
        assertNotNull(await.inputRequest(), "the bot must wait for a title");

        InputOutcome outcome = await.inputRequest().handler().accept("Дела на неделю");

        assertTrue(outcome.accepted());
        NoteDocument doc = notes.byTitle("Дела на неделю").orElseThrow();
        assertEquals(CallbackData.noteOpen(doc.id(), 0).encode(), outcome.returnTo().encode(), "straight to the card after creation");
    }

    @Test
    void emptyTitleIsRejectedAndModeStays() {
        InputOutcome outcome = press(CallbackData.notesCreate("note")).inputRequest().handler().accept("   ");

        assertFalse(outcome.accepted());
        assertTrue(notes.all().isEmpty());
    }

    @Test
    void openWithPaginationBy6() {
        for (int i = 1; i <= NotesScreens.PAGE_SIZE + 1; i++) {
            notes.createList("Список " + i, List.of());
        }

        MenuScreen first = press(CallbackData.notesList(0)).screen();
        MenuScreen second = press(CallbackData.notesList(1)).screen();

        assertTrue(keyboard(first).contains("1 / 2"), keyboard(first));
        assertTrue(keyboard(second).contains("2 / 2"), keyboard(second));
    }

    @Test
    void listItemsAreCheckboxButtonsAndPressToggles() {
        NoteDocument doc = notes.createList("Покупки", List.of());
        notes.addItem(doc.id(), "молоко");
        notes.addItem(doc.id(), "хлеб");

        MenuScreen screen = press(CallbackData.noteOpen(doc.id(), 0)).screen();
        assertTrue(keyboard(screen).contains("☐ молоко"), keyboard(screen));
        assertTrue(keyboard(screen).contains(CallbackData.noteToggle(doc.id(), 1, 0).encode()));

        MenuResponse toggled = press(CallbackData.noteToggle(doc.id(), 1, 0));

        assertEquals("Done", toggled.toast());
        assertTrue(keyboard(toggled.screen()).contains("☑ хлеб"), keyboard(toggled.screen()));
        assertTrue(notes.byId(doc.id()).orElseThrow().items().get(1).done());
        assertEquals("Unchecked", press(CallbackData.noteToggle(doc.id(), 1, 0)).toast());
    }

    @Test
    void itemWithReminderIsMarkedWithClock() {
        NoteDocument doc = notes.createList("Дела", List.of());
        notes.addItem(doc.id(), "отчёт", 5L);

        assertTrue(keyboard(press(CallbackData.noteOpen(doc.id(), 0)).screen()).contains("☐ отчёт ⏰"));
    }

    @Test
    void itemRemovalMode() {
        NoteDocument doc = notes.createList("Дела", List.of());
        notes.addItem(doc.id(), "а");
        notes.addItem(doc.id(), "б");

        MenuScreen mode = press(CallbackData.noteRemoveMode(doc.id(), 0)).screen();
        assertTrue(keyboard(mode).contains("✖️ а"), keyboard(mode));
        assertTrue(keyboard(mode).contains(CallbackData.noteRemove(doc.id(), 0, 0).encode()));
        assertEquals(2, notes.byId(doc.id()).orElseThrow().items().size(), "entering the mode deletes nothing");

        MenuResponse removed = press(CallbackData.noteRemove(doc.id(), 0, 0));

        assertEquals("Removed: а", removed.toast());
        assertEquals(List.of("б"), notes.byId(doc.id()).orElseThrow().items().stream().map(i -> i.text()).toList());
    }

    @Test
    void addItemViaPendingInput() {
        NoteDocument doc = notes.createList("Дела", List.of());

        MenuResponse await = press(CallbackData.noteAdd(doc.id(), 0));
        InputOutcome outcome = await.inputRequest().handler().accept("позвонить");

        assertTrue(outcome.accepted());
        assertEquals("позвонить", notes.byId(doc.id()).orElseThrow().items().getFirst().text());
    }

    @Test
    void noteShowsTextAndCanBeAppended() {
        NoteDocument doc = notes.createNote("Идея", "Сделать бота", List.of("работа"));

        MenuScreen screen = press(CallbackData.noteOpen(doc.id(), 0)).screen();
        assertTrue(screen.text().contains("Сделать бота"), screen.text());
        assertTrue(screen.text().contains("#работа"), screen.text());
        assertFalse(keyboard(screen).contains("☐"));

        press(CallbackData.noteAdd(doc.id(), 0)).inputRequest().handler().accept("ещё мысль");

        assertTrue(notes.byId(doc.id()).orElseThrow().body().endsWith("ещё мысль"));
    }

    @Test
    void documentDeletionRequiresConfirmation() {
        NoteDocument doc = notes.createList("Временный", List.of());

        MenuScreen confirm = press(CallbackData.noteDelete(doc.id(), 0, false)).screen();
        assertTrue(confirm.text().contains("Delete «Временный»"), confirm.text());
        assertTrue(notes.byId(doc.id()).isPresent());

        MenuResponse done = press(CallbackData.noteDelete(doc.id(), 0, true));

        assertEquals("Deleted: Временный", done.toast());
        assertTrue(notes.byId(doc.id()).isEmpty());
    }

    @Test
    void missingDocumentReturnsToList() {
        MenuResponse response = press(CallbackData.noteOpen(999, 0));

        assertEquals("Not found", response.toast());
        assertNull(response.inputRequest());
    }

    @Test
    void longListPagesInsideCard() {
        NoteDocument doc = notes.createList("Длинный", List.of());
        for (int i = 1; i <= NotesScreens.ITEMS_PER_PAGE + 2; i++) {
            notes.addItem(doc.id(), "пункт " + i);
        }

        MenuScreen first = press(CallbackData.noteOpen(doc.id(), 0)).screen();
        MenuScreen second = press(CallbackData.noteOpen(doc.id(), 0, 1)).screen();

        assertTrue(keyboard(first).contains("1 / 2"), keyboard(first));
        assertFalse(keyboard(first).contains("пункт 9"), keyboard(first));
        assertTrue(keyboard(second).contains("пункт 9"), keyboard(second));

        MenuResponse toggled = press(CallbackData.noteToggle(doc.id(), 8, 0, 1));
        assertTrue(keyboard(toggled.screen()).contains("☑ пункт 9"), keyboard(toggled.screen()));
        assertTrue(keyboard(toggled.screen()).contains("2 / 2"));
    }
}
