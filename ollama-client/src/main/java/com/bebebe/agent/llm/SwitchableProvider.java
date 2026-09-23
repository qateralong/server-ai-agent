package com.bebebe.agent.llm;

import com.bebebe.agent.ollama.OllamaStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.function.Function;

public final class SwitchableProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(SwitchableProvider.class);

    private final Function<String, LlmProvider> factory;
    private volatile LlmProvider current;

    public SwitchableProvider(LlmProvider initial, Function<String, LlmProvider> factory) {
        this.current = initial;
        this.factory = factory;
    }

    public LlmProvider pin() {
        return current;
    }

    public synchronized void switchTo(String providerId) {
        LlmProvider next = factory.apply(providerId);
        LlmProvider previous = current;
        current = next;
        log.atInfo().addKeyValue("event", "llm.switch").addKeyValue("from", previous.id()).addKeyValue("to", next.id())
                .addKeyValue("model", next.model())
                .log("Model provider: {} -> {} ({}, {})", previous.displayName(), next.displayName(), next.model(), next.endpoint());

    }

    @Override
    public String id() {
        return current.id();
    }

    @Override
    public String displayName() {
        return current.displayName();
    }

    @Override
    public String model() {
        return current.model();
    }

    /** Asked of whoever answers next, not of whoever answered last. */
    @Override
    public boolean supportsImages() {
        return current.supportsImages();
    }

    @Override
    public void setModel(String model) {
        current.setModel(model);
    }

    @Override
    public void setApiKey(String apiKey) {
        current.setApiKey(apiKey);
    }

    @Override
    public String endpoint() {
        return current.endpoint();
    }

    @Override
    public Duration timeout() {
        return current.timeout();
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        return current.chat(request);
    }

    @Override
    public List<String> listModels() {
        return current.listModels();
    }

    @Override
    public boolean ping() {
        return current.ping();
    }

    @Override
    public OllamaStats stats() {
        return current.stats();
    }

    @Override
    public void close() {
        current.close();
    }
}
