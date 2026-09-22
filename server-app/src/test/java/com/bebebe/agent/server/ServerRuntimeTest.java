package com.bebebe.agent.server;

import com.bebebe.agent.config.AppConfig;
import com.bebebe.agent.config.AppSettings;
import com.bebebe.agent.core.AgentReply;
import com.bebebe.agent.core.UserMessage;
import com.bebebe.agent.transport.Envelope;
import com.bebebe.agent.transport.MessageType;
import com.bebebe.agent.transport.PairingTokens;
import com.bebebe.agent.transport.TransportClient;
import com.bebebe.agent.transport.TransportConfig;
import com.bebebe.agent.transport.actions.ActionResult;
import com.bebebe.agent.transport.messages.RunScriptRequest;
import com.bebebe.agent.transport.messages.RunScriptResult;
import com.bebebe.agent.transport.messages.StatusPush;
import com.bebebe.agent.transport.messages.VoiceAudioPush;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerRuntimeTest {

    private static final Duration WAIT = Duration.ofSeconds(8);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path temp;

    private HttpServer ollama;
    private final LinkedBlockingQueue<String> ollamaReplies = new LinkedBlockingQueue<>();
    private final List<JsonNode> ollamaRequests = new CopyOnWriteArrayList<>();
    private ServerRuntime runtime;
    private TransportClient client;

    @BeforeEach
    void setUp() throws IOException {
        ollama = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ollama.createContext("/api/chat", exchange -> {
            ollamaRequests.add(MAPPER.readTree(exchange.getRequestBody().readAllBytes()));
            String content = ollamaReplies.poll();
            if (content == null) {
                content = "{\"type\":\"reply\",\"reply\":\"ок\",\"python_code\":\"\"}";
            }
            byte[] bytes = ("{\"model\":\"stub\",\"done\":true,\"message\":{\"role\":\"assistant\",\"content\":"
                    + MAPPER.writeValueAsString(content) + "}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        ollama.start();

        Path whisper = temp.resolve("whisper-cli");
        Files.writeString(whisper, "#!/bin/sh\necho ' открой браузер'\n");
        Files.setPosixFilePermissions(whisper, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path model = temp.resolve("ggml.bin");
        Files.writeString(model, "fake");

        AppConfig config = AppConfig.fromToml("""
                [llm]
                provider = "ollama"
                [llm.ollama]
                model = "stub"
                endpoint = "http://127.0.0.1:%d"
                [agent]
                enabled_on_start = true
                [telegram]
                enabled = false
                [transport]
                enabled = true
                bind = "127.0.0.1"
                port = 0
                data_dir = "%s"
                heartbeat_seconds = 1
                [scripts]
                timeout_seconds = 2
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
                """.formatted(ollama.getAddress().getPort(), temp.resolve("transport"),
                temp.resolve("venv"), temp.resolve("run"), temp.resolve("library.db"), temp.resolve("lib"),
                temp.resolve("memory.db"), temp.resolve("jobs.db"), temp.resolve("notes"), whisper, model));
        runtime = ServerRuntime.start(config, AppSettings.from(config), null, null);
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        runtime.close();
        ollama.stop(0);
    }

    private void connectClient() throws Exception {
        TransportConfig tc = TransportConfig.inDirectory(temp.resolve("transport"), 0, Duration.ofSeconds(1), Duration.ofSeconds(2));
        PairingTokens.Pairing pairing = new PairingTokens(tc.clientsFile()).issue("ноут");
        client = new TransportClient(TransportClient.Config.of(
                URI.create("wss://127.0.0.1:" + runtime.transport().port()), runtime.transport().fingerprint(),
                pairing.clientId(), "ноут", pairing.token()), new TransportClient.Handler() {
            @Override
            public Optional<Envelope> onRequest(Envelope request) {
                if (request.type() == MessageType.RUN_SCRIPT_REQUEST) {
                    String code = request.payloadAs(RunScriptRequest.class).code();
                    return Optional.of(request.reply(MessageType.RUN_SCRIPT_RESULT,
                            new RunScriptResult(0, "выполнено на ноуте: " + code, "", 5, false)));
                }
                return Optional.empty();
            }

            @Override
            public StatusPush status() {
                return new StatusPush("ноут", "Arch", "я", "wayland-0", List.of("scripts", "clipboard", "voice"), "t", 1);
            }
        });
        client.start();
        assertTrue(client.awaitConnected(WAIT));
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline && (runtime.transport().clients().isEmpty()
                || runtime.transport().clients().getFirst().status() == null)) {
            Thread.sleep(20);
        }
    }

    @Test
    void withoutClientScriptDoesNotRunAndErrorIsClearAndImmediate() {
        long started = System.nanoTime();
        ActionResult result = runtime.app().core().executor().run("print(1)");

        assertFalse(result.isSuccess());
        assertTrue(result.stderr().contains("нет связи с компьютером"), result.stderr());
        assertTrue((System.nanoTime() - started) / 1_000_000 < 500);
        assertTrue(runtime.status().clients().isEmpty());
        assertTrue(runtime.app().core().executor().name().contains("not connected"));
    }

    @Test
    void scriptFromCoreGoesToClientAndReplyIsFormulatedByModel() throws Exception {
        connectClient();

        ollamaReplies.add("{\"type\":\"run_script\",\"reply\":\"\",\"python_code\":\"print('hi')\","
                + "\"script_name\":\"привет\",\"explanation\":\"печатает\"}");
        ollamaReplies.add("Готово: на ноуте напечатано hi");

        AgentReply first = runtime.app().core().handle(UserMessage.telegram("напечатай hi", 1L));
        assertTrue(first instanceof AgentReply.NeedsConfirmation, "a new script requires confirmation: " + first);
        AgentReply reply = runtime.app().core().confirm(((AgentReply.NeedsConfirmation) first).token());

        assertEquals("Готово: на ноуте напечатано hi", reply.asPlainText());
        JsonNode summarize = ollamaRequests.getLast();
        assertTrue(summarize.toString().contains("выполнено на ноуте: print('hi')"),
                "the model formulates the reply from the client stdout: " + summarize);
        assertEquals("computer «ноут»", runtime.app().core().executor().name());
        assertEquals(1, runtime.status().clients().size());
        assertTrue(runtime.status().clients().getFirst().contains("ноут"));
    }

    @Test
    void voiceFromClientIsTranscribedOnServerAndGoesToCore() throws Exception {
        connectClient();
        assertTrue(runtime.voice().orElseThrow().isReady(), "whisper configured -- without the evdev helper");
        ollamaReplies.add("{\"type\":\"reply\",\"reply\":\"открываю\",\"python_code\":\"\"}");

        client.send(Envelope.of(MessageType.VOICE_AUDIO_PUSH, VoiceAudioPush.wav(new byte[20_000], 1200)));

        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline && ollamaRequests.isEmpty()) {
            TimeUnit.MILLISECONDS.sleep(50);
        }
        assertFalse(ollamaRequests.isEmpty(), "the recognised text must reach the model");
        assertTrue(ollamaRequests.getFirst().toString().contains("открой браузер"), ollamaRequests.getFirst().toString());
    }

    @Test
    void statusIsAssembledWithoutWindow() {
        var status = runtime.status();

        assertTrue(status.agentOn());
        assertTrue(status.providerLine().startsWith("Ollama"));
        assertEquals("no model calls yet", status.modelState());
        assertTrue(status.configPath().length() > 0);
        assertFalse(status.diskLines().isEmpty());
    }
}
