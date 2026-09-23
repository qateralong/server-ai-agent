package com.bebebe.agent.telegram.menu;

import com.bebebe.agent.config.AppConfig;
import com.bebebe.agent.config.AppSettings;
import com.bebebe.agent.core.AgentSwitch;
import com.bebebe.agent.memory.Entity;
import com.bebebe.agent.memory.Fact;
import com.bebebe.agent.memory.FactCategory;
import com.bebebe.agent.memory.MemoryStore;
import com.bebebe.agent.memory.MessageRole;
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

class MemoryMenuTest {

    private static final String CHAT = "TELEGRAM:1";

    @TempDir
    Path temp;

    private ScriptLibrary library;
    private MemoryStore memory;
    private MenuController controller;

    @BeforeEach
    void setUp() {
        library = TestLibrary.inDirectory(temp);
        memory = TestLibrary.memoryIn(temp);
        controller = new MenuController(new AgentSwitch(false), AppSettings.from(AppConfig.fromToml("")),
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

    private MenuResponse press(CallbackData data) {
        return controller.handle(data, CHAT);
    }

    /**
     * The review queue. Confirming a fact is the cheapest high-quality signal memory can get --
     * everything else it knows about a fact is either the model's own judgement or a count of how
     * often the fact got reused.
     */
    @Test
    void reviewQueueShowsOnlyWhatNobodyHasCheckedYet() {
        Fact guessed = memory.addFact("Пользователь любит кофе", FactCategory.PREFERENCE, null, null,
                List.of(), com.bebebe.agent.memory.FactSource.EXTRACTED, List.of());
        memory.addFact("Пользователь просил отвечать коротко", FactCategory.PREFERENCE, null, null,
                List.of(), com.bebebe.agent.memory.FactSource.STATED, List.of());

        MenuScreen root = controller.screenFor(MenuSection.MEMORY);
        assertTrue(keyboard(root).contains(CallbackData.memoryReview(0).encode()),
                "with something to review the button is there: " + keyboard(root));

        MenuScreen queue = press(CallbackData.memoryReview(0)).screen();
        assertTrue(queue.text().contains("любит кофе"), queue.text());
        assertFalse(queue.text().contains("отвечать коротко"),
                "what the user dictated was never a guess and needs no review");
        assertTrue(keyboard(queue).contains(CallbackData.memoryFactConfirm(guessed.id(), 0).encode()));
    }

    @Test
    void confirmingAFactTakesItOutOfTheQueue() {
        Fact guessed = memory.addFact("Пользователь любит кофе", FactCategory.PREFERENCE, null, null, List.of());

        MenuResponse response = press(CallbackData.memoryFactConfirm(guessed.id(), 0));

        assertEquals(com.bebebe.agent.memory.FactSource.CONFIRMED,
                memory.fact(guessed.id()).orElseThrow().source());
        assertEquals(0, memory.countUnreviewed());
        assertTrue(response.screen().text().contains("Nothing to review"),
                response.screen().text());
    }

    @Test
    void rejectingAFactRetractsItButKeepsIt() {
        Fact wrong = memory.addFact("Пользователь курит", FactCategory.TRAIT, null, null, List.of());

        press(CallbackData.memoryFactRetract(wrong.id(), 0));

        assertTrue(memory.factsAboutUser().isEmpty(), "it is not offered to the model any more");
        assertTrue(memory.fact(wrong.id()).isPresent(),
                "rejecting an extraction is a verdict about it, not a request to erase it");
        assertFalse(memory.fact(wrong.id()).orElseThrow().isCurrent());
    }

    @Test
    void withNothingToReviewTheButtonIsNotShown() {
        memory.addFact("Пользователь просил отвечать коротко", FactCategory.PREFERENCE, null, null,
                List.of(), com.bebebe.agent.memory.FactSource.STATED, List.of());

        assertFalse(keyboard(controller.screenFor(MenuSection.MEMORY))
                        .contains(CallbackData.memoryReview(0).encode()),
                "a button that always says zero teaches people to stop reading it");
    }

    @Test
    void rootScreenShowsCounters() {
        memory.addEntity("Саша", List.of(), "", "");
        memory.addFact("факт", FactCategory.EVENT, null, null, List.of());

        MenuScreen screen = controller.screenFor(MenuSection.MEMORY);

        assertTrue(screen.text().contains("People: 1"), screen.text());
        assertTrue(screen.text().contains("Facts: 1"));
        assertTrue(keyboard(screen).contains(CallbackData.memoryPeople(0).encode()));
        assertTrue(keyboard(screen).contains(CallbackData.memoryForget().encode()));
    }

    @Test
    void emptyPeopleListExplainsWhy() {
        MenuScreen screen = press(CallbackData.memoryPeople(0)).screen();

        assertTrue(screen.text().contains("No one yet"), screen.text());
    }

    @Test
    void peopleListWithPagination() {
        for (int i = 1; i <= MemoryScreens.PAGE_SIZE + 2; i++) {
            memory.addEntity("Человек " + i, List.of(), "", "");
        }

        MenuScreen first = press(CallbackData.memoryPeople(0)).screen();
        MenuScreen second = press(CallbackData.memoryPeople(1)).screen();

        assertTrue(keyboard(first).contains("1 / 2"), keyboard(first));
        assertTrue(keyboard(first).contains(CallbackData.memoryPeople(1).encode()));
        assertTrue(keyboard(second).contains("2 / 2"));
        assertTrue(keyboard(second).contains(CallbackData.memoryPeople(0).encode()));
    }

    @Test
    void cardShowsFactsWithDeleteButtons() {
        Entity sasha = memory.addEntity("Саша", List.of("Шурик"), "друг", "");
        Fact fact = memory.addFact("не ест мясо", FactCategory.PREFERENCE, null, null, List.of(sasha.id()));

        MenuScreen card = press(CallbackData.memoryPerson(sasha.id(), 0)).screen();

        assertTrue(card.text().contains("Саша"));
        assertTrue(card.text().contains("друг"));
        assertTrue(card.text().contains("Шурик"));
        assertTrue(card.text().contains("не ест мясо"));
        assertTrue(card.text().contains("preference"));
        assertTrue(keyboard(card).contains(CallbackData.memoryFactDelete(fact.id(), sasha.id(), 0).encode()));
        assertTrue(keyboard(card).contains(CallbackData.memoryPersonDelete(sasha.id()).encode()));
    }

    @Test
    void factDeletionStaysOnCard() {
        Entity sasha = memory.addEntity("Саша", List.of(), "", "");
        Fact fact = memory.addFact("удали меня", FactCategory.EVENT, null, null, List.of(sasha.id()));

        MenuResponse response = press(CallbackData.memoryFactDelete(fact.id(), sasha.id(), 0));

        assertEquals("Fact deleted", response.toast());
        assertTrue(response.screen().text().contains("No facts yet"));
        assertTrue(memory.factsOf(sasha.id()).isEmpty());
    }

    @Test
    void personDeletionRequiresConfirmation() {
        Entity sasha = memory.addEntity("Саша", List.of(), "", "");
        memory.addFact("факт", FactCategory.EVENT, null, null, List.of(sasha.id()));

        MenuResponse first = press(CallbackData.memoryPersonDelete(sasha.id()));

        assertTrue(first.screen().text().contains("Delete"), first.screen().text());
        assertTrue(first.screen().text().contains("1 fact"));
        assertTrue(memory.entity(sasha.id()).isPresent(), "must not delete without confirmation");

        MenuResponse second = press(CallbackData.memoryPersonDeleteConfirmed(sasha.id()));

        assertTrue(second.toast().contains("Deleted"));
        assertTrue(memory.entity(sasha.id()).isEmpty());
        assertEquals(0, memory.countFacts());
    }

    @Test
    void forgetCurrentConversationRequiresConfirmationAndKeepsFacts() {
        var session = memory.openOrContinue(CHAT);
        memory.append(session.id(), MessageRole.USER, "текст", "T", null);
        memory.addFact("факт", FactCategory.EVENT, null, null, List.of());

        MenuResponse ask = press(CallbackData.memoryForget("session", false));
        assertTrue(ask.screen().text().contains("the current conversation"));
        assertEquals(1, memory.countMessages(), "nothing is deleted before confirmation");

        MenuResponse done = press(CallbackData.memoryForget("session", true));

        assertEquals("Messages forgotten: 1", done.toast());
        assertEquals(0, memory.countMessages());
        assertEquals(1, memory.countFacts());
    }

    @Test
    void forgetEverythingClearsWholeMemory() {
        memory.addEntity("Саша", List.of(), "", "");
        memory.addFact("факт", FactCategory.EVENT, null, null, List.of());

        press(CallbackData.memoryForget("all", true));

        assertEquals(0, memory.countEntities());
        assertEquals(0, memory.countFacts());
    }

    @Test
    void memoryQuestionAnswerGoesToCore() {
        MenuResponse same = press(CallbackData.memoryResolve("tok1", true));
        MenuResponse other = press(CallbackData.memoryResolve("tok1", false));

        assertEquals("resolved:true", same.screen().text());
        assertEquals("resolved:false", other.screen().text());
        assertEquals(0, same.screen().keyboard().buttonCount(), "buttons disappear after the answer");
    }

    @Test
    void questionScreenHasBothButtons() {
        Entity sasha = memory.addEntity("Саша", List.of(), "друг", "");

        MenuScreen screen = MemoryScreens.entityQuestion("tok", "Александр", sasha, 2);

        assertTrue(screen.text().contains("Александр"));
        assertTrue(screen.text().contains("Саша"));
        assertTrue(screen.text().contains("2 fact"));
        assertTrue(keyboard(screen).contains(CallbackData.memoryResolve("tok", true).encode()));
        assertTrue(keyboard(screen).contains(CallbackData.memoryResolve("tok", false).encode()));
    }

    @Test
    void allMemoryButtonsFitTheLimit() {
        for (CallbackData data : List.of(
                CallbackData.memoryPeople(99),
                CallbackData.memoryPerson(999999L, 99),
                CallbackData.memoryFactDelete(999999L, 999999L, 99),
                CallbackData.memoryPersonDeleteConfirmed(999999L),
                CallbackData.memoryForget("sessions", true),
                CallbackData.memoryResolve("deadbeef", false))) {
            assertTrue(data.byteSize() <= CallbackData.WARN_BYTES, data + " = " + data.byteSize() + " bytes");
        }
    }

    @Test
    void unknownButtonDoesNotBreakController() {
        assertEquals("Unknown button", press(CallbackData.of("mem", "wat")).toast());
        assertEquals("Unknown button", press(CallbackData.of("mem", "forget", "planet")).toast());
        assertFalse(press(CallbackData.memoryPerson(12345L, 0)).screen().text().isEmpty());
    }
}
