package com.bebebe.agent.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class LeakGuardTest {

    @Test
    void rawDecisionJsonIsCaught() {
        assertNotNull(LeakGuard.suspect(
                "{\"type\":\"reply\",\"reply\":\"привет\",\"python_code\":\"\"}"));
    }

    @Test
    void decisionJsonWrappedInFencesIsCaughtToo() {
        assertNotNull(LeakGuard.suspect("""
                ```json
                {"type":"tool_call","tool_name":"web_search","arguments":{"query":"курс"}}
                ```"""));
    }

    @Test
    void javaStackTracesAreCaught() {
        assertNotNull(LeakGuard.suspect("""
                java.lang.IllegalStateException: boom
                \tat com.bebebe.agent.core.AgentCore.handle(AgentCore.java:120)
                \tat java.base/java.lang.Thread.run(Thread.java:1583)"""));
    }

    @Test
    void pythonTracebacksAreCaught() {
        assertNotNull(LeakGuard.suspect("""
                Traceback (most recent call last):
                  File "<stdin>", line 1, in <module>
                ZeroDivisionError: division by zero"""));
    }

    @Test
    void severalInternalNamesInProseAreCaught() {
        assertNotNull(LeakGuard.suspect(
                "Я работаю так: возвращаю run_script с полем python_code, либо tool_call."));
    }

    @Test
    void ordinaryAnswersPassThrough() {
        assertNull(LeakGuard.suspect("Привет! Сегодня среда, 23 сентября."));
        assertNull(LeakGuard.suspect("Свободно 12 ГБ в /tmp."));
        assertNull(LeakGuard.suspect("Не могу выполнять действия на компьютере: они отключены в настройках."));
    }

    @Test
    void anAnswerThatLegitimatelyContainsJsonIsNotALeak() {

        assertNull(LeakGuard.suspect("""
                Конфиг выглядит так:
                {"name":"Саша","age":30}"""),
                "JSON is not the problem; JSON of the decision protocol is");
    }

    @Test
    void oneInternalWordAloneIsNotEnough() {

        assertNull(LeakGuard.suspect("Скрипт не запущен, потому что run_script сейчас отключён."),
                "a single name can legitimately come up when the user asks about this project");
    }

    @Test
    void theNeutralReplySaysNothingAboutTheCause() {
        String neutral = LeakGuard.neutralReply();

        assertNull(LeakGuard.suspect(neutral), "the replacement must not itself trip the guard");
    }
}
