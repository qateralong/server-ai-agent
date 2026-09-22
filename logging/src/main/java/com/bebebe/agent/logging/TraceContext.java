package com.bebebe.agent.logging;

import org.slf4j.MDC;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.Callable;

public final class TraceContext {

    public static final String KEY = "trace_id";

    private static final SecureRandom RANDOM = new SecureRandom();

    private TraceContext() {
    }

    public static String newId() {
        byte[] bytes = new byte[6];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public static Optional<String> current() {
        return Optional.ofNullable(MDC.get(KEY)).filter(s -> !s.isBlank());
    }

    public static String currentOrNew() {
        return current().orElseGet(TraceContext::newId);
    }

    public static Scope open(String traceId) {
        String previous = MDC.get(KEY);
        MDC.put(KEY, traceId);
        return new Scope(previous);
    }

    public static Scope openNew() {
        return open(newId());
    }

    public static Runnable wrap(String traceId, Runnable task) {
        return () -> {
            try (Scope ignored = open(traceId)) {
                task.run();
            }
        };
    }

    public static <T> Callable<T> wrap(String traceId, Callable<T> task) {
        return () -> {
            try (Scope ignored = open(traceId)) {
                return task.call();
            }
        };
    }

    public static final class Scope implements AutoCloseable {

        private final String previous;

        private Scope(String previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous == null) {
                MDC.remove(KEY);
            } else {
                MDC.put(KEY, previous);
            }
        }
    }
}
