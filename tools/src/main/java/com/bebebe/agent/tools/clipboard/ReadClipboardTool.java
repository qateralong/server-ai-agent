package com.bebebe.agent.tools.clipboard;

import com.bebebe.agent.tools.Tool;
import com.bebebe.agent.tools.ToolContext;
import com.bebebe.agent.tools.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;

public final class ReadClipboardTool implements Tool {

    public static final String NAME = "read_clipboard";
    public static final String CONTEXT_SOURCE = "CLIPBOARD";

    private static final Logger log = LoggerFactory.getLogger(ReadClipboardTool.class);
    static final int MAX_CHARS = 20_000;

    @FunctionalInterface
    public interface Source {

        Optional<String> read();
    }

    private final Source source;
    private final java.util.function.BooleanSupplier available;

    public ReadClipboardTool(Source source) {
        this(source, () -> true);
    }

    public ReadClipboardTool(com.bebebe.agent.transport.actions.ClipboardTool clipboard) {
        this(clipboard::read, clipboard::isReady);
    }

    public ReadClipboardTool(Source source, java.util.function.BooleanSupplier available) {
        this.source = source;
        this.available = available;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Read text from the clipboard. Call it ONLY when the user explicitly refers to it: "
                + "\"возьми то, что я скопировал\", \"из буфера\", \"переведи скопированное\", \"вот это\" after "
                + "mentioning copying. Without such a phrase do not read the clipboard. No parameters.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of();
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolContext context) {
        if (!available.getAsBoolean()) {
            return ToolResult.failure("Clipboard reading is unavailable: wl-paste is not installed "
                    + "(package wl-clipboard). Tell the user.");
        }
        Optional<String> text = source.read();
        if (text.isEmpty() || text.get().isBlank()) {
            log.info("Clipboard is empty or contains non-text");
            return ToolResult.failure("The clipboard is empty or contains non-text (e.g. an image). "
                    + "Tell the user.");
        }
        String content = text.get();
        boolean cut = content.length() > MAX_CHARS;
        if (cut) {
            content = content.substring(0, MAX_CHARS);
        }
        log.atInfo().addKeyValue("event", "clipboard.read").addKeyValue("chars", content.length())
                .addKeyValue("truncated", cut).log("Clipboard read: {} chars", content.length());
        return ToolResult.okAsContext("Clipboard contents" + (cut ? " (truncated to " + MAX_CHARS + " chars)" : "")
                + ":\n\n" + content, CONTEXT_SOURCE);
    }
}
