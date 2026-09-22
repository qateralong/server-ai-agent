package com.bebebe.agent.assembly;

import com.bebebe.agent.config.AppConfig;
import com.bebebe.agent.config.AppSettings;
import com.bebebe.agent.core.UserMessage;
import com.bebebe.agent.logging.LogBuffer;
import com.bebebe.agent.logging.LogEntry;
import com.bebebe.agent.memory.Persona;
import com.bebebe.agent.scheduler.Job;
import com.bebebe.agent.scheduler.JobStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndToEndScenariosTest {

    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final long CHAT = 4242L;
    private static final String USER = "tester";
    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    private static final Instant START = ZonedDateTime.of(2026, 9, 20, 15, 0, 0, 0, ZONE).toInstant();
    private static final Pattern CALLBACK = Pattern.compile("\"callback_data\":\"(cfm:(?:run|no):[0-9a-f]+)\"");

    private static Path venv;

    private static final class MutableClock extends Clock {
        volatile Instant now = START;
        public ZoneId getZone() { return ZONE; }
        public Clock withZone(ZoneId z) { return this; }
        public Instant instant() { return now; }
    }

    @TempDir
    Path temp;

    private OllamaStubServer ollama;
    private TelegramStubServer telegram;
    private HttpServer web;
    private AgentAssembly app;
    private AppSettings settings;
    private final MutableClock clock = new MutableClock();
    private final AtomicInteger updateIds = new AtomicInteger(1);
    private final List<String> spoken = new CopyOnWriteArrayList<>();
    private final AtomicInteger webSearches = new AtomicInteger();

    private long logStart;

    @BeforeAll
    static void venv() throws IOException {
        venv = Files.createTempDirectory("bebebe-e2e-venv");
    }

    @BeforeEach
    void setUp() throws IOException {
        logStart = LogBuffer.global().snapshot().stream().mapToLong(LogEntry::seq).max().orElse(0);
        ollama = new OllamaStubServer();
        telegram = new TelegramStubServer();
        web = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        web.createContext("/ddg/", ex -> {
            webSearches.incrementAndGet();
            String query = java.net.URLDecoder.decode(ex.getRequestURI().getQuery(), StandardCharsets.UTF_8);

            String html = query.contains("курс доллара цб") ? """
                    <a class="result__a" href="//duckduckgo.com/l/?uddg=%s">ЦБ РФ: курс доллара</a>
                    <a class="result__snippet">Официальный курс на сегодня</a>"""
                    .formatted(java.net.URLEncoder.encode(webUrl("/page"), StandardCharsets.UTF_8))
                    : """
                    <a class="result__a" href="//duckduckgo.com/l/?uddg=%s">Форум про попугаев</a>
                    <a class="result__snippet">Обсуждаем корм</a>"""
                    .formatted(java.net.URLEncoder.encode(webUrl("/junk"), StandardCharsets.UTF_8));
            respond(ex, html);
        });

        web.createContext("/page", ex -> respond(ex, "<html><body><h1>Курс ЦБ</h1><p>1 USD = 84,20 RUB на 20 сентября.</p>"
                + "<p>Банк России устанавливает официальные курсы иностранных валют по отношению к рублю ежедневно "
                + "на основании данных биржевых торгов; значения публикуются на сайте регулятора и действуют "
                + "со следующего календарного дня.</p></body></html>"));
        web.createContext("/junk", ex -> respond(ex, "<html><body>попугаи</body></html>"));
        web.start();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (var out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private String webUrl(String path) {
        return "http://127.0.0.1:" + web.getAddress().getPort() + path;
    }

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.close();
        }
        web.stop(0);
        telegram.close();
        ollama.close();
    }

    private void start(String extraToml) {
        AppConfig config = AppConfig.fromToml("""
                [agent]
                enabled_on_start = true
                request_budget = 15
                stop_grace_seconds = 10
                [ollama]
                base_url = "%s"
                api_key = ""
                model = "stub"
                timeout_seconds = 30
                [telegram]
                bot_token = "%s"
                allowed_usernames = ["%s"]
                poll_timeout_seconds = 1
                [scripts]
                venv_dir = "%s"
                scripts_dir = "%s"
                timeout_seconds = 20
                auto_install_deps = false
                [library]
                db_path = "%s"
                scripts_dir = "%s"
                [memory]
                db_path = "%s"
                consolidate_every = 1000
                [scheduler]
                db_path = "%s"
                tick_seconds = 30
                late_tolerance_minutes = 5
                desktop_notifications = false
                [notes]
                dir = "%s"
                git_history = false
                [tools.web_search]
                provider = "ddg"
                ddg_endpoint = "%s"
                max_results = 3
                fetch_pages = 1
                max_reformulations = 2
                [tts]
                voice_replies = false
                [updates]
                enabled = false
                [watchdog]
                enabled = false
                %s
                """.formatted(ollama.baseUrl(), TelegramStubServer.TOKEN, USER, venv, temp.resolve("scripts"),
                temp.resolve("library.db"), temp.resolve("lib"), temp.resolve("data/memory.db"),
                temp.resolve("data/scheduler.db"), temp.resolve("notes"), webUrl("/ddg/"), extraToml));
        settings = AppSettings.from(config);
        app = AgentAssembly.build(config, settings, new AgentAssembly.Options(
                ollama.baseUrl(), telegram.baseUrl(), clock,
                text -> {
                    spoken.add(text);
                    try {
                        Path ogg = Files.createTempFile(temp, "reply", ".ogg");
                        Files.writeString(ogg, "OggS");
                        return Optional.of(ogg);
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                }, false));
        assertTrue(telegram.awaitCalls("getMe", 1, WAIT), "the bridge must identify itself");
    }

    private void say(String text) {
        telegram.enqueue("""
                {"update_id":%d,"message":{"message_id":%d,"date":0,
                 "from":{"id":777,"is_bot":false,"first_name":"Тест","username":"%s"},
                 "chat":{"id":%d,"type":"private"},"text":"%s"}}"""
                .formatted(updateIds.getAndIncrement(), updateIds.get(), USER, CHAT, text.replace("\"", "\\\"")));
    }

    private void press(String callbackData) {
        telegram.enqueue("""
                {"update_id":%d,"callback_query":{"id":"cb-%d",
                 "from":{"id":777,"is_bot":false,"first_name":"Тест","username":"%s"},
                 "message":{"message_id":900,"date":0,"chat":{"id":%d,"type":"private"}},
                 "data":"%s"}}"""
                .formatted(updateIds.getAndIncrement(), updateIds.get(), USER, CHAT, callbackData));
    }

    private JsonNode awaitText(String fragment) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            for (String method : List.of("sendMessage", "editMessageText")) {
                for (JsonNode call : telegram.calls(method)) {
                    if (call.path("text").asText("").contains(fragment)) {
                        return call;
                    }
                }
            }
            sleep(50);
        }
        throw new AssertionError("No chat message containing «" + fragment + "». Got: " + sentTexts());
    }

    private List<String> sentTexts() {
        return telegram.calls("sendMessage").stream().map(c -> c.path("text").asText("")).toList();
    }

    private String confirmationToken(String action) {
        JsonNode request = awaitText("Run the script");
        Matcher m = CALLBACK.matcher(request.toString());
        while (m.find()) {
            if (m.group(1).startsWith("cfm:" + action + ":")) {
                return m.group(1);
            }
        }
        throw new AssertionError("no cfm:" + action + " button in " + request);
    }

    private void enqueueScript(String code, String name) {
        ollama.enqueue("""
                {"type":"run_script","reply":"","python_code":%s,"script_name":"%s",
                 "explanation":"%s","script_tags":["тест"]}"""
                .formatted(quote(code), name, name));
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String systemPromptOf(int request) {
        return ollama.requests().get(request).get("messages").get(0).get("content").asText();
    }

    private List<LogEntry> logs(String event) {
        return LogBuffer.global().snapshot(e -> e.seq() > logStart && event.equals(e.kv().get("event")), 200);
    }

    @Test
    void voiceOpenSpotifyScriptFailsGetsFixedAndReplyGoesAsTextAndVoice() throws Exception {
        start("");
        settings.setVoiceReplies(true);

        ollama.enqueueReply("Привет!");
        say("привет");
        awaitText("Привет!");

        enqueueScript("import subprocess\nsubprocess.run(['spotify-нет-такого'])", "Открыть Spotify");
        CompletableFuture<Void> voice = CompletableFuture.runAsync(
                () -> app.handleVoice(UserMessage.voice("открой Spotify")));

        awaitText("🎤 открой Spotify");
        String run = confirmationToken("run");
        enqueueScript("print('spotify started')", "Открыть Spotify");
        ollama.enqueuePlain("Spotify открыт.");
        press(run);

        voice.get(60, TimeUnit.SECONDS);
        awaitText("Spotify открыт.");
        assertTrue(telegram.awaitCalls("sendVoice", 1, WAIT), "toggle on -- reply also as voice");
        assertEquals(List.of("Spotify открыт."), spoken);

        assertEquals(1, app.library().latestVersions().size(), "one script in the catalog");
        assertEquals(2, app.library().latestVersions().getFirst().version(), "version 2 after the fix");
        List<LogEntry> audits = logs("script.audit");
        assertEquals(2, audits.size(), "two runs: the failed and the fixed one");
        assertEquals(1, audits.stream().map(LogEntry::traceId).distinct().count(), "the fix is the same request");
        assertFalse(audits.getFirst().traceId().isEmpty());
    }

    @Test
    void telegramTextWebSearchWithReformulationAndSingleTraceId() {
        start("");
        ollama.enqueue("""
                {"type":"tool_call","tool_name":"web_search","arguments":{"query":"курс доллара"}}""");
        ollama.enqueuePlain("попугаи корм");
        ollama.enqueue("{\"relevant\":false,\"new_query\":\"курс доллара цб\"}");
        ollama.enqueue("{\"relevant\":true,\"new_query\":\"\"}");
        ollama.enqueuePlain("Курс ЦБ: 84,20 ₽ за доллар.");

        say("какой сейчас курс доллара?");

        JsonNode reply = awaitText("84,20");
        assertEquals(String.valueOf(CHAT), reply.path("chat_id").asText());
        assertEquals(2, webSearches.get(), "search, 'not it' evaluation, reformulation, second search");
        assertEquals(5, ollama.callCount(), "decision + query + 2 evaluations + answer = 5 of budget 15");

        String trace = logs("request.start").getLast().traceId();
        assertFalse(trace.isEmpty());
        for (String event : List.of("tool.call", "web.search", "reply.ready", "reply.sent")) {
            List<LogEntry> entries = logs(event);
            assertFalse(entries.isEmpty(), event);
            assertEquals(trace, entries.getLast().traceId(), "trace_id diverged on " + event);
        }
        JsonNode last = ollama.requests().get(4).get("messages");
        assertTrue(last.get(last.size() - 1).get("content").asText().contains("84,20 RUB"),
                "the model formulates the answer from the fetched page, not the snippet");
    }

    @Test
    void addToTaskListAndItemVisibleInMenu() {
        start("");
        ollama.enqueue("""
                {"type":"tool_call","tool_name":"notes_tool","arguments":
                  {"action":"add_item","name":"задачи","text":"купить хлеб"}}""");
        ollama.enqueuePlain("Записал в список задач: купить хлеб.");

        say("запиши в список задач купить хлеб");
        awaitText("Записал");

        assertTrue(Files.exists(temp.resolve("notes/lists/задачи.md")));
        long id = app.notes().byTitle("задачи").orElseThrow().id();
        press("nt:open:" + id + ":0");

        JsonNode card = awaitText("Tap an item");
        assertTrue(card.toString().contains("☐ купить хлеб"), card.toString());
        assertEquals(2, ollama.callCount(), "no confirmation: decision + formulation");
    }

    @Test
    void reminderFiresAndIsCaughtUpAfterStop() throws Exception {
        start("");
        ollama.enqueue("""
                {"type":"tool_call","tool_name":"set_reminder","arguments":
                  {"fire_at":"2026-09-20T17:00:00+03:00","prompt":"Пора напомнить про врача","summary":"врач","repeat":""}}""");
        ollama.enqueuePlain("Напомню в 17:00.");
        say("напомни в 17:00 про врача");
        awaitText("Напомню в 17:00.");
        assertEquals(1, app.jobs().countPending());
        assertEquals("TELEGRAM:" + CHAT, app.jobs().pending().getFirst().conversationKey());

        clock.now = START.plus(Duration.ofHours(2)).plusSeconds(30);
        ollama.enqueueReply("Пора к врачу!");
        app.core().reminders().runner().tick();
        awaitText("Пора к врачу!");
        assertEquals(JobStatus.FIRED, app.jobs().history().getFirst().status());
        assertTrue(ollama.requests().getLast().toString().contains("System message from the scheduler"));

        ollama.enqueue("""
                {"type":"tool_call","tool_name":"set_reminder","arguments":
                  {"fire_at":"2026-09-20T18:00:00+03:00","prompt":"Пора напомнить позвонить маме","summary":"мама","repeat":""}}""");
        ollama.enqueuePlain("Напомню в 18:00.");
        say("напомни в 18:00 позвонить маме");
        awaitText("Напомню в 18:00.");

        app.agentSwitch().turnOff();
        clock.now = START.plus(Duration.ofHours(6));
        ollama.enqueueReply("Прости, опоздал на три часа: надо было позвонить маме.");
        app.agentSwitch().turnOn();

        awaitText("опоздал");
        Job missed = app.jobs().history().stream().filter(j -> j.summary().equals("мама")).findFirst().orElseThrow();
        assertEquals(JobStatus.MISSED, missed.status());
        String injection = ollama.requests().getLast().toString();
        assertTrue(injection.contains("should have fired"), injection);
    }

    @Test
    void newPersonBecomesEntityAndFactIsRecalledWithoutAsking() {
        start("");
        ollama.enqueueReply("Запомнил.");
        say("мой друг Саша не ест мясо");
        awaitText("Запомнил.");

        ollama.enqueue("""
                {"entities":[{"name":"Саша","relation":"друг","aliases":[],"match":{"entity_id":0,"confidence":"none"}}],
                 "facts":[{"text":"Саша не ест мясо","category":"preference","date":"","entities":["Саша"]}]}""");
        app.agentSwitch().turnOff();
        assertEquals(1, app.memory().entities().size());
        assertEquals("Саша", app.memory().entities().getFirst().canonicalName());
        app.agentSwitch().turnOn();

        ollama.enqueueReply("Саша не ест мясо — приготовь овощное рагу.");
        say("что приготовить Саше на ужин?");
        awaitText("овощное рагу");

        String system = systemPromptOf(ollama.requests().size() - 1);
        assertTrue(system.contains("не ест мясо"), "the fact is in the prompt -- the agent does not ask again:\n" + system);
        assertTrue(sentTexts().stream().noneMatch(t -> t.contains("Same person")), "no 'same person?' questions");
    }

    @Test
    void riskyScriptRefusedByButtonIsNotRun() {
        start("");
        enqueueScript("import shutil\nshutil.rmtree('/home')", "Удалить всё");
        say("удали все мои файлы");

        String cancel = confirmationToken("no");
        press(cancel);

        awaitText("Отменено");
        assertTrue(logs("script.audit").isEmpty(), "the script was not run");
        assertEquals(1, ollama.callCount(), "the model is not called after refusal");
        assertTrue(app.library().latestVersions().stream().allMatch(s -> s.successCount() + s.failureCount() == 0));
    }

    @Test
    void switchingPersonaChangesNextReply() {
        start("");
        ollama.enqueueReply("Здравствуйте.");
        say("привет");
        awaitText("Здравствуйте.");
        assertTrue(systemPromptOf(0).contains("Persona «Default»"));

        Persona pirate = app.personas().create("Пират", "Отвечай как пират, добавляй «йо-хо-хо».");
        press("per:act:" + pirate.id());
        awaitText("Active: <b>Пират</b>");

        ollama.enqueueReply("Йо-хо-хо, здорово!");
        say("ещё раз привет");
        awaitText("Йо-хо-хо");
        String system = systemPromptOf(ollama.requests().size() - 1);
        assertTrue(system.contains("Persona «Пират»"), system);
        assertTrue(system.contains("йо-хо-хо"), system);
        assertFalse(system.contains("Persona «Default»"));
    }

    @Test
    void stopDuringScriptWaitsForItAndConsolidatesMemory() throws Exception {
        start("");
        enqueueScript("import time\ntime.sleep(3)\nprint('готово')", "Долгое дело");
        say("сделай долгое дело");
        String run = confirmationToken("run");
        ollama.enqueuePlain("Долгое дело сделано.");
        press(run);

        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline && app.core().activity().inFlight()
                .flatMap(f -> f.busy()).filter(b -> b.reason().startsWith("script")).isEmpty()) {
            sleep(50);
        }
        ollama.enqueue("{\"entities\":[],\"facts\":[]}");
        long started = System.nanoTime();
        app.agentSwitch().turnOff();
        long waitedMs = (System.nanoTime() - started) / 1_000_000;

        assertTrue(waitedMs >= 1000, "stopping waited for the script: " + waitedMs + " мс");
        awaitText("Долгое дело сделано.");
        assertTrue(app.memory().activeSessions().isEmpty(), "session closed by consolidation");
        assertFalse(logs("stop.waiting").isEmpty(), "graceful-stop waited");
        assertTrue(logs("stop.interrupt").isEmpty(), "no interruption was needed");
        assertTrue(logs("script.audit").getLast().kv().get("exit_code").toString().equals("0"));
        assertFalse(app.agentSwitch().isOn());
    }
}
