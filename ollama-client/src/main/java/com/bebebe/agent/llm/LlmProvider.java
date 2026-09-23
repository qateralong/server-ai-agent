package com.bebebe.agent.llm;

import com.bebebe.agent.ollama.OllamaStats;

import java.time.Duration;
import java.util.List;

public interface LlmProvider extends AutoCloseable {

    String id();

    String displayName();

    String model();

    void setModel(String model);

    void setApiKey(String apiKey);

    String endpoint();

    Duration timeout();

    LlmResponse chat(LlmRequest request);

    /**
     * Whether the provider can look at an image <b>with its currently selected model</b>.
     * Claude always can; for Ollama it depends on the model. Default false, so a provider added
     * later has to say so deliberately rather than silently dropping attachments.
     */
    default boolean supportsImages() {
        return false;
    }

    List<String> listModels();

    boolean ping();

    OllamaStats stats();

    @Override
    void close();
}
