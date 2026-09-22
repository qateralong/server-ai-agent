package com.bebebe.agent.core;

import com.bebebe.agent.logging.TraceContext;
import com.bebebe.agent.watchdog.Restartable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

public final class AgentWorker implements Restartable, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AgentWorker.class);

    public static final class Restarted extends RuntimeException {
        Restarted(String reason) {
            super(reason);
        }
    }

    private final ActivityMonitor activity;
    private final AtomicInteger generation = new AtomicInteger();
    private volatile ExecutorService executor;
    private volatile Thread workerThread;
    private volatile Future<?> current;

    public AgentWorker(ActivityMonitor activity) {
        this.activity = activity;
        this.executor = newExecutor();
    }

    public <T> T run(String what, String conversation, Callable<T> body) {
        if (Thread.currentThread() == workerThread) {
            return call(body);
        }
        String traceId = TraceContext.current().orElse(null);
        Future<T> future;
        synchronized (this) {
            future = executor.submit(TraceContext.wrap(traceId, () -> {
                activity.begin(what, conversation);
                try {
                    return body.call();
                } finally {
                    activity.end();
                }
            }));
            current = future;
        }
        try {
            return future.get();
        } catch (CancellationException e) {
            throw new Restarted("worker thread restarted");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new Restarted("waiting for the reply was interrupted");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            if (e.getCause() instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException(e.getCause());
        }
    }

    public boolean inFlight() {
        Future<?> f = current;
        return f != null && !f.isDone();
    }

    public boolean interruptCurrent() {
        Future<?> f = current;
        return f != null && !f.isDone() && f.cancel(true);
    }

    public boolean awaitIdle(Duration grace) {
        Future<?> f = current;
        if (f == null || f.isDone()) {
            return true;
        }
        try {
            f.get(grace.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | CancellationException e) {

        }
        return true;
    }

    @Override
    public synchronized Optional<String> restart(String reason) {
        Optional<String> conversation = activity.inFlight().flatMap(f -> f.conversation());
        Future<?> f = current;
        if (f != null) {
            f.cancel(true);
        }
        ExecutorService old = executor;
        executor = newExecutor();
        old.shutdownNow();
        activity.end();
        int n = generation.incrementAndGet();
        log.atWarn().addKeyValue("event", "worker.restart").addKeyValue("generation", n)
                .addKeyValue("reason", reason).log("Worker thread restarted ({}): {}", n, reason);
        return conversation;
    }

    public int generation() {
        return generation.get();
    }

    private ExecutorService newExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(() -> {
                workerThread = Thread.currentThread();
                r.run();
            }, "agent-worker-" + generation.get());
            t.setDaemon(true);
            return t;
        });
    }

    private static <T> T call(Callable<T> body) {
        try {
            return body.call();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
