package com.bebebe.agent.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonLayoutTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JsonLayout layout = new JsonLayout();

    private final ListAppender<ILoggingEvent> events = new ListAppender<>() {
        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    };
    private Logger logger;

    @BeforeEach
    void setUp() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logger = context.getLogger("com.bebebe.agent.core.TestLogger");
        logger.setLevel(Level.DEBUG);
        events.start();
        logger.addAppender(events);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(events);
    }

    private JsonNode last() throws Exception {
        String line = layout.doLayout(events.list.getLast());
        assertTrue(line.endsWith(System.lineSeparator()), "each record is a separate line");
        return MAPPER.readTree(line);
    }

    @Test
    void oneRecordOneJsonWithAllFields() throws Exception {
        logger.info("Привет, {}", "мир");

        JsonNode node = last();

        assertEquals("INFO", node.get("level").asText());
        assertEquals("agent-core", node.get("subsystem").asText());
        assertEquals("c.b.agent.core.TestLogger", node.get("logger").asText());
        assertEquals("Привет, мир", node.get("msg").asText());
        assertTrue(node.has("ts"));
        assertTrue(node.has("thread"));
        assertFalse(node.has("trace_id"), "without a trace scope the field must be absent");
    }

    @Test
    void traceIdComesFromContext() throws Exception {
        try (TraceContext.Scope ignored = TraceContext.open("abc123def456")) {
            logger.info("под запросом");
        }

        assertEquals("abc123def456", last().get("trace_id").asText());
    }

    @Test
    void structuredFieldsAreNotSmearedIntoText() throws Exception {
        logger.atInfo()
                .addKeyValue("exit_code", 1)
                .addKeyValue("success", false)
                .addKeyValue("file", "a.py")
                .log("Скрипт упал");

        JsonNode node = last();

        assertEquals("Скрипт упал", node.get("msg").asText());
        JsonNode kv = node.get("kv");
        assertEquals(1, kv.get("exit_code").asInt(), "a number must stay a number");
        assertFalse(kv.get("success").asBoolean());
        assertEquals("a.py", kv.get("file").asText());
    }

    @Test
    void exceptionIsSplitIntoFields() throws Exception {
        logger.error("Сломалось", new IllegalStateException("причина",
                new java.io.IOException("корень")));

        JsonNode error = last().get("error");

        assertEquals("java.lang.IllegalStateException", error.get("type").asText());
        assertEquals("причина", error.get("message").asText());
        assertTrue(error.get("stack").asText().contains("JsonLayoutTest"));
        assertEquals("корень", error.get("cause").get("message").asText());
    }

    @Test
    void subsystemIsDeterminedByLoggerPrefix() {
        assertEquals("agent-core", JsonLayout.subsystemOf("com.bebebe.agent.core.AgentCore"));
        assertEquals("script-runtime", JsonLayout.subsystemOf("com.bebebe.agent.script.runtime.ScriptRuntime"));
        assertEquals("script-runtime", JsonLayout.subsystemOf("com.bebebe.agent.script.library.ScriptLibrary"));
        assertEquals("telegram-bridge", JsonLayout.subsystemOf("com.bebebe.agent.telegram.TelegramBridge"));
        assertEquals("scheduler", JsonLayout.subsystemOf("com.bebebe.agent.scheduler.AgentScheduler"));
        assertEquals("watchdog", JsonLayout.subsystemOf("com.bebebe.agent.watchdog.Watchdog"));
        assertEquals("external", JsonLayout.subsystemOf("org.xerial.sqlite.Loader"));
        assertEquals("external", JsonLayout.subsystemOf(null));
    }

    @Test
    void cyrillicAndQuotesDoNotBreakJson() throws Exception {
        String tricky = "Текст с " + '"' + "кавычками" + '"' + ", \\ слэшем и переводом\nстроки";
        logger.info(tricky);

        JsonNode node = last();

        assertEquals(tricky, node.get("msg").asText());
    }
}
