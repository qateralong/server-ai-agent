package com.bebebe.agent.core;

import com.bebebe.agent.memory.DialogSession;
import com.bebebe.agent.memory.Entity;
import com.bebebe.agent.memory.Fact;
import com.bebebe.agent.memory.FactCategory;
import com.bebebe.agent.memory.MemoryStore;
import com.bebebe.agent.memory.MessageRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How often the fact that answers the question actually reaches the prompt.
 *
 * <p>This is the measurement everything else about memory is judged by. Until it existed, every
 * change to the selection -- a weight, a cap, one more block in the context -- was an opinion:
 * the only way to see the effect was to talk to the agent and form an impression, and an
 * impression cannot fail a build.
 *
 * <p>The corpus is small and hand-written on purpose. It is not a benchmark of the model; there is
 * no model here at all. It is a fixture for the part that is ours: which of a few dozen remembered
 * facts a question drags into the prompt, and how much else it drags in with them.
 *
 * <p>Two numbers are asserted, and they pull against each other on purpose -- padding the prompt
 * with everything would make recall perfect and the prompt useless:
 *
 * <ul>
 *   <li><b>recall</b> -- the expected fact was offered. Questions that the lexical approach cannot
 *       answer are listed separately, as known blind spots: they are printed, counted, and left
 *       out of the floor, because pretending they pass would hide exactly what a semantic index
 *       would be for.
 *   <li><b>noise</b> -- how many facts were offered in total. The whole point of ranking is that
 *       the prompt grows with the question, not with the size of memory.
 * </ul>
 */
class MemoryEvalTest {

    /** Recall over the questions the lexical selection is supposed to handle. */
    private static final double RECALL_FLOOR = 1.0;

    /** Facts per question, averaged. Memory here holds 20; a selection that offers most of it is broken. */
    private static final double NOISE_CEILING = 12.0;

    @TempDir
    Path temp;

    private MemoryStore memory;
    private MemoryRecall recall;

