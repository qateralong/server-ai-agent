package com.bebebe.agent.tools.clipboard;

import com.bebebe.agent.tools.ToolContext;
import com.bebebe.agent.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadClipboardToolTest {

    private static final ToolContext NO_LLM = new ToolContext((s, u, sc) -> { throw new AssertionError(); }, "");

    @Test
    void clipboardTextIsReturnedAsConversationContext() {
        ToolResult r = new ReadClipboardTool(() -> Optional.of("Hello, world")).execute(Map.of(), NO_LLM);

        assertTrue(r.success());
        assertTrue(r.content().contains("Hello, world"), r.content());
        assertEquals(ReadClipboardTool.CONTEXT_SOURCE, r.contextSource(), "the core will put this into the session log");
    }

    @Test
    void emptyClipboardIsErrorForModelNotCrash() {
        ToolResult r = new ReadClipboardTool(Optional::empty).execute(Map.of(), NO_LLM);

        assertFalse(r.success());
        assertTrue(r.content().contains("empty"), r.content());
        assertNull(r.contextSource());
    }

    @Test
    void veryLongTextIsTruncated() {
        String big = "a".repeat(ReadClipboardTool.MAX_CHARS + 500);

        ToolResult r = new ReadClipboardTool(() -> Optional.of(big)).execute(Map.of(), NO_LLM);

        assertTrue(r.content().contains("truncated"));
        assertTrue(r.content().length() < big.length());
    }

    @Test
    void withoutWlPasteErrorNamesPackage() {
        ToolResult r = new ReadClipboardTool(() -> Optional.of("x"), () -> false).execute(Map.of(), NO_LLM);

        assertFalse(r.success());
        assertTrue(r.content().contains("wl-clipboard"), r.content());
    }
}
