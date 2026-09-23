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

    private final List<String> attempts = new ArrayList<>();

    void record(String what, String failure) {
        if (attempts.size() >= REMEMBERED) {
            attempts.removeFirst();
        }
        attempts.add((what == null ? "?" : what.strip()) + " -- " + cut(failure));
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
            sb.append("  ").append(i + 1).append(") ").append(attempts.get(i)).append('\n');
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
