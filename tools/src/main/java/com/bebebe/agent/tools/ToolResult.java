package com.bebebe.agent.tools;

public record ToolResult(boolean success, String content, String sources, String contextSource) {

    public ToolResult(boolean success, String content, String sources) {
        this(success, content, sources, null);
    }

    public static ToolResult okAsContext(String content, String contextSource) {
        return new ToolResult(true, content, "", contextSource);
    }

    public static ToolResult ok(String content) {
        return new ToolResult(true, content, "");
    }

    public static ToolResult ok(String content, String sources) {
        return new ToolResult(true, content, sources);
    }

    public static ToolResult failure(String reason) {
        return new ToolResult(false, reason, "");
    }

    public String describeForModel() {
        StringBuilder sb = new StringBuilder(success ? "" : "ERROR: ").append(content);
        if (sources != null && !sources.isBlank()) {
            sb.append("\n\nSources:\n").append(sources);
        }
        return sb.toString();
    }
}