    private final Map<String, Long> facts = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        memory = TestMemory.inDirectory(temp);
        recall = new MemoryRecall(memory);
        fillMemory();
    }

    @AfterEach
    void tearDown() {
        memory.close();
    }

    private void fillMemory() {
        aboutUser("Пользователя зовут Иван", FactCategory.TRAIT);
        aboutUser("Пользователь живёт в Казани", FactCategory.TRAIT);
        aboutUser("Пользователь работает бэкенд-разработчиком", FactCategory.TRAIT);
        aboutUser("У пользователя аллергия на орехи", FactCategory.TRAIT);
        aboutUser("Пользователь пользуется Arch Linux", FactCategory.TRAIT);
        aboutUser("Пользователь не любит длинные ответы", FactCategory.PREFERENCE);
        aboutUser("Пользователь копит на велосипед", FactCategory.EVENT);
        aboutUser("Пользователь смотрел фильм «Дюна»", FactCategory.EVENT);
        aboutUser("Пользователь купил механическую клавиатуру", FactCategory.EVENT);
        aboutUser("Перед стримом: включить OBS, проверить свет, запустить обратный отсчёт",
                FactCategory.PROCEDURE);
        aboutUser("Перед релизом: прогнать тесты, поставить тег, дождаться CI", FactCategory.PROCEDURE);

        long sasha = memory.addEntity("Саша", List.of("Александр"), "друг", "").id();
        about(sasha, "Саша не ест мясо", FactCategory.PREFERENCE);
        about(sasha, "Саша живёт в Питере", FactCategory.TRAIT);
        about(sasha, "Саша играет на гитаре", FactCategory.TRAIT);
        about(sasha, "У Саши день рождения 12 марта", FactCategory.TRAIT);

        long marina = memory.addEntity("Марина", List.of(), "сестра", "").id();
        about(marina, "Марина учится на врача", FactCategory.TRAIT);
        about(marina, "Марина боится собак", FactCategory.TRAIT);

        long petr = memory.addEntity("Пётр", List.of(), "коллега", "").id();
        about(petr, "Пётр ведёт проект «Атлас»", FactCategory.TRAIT);
        about(petr, "Пётр уходит в отпуск в июле", FactCategory.EVENT);
        about(petr, "С Петром договорились созвониться в понедельник", FactCategory.AGREEMENT);
    }

    private void aboutUser(String text, FactCategory category) {
        facts.put(text, memory.addFact(text, category, null, null, List.of()).id());
    }

    private void about(long entityId, String text, FactCategory category) {
        facts.put(text, memory.addFact(text, category, null, null, List.of(entityId)).id());
    }

    private long id(String text) {
        Long id = facts.get(text);
        if (id == null) {
            throw new IllegalArgumentException("No such fact in the corpus: " + text);
        }
        return id;
    }

    private record Question(String text, long expected, boolean blindSpot) {
    }

    private List<Question> questions() {
        return List.of(
                // Named person: the oldest path and the one that must never break.
                new Question("что приготовить Саше на ужин?", id("Саша не ест мясо"), false),
                new Question("когда у Саши день рождения?", id("У Саши день рождения 12 марта"), false),
                new Question("Саша умеет играть на чём-нибудь?", id("Саша играет на гитаре"), false),
                new Question("что там у Марины с учёбой?", id("Марина учится на врача"), false),

                // The user themselves: their facts are offered whether or not the words match.
                new Question("какая у меня операционная система?", id("Пользователь пользуется Arch Linux"), false),
                new Question("мне можно съесть это печенье?", id("У пользователя аллергия на орехи"), false),
                new Question("как меня зовут?", id("Пользователя зовут Иван"), false),

                // Procedures: an instruction the user asked to keep is always in front of the model.
                new Question("что мне сделать перед стримом?",
                        id("Перед стримом: включить OBS, проверить свет, запустить обратный отсчёт"), false),
                new Question("напомни порядок выкатки",
                        id("Перед релизом: прогнать тесты, поставить тег, дождаться CI"), false),

                // Nobody named, but a word of the question is a word of the fact. This is what the
                // keyword sweep is for: without it the fact was invisible unless a name was typed.
                new Question("как там проект Атлас?", id("Пётр ведёт проект «Атлас»"), false),
                new Question("кто боится собак?", id("Марина боится собак"), false),
                new Question("когда у Петра отпуск?", id("Пётр уходит в отпуск в июле"), false),
                new Question("о чём мы договорились созвониться?",
                        id("С Петром договорились созвониться в понедельник"), false),

                // Known blind spots: the question and the fact share no word at all. Only a
                // semantic index fixes these -- the model can reach them today via the recall
                // tool, but the automatic selection cannot.
                new Question("кто из моих знакомых вегетарианец?", id("Саша не ест мясо"), true),
                new Question("кому из моих знакомых нельзя дарить щенка?", id("Марина боится собак"), true),
                new Question("в каком городе я живу?", id("Пользователь живёт в Казани"), false));
    }

    @Test
    void theFactThatAnswersTheQuestionReachesThePrompt() {
        Instant now = Instant.now();
        List<String> misses = new ArrayList<>();
        List<String> blindSpots = new ArrayList<>();
        int answerable = 0;
        int hits = 0;
        int offeredTotal = 0;

        StringBuilder report = new StringBuilder("\nMemory recall over ")
                .append(memory.countFacts()).append(" facts:\n");

        for (Question question : questions()) {
            Set<Long> offered = Set.copyOf(recall.select(question.text(), now).offered());
            boolean hit = offered.contains(question.expected());
            offeredTotal += offered.size();

            report.append(hit ? "  ok    " : "  MISS  ")
                    .append(question.blindSpot() ? "[blind spot] " : "")
                    .append(question.text())
                    .append("  (offered ").append(offered.size()).append(")\n");

            if (question.blindSpot()) {
                if (!hit) {
                    blindSpots.add(question.text());
                }
                continue;
            }
            answerable++;
            if (hit) {
                hits++;
            } else {
                misses.add(question.text() + " -> expected #" + question.expected());
            }
        }

        double recallRate = (double) hits / answerable;
        double noise = (double) offeredTotal / questions().size();
        report.append(String.format("  recall %.2f over %d answerable, %d known blind spots, "
                + "%.1f facts offered per question%n", recallRate, answerable, blindSpots.size(), noise));
        System.out.print(report);

        assertTrue(recallRate >= RECALL_FLOOR,
                "Recall dropped to " + recallRate + " (floor " + RECALL_FLOOR + "). Misses: " + misses + report);
        assertTrue(noise <= NOISE_CEILING,
                "The prompt is growing with the size of memory rather than with the question: "
                        + noise + " facts per question" + report);
    }

    /**
     * The blind spots are named in the report above, not silently passed. If one of them starts
     * working, this test says so instead of quietly agreeing -- that is the moment to move it up
     * into the answerable list.
     */
    @Test
    void knownBlindSpotsAreStillBlind() {
        Instant now = Instant.now();
        List<String> fixed = new ArrayList<>();
        for (Question question : questions()) {
            if (question.blindSpot()
                    && recall.select(question.text(), now).offered().contains(question.expected())) {
                fixed.add(question.text());
            }
        }
        System.out.println("Blind spots now answered: " + fixed);
    }

    @Test
    void whatWasDiscussedBeforeIsStillReachableAfterTheConversationIsOver() {
        DialogSession session = memory.openOrContinue("TELEGRAM:1");
        memory.append(session.id(), MessageRole.USER, "разбирались с whisper", "TELEGRAM", "t");
        memory.saveSummary(session.id(), "Настраивали распознавание речи: собрали whisper.cpp, "
                + "скачали модель large-v3-turbo.");
        memory.endSession(session.id());

        String block = recall.select("а какую модель мы тогда скачали?", Instant.now()).block();

        assertTrue(block.contains("large-v3-turbo"),
                "a finished conversation must leave something the next one can lean on:\n" + block);
    }

    /**
     * A fact the model actually leaned on outranks one that merely shares a word. Without a signal
     * like this the weights are guesses, and there is nothing for them to be wrong about.
     */
    @Test
    void aFactThatHasProvedUsefulRisesAboveOneThatHasNot() {
        long useful = id("Пользователь купил механическую клавиатуру");
        for (int i = 0; i < 4; i++) {
            memory.markUsed(List.of(useful));
        }
        memory.confirmFact(useful);

        List<Fact> ranked = FactRelevance.pick(memory.factsAboutUser(), "клавиатура", Instant.now(), 3);

        assertTrue(ranked.stream().anyMatch(f -> f.id() == useful));
        assertTrue(ranked.getFirst().id() == useful,
                "a fact that has been used and confirmed must come first: "
                        + ranked.stream().map(Fact::text).toList());
    }

    @Test
    void whatIsNoLongerCurrentIsNotOffered() {
        Fact kazan = memory.fact(id("Пользователь живёт в Казани")).orElseThrow();
        Fact moscow = memory.addFact("Пользователь живёт в Москве", FactCategory.TRAIT, null, null, List.of());
        memory.supersede(kazan.id(), moscow.id(), "переехал");

        String block = recall.select("в каком городе я живу?", Instant.now()).block();

        assertTrue(block.contains("в Москве"), block);
        assertTrue(!block.contains("в Казани"),
                "the model must not have to choose between two answers it was given as equals:\n" + block);
    }

    @Test
    void entitiesAreStillResolvedByName() {
        Entity sasha = memory.findByName("Саша").orElseThrow();
        assertTrue(recall.select("что там у Александра?", Instant.now()).mentioned().stream()
                        .anyMatch(e -> e.id() == sasha.id()),
                "an alias is a name too");
    }
}
