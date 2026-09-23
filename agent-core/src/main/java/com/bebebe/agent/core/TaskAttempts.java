package com.bebebe.agent.core;

import java.util.ArrayList;
import java.util.List;

/**
 * What has already been tried while working on <i>this one</i> request.
 *
 * <p>Long-term memory is about the user; this is about the task in hand, and it lasts exactly as
 * long as the request does. The fix loop used to show the model only the code that had just
 * failed and the error it produced, so the second failure looked exactly like the first one from
 * inside: nothing said that the approach now being proposed had already been tried two rounds
 * ago. With a budget of fifteen calls, going round the same circle twice is most of it.
 *
 * <p>It survives the pause for a confirmation button, riding along in {@link PendingExecution}
 * the same way the budget does -- the work is the same piece of work, whatever happened in
 * between.
 */
final class TaskAttempts {

    /** Enough to break a loop; more would push the actual error out of sight. */
    private static final int REMEMBERED = 4;

    private static final int FAILURE_CHARS = 200;

    private record Attempt(String what, String failure) {
    }

    private final List<Attempt> attempts = new ArrayList<>();

    private boolean succeeded;

    void record(String what, String failure) {
        if (attempts.size() >= REMEMBERED) {
            attempts.removeFirst();
        }
        attempts.add(new Attempt(what == null ? "?" : what.strip(), cut(failure)));
    }

    /** Something finally worked, so there is no dead end to warn anybody about. */
    void succeed() {
        succeeded = true;
    }

    boolean hasSucceeded() {
        return succeeded;
    }

    /**
     * What is worth remembering about a request that ran out of ways to succeed.
     *
     * <p>This memory dies with the request, and until now so did the knowledge that the whole
     * approach does not work here. Asked the same thing tomorrow, the agent set off down the same
     * dead end, paid for it again, and failed again in the same way.
     *
     * <p>Built without asking the model: the task and the error are already in hand, and a fact
     * nobody paid for is a fact that can be written every time it is earned.
     *
     * @return empty when there is nothing worth keeping -- nothing failed, or something worked
     */
    String outcomeFor(String task) {
        if (attempts.isEmpty() || succeeded || task == null || task.isBlank()) {
            return "";
        }
        Attempt last = attempts.getLast();
        return "Не удалось выполнить скриптом: «" + task.strip() + "». Последняя попытка ("
                + last.what() + ") — " + last.failure();
    }

    boolean isEmpty() {
        return attempts.isEmpty();
    }

    int size() {
        return attempts.size();
    }

    /** Empty when nothing has failed yet, so the prompt stays as it was on the first attempt. */
    String describeForModel() {
        if (attempts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\nAlready tried in this request, do not repeat any of it:\n");
        for (int i = 0; i < attempts.size(); i++) {
            Attempt attempt = attempts.get(i);
            sb.append("  ").append(i + 1).append(") ").append(attempt.what())
                    .append(" -- ").append(attempt.failure()).append('\n');
        }
        return sb.toString();
    }

    private static String cut(String failure) {
        if (failure == null || failure.isBlank()) {
            return "failed without a message";
        }
        String one = failure.strip().replace('\n', ' ');
        return one.length() <= FAILURE_CHARS ? one : one.substring(0, FAILURE_CHARS) + "…";
    }
}
