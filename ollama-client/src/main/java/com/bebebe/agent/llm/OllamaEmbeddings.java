package com.bebebe.agent.llm;

import com.bebebe.agent.ollama.OllamaClient;
import com.bebebe.agent.ollama.OllamaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Embeddings through Ollama's {@code /api/embed} -- cloud or local, the same as for chat.
 *
 * <p>Failures are not thrown at the caller: semantic recall is an improvement on top of lexical
 * recall, and an embedding endpoint having a bad day must degrade the search, not the answer.
 */
public final class OllamaEmbeddings implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(OllamaEmbeddings.class);

    private final OllamaClient client;
    private final String model;

    private volatile boolean broken;

    public OllamaEmbeddings(OllamaClient client, String model) {
        this.client = client;
        this.model = model == null ? "" : model.strip();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (!isReady() || texts == null || texts.isEmpty()) {
            return List.of();
        }
        try {
            return client.embed(model, texts);
        } catch (OllamaException e) {

            // Once, loudly, and then quietly: a missing embedding model would otherwise fill the
            // log with the same line on every message.
            if (!broken) {
                broken = true;
                log.warn("Embeddings are unavailable ({}), recall stays lexical: {}", model, e.getMessage());
            } else {
                log.debug("Embeddings are still unavailable: {}", e.getMessage());
            }
            return List.of();
        }
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public boolean isReady() {
        return !model.isEmpty() && !broken;
    }
}
