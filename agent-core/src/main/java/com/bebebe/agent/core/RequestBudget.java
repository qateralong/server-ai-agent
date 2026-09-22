package com.bebebe.agent.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public final class RequestBudget {

    private static final Logger log = LoggerFactory.getLogger(RequestBudget.class);

    public static final int DEFAULT_LIMIT = 15;

    private final int limit;
    private final List<String> spentOn = new ArrayList<>();

    public RequestBudget() {
        this(DEFAULT_LIMIT);
    }

    public RequestBudget(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("Budget must be >= 1, not " + limit);
        }
        this.limit = limit;
    }

    public void spend(String reason) {
        if (isExhausted()) {
            throw new BudgetExhaustedException(this);
        }
        spentOn.add(reason);
        log.debug("Request budget: {}/{} -- {}", spentOn.size(), limit, reason);
    }

    public boolean trySpend(String reason) {
        if (isExhausted()) {
            return false;
        }
        spend(reason);
        return true;
    }

    public int used() {
        return spentOn.size();
    }

    public int limit() {
        return limit;
    }

    public int remaining() {
        return limit - spentOn.size();
    }

    public boolean isExhausted() {
        return spentOn.size() >= limit;
    }

    public List<String> spentOn() {
        return List.copyOf(spentOn);
    }

    @Override
    public String toString() {
        return "budget " + used() + "/" + limit;
    }

    public static final class BudgetExhaustedException extends RuntimeException {

        private final transient List<String> spentOn;

        BudgetExhaustedException(RequestBudget budget) {
            super("Model call budget exhausted: " + budget.limit());
            this.spentOn = budget.spentOn();
        }

        public List<String> spentOn() {
            return spentOn;
        }
    }
}
