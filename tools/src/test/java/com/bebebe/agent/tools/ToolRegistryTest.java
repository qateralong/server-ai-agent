package com.bebebe.agent.tools;

import com.bebebe.agent.tools.time.GetCurrentTimeTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {

    private static final ToolContext CTX = new ToolContext((s, u, sch) -> "", "");

    @Test
    void registersAndFinds() {
        ToolRegistry registry = new ToolRegistry().register(new GetCurrentTimeTool());

        assertEquals(List.of("get_current_time"), registry.names());
        assertTrue(registry.find("get_current_time").isPresent());
        assertTrue(registry.find(" get_current_time ").isPresent(), "whitespace from the model must not matter");
        assertTrue(registry.find("nope").isEmpty());
    }

    @Test
    void descriptionForModelContainsNameAndParameters() {
        String described = new ToolRegistry().register(new GetCurrentTimeTool()).describeForModel();

        assertTrue(described.contains("get_current_time"));
        assertTrue(described.contains("timezone"));
    }

    @Test
    void unknownToolIsResultNotException() {
        ToolResult result = new ToolRegistry().execute("nope", Map.of(), CTX);

        assertFalse(result.success());
        assertTrue(result.content().contains("No such tool"));
    }

    @Test
    void failingToolIsResultNotException() {
        Tool broken = new Tool() {
            public String name() { return "broken"; }
            public String description() { return ""; }
            public Map<String, Object> parameters() { return Map.of(); }
            public ToolResult execute(Map<String, Object> a, ToolContext c) { throw new IllegalStateException("boom"); }
        };

        ToolResult result = new ToolRegistry().register(broken).execute("broken", Map.of(), CTX);

        assertFalse(result.success());
        assertTrue(result.content().contains("boom"));
    }

    @Test
    void exhaustedBudgetPropagates() {

        Tool hungry = new Tool() {
            public String name() { return "hungry"; }
            public String description() { return ""; }
            public Map<String, Object> parameters() { return Map.of(); }
            public ToolResult execute(Map<String, Object> a, ToolContext c) {
                throw new ToolContext.BudgetExhausted("done");
            }
        };

        assertThrows(ToolContext.BudgetExhausted.class,
                () -> new ToolRegistry().register(hungry).execute("hungry", Map.of(), CTX));
    }

    @Test
    void nameIsLowercaseLatinOnly() {
        Tool bad = new Tool() {
            public String name() { return "Web Search"; }
            public String description() { return ""; }
            public Map<String, Object> parameters() { return Map.of(); }
            public ToolResult execute(Map<String, Object> a, ToolContext c) { return ToolResult.ok(""); }
        };
        assertThrows(IllegalArgumentException.class, () -> new ToolRegistry().register(bad));
    }
}
