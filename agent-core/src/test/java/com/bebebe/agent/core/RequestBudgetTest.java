package com.bebebe.agent.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestBudgetTest {

    @Test
    void defaultIs15Calls() {
        assertEquals(15, new RequestBudget().limit());
        assertEquals(15, RequestBudget.DEFAULT_LIMIT);
    }

    @Test
    void countsSpentAndRemaining() {
        RequestBudget budget = new RequestBudget(3);

        budget.spend("decision");
        budget.spend("fix");

        assertEquals(2, budget.used());
        assertEquals(1, budget.remaining());
        assertFalse(budget.isExhausted());
    }

    @Test
    void throwsWhenExhausted() {
        RequestBudget budget = new RequestBudget(2);
        budget.spend("one");
        budget.spend("two");

        assertTrue(budget.isExhausted());
        RequestBudget.BudgetExhaustedException e =
                assertThrows(RequestBudget.BudgetExhaustedException.class, () -> budget.spend("three"));
        assertEquals(java.util.List.of("one", "two"), e.spentOn());
    }

    @Test
    void trySpendReturnsFalseInsteadOfThrowing() {
        RequestBudget budget = new RequestBudget(1);

        assertTrue(budget.trySpend("first"));
        assertFalse(budget.trySpend("second"));
        assertEquals(1, budget.used());
    }

    @Test
    void remembersWhatItWasSpentOn() {

        RequestBudget budget = new RequestBudget(5);
        budget.spend("request decision");
        budget.spend("script fix #1");

        assertEquals(java.util.List.of("request decision", "script fix #1"), budget.spentOn());
    }

    @Test
    void zeroBudgetIsMeaningless() {
        assertThrows(IllegalArgumentException.class, () -> new RequestBudget(0));
        assertThrows(IllegalArgumentException.class, () -> new RequestBudget(-1));
    }
}
