package com.bebebe.agent.logging;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceContextTest {

    @Test
    void identifierIsShortAndUnique() {
        String a = TraceContext.newId();
        String b = TraceContext.newId();

        assertEquals(12, a.length());
        assertTrue(a.matches("[0-9a-f]+"));
        assertNotEquals(a, b);
    }

    @Test
    void scopeSetsAndClearsValue() {
        assertTrue(TraceContext.current().isEmpty());

        try (TraceContext.Scope ignored = TraceContext.open("aaa")) {
            assertEquals("aaa", TraceContext.current().orElseThrow());
        }

        assertTrue(TraceContext.current().isEmpty());
        assertNull(MDC.get(TraceContext.KEY));
    }

    @Test
    void nestedScopeRestoresOuter() {
        try (TraceContext.Scope outer = TraceContext.open("внешний")) {
            try (TraceContext.Scope inner = TraceContext.open("внутренний")) {
                assertEquals("внутренний", TraceContext.current().orElseThrow());
            }
            assertEquals("внешний", TraceContext.current().orElseThrow());
        }
    }

    @Test
    void currentOrNewTakesCurrentIfPresent() {
        try (TraceContext.Scope ignored = TraceContext.open("есть")) {
            assertEquals("есть", TraceContext.currentOrNew());
        }
        assertEquals(12, TraceContext.currentOrNew().length());
    }

    @Test
    void wrapCarriesIdentifierToAnotherThread() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<String> seen = new AtomicReference<>();
        try {
            Future<?> done = executor.submit(TraceContext.wrap("перенос",
                    () -> seen.set(TraceContext.current().orElse("<пусто>"))));
            done.get();
        } finally {
            executor.shutdownNow();
        }

        assertEquals("перенос", seen.get());
        assertTrue(TraceContext.current().isEmpty(), "nothing must remain in the original thread");
    }

    @Test
    void wrapCallableReturnsResult() throws Exception {
        String result = TraceContext.wrap("x", () -> "результат:" + TraceContext.current().orElseThrow()).call();

        assertEquals("результат:x", result);
    }
}
