package com.bebebe.agent.core;

import com.bebebe.agent.llm.EmbeddingProvider;
import com.bebebe.agent.memory.Fact;
import com.bebebe.agent.memory.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Finding a fact by what it means rather than by the words it is written in.
 *
 * <p>This is what the eval has been printing as blind spots all along: "кто из моих знакомых
 * вегетарианец?" against "Саша не ест мясо", "щенка" against "щенок". Keywords written at
 * extraction time pushed that wall further out but could not remove it -- they only help when the
 * model happened to think of the word, and when the word survives prefix stemming.
 *
 * <p>Deliberately a <b>fallback</b>, not the main road. Lexical recall is free, instant and
 * predictable, and it answers most questions; this costs a network call, so it runs only when the
 * free path came back with nothing and the message actually looks like a question. Everything
 * here also degrades quietly: no embedding model configured, or an endpoint having a bad day,
 * and recall is exactly what it was before.
 */
final class SemanticRecall {

    private static final Logger log = LoggerFactory.getLogger(SemanticRecall.class);

    /**
     * Below this two texts are not about the same thing, they merely both exist. Tuned to be
     * strict: an irrelevant fact offered as a memory is worse than no fact at all, because the
     * model will try to use it.
     */
    static final double MIN_SIMILARITY = 0.55;

    /** How many facts are embedded per background pass. */
    static final int BACKFILL_BATCH = 32;

    private final MemoryStore memory;
    private final EmbeddingProvider embeddings;

    /**
     * Vectors in memory, because the comparison is a dot product over all of them and reading a
     * few megabytes of blobs per question would be the slow part. Kept in step by hand: this
     * class is the only thing that writes them.
     */
    private volatile Map<Long, float[]> cache;

    SemanticRecall(MemoryStore memory, EmbeddingProvider embeddings) {
        this.memory = memory;
        this.embeddings = embeddings;
    }

    boolean isReady() {
        return embeddings != null && embeddings.isReady();
    }

    /**
     * Facts closest in meaning to the query, best first.
     *
     * @return empty when embeddings are unavailable, nothing has been embedded yet, or nothing is
     *         close enough -- in every case the caller simply carries on without them
     */
    List<Fact> similar(String query, int limit) {
        if (!isReady() || query == null || query.isBlank()) {
            return List.of();
        }
        Map<Long, float[]> vectors = vectors();
        if (vectors.isEmpty()) {
            return List.of();
        }
        float[] asked = embeddings.embed(query);
        if (asked.length == 0) {
            return List.of();
        }

        record Scored(long id, double score) {
        }
        List<Scored> scored = new ArrayList<>();
        for (Map.Entry<Long, float[]> entry : vectors.entrySet()) {
            double score = cosine(asked, entry.getValue());
            if (score >= MIN_SIMILARITY) {
                scored.add(new Scored(entry.getKey(), score));
            }
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());

        List<Fact> found = new ArrayList<>();
        for (Scored hit : scored) {
            if (found.size() >= limit) {
                break;
            }
            Optional<Fact> fact = memory.fact(hit.id());
            if (fact.isPresent() && fact.get().isCurrent()) {
                found.add(fact.get());
            }
        }
        if (!found.isEmpty()) {
            log.atInfo()
                    .addKeyValue("event", "memory.semantic")
                    .addKeyValue("query", query)
                    .addKeyValue("found", found.size())
                    .addKeyValue("best", String.format("%.2f", scored.getFirst().score()))
                    .log("Found by meaning: {}", found.stream().map(Fact::text).toList());
        }
        return found;
    }

    /**
     * Embeds a batch of facts that have no vector yet.
     *
     * <p>Runs in the background rather than when the fact is written: a fact is stored in the
     * middle of answering somebody, and an extra round trip there buys nothing -- the vector is
     * needed by the next question, not by this one.
     *
     * @return how many facts were embedded
     */
    int backfill() {
        if (!isReady()) {
            return 0;
        }
        List<Fact> pending = memory.factsWithoutVectors(embeddings.model(), BACKFILL_BATCH);
        if (pending.isEmpty()) {
            return 0;
        }
        List<float[]> vectors = embeddings.embed(pending.stream().map(Fact::searchText).toList());
        if (vectors.size() != pending.size()) {
            log.debug("Embeddings returned {} vectors for {} facts -- skipping this pass",
                    vectors.size(), pending.size());
            return 0;
        }
        for (int i = 0; i < pending.size(); i++) {
            memory.saveVector(pending.get(i).id(), embeddings.model(), vectors.get(i));
        }
        cache = null;
        log.atInfo()
                .addKeyValue("event", "memory.embedded")
                .addKeyValue("count", pending.size())
                .addKeyValue("model", embeddings.model())
                .log("Embedded {} facts", pending.size());
        return pending.size();
    }

    private Map<Long, float[]> vectors() {
        Map<Long, float[]> known = cache;
        if (known == null) {
            known = memory.vectors(embeddings.model());
            cache = known;
        }
        return known;
    }

    /** Forgets what is cached; the next question reads the vectors again. */
    void invalidate() {
        cache = null;
    }

    static double cosine(float[] a, float[] b) {
        if (a.length != b.length || a.length == 0) {
            return 0;
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
