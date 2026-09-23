package com.bebebe.agent.llm;

import java.util.List;

/**
 * Turns text into a vector, so that two ways of saying the same thing end up close together.
 *
 * <p>Separate from {@link LlmProvider} because it is a different kind of service with a different
 * answer: Anthropic has no embedding API at all, and a Claude-driven agent that wants semantic
 * recall still reaches an Ollama endpoint for it. Nothing in the core requires this to exist --
 * without it recall stays lexical, which is what it has always been.
 */
public interface EmbeddingProvider {

    /** One vector per input, in the same order. */
    List<float[]> embed(List<String> texts);

    default float[] embed(String text) {
        List<float[]> vectors = embed(List.of(text));
        return vectors.isEmpty() ? new float[0] : vectors.getFirst();
    }

    /** Which model produced the vectors; stored with them, because vectors of two models do not mix. */
    String model();

    /** False when there is nothing configured to call, so callers can quietly stay lexical. */
    boolean isReady();
}
