package com.bebebe.agent.llm;

import com.bebebe.agent.ollama.ChatMessage;
import com.bebebe.agent.ollama.ChatRequest;
import com.bebebe.agent.ollama.ChatResponse;
import com.bebebe.agent.ollama.OllamaClient;
import com.bebebe.agent.ollama.OllamaConfig;
import com.bebebe.agent.ollama.OllamaException;
import com.bebebe.agent.ollama.OllamaStats;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class OllamaProvider implements LlmProvider {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OllamaProvider.class);

    public static final String ID = "ollama";

    private final OllamaClient client;

    public OllamaProvider(OllamaConfig config) {
        this(new OllamaClient(config));
    }

    public OllamaProvider(OllamaClient client) {
        this.client = client;
    }

    public OllamaClient client() {
        return client;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Ollama";
    }

    @Override
    public String model() {
        return client.config().model();
    }

    @Override
    public void setModel(String model) {
        client.setModel(model);
    }

    @Override
    public void setApiKey(String apiKey) {
        client.setApiKey(apiKey);
    }

    @Override
    public String endpoint() {
        return client.config().baseUrl();
    }

    @Override
    public Duration timeout() {
        return client.config().timeout();
    }

    /**
     * Vision in Ollama belongs to the model, not to the endpoint, so the model itself is asked --
     * {@code /api/show} reports a {@code vision} capability. The answer is cached per model name:
     * it cannot change without the model changing, and a round trip before every picture would be
     * a waste.
     */
    @Override
    public boolean supportsImages() {
        String model = client.config().model();
        return visionByModel.computeIfAbsent(model, m -> {
            boolean vision = client.capabilities(m).contains("vision");
            log.info("Model «{}»: images {}", m, vision ? "supported" : "not supported");
            return vision;
        });
    }

    private final java.util.Map<String, Boolean> visionByModel = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public LlmResponse chat(LlmRequest request) {
        OllamaConfig config = client.config();
        List<ChatMessage> history = new ArrayList<>();
        for (LlmRequest.LlmMessage m : request.history()) {
            history.add("assistant".equals(m.role()) ? ChatMessage.assistant(m.text()) : ChatMessage.user(m.text()));
        }
        ChatRequest.Builder builder = ChatRequest.builder(config.model())
                .system(request.system())
                .messages(history)
                .user(request.user(), request.images().stream().map(LlmImage::base64).toList())
                .temperature(request.temperature() != null ? request.temperature() : config.temperature())
                .numCtx(config.numCtx());
        if (request.structured()) {
            builder.format(request.schema());
        }
        try {
            ChatResponse response = client.chat(builder.build());
            return new LlmResponse(response.text(), response.promptEvalCount(), response.evalCount(),
                    response.evalDuration(), response.model());
        } catch (OllamaException e) {
            throw e;
        }
    }

    @Override
    public List<String> listModels() {
        return client.listModels();
    }

    @Override
    public boolean ping() {
        try {
            client.ping();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public OllamaStats stats() {
        return client.stats();
    }

    @Override
    public void close() {
        client.close();
    }
}
