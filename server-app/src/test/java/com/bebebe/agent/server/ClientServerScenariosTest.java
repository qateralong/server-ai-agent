package com.bebebe.agent.server;

import com.bebebe.agent.assembly.OllamaStubServer;
import com.bebebe.agent.assembly.TelegramStubServer;
import com.bebebe.agent.capture.JavaSoundRecorder;
import com.bebebe.agent.client.ClientConfig;
import com.bebebe.agent.client.ClientRuntime;
import com.bebebe.agent.config.AppConfig;
import com.bebebe.agent.config.AppSettings;
import com.bebebe.agent.transport.PairingTokens;
import com.bebebe.agent.transport.TransportClient;
import com.bebebe.agent.transport.TransportConfig;
import com.bebebe.agent.transport.actions.ClipboardTool;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientServerScenariosTest {

    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final long CHAT = 4242L;
    private static final String USER = "tester";
    private static final Pattern CALLBACK = Pattern.compile("\"callback_data\":\"(cfm:(?:run|no):[0-9a-f]+)\"");

    @TempDir
    Path temp;

    private OllamaStubServer ollama;
    private TelegramStubServer telegram;
    private ServerRuntime server;
    private ClientRuntime client;
    private AppSettings settings;
    private final AtomicInteger updateIds = new AtomicInteger(1);
    private volatile String clipboardText = "скопированный на ноуте текст";

    @BeforeEach
    void setUp() throws IOException {
        ollama = new OllamaStubServer();
        telegram = new TelegramStubServer();
        Path whisper = temp.resolve("whisper-cli");
        Files.writeString(whisper, "#!/bin/sh\necho ' сколько файлов в домашней папке'\n");
        Files.setPosixFilePermissions(whisper, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path model = temp.resolve("ggml.bin");
        Files.writeString(model, "fake");

        AppConfig config = AppConfig.fromToml("""
                [llm]
                provider = "ollama"
                [llm.ollama]
                model = "stub"
                endpoint = "%s"
                [llm.claude]
                api_key = "sk-ant-test"
                model = "claude-test"
                [agent]
                enabled_on_start = true
                live_replies = true
                [telegram]
                bot_token = "%s"
                allowed_usernames = ["%s"]
                poll_timeout_seconds = 1
                [transport]
                bind = "127.0.0.1"
                port = 0
                data_dir = "%s"
                heartbeat_seconds = 1
                [scripts]
                timeout_seconds = 5
                venv_dir = "%s"
                scripts_dir = "%s"
                [library]
                db_path = "%s"
                scripts_dir = "%s"
                [memory]
                db_path = "%s"
                [scheduler]
                db_path = "%s"
                [notes]
                dir = "%s"
                [stt]
                whisper_binary = "%s"
                model_path = "%s"
                [tts]
                enabled = false
                [updates]
                enabled = false
                [watchdog]
                enabled = false
                """.formatted(ollama.baseUrl(), TelegramStubServer.TOKEN, USER, temp.resolve("transport"),
                temp.resolve("srv-venv"), temp.resolve("srv-run"), temp.resolve("library.db"), temp.resolve("lib"),
                temp.resolve("memory.db"), temp.resolve("jobs.db"), temp.resolve("notes"), whisper, model));
        settings = AppSettings.from(config);
        server = ServerRuntime.start(config, settings, null, telegram.baseUrl());
        assertTrue(telegram.awaitCalls("getMe", 1, WAIT));
        connectClient();
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        server.close();
        telegram.close();
        ollama.close();
    }

    private void connectClient() {
        TransportConfig tc = TransportConfig.inDirectory(temp.resolve("transport"), 0, Duration.ofSeconds(1), Duration.ofSeconds(2));
        PairingTokens.Pairing pairing = new PairingTokens(tc.clientsFile()).issue("ноут");
        ClientConfig cfg = ClientConfig.from(AppConfig.fromToml("""
                [server]
                url = "wss://127.0.0.1:%d"
                fingerprint = "%s"
                client_id = "%s"
                token = "%s"
                [client]
                name = "ноут"
                tray = false
                [hotkey]
                helper = "%s"
                [scripts]
                venv_dir = "%s"
                scripts_dir = "%s"
                timeout_seconds = 5
                """.formatted(server.transport().port(), server.transport().fingerprint(), pairing.clientId(),
                pairing.token(), temp.resolve("нет"), temp.resolve("cli-venv"), temp.resolve("cli-run"))));
        ClipboardTool clipboard = new ClipboardTool() {
            @Override
            public String name() {
                return "тест";
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public Optional<String> read() {
                return Optional.ofNullable(clipboardText);
            }
        };
        client = ClientRuntime.start(cfg, clipboard, false, () -> { });
        try {
            assertTrue(client.transport().awaitConnected(WAIT), "client did not connect");
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (System.nanoTime() < deadline && (server.transport().clients().isEmpty()
                    || server.transport().clients().getFirst().status() == null)) {
                Thread.sleep(20);
            }
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    private void say(String text) {
        telegram.enqueue("""
                {"update_id":%d,"message":{"message_id":%d,"date":0,
                 "from":{"id":777,"is_bot":false,"first_name":"Тест","username":"%s"},
                 "chat":{"id":%d,"type":"private"},"text":"%s"}}"""
                .formatted(updateIds.getAndIncrement(), updateIds.get(), USER, CHAT, text));
    }

    private void press(String callbackData) {
        telegram.enqueue("""
                {"update_id":%d,"callback_query":{"id":"cb-%d",
                 "from":{"id":777,"is_bot":false,"first_name":"Тест","username":"%s"},
                 "message":{"message_id":900,"date":0,"chat":{"id":%d,"type":"private"}},
                 "data":"%s"}}"""
                .formatted(updateIds.getAndIncrement(), updateIds.get(), USER, CHAT, callbackData));
    }

    private JsonNode awaitText(String fragment) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {

            for (String method : List.of("sendMessage", "editMessageText")) {
                for (JsonNode call : telegram.calls(method)) {
                    if (call.path("text").asText("").contains(fragment)) {
                        return call;
                    }
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Telegram did not receive «" + fragment + "»; got: "
                + telegram.calls("sendMessage").stream().map(c -> c.path("text").asText()).toList());
    }

    private String confirmationToken() throws InterruptedException {
        Matcher m = CALLBACK.matcher(awaitText("Run the script").toString());
        while (m.find()) {
            if (m.group(1).startsWith("cfm:run:")) {
                return m.group(1);
            }
        }
        throw new AssertionError("no confirmation button");
    }

    private void enqueueScript(String code) {
        ollama.enqueue("""
                {"type":"run_script","reply":"","python_code":%s,"script_name":"тестовый",
                 "explanation":"тест","script_tags":["тест"]}""".formatted(quote(code)));
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private boolean scriptRanOnClient(String marker) throws IOException {
        if (!Files.isDirectory(temp.resolve("cli-run"))) {
            return false;
        }
        try (var files = Files.walk(temp.resolve("cli-run"))) {
            return files.filter(Files::isRegularFile).anyMatch(f -> {
                try {
                    return Files.readString(f).contains(marker);
                } catch (IOException e) {
                    return false;
                }
            });
        }
    }

    @Test
    void voiceFromClientViaWhisperOnServerScriptOnClientReplyInTelegramLivelyStyle() throws Exception {
        say("привет");
        awaitText("no more replies");
        enqueueScript("print('MARKER_VOICE 42 файла')");
        ollama.enqueuePlain("В домашней папке 42 файла.\n---\nПосчитал на твоём ноуте.");

        client.pushToTalk().send(JavaSoundRecorder.toWav(new byte[32_000]), "trace-voice-e2e");

        awaitText("🎤 сколько файлов");
        press(confirmationToken());

        awaitText("Посчитал на твоём ноуте");
        assertTrue(telegram.calls("sendMessage").stream().anyMatch(c -> c.path("text").asText().equals("В домашней папке 42 файла.")));
        assertTrue(telegram.awaitCalls("sendChatAction", 1, WAIT), "typing indicator");
        assertTrue(scriptRanOnClient("MARKER_VOICE"), "code saved and run on the client, not the server");
        assertFalse(Files.exists(temp.resolve("srv-run")) && Files.list(temp.resolve("srv-run")).findAny().isPresent(),
                "no scripts on the server");
        assertTrue(ollama.requests().getLast().toString().contains("MARKER_VOICE 42 файла"),
                "stdout from the client reached the formulation");
    }

    @Test
    void clipboardIsReadOnClientOnChatRequest() throws Exception {
        ollama.enqueue("{\"type\":\"tool_call\",\"tool_name\":\"read_clipboard\",\"arguments\":{},\"reply\":\"\",\"python_code\":\"\"}");
        ollama.enqueuePlain("Перевод: copied text on the laptop");

        say("переведи то, что я скопировал");

        awaitText("Перевод:");
        assertTrue(ollama.requests().getLast().toString().contains("скопированный на ноуте текст"),
                "the client clipboard text reached the model context");
    }

    @Test
    void clientDropDuringScriptDoesNotHangServerAndClientReturns() throws Exception {
        enqueueScript("import time; time.sleep(4); print('late')");
        say("сделай долгое");
        press(confirmationToken());
        Thread.sleep(700);

        Instant before = Instant.now();
        assertTrue(server.transport().disconnect(server.transport().clients().getFirst().clientId(), "drop test"));

        JsonNode reply = awaitText("Не удалось выполнить");
        assertTrue(reply.path("text").asText().contains("оборвалось"), reply.path("text").asText());
        assertTrue(Duration.between(before, Instant.now()).getSeconds() < 10, "immediate reply, not by timeout");
        assertEquals(1, ollama.callCount(), "decision only: the model was not asked to fix or formulate -- connectivity is not its concern");

        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline && !server.transport().clients().stream()
                .anyMatch(c -> c.connectedAt().isAfter(before))) {
            Thread.sleep(50);
        }

        assertTrue(client.transport().awaitConnected(WAIT), "the client did not return to CONNECTED");
        assertEquals(TransportClient.State.CONNECTED, client.transport().state());
        assertEquals(1, server.transport().clients().size());

        Thread.sleep(4500);
    }

    @Test
    void riskyScriptAfterConfirmationRunsOnClient() throws Exception {
        enqueueScript("print('MARKER_RISKY')");
        ollama.enqueuePlain("Сделано.");
        say("удали всё из корзины");

        String token = confirmationToken();
        assertFalse(scriptRanOnClient("MARKER_RISKY"), "nothing ran before ✅ was pressed");
        press(token);

        awaitText("Сделано.");
        assertTrue(scriptRanOnClient("MARKER_RISKY"));
        assertTrue(server.transport().clients().getFirst().name().equals("ноут"));
    }

    @Test
    void withScriptsDisabledServerSendsNoRunScriptRequest() throws Exception {
        settings.setScriptsEnabled(false);
        ollama.enqueueReply("Выполнение действий на компьютере сейчас отключено в настройках.");

        say("открой браузер");

        awaitText("отключено в настройках");
        JsonNode request = ollama.requests().getFirst();
        assertEquals(2, request.path("format").path("properties").path("type").path("enum").size(),
                "the model sees only reply and tool_call");
        assertFalse(request.path("messages").get(0).path("content").asText().contains("run_script"),
                "the server prompt does not mention scripts");
        assertEquals(1, ollama.callCount());
        assertFalse(Files.isDirectory(temp.resolve("cli-run")), "the client received no RUN_SCRIPT_REQUEST");

        enqueueScript("print('MARKER_DISABLED')");
        ollama.enqueuePlain("Действия на компьютере отключены, скрипт не запускаю.");
        say("всё равно открой");
        awaitText("скрипт не запускаю");
        assertFalse(scriptRanOnClient("MARKER_DISABLED"), "the client received no RUN_SCRIPT_REQUEST");
        assertFalse(telegram.calls("sendMessage").stream().anyMatch(c -> c.path("text").asText().contains("Не разобрался")));
    }

    @Test
    void runButtonAfterScriptsDisabledDoesNotReachClient() throws Exception {
        enqueueScript("print('MARKER_LATE_OFF')");
        say("сделай что-нибудь");
        String token = confirmationToken();

        settings.setScriptsEnabled(false);
        press(token);

        awaitText("отключено в настройках");
        assertFalse(scriptRanOnClient("MARKER_LATE_OFF"), "the client received no RUN_SCRIPT_REQUEST");
        assertEquals(1, ollama.callCount(), "no answer formulation");
    }

    @Test
    void providerSwitchDoesNotTouchClient() throws Exception {
        Instant connectedAt = server.transport().clients().getFirst().connectedAt();

        settings.setProvider(AppSettings.PROVIDER_CLAUDE);
        Thread.sleep(300);
        settings.setProvider(AppSettings.PROVIDER_OLLAMA);

        assertEquals(connectedAt, server.transport().clients().getFirst().connectedAt(), "the connection was not re-established");
        assertEquals(TransportClient.State.CONNECTED, client.transport().state());

        enqueueScript("print('MARKER_AFTER_SWITCH')");
        ollama.enqueuePlain("После переключения тоже работает.");
        say("проверь");
        press(confirmationToken());
        awaitText("После переключения тоже работает");
        assertTrue(scriptRanOnClient("MARKER_AFTER_SWITCH"));
    }
}
