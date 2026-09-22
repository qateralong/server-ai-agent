package com.bebebe.agent.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentDecisionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static AgentDecision parse(String json) {
        return AgentDecision.parse(json, MAPPER);
    }

    @Test
    void parsesPlainReply() {
        AgentDecision decision = parse("""
                {"type":"reply","reply":"Париж","python_code":""}""");

        assertEquals(DecisionType.REPLY, decision.type());
        assertEquals("Париж", decision.reply());
        assertTrue(decision.isUsable());
    }

    @Test
    void parsesReadyScriptChoice() {
        AgentDecision decision = parse("""
                {"type":"use_script","reply":"","python_code":"","script_id":7}""");

        assertEquals(DecisionType.USE_SCRIPT, decision.type());
        assertEquals(7, decision.scriptId());
        assertTrue(decision.isUsable());
    }

    @Test
    void idMayArriveAsString() {

        assertEquals(7, parse("""
                {"type":"use_script","script_id":"7"}""").scriptId());
        assertEquals(0, parse("""
                {"type":"use_script","script_id":"не число"}""").scriptId());
    }

    @Test
    void parsesFixOfPreviousScript() {
        AgentDecision decision = parse("""
                {"type":"fix_last_script","reply":"","python_code":"print(2)"}""");

        assertEquals(DecisionType.FIX_LAST_SCRIPT, decision.type());
        assertEquals("print(2)", decision.pythonCode());
        assertTrue(decision.isUsable());
    }

    @Test
    void parsesNameAndTagsOfNewScript() {
        AgentDecision decision = parse("""
                {"type":"run_script","python_code":"print(1)",
                 "script_name":"Свободное место","script_tags":["диск","место"]}""");

        assertEquals("Свободное место", decision.scriptName());
        assertEquals(List.of("диск", "место"), decision.scriptTags());
    }

    @Test
    void parsesScriptRun() {
        AgentDecision decision = parse("""
                {"type":"run_script","reply":"","python_code":"print(1)","explanation":"считает"}""");

        assertEquals(DecisionType.RUN_SCRIPT, decision.type());
        assertEquals("print(1)", decision.pythonCode());
        assertEquals("считает", decision.explanation());
        assertTrue(decision.isUsable());
    }

    @Test
    void unknownTypeDoesNotBreakParsing() {

        AgentDecision withReply = parse("""
                {"type":"нечто","reply":"x","python_code":""}""");
        assertEquals(DecisionType.REPLY, withReply.type());

        AgentDecision empty = parse("""
                {"type":"нечто","reply":"","python_code":""}""");
        assertEquals(DecisionType.UNKNOWN, empty.type());
        assertFalse(empty.isUsable());
    }

    @Test
    void survivesMissingFields() {

        AgentDecision decision = parse("""
                {"type":"reply"}""");

        assertEquals(DecisionType.REPLY, decision.type());
        assertFalse(decision.isUsable(), "a reply without text has nothing to give the user");
    }

    @Test
    void survivesNonJson() {
        assertEquals(DecisionType.UNKNOWN, parse("это не json").type());
        assertEquals(DecisionType.UNKNOWN, parse("").type());
        assertEquals(DecisionType.UNKNOWN, parse(null).type());
    }

    @Test
    void ignoresExtraFields() {
        AgentDecision decision = parse("""
                {"type":"reply","reply":"ок","python_code":"","script":"лишнее"}""");

        assertEquals(DecisionType.REPLY, decision.type());
        assertEquals("ок", decision.reply());
    }

    @Test
    void useScriptWithoutIdIsUnusable() {
        assertFalse(parse("{\"type\":\"use_script\"}").isUsable());
    }

    @Test
    void schemaContainsAllSupportedTypes() {
        List<String> expected = List.of("reply", "run_script", "use_script", "fix_last_script", "tool_call");
        assertEquals(expected, DecisionType.supportedWireNames());

        @SuppressWarnings("unchecked")
        Map<String, Object> typeField =
                (Map<String, Object>) ((Map<String, Object>) DecisionProtocol.responseSchema(null)
                        .get("properties")).get("type");
        assertEquals(expected, typeField.get("enum"));
    }

    @Test
    void threeDecisionFamilies() {
        assertEquals("reply", DecisionType.REPLY.family());
        assertEquals("script", DecisionType.RUN_SCRIPT.family());
        assertEquals("script", DecisionType.USE_SCRIPT.family());
        assertEquals("script", DecisionType.FIX_LAST_SCRIPT.family());
        assertEquals("tool", DecisionType.TOOL_CALL.family());
    }

    @Test
    void toolNamesGetIntoSchemaFromRegistry() {
        com.bebebe.agent.tools.ToolRegistry tools = new com.bebebe.agent.tools.ToolRegistry()
                .register(new com.bebebe.agent.tools.time.GetCurrentTimeTool());

        @SuppressWarnings("unchecked")
        Map<String, Object> toolName =
                (Map<String, Object>) ((Map<String, Object>) DecisionProtocol.responseSchema(tools)
                        .get("properties")).get("tool_name");

        assertEquals(List.of("get_current_time", ""), toolName.get("enum"));
        assertTrue(DecisionProtocol.decisionPrompt(tools).contains("get_current_time"));
    }

    @Test
    void promptAllowsSearchInConversationButNotOnEveryMessage() {
        for (boolean scripts : List.of(true, false)) {
            String prompt = DecisionProtocol.decisionPrompt(null, scripts);
            assertTrue(prompt.contains("in free conversation"), "discretionary search described (scripts=" + scripts + ")");
            assertTrue(prompt.contains("exchange rates"), "mandatory cases remain");
            assertTrue(prompt.contains("not on every message"), "frequency limit in words");
            assertTrue(prompt.contains("already come up in this"), "repeated search on the same topic forbidden");
            assertTrue(prompt.contains("persona instruction"), "persona may move the boundary");
        }
        String without = DecisionProtocol.withoutToolPrompt("что там?", "web_search");
        assertTrue(without.contains("Do not mention limits") && without.contains("что там?"));
    }

    @Test
    void parsesToolCall() {
        AgentDecision decision = parse("""
                {"type":"tool_call","reply":"","python_code":"","tool_name":"web_search",
                 "arguments":{"query":"курс доллара"}}""");

        assertEquals(DecisionType.TOOL_CALL, decision.type());
        assertEquals("web_search", decision.toolName());
        assertEquals("курс доллара", decision.arguments().get("query"));
        assertTrue(decision.isUsable());
    }

    @Test
    void argumentsAsJsonStringAreAccepted() {

        AgentDecision decision = parse("""
                {"type":"tool_call","tool_name":"web_search","arguments":"{\\"query\\":\\"погода\\"}"}""");

        assertEquals("погода", decision.arguments().get("query"));
    }

    @Test
    void nullInArgumentsDoesNotBreakParsing() {

        AgentDecision decision = parse("""
                {"type":"tool_call","tool_name":"get_current_time","arguments":{"timezone":null,"x":1}}""");

        assertEquals(DecisionType.TOOL_CALL, decision.type());
        assertTrue(decision.isUsable());
        assertEquals(Map.of("x", 1), decision.arguments());
    }

    @Test
    void withoutTypeItIsInferredFromContent() {

        assertEquals(DecisionType.REPLY, parse("{\"reply\":\"Лиссабон\"}").type());
        assertEquals(DecisionType.TOOL_CALL, parse("{\"tool_name\":\"web_search\",\"arguments\":{}}").type());
        assertEquals(DecisionType.RUN_SCRIPT, parse("{\"python_code\":\"print(1)\"}").type());
        assertEquals(DecisionType.USE_SCRIPT, parse("{\"script_id\":3}").type());
        assertEquals(DecisionType.UNKNOWN, parse("{}").type());
    }

    @Test
    void nestedFormIsFlattened() {

        AgentDecision decision = parse("""
                {"reply":"","run_script":{},"tool_call":{"tool_name":"get_current_time",
                 "arguments":{"timezone":""}}}""");

        assertEquals(DecisionType.TOOL_CALL, decision.type());
        assertEquals("get_current_time", decision.toolName());
        assertTrue(decision.isUsable());

        AgentDecision script = parse("""
                {"run_script":{"python_code":"print(1)","script_name":"Тест"}}""");
        assertEquals(DecisionType.RUN_SCRIPT, script.type());
        assertEquals("print(1)", script.pythonCode());
        assertEquals("Тест", script.scriptName());
    }

    @Test
    void callWithoutToolNameIsUnusable() {
        assertFalse(parse("{\"type\":\"tool_call\",\"tool_name\":\"\"}").isUsable());
    }

    @Test
    void schemaForbidsExtraFields() {

        assertEquals(Boolean.FALSE, DecisionProtocol.responseSchema(null).get("additionalProperties"));
    }

    @Test
    void jsonInMarkdownFenceIsParsed() {

        AgentDecision decision = parse("""
                ```json
                {"type": "reply", "reply": "Лиссабон"}
                ```""");
        assertEquals(DecisionType.REPLY, decision.type());
        assertEquals("Лиссабон", decision.reply());

        AgentDecision bare = parse("```\n{\"type\":\"reply\",\"reply\":\"ок\"}\n```");
        assertEquals("ок", bare.reply());
    }

    @Test
    void typeToleratesCaseAndSpaces() {
        assertEquals(DecisionType.TOOL_CALL,
                parse("{\"type\":\" Tool_Call \",\"tool_name\":\"web_search\"}").type());
        assertEquals(DecisionType.USE_SCRIPT,
                parse("{\"type\":\"USE_SCRIPT\",\"script_id\":3}").type());
    }

    @Test
    void scriptWithoutCodeIsUnusable() {
        assertFalse(parse("{\"type\":\"run_script\",\"script_name\":\"пусто\"}").isUsable());
        assertFalse(parse("{\"type\":\"fix_last_script\",\"python_code\":\"\"}").isUsable());
        assertTrue(parse("{\"type\":\"fix_last_script\",\"python_code\":\"print(2)\"}").isUsable());
    }

    @Test
    void commaSeparatedTagsStringIsAccepted() {
        AgentDecision decision = parse("""
                {"type":"run_script","python_code":"print(1)","script_name":"диск",
                 "script_tags":" диск, место ,, свободно "}""");
        assertEquals(List.of("диск", "место", "свободно"), decision.scriptTags());

        AgentDecision empty = parse("{\"type\":\"run_script\",\"python_code\":\"x\",\"script_tags\":null}");
        assertEquals(List.of(), empty.scriptTags());
    }

    @Test
    void garbageInsteadOfScriptIdMakesDecisionUnusableWithoutCrash() {
        AgentDecision decision = parse("{\"type\":\"use_script\",\"script_id\":\"семь\"}");
        assertEquals(DecisionType.USE_SCRIPT, decision.type());
        assertEquals(0, decision.scriptId());
        assertFalse(decision.isUsable());
    }

    @Test
    void explanationIsTrimmedAndKept() {
        AgentDecision decision = parse("""
                {"type":"run_script","python_code":"print(1)","script_name":"x",
                 "explanation":"  Посчитает файлы в /etc  "}""");
        assertEquals("Посчитает файлы в /etc", decision.explanation());
        assertEquals("", parse("{\"type\":\"reply\",\"reply\":\"а\"}").explanation());
    }

    @Test
    void explicitTypeWinsWhenBothFieldsPresent() {

        AgentDecision decision = parse("""
                {"type":"reply","reply":"Отвечаю сам","tool_name":"web_search"}""");
        assertEquals(DecisionType.REPLY, decision.type());
        assertTrue(decision.isUsable());
    }

    @Test
    void emptyStringAndWhitespaceAreUnknown() {
        assertEquals(DecisionType.UNKNOWN, parse("").type());
        assertEquals(DecisionType.UNKNOWN, parse("   \n").type());
        assertEquals(DecisionType.UNKNOWN, parse(null).type());
        assertEquals(DecisionType.UNKNOWN, parse("[1,2,3]").type(), "array instead of object");
    }

    @Test
    void schemaRequiresTypeAndEnumeratesItsValues() {
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) (Map<?, ?>) DecisionProtocol.responseSchema(null);
        assertTrue(((List<?>) schema.get("required")).contains("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        List<String> types = (List<String>) ((Map<String, Object>) props.get("type")).get("enum");
        assertTrue(types.containsAll(List.of("reply", "tool_call", "use_script", "run_script", "fix_last_script")));
        assertFalse(types.contains("unknown"), "unknown is our internal status, never offered to the model");
        assertFalse(props.containsKey("messages"), "without lively style there is no messages array in the schema");
    }

    @Test
    void withScriptsDisabledSchemaHasNeitherScriptTypesNorFields() {
        Map<String, Object> schema = DecisionProtocol.responseSchema(null, false, false);
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        List<String> types = (List<String>) ((Map<String, Object>) props.get("type")).get("enum");
        assertEquals(List.of("reply", "tool_call"), types);
        assertEquals(List.of("type", "reply", "tool_name", "arguments"), List.copyOf(props.keySet()));
        assertEquals(List.of("type", "reply"), schema.get("required"));
        assertTrue(DecisionType.RUN_SCRIPT.isScript() && DecisionType.USE_SCRIPT.isScript()
                && DecisionType.FIX_LAST_SCRIPT.isScript() && !DecisionType.REPLY.isScript());
    }

    @Test
    void withScriptsDisabledPromptDoesNotMentionThem() {
        String prompt = DecisionProtocol.decisionPrompt(null, false);
        for (String word : List.of("run_script", "use_script", "fix_last_script", "python_code", "script_id", "script")) {
            assertFalse(prompt.toLowerCase().contains(word), "no-scripts prompt contains '" + word + "'");
        }
        assertTrue(prompt.contains("DISABLED"), prompt);
        assertTrue(prompt.contains("\"reply\", \"tool_call\""), prompt);
        assertTrue(DecisionProtocol.decisionPrompt(null, true).contains("run_script"));
    }
}
