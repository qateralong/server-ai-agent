package com.bebebe.agent.core;

import com.bebebe.agent.llm.EmbeddingProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * An embedding model, minus the model.
 *
 * <p>Texts are placed on a handful of hand-written topics, so that two ways of saying the same
 * thing land on the same one: «мясо», «вегетарианец» and «ужин» are all FOOD, so they come out
 * close together exactly as a real embedding would put them.
 *
 * <p>What this can and cannot show. It proves <b>our</b> half: that vectors are stored and read
 * back, that the cosine ranks what it should, that a query reaches the search, that the threshold
 * and the caps hold, and that the whole thing stays out of the way when it is switched off. It
 * proves nothing at all about how well a real embedding model groups Russian -- that is the
 * model's half, and no offline test can speak for it.
 */
final class TestEmbeddings implements EmbeddingProvider {

    /** Topic -> the words that put a text on it. Order fixes the dimension of the vectors. */
    private static final Map<String, List<String>> TOPICS = new LinkedHashMap<>();

    static {
        TOPICS.put("food", List.of("мяс", "вегетариан", "еда", "ужин", "питан", "орех", "арахис", "кофе", "чай"));
        TOPICS.put("animals", List.of("собак", "щен", "кот", "животн", "питомец"));
        TOPICS.put("music", List.of("гитар", "музык", "играет", "пианино", "песн"));
        TOPICS.put("place", List.of("город", "живёт", "живет", "переех", "казан", "москв", "питер"));
        TOPICS.put("work", List.of("работа", "разработчик", "проект", "банк", "стартап", "коллег"));
        TOPICS.put("health", List.of("аллерг", "здоровь", "врач", "болезн", "нельзя"));
        TOPICS.put("time", List.of("рождени", "март", "отпуск", "июл", "завтра", "суббот"));
        TOPICS.put("tech", List.of("linux", "arch", "клавиатур", "whisper", "скрипт", "звук"));
    }

    /** Counts calls, so a test can prove the expensive path was not taken. */
    private int calls;

    @Override
    public List<float[]> embed(List<String> texts) {
        calls += texts.size();
        List<float[]> out = new ArrayList<>();
        for (String text : texts) {
            out.add(vector(text));
        }
        return out;
    }

    static float[] vector(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        float[] values = new float[TOPICS.size()];
        int i = 0;
        for (List<String> markers : TOPICS.values()) {
            for (String marker : markers) {
                if (lower.contains(marker)) {
                    values[i] += 1f;
                }
            }
            i++;
        }
        return values;
    }

    int calls() {
        return calls;
    }

    @Override
    public String model() {
        return "test-embeddings";
    }

    @Override
    public boolean isReady() {
        return true;
    }
}
