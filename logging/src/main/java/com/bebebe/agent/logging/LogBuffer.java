package com.bebebe.agent.logging;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class LogBuffer {

    public static final int DEFAULT_CAPACITY = 3000;

    private static final LogBuffer INSTANCE = new LogBuffer(DEFAULT_CAPACITY);

    public static LogBuffer global() {
        return INSTANCE;
    }

    private final int capacity;
    private final ArrayDeque<LogEntry> entries;
    private final List<Consumer<LogEntry>> listeners = new CopyOnWriteArrayList<>();
    private long seq;
    private volatile LogEntry lastError;

    public LogBuffer(int capacity) {
        this.capacity = capacity;
        this.entries = new ArrayDeque<>(capacity);
    }

    public void add(LogEntry entry) {
        LogEntry stamped;
        synchronized (this) {
            stamped = new LogEntry(++seq, entry.ts(), entry.level(), entry.subsystem(), entry.logger(),
                    entry.thread(), entry.traceId(), entry.message(), entry.kv(), entry.error());
            if (entries.size() >= capacity) {
                entries.pollFirst();
            }
            entries.addLast(stamped);
            if (stamped.isError()) {
                lastError = stamped;
            }
        }
        for (Consumer<LogEntry> l : listeners) {
            try {
                l.accept(stamped);
            } catch (RuntimeException ignored) {

            }
        }
    }

    public synchronized List<LogEntry> snapshot() {
        return new ArrayList<>(entries);
    }

    public synchronized List<LogEntry> snapshot(Predicate<LogEntry> filter, int limit) {
        List<LogEntry> out = new ArrayList<>();
        var it = entries.descendingIterator();
        while (it.hasNext() && out.size() < limit) {
            LogEntry e = it.next();
            if (filter.test(e)) {
                out.add(e);
            }
        }
        java.util.Collections.reverse(out);
        return out;
    }

    public Optional<LogEntry> lastError() {
        return Optional.ofNullable(lastError);
    }

    public synchronized int size() {
        return entries.size();
    }

    public void addListener(Consumer<LogEntry> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<LogEntry> listener) {
        listeners.remove(listener);
    }

    synchronized void clear() {
        entries.clear();
        lastError = null;
    }
}
