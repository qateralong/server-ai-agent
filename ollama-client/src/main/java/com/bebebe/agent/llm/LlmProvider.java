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

    List<String> listModels();

    boolean ping();

    OllamaStats stats();

    @Override
    void close();
}
