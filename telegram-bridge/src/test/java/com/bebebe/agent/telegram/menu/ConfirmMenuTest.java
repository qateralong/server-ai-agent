package com.bebebe.agent.telegram.menu;

import com.bebebe.agent.config.AppConfig;
import com.bebebe.agent.config.AppSettings;
import com.bebebe.agent.core.AgentSwitch;
import com.bebebe.agent.script.library.ScriptEntry;
import com.bebebe.agent.script.library.ScriptLibrary;
import com.bebebe.agent.telegram.TestLibrary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfirmMenuTest {

    @TempDir
    Path temp;

    private ScriptLibrary library;
    private com.bebebe.agent.memory.MemoryStore memory;
    private MenuController controller;

    @BeforeEach
    void setUp() {
        library = TestLibrary.inDirectory(temp);
        memory = TestLibrary.memoryIn(temp);
        controller = new MenuController(new AgentSwitch(false),
                AppSettings.from(AppConfig.fromToml("")),
                List::of, library, TestLibrary.NO_CONFIRM, memory, TestLibrary.NO_MEMORY_ACTIONS);
    }

    @AfterEach
    void tearDown() {
        library.close();
        memory.close();
    }

    private static String keyboard(MenuScreen screen) {
        return screen.keyboard().inlineKeyboard().toString();
    }

    private ScriptEntry add(String name) {
        return library.add(name, "description " + name, List.of("tag"), "print(1)");
    }

    @Test
    void emptyCatalogExplainsWhy() {
        MenuScreen screen = controller.screenFor(MenuSection.CONFIRMATIONS);

        assertTrue(screen.text().contains("catalog is empty"), screen.text());
        assertTrue(keyboard(screen).contains(CallbackData.root().encode()));
    }

    @Test
    void showsScriptsWithFlagIndicator() {
        ScriptEntry first = add("Первый");
        ScriptEntry second = add("Второй");
        library.setRequiresConfirmation(second.id(), false);

        MenuScreen screen = controller.screenFor(MenuSection.CONFIRMATIONS);

        assertTrue(keyboard(screen).contains("🔔 Первый"), keyboard(screen));
        assertTrue(keyboard(screen).contains("🔕 Второй"), keyboard(screen));
        assertTrue(keyboard(screen).contains(CallbackData.confirmToggle(first.id(), 0).encode()));
    }

    @Test
    void toggleFlipsFlagAndStaysOnSamePage() {
        ScriptEntry entry = add("Скрипт");

        MenuResponse response = controller.handle(CallbackData.confirmToggle(entry.id(), 0));

        assertFalse(library.byId(entry.id()).orElseThrow().requiresConfirmation());
        assertEquals("Won't ask anymore", response.toast());
        assertEquals(MenuSection.CONFIRMATIONS, response.screen().section());

        MenuResponse back = controller.handle(CallbackData.confirmToggle(entry.id(), 0));
        assertTrue(library.byId(entry.id()).orElseThrow().requiresConfirmation());
        assertEquals("Will ask", back.toast());
    }

    @Test
    void longListIsSplitIntoPages() {
        for (int i = 1; i <= ConfirmScreens.PAGE_SIZE * 2 + 1; i++) {
            add("Скрипт " + i);
        }

        MenuScreen first = ConfirmScreens.list(library.latestVersions(), 0);
        assertTrue(keyboard(first).contains("1 / 3"), keyboard(first));
        assertTrue(keyboard(first).contains(CallbackData.confirmList(1).encode()));
        assertFalse(keyboard(first).contains(CallbackData.confirmList(-1).encode()));

        MenuScreen middle = ConfirmScreens.list(library.latestVersions(), 1);
        assertTrue(keyboard(middle).contains("2 / 3"));
        assertTrue(keyboard(middle).contains(CallbackData.confirmList(0).encode()));
        assertTrue(keyboard(middle).contains(CallbackData.confirmList(2).encode()));

        MenuScreen last = ConfirmScreens.list(library.latestVersions(), 2);
        assertTrue(keyboard(last).contains("3 / 3"));
        assertFalse(keyboard(last).contains(CallbackData.confirmList(3).encode()));
    }

    @Test
    void singlePageWithoutPagination() {
        add("Один");

        assertFalse(keyboard(ConfirmScreens.list(library.latestVersions(), 0)).contains("1 / 1"));
    }

    @Test
    void outOfRangePageDoesNotBreakScreen() {
        add("Один");

        assertEquals(1, ConfirmScreens.list(library.latestVersions(), 99)
                .keyboard().inlineKeyboard().size() - 1);
        assertEquals(1, ConfirmScreens.list(library.latestVersions(), -5)
                .keyboard().inlineKeyboard().size() - 1);
    }

    @Test
    void listHasOnlyLatestVersions() {
        ScriptEntry v1 = add("Скрипт");
        library.addVersion(v1, "print(2)");

        MenuScreen screen = controller.screenFor(MenuSection.CONFIRMATIONS);

        assertTrue(keyboard(screen).contains("Скрипт v2"), keyboard(screen));
        assertFalse(keyboard(screen).contains(CallbackData.confirmToggle(v1.id(), 0).encode()));
    }

    @Test
    void requestScreenHasDescriptionAndBothButtons() {
        ScriptEntry entry = add("Удаление файлов");

        MenuScreen screen = ConfirmScreens.request(entry, "Удалит старые логи", "abcd1234");

        assertTrue(screen.text().contains("Удаление файлов"));
        assertTrue(screen.text().contains("Удалит старые логи"));
        assertTrue(screen.text().contains("first time"), screen.text());
        assertTrue(keyboard(screen).contains(CallbackData.confirmRun("abcd1234").encode()));
        assertTrue(keyboard(screen).contains(CallbackData.confirmCancel("abcd1234").encode()));
    }

    @Test
    void requestScreenShowsStats() {
        ScriptEntry entry = add("Скрипт");
        library.recordRun(entry.id(), true);
        library.recordRun(entry.id(), false);

        MenuScreen screen = ConfirmScreens.request(
                library.byId(entry.id()).orElseThrow(), "description", "tok");

        assertTrue(screen.text().contains("2 times"), screen.text());
        assertTrue(screen.text().contains("1 successfully"), screen.text());
    }

    @Test
    void newVersionExplainsWhyItAsksAgain() {

        ScriptEntry v1 = add("Скрипт");
        library.setRequiresConfirmation(v1.id(), false);
        ScriptEntry v2 = library.addVersion(v1, "print(2)");

        MenuScreen screen = ConfirmScreens.request(v2, "description", "tok");

        assertTrue(screen.text().contains("new version"), screen.text());
        assertTrue(v2.requiresConfirmation(), "a new version must require confirmation again");
        assertFalse(ConfirmScreens.request(v1, "description", "tok").text().contains("new version"));
    }

    @Test
    void allConfirmationButtonsFitTheLimit() {

        List<CallbackData> all = List.of(
                CallbackData.confirmRun("deadbeef"),
                CallbackData.confirmCancel("deadbeef"),
                CallbackData.confirmTrust(999999L),
                CallbackData.confirmList(42),
                CallbackData.confirmToggle(999999L, 42));

        for (CallbackData data : all) {
            assertTrue(data.byteSize() <= CallbackData.WARN_BYTES,
                    data + " takes " + data.byteSize() + " bytes");
        }
    }

    @Test
    void unknownButtonDoesNotBreakController() {
        assertEquals("Unknown button", controller.handle(CallbackData.of("cfm", "wat")).toast());
        assertEquals("Unknown button",
                controller.handle(CallbackData.of("cfm", "tgl", "not-a-number", "0")).toast());
    }

    @Test
    void toggleOfDeletedScriptDoesNotCrash() {
        MenuResponse response = controller.handle(CallbackData.confirmToggle(12345L, 0));

        assertEquals("Script not found", response.toast());
    }
}
