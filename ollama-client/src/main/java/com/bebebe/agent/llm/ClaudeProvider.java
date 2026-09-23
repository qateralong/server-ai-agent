package com.bebebe.agent.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.models.ModelListPage;
import com.anthropic.models.models.ModelListParams;
import com.bebebe.agent.ollama.OllamaStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ClaudeProvider implements LlmProvider {

    public static final String ID = "claude";

    private static final Logger log = LoggerFactory.getLogger(ClaudeProvider.class);

    private final ClaudeConfig config;
    private final String endpoint;
    private final OllamaStats stats;
    private volatile String apiKey;
    private volatile String model;
    private volatile AnthropicClient client;

    public ClaudeProvider(ClaudeConfig config, String apiKey, String model, String endpoint) {
        this(config, apiKey, model, endpoint, new OllamaStats());
    }

    public ClaudeProvider(ClaudeConfig config, String apiKey, String model, String endpoint, OllamaStats stats) {
        this.config = config;
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.model = model == null || model.isBlank() ? ClaudeConfig.DEFAULT_MODEL : model.strip();
        this.endpoint = endpoint == null || endpoint.isBlank() ? ClaudeConfig.DEFAULT_ENDPOINT : endpoint.strip();
        this.stats = stats;
        this.client = buildClient();
    }

    private AnthropicClient buildClient() {
        return AnthropicOkHttpClient.builder()
                .apiKey(apiKey.isEmpty() ? "missing" : apiKey)
                .baseUrl(endpoint)
                .timeout(config.timeout())
                .maxRetries(1)
                .build();
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Claude (Anthropic)";
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public void setModel(String model) {
        if (model != null && !model.isBlank()) {
            this.model = model.strip();
        }
    }

    @Override
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        AnthropicClient old = client;
        client = buildClient();
        old.close();
    }

    @Override
    public String endpoint() {
        return endpoint;
    }

    @Override
    public Duration timeout() {
        return config.timeout();
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        if (apiKey.isEmpty()) {
            throw new LlmException("Claude API key is not set: Settings -> API key (Claude provider), "
                    + "keys are created at https://platform.claude.com");
        }
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(config.maxTokens());
        if (!request.system().isBlank()) {
            params.system(request.system());
        }
        Double temperature = request.temperature() != null ? request.temperature() : config.temperature();
        if (temperature != null) {
            params.temperature(temperature);
        }
        List<LlmRequest.LlmMessage> turns = alternate(request.history(), request.user());
        for (int i = 0; i < turns.size(); i++) {
            LlmRequest.LlmMessage m = turns.get(i);
            if ("assistant".equals(m.role())) {
                params.addAssistantMessage(m.text());
            } else if (request.hasImages() && i == turns.size() - 1) {

                // Only the last turn -- that is the question the pictures came with. The
                // documentation asks for images before the text, and says it reads better that way.
                params.addUserMessageOfBlockParams(imageBlocks(request, m.text()));
            } else {
                params.addUserMessage(m.text());
            }
        }
        if (request.structured()) {
            params.outputConfig(OutputConfig.builder()
                    .format(JsonOutputFormat.builder()
                            .schema(JsonOutputFormat.Schema.builder()
                                    .additionalProperties(toJson(request.schema()))
                                    .build())
                            .build())
                    .build());
        }
        long started = System.nanoTime();
        try {
            Message message = client.messages().create(params.build());
            StringBuilder text = new StringBuilder();
            for (ContentBlock block : message.content()) {
                block.text().ifPresent(t -> text.append(t.text()));
            }
            LlmResponse response = new LlmResponse(text.toString(),
                    (int) message.usage().inputTokens(), (int) message.usage().outputTokens(),
                    System.nanoTime() - started, message.model().toString());
            stats.success(response.inputTokens(), response.outputTokens());
            return response;
        } catch (AnthropicServiceException e) {
            LlmException wrapped = new LlmException("Claude API: HTTP " + e.statusCode() + hint(e.statusCode()), e, e.statusCode());
            stats.failure(wrapped.getMessage());
            throw wrapped;
        } catch (AnthropicIoException e) {
            LlmException wrapped = new LlmException("Network unavailable when calling " + endpoint + ": " + e.getMessage(), e);
            stats.failure(wrapped.getMessage());
            throw wrapped;
        } catch (AnthropicException e) {
            LlmException wrapped = new LlmException("Claude API: " + e.getMessage(), e);
            stats.failure(wrapped.getMessage());
            throw wrapped;
        }
    }

    /** An {@code image} content block per picture, then the text -- the order the docs recommend. */
    private static List<com.anthropic.models.messages.ContentBlockParam> imageBlocks(
            LlmRequest request, String text) {
        List<com.anthropic.models.messages.ContentBlockParam> blocks = new ArrayList<>();
        for (LlmImage image : request.images()) {
            blocks.add(com.anthropic.models.messages.ContentBlockParam.ofImage(
                    com.anthropic.models.messages.ImageBlockParam.builder()
                            .source(com.anthropic.models.messages.Base64ImageSource.builder()
                                    .mediaType(mediaType(image.mediaType()))
                                    .data(image.base64())
                                    .build())
                            .build()));
        }
        blocks.add(com.anthropic.models.messages.ContentBlockParam.ofText(
                com.anthropic.models.messages.TextBlockParam.builder()
                        .text(text == null || text.isBlank() ? "(empty)" : text)
                        .build()));
        return blocks;
    }

    private static com.anthropic.models.messages.Base64ImageSource.MediaType mediaType(String raw) {
        return switch (raw) {
            case "image/png" -> com.anthropic.models.messages.Base64ImageSource.MediaType.IMAGE_PNG;
            case "image/gif" -> com.anthropic.models.messages.Base64ImageSource.MediaType.IMAGE_GIF;
            case "image/webp" -> com.anthropic.models.messages.Base64ImageSource.MediaType.IMAGE_WEBP;
            default -> com.anthropic.models.messages.Base64ImageSource.MediaType.IMAGE_JPEG;
        };
    }

    /** Every current Claude model reads images; unlike Ollama it does not depend on the model. */
    @Override
    public boolean supportsImages() {
        return true;
    }

    static List<LlmRequest.LlmMessage> alternate(List<LlmRequest.LlmMessage> history, String user) {
        List<LlmRequest.LlmMessage> out = new ArrayList<>();
        for (LlmRequest.LlmMessage m : history) {
            if (m.text() == null || m.text().isBlank()) {
                continue;
            }
            String role = "assistant".equals(m.role()) ? "assistant" : "user";
            if (!out.isEmpty() && out.getLast().role().equals(role)) {
                LlmRequest.LlmMessage prev = out.removeLast();
                out.add(new LlmRequest.LlmMessage(role, prev.text() + "\n\n" + m.text()));
            } else {
                out.add(new LlmRequest.LlmMessage(role, m.text()));
            }
        }
        if (!out.isEmpty() && out.getFirst().role().equals("assistant")) {
            out.removeFirst();
        }
        String question = user == null || user.isBlank() ? "(empty)" : user;
        if (!out.isEmpty() && out.getLast().role().equals("user")) {
            LlmRequest.LlmMessage prev = out.removeLast();
            out.add(new LlmRequest.LlmMessage("user", prev.text() + "\n\n" + question));
        } else {
            out.add(new LlmRequest.LlmMessage("user", question));
        }
        return out;
    }

    private static Map<String, JsonValue> toJson(Map<String, Object> schema) {
        Map<String, JsonValue> out = new java.util.LinkedHashMap<>();
        schema.forEach((k, v) -> out.put(k, JsonValue.from(v)));
        return out;
    }

    private static String hint(int status) {
        return switch (status) {
            case 401 -> " -- invalid Claude API key";
            case 403 -> " -- the key lacks permissions or the region is unsupported";
            case 404 -> " -- model not found: check the name (e.g. claude-opus-5) or the endpoint";
            case 429 -> " -- rate limit exceeded, retry later";
            case 529 -> " -- Anthropic is overloaded, retry later";
            default -> status >= 500 ? " -- error on the Anthropic side" : "";
        };
    }

    @Override
    public List<String> listModels() {
        try {

            ModelListPage page = client.models().list(ModelListParams.builder().limit(100L).build());
            List<String> ids = new ArrayList<>();
            page.data().forEach(m -> ids.add(m.id()));
            return ids;
        } catch (AnthropicException e) {
            log.warn("Claude model list unavailable: {}", e.getMessage());
            throw new LlmException("Claude API: cannot fetch the model list: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean ping() {
        try {
            return !listModels().isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public OllamaStats stats() {
        return stats;
    }

    @Override
    public void close() {
        client.close();
    }
}
