package com.bebebe.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry register(Tool tool) {
        if (!tool.name().matches("[a-z][a-z0-9_]*")) {
            throw new IllegalArgumentException("Tool name must be lowercase latin and _: " + tool.name());
        }
        tools.put(tool.name(), tool);
        log.info("Tool registered: {}", tool.name());
        return this;
    }

    public Optional<Tool> find(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(tools.get(name.strip()));
    }

    public List<String> names() {
        return List.copyOf(tools.keySet());
    }

    public boolean isEmpty() {
        return tools.isEmpty();
    }

    public String describeForModel() {
        if (tools.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Tool tool : tools.values()) {
            sb.append("  ").append(tool.name()).append(" — ").append(tool.description()).append('\n');
            tool.parameters().forEach((key, spec) -> {
                String description = spec instanceof Map<?, ?> map && map.get("description") != null
                        ? map.get("description").toString()
                        : "";
                sb.append("      ").append(key).append(": ").append(description).append('\n');
            });
        }
        return sb.toString();
    }

    public ToolResult execute(String name, Map<String, Object> arguments, ToolContext context) {
        Optional<Tool> tool = find(name);
        if (tool.isEmpty()) {
            return ToolResult.failure("No such tool: " + name + ". Available: " + names());
        }
        long started = System.nanoTime();
        try {
            ToolResult result = tool.get().execute(arguments == null ? Map.of() : arguments, context);
            log.atInfo()
                    .addKeyValue("event", "tool.result")
                    .addKeyValue("tool", name)
                    .addKeyValue("success", result.success())
                    .addKeyValue("duration_ms", (System.nanoTime() - started) / 1_000_000)
                    .log("Tool {}: {}", name, result.success() ? "ok" : "error");
            return result;
        } catch (ToolContext.BudgetExhausted e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("Tool {} failed", name, e);
            return ToolResult.failure("Tool " + name + " failed: " + e.getMessage());
        }
    }
}
