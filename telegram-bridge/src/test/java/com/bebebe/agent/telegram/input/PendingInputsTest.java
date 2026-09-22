package com.bebebe.agent.telegram.input;

import com.bebebe.agent.telegram.menu.CallbackData;
import com.bebebe.agent.telegram.menu.MenuSection;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingInputsTest {

    private static final long CHAT = 42L;

    private final PendingInputs inputs = new PendingInputs();

    private static PendingInput input(String field, InputHandler handler) {
        return PendingInput.of(field, "Send the value", 100, handler);
    }

    @Test
    void interceptsNothingWithoutPending() {
        assertTrue(inputs.peek(CHAT).isEmpty());
        assertTrue(inputs.consume(CHAT).isEmpty());
        assertEquals(0, inputs.size());
    }

    @Test
    void interceptsNextMessage() {
        AtomicReference<String> received = new AtomicReference<>();
        inputs.await(CHAT, input("ollama.model", value -> {
            received.set(value);
            return InputOutcome.accepted("Done", CallbackData.section(MenuSection.SETTINGS));
        }));

        PendingInput pending = inputs.consume(CHAT).orElseThrow();
        InputOutcome outcome = pending.handler().accept("qwen3.5:cloud");

        assertEquals("qwen3.5:cloud", received.get());
        assertTrue(outcome.accepted());
        assertEquals(CallbackData.section(MenuSection.SETTINGS), outcome.returnTo());
    }

    @Test
    void afterConsumptionModeIsCleared() {
        inputs.await(CHAT, input("field", value -> InputOutcome.accepted("ok")));

        assertTrue(inputs.consume(CHAT).isPresent());
        assertTrue(inputs.consume(CHAT).isEmpty());
        assertEquals(0, inputs.size());
    }

    @Test
    void pendingIsPerChat() {
        inputs.await(1L, input("a", value -> InputOutcome.accepted("ok")));
        inputs.await(2L, input("b", value -> InputOutcome.accepted("ok")));

        assertEquals("a", inputs.peek(1L).orElseThrow().fieldKey());
        assertEquals("b", inputs.peek(2L).orElseThrow().fieldKey());
        assertEquals(2, inputs.size());
    }

    @Test
    void newPendingReplacesOld() {
        inputs.await(CHAT, input("first", value -> InputOutcome.accepted("ok")));
        inputs.await(CHAT, input("second", value -> InputOutcome.accepted("ok")));

        assertEquals("second", inputs.peek(CHAT).orElseThrow().fieldKey());
        assertEquals(1, inputs.size());
    }

    @Test
    void cancelClearsMode() {
        inputs.await(CHAT, input("field", value -> InputOutcome.accepted("ok")));

        assertTrue(inputs.cancel(CHAT));
        assertTrue(inputs.peek(CHAT).isEmpty());
        assertFalse(inputs.cancel(CHAT));
    }

    @Test
    void expiredPendingDoesNotIntercept() {

        PendingInput expired = new PendingInput(
                "field", "Send the value", 100,
                Instant.now().minusSeconds(1),
                value -> InputOutcome.accepted("ok"));
        inputs.await(CHAT, expired);

        assertTrue(inputs.peek(CHAT).isEmpty());
        assertEquals(0, inputs.size());
    }

    @Test
    void rejectedValueKeepsPending() {
        InputOutcome outcome = InputOutcome.rejected("A number is required");

        assertFalse(outcome.accepted());
        assertEquals("A number is required", outcome.message());
    }
}
