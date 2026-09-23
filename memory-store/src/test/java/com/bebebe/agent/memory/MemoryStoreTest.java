package com.bebebe.agent.memory;

import com.bebebe.agent.config.AppConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryStoreTest {

    private static final String CHAT = "TELEGRAM:1";

    @TempDir
    Path temp;

    private MemoryStore store;

    @BeforeEach
    void setUp() {
        store = open("session_idle_minutes = 60");
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private MemoryStore open(String extra) {
        return new MemoryStore(MemoryConfig.from(AppConfig.fromToml("""
                [memory]
                db_path = "%s"
                %s
                """.formatted(temp.resolve("memory.db"), extra)).section(MemoryConfig.SECTION)));
    }

    @Test
    void sessionOpensAndContinues() {
        DialogSession first = store.openOrContinue(CHAT);
        store.append(first.id(), MessageRole.USER, "привет", "TELEGRAM", "t1");

        DialogSession again = store.openOrContinue(CHAT);

        assertEquals(first.id(), again.id(), "within the idle window the session is the same");
        assertTrue(again.isActive());
    }

    /**
     * Asking out loud and following up by text is one conversation, not two. The agent answers
     * both into the same chat, so the user sees a single stream -- and the agent used to keep two
     * logs and fail to understand "а теперь на английский" about something said a minute earlier.
     */
    @Test
    void voiceAndChatAreOneConversation() {
        DialogSession byVoice = store.openOrContinue("VOICE:-");
        DialogSession byText = store.openOrContinue("TELEGRAM:1");

        assertEquals(byVoice.id(), byText.id());
        assertEquals("TELEGRAM:1", byText.conversationKey(),
                "the session follows the channel the user last spoke through, so replies go where they are");
        assertEquals(1, store.activeSessions().size());
    }

    @Test
    void sessionLogIsStoredAsIs() {
        DialogSession session = store.openOrContinue(CHAT);
        store.append(session.id(), MessageRole.USER, "Сколько места на диске?", "TELEGRAM", "t1");
        store.append(session.id(), MessageRole.ASSISTANT, "Свободно 40 ГБ.", "TELEGRAM", "t1");
        store.append(session.id(), MessageRole.USER, "А в гигабайтах?", "TELEGRAM", "t2");

        List<DialogMessage> log = store.messages(session.id());

        assertEquals(3, log.size());
        assertEquals(MessageRole.USER, log.get(0).role());
        assertEquals("Свободно 40 ГБ.", log.get(1).text());
        assertEquals("t2", log.get(2).traceId());
    }

    @Test
    void closedSessionDoesNotContinue() {
        DialogSession first = store.openOrContinue(CHAT);
        store.endSession(first.id());

        DialogSession second = store.openOrContinue(CHAT);

        assertNotEquals(first.id(), second.id());
        assertFalse(store.session(first.id()).orElseThrow().isActive());
    }

    @Test
    void consolidationMarksHowFarItGot() {
        DialogSession session = store.openOrContinue(CHAT);
        DialogMessage m1 = store.append(session.id(), MessageRole.USER, "раз", "T", null);
        DialogMessage m2 = store.append(session.id(), MessageRole.ASSISTANT, "два", "T", null);

        assertEquals(2, store.unconsolidated(session).size());
        store.markConsolidated(session.id(), m1.id());

        List<DialogMessage> tail = store.unconsolidated(store.session(session.id()).orElseThrow());
        assertEquals(List.of(m2.id()), tail.stream().map(DialogMessage::id).toList());
    }

    @Test
    void activeSessionsAreVisibleForConsolidationOnStop() {
        store.openOrContinue("TELEGRAM:1");
        store.append(store.activeSession().orElseThrow().id(), MessageRole.USER, "привет", "T", null);

        assertEquals(1, store.activeSessions().size());
        assertEquals("TELEGRAM:1", store.activeSessions().getFirst().conversationKey());

        store.endSession(store.activeSessions().getFirst().id());
        assertTrue(store.activeSessions().isEmpty());
    }

    @Test
    void entityIsFoundByNameAndAliasCaseInsensitively() {
        Entity sasha = store.addEntity("Саша", List.of("Александр", "Шурик"), "друг", "");

        assertEquals(sasha.id(), store.findByName("саша").orElseThrow().id());
        assertEquals(sasha.id(), store.findByName("ШУРИК").orElseThrow().id());
        assertTrue(store.findByName("Петя").isEmpty());
    }

    @Test
    void enrichmentAppendsButDoesNotOverwrite() {
        Entity sasha = store.addEntity("Саша", List.of("Александр"), "", "");

        Entity enriched = store.enrichEntity(sasha.id(), List.of("Шурик", "александр", "Саша"), "друг");

        assertEquals(List.of("Александр", "Шурик"), enriched.aliases(), "case-insensitive duplicates are not added");
        assertEquals("друг", enriched.relation());

        Entity again = store.enrichEntity(sasha.id(), List.of(), "коллега");
        assertEquals("друг", again.relation(), "an already set relation is not overwritten");
    }

    @Test
    void descriptionForModelContainsId() {
        Entity sasha = store.addEntity("Саша", List.of("Шурик"), "друг", "");

        String described = sasha.describeForModel();

        assertTrue(described.startsWith("[" + sasha.id() + "] Саша"), described);
        assertTrue(described.contains("друг"));
        assertTrue(described.contains("Шурик"));
    }

    @Test
    void factLinksToSeveralEntities() {
        Entity sasha = store.addEntity("Саша", List.of(), "друг", "");
        Entity petya = store.addEntity("Петя", List.of(), "коллега", "");

        Fact fact = store.addFact("Саша и Петя вместе ездили в Питер", FactCategory.EVENT,
                LocalDate.of(2026, 9, 1), null, List.of(sasha.id(), petya.id()));

        assertEquals(List.of(sasha.id(), petya.id()), fact.entityIds());
        assertEquals(1, store.factsOf(sasha.id()).size());
        assertEquals(1, store.factsOf(petya.id()).size());
        assertEquals(LocalDate.of(2026, 9, 1), fact.factDate());
    }

    @Test
    void userFactsHaveNoLinks() {
        store.addFact("Не пьёт кофе", FactCategory.PREFERENCE, null, null, List.of());
        Entity sasha = store.addEntity("Саша", List.of(), "", "");
        store.addFact("Саша любит чай", FactCategory.PREFERENCE, null, null, List.of(sasha.id()));

        List<Fact> mine = store.factsAboutUser();

        assertEquals(1, mine.size());
        assertEquals("Не пьёт кофе", mine.getFirst().text());
    }

    @Test
    void proceduresAreSelectedSeparately() {
        store.addFact("Перед стримом: скрипты А, Б, В", FactCategory.PROCEDURE, null, null, List.of());
        store.addFact("Любит чай", FactCategory.PREFERENCE, null, null, List.of());

        List<Fact> procedures = store.factsByCategory(FactCategory.PROCEDURE);

        assertEquals(1, procedures.size());
        assertTrue(procedures.getFirst().describeForModel().contains("[procedure]"));
    }

    @Test
    void factIsLinkedToSourceMessage() {
        DialogSession session = store.openOrContinue(CHAT);
        DialogMessage source = store.append(session.id(), MessageRole.USER, "Саша не ест мясо", "T", "t1");

        Fact fact = store.addFact("Саша не ест мясо", FactCategory.PREFERENCE, null, source.id(), List.of());

        assertEquals(source.id(), fact.sourceMessageId());
    }

    @Test
    void deletingEntityRemovesOnlyItsOwnFacts() {
        Entity sasha = store.addEntity("Саша", List.of(), "", "");
        Entity petya = store.addEntity("Петя", List.of(), "", "");
        store.addFact("Саша любит чай", FactCategory.PREFERENCE, null, null, List.of(sasha.id()));
        Fact shared = store.addFact("Саша и Петя коллеги", FactCategory.TRAIT, null, null,
                List.of(sasha.id(), petya.id()));

        assertTrue(store.deleteEntity(sasha.id()));

        assertEquals(1, store.countFacts(), "the shared fact must remain");
        assertEquals(List.of(petya.id()), store.fact(shared.id()).orElseThrow().entityIds());
        assertTrue(store.entity(sasha.id()).isEmpty());
    }

    @Test
    void deleteFact() {
        Entity sasha = store.addEntity("Саша", List.of(), "", "");
        Fact fact = store.addFact("что-то", FactCategory.EVENT, null, null, List.of(sasha.id()));

        assertTrue(store.deleteFact(fact.id()));
        assertTrue(store.factsOf(sasha.id()).isEmpty());
        assertFalse(store.deleteFact(fact.id()));
    }

    @Test
    void forgettingSessionLeavesFacts() {
        DialogSession session = store.openOrContinue(CHAT);
        store.append(session.id(), MessageRole.USER, "текст", "T", null);
        store.addFact("факт", FactCategory.EVENT, null, null, List.of());

        assertEquals(1, store.forgetActiveSession(CHAT));

        assertEquals(0, store.countMessages());
        assertEquals(1, store.countFacts());
        assertTrue(store.activeSession(CHAT).isEmpty());
    }

    @Test
    void forgetEverything() {
        store.openOrContinue(CHAT);
        store.addEntity("Саша", List.of(), "", "");
        store.addFact("факт", FactCategory.EVENT, null, null, List.of());

        store.forgetEverything();

        assertEquals(0, store.countEntities());
        assertEquals(0, store.countFacts());
        assertEquals(0, store.countMessages());
    }

    @Test
    void dataSurvivesRestart() {
        Entity sasha = store.addEntity("Саша", List.of("Шурик"), "друг", "заметка");
        store.addFact("Любит чай", FactCategory.PREFERENCE, LocalDate.of(2026, 1, 1), null, List.of(sasha.id()));
        store.close();

        store = open("session_idle_minutes = 60");

        Entity reloaded = store.entity(sasha.id()).orElseThrow();
        assertEquals(List.of("Шурик"), reloaded.aliases());
        assertEquals("заметка", reloaded.notes());
        assertEquals(1, store.factsOf(sasha.id()).size());
    }

    @Test
    void categoryIsParsedFromAnySpelling() {
        assertEquals(FactCategory.PROCEDURE, FactCategory.fromWire("procedure"));
        assertEquals(FactCategory.PROCEDURE, FactCategory.fromWire("Procedure"));
        assertEquals(FactCategory.EVENT, FactCategory.fromWire("что-то странное"));
    }

    @Test
    void consolidationTailStartsAfterMark() {
        DialogSession session = store.openOrContinue(CHAT);
        DialogMessage m1 = store.append(session.id(), MessageRole.USER, "раз", "TELEGRAM", "t1");
        store.append(session.id(), MessageRole.ASSISTANT, "два", "TELEGRAM", "t1");
        store.markConsolidated(session.id(), m1.id());
        DialogMessage m3 = store.append(session.id(), MessageRole.USER, "три", "TELEGRAM", "t2");

        List<DialogMessage> tail = store.unconsolidated(store.session(session.id()).orElseThrow());

        assertEquals(List.of("два", "три"), tail.stream().map(DialogMessage::text).toList());
        store.markConsolidated(session.id(), m3.id());
        assertTrue(store.unconsolidated(store.session(session.id()).orElseThrow()).isEmpty());
    }

    @Test
    void aliasesAreNotDuplicatedAndDoNotRepeatName() {
        Entity sasha = store.addEntity("Саша", List.of("Шурик"), "друг", "");

        Entity enriched = store.enrichEntity(sasha.id(), List.of("шурик", "САША", "Александр", " ", "Александр"), "коллега");

        assertEquals(List.of("Шурик", "Александр"), enriched.aliases());
        assertEquals("друг", enriched.relation(), "an already known relation is not overwritten");
        assertTrue(store.findByName("александр").isPresent(), "the person is found by the new alias");
    }

    @Test
    void deletingFactRemovesItsLinksButKeepsPeople() {
        Entity sasha = store.addEntity("Саша", List.of(), "друг", "");
        Entity lena = store.addEntity("Лена", List.of(), "сестра", "");
        Fact shared = store.addFact("Саша и Лена идут в поход", FactCategory.AGREEMENT, LocalDate.of(2026, 9, 26),
                null, List.of(sasha.id(), lena.id()));

        assertTrue(store.deleteFact(shared.id()));

        assertTrue(store.factsOf(sasha.id()).isEmpty());
        assertTrue(store.factsOf(lena.id()).isEmpty());
        assertEquals(2, store.countEntities());
        assertFalse(store.deleteFact(shared.id()), "repeated deletion -- false, not an exception");
    }

    @Test
    void forgettingAllFactsKeepsPeopleAndConversations() {
        Entity sasha = store.addEntity("Саша", List.of(), "друг", "");
        DialogSession session = store.openOrContinue(CHAT);
        store.append(session.id(), MessageRole.USER, "Саша не ест мясо", "TELEGRAM", "t1");
        store.addFact("Саша не ест мясо", FactCategory.PREFERENCE, null, null, List.of(sasha.id()));
        store.addFact("Пользователь любит кофе", FactCategory.PREFERENCE, null, null, List.of());
        assertEquals(2, store.countFacts());

        assertEquals(2, store.forgetAllFacts(), "facts are counted, not rows removed by cascade");

        assertEquals(0, store.countFacts());
        assertEquals(1, store.countEntities());
        assertEquals(1, store.countMessages());
        assertTrue(store.factsAboutUser().isEmpty());
    }

    @Test
    void factsAreFilteredByCategoryRegardlessOfLinks() {
        Entity sasha = store.addEntity("Саша", List.of(), "друг", "");
        store.addFact("перед стримом: OBS, свет", FactCategory.PROCEDURE, null, null, List.of());
        store.addFact("Саша всегда опаздывает", FactCategory.TRAIT, null, null, List.of(sasha.id()));
        store.addFact("проверить микрофон перед созвоном", FactCategory.PROCEDURE, null, null, List.of(sasha.id()));

        List<Fact> procedures = store.factsByCategory(FactCategory.PROCEDURE);

        assertEquals(2, procedures.size());
        assertTrue(procedures.stream().allMatch(f -> f.category() == FactCategory.PROCEDURE));
        assertEquals(1, store.factsByCategory(FactCategory.TRAIT).size());
        assertTrue(store.factsByCategory(FactCategory.EVENT).isEmpty());
    }
}
