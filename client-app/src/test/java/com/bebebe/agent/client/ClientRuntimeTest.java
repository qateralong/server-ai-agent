package com.bebebe.agent.client;

import com.bebebe.agent.capture.JavaSoundRecorder;
import com.bebebe.agent.config.AppConfig;
import com.bebebe.agent.transport.Envelope;
import com.bebebe.agent.transport.MessageType;
import com.bebebe.agent.transport.PairingTokens;
import com.bebebe.agent.transport.TransportClient;
import com.bebebe.agent.transport.TransportConfig;
import com.bebebe.agent.transport.TransportServer;
import com.bebebe.agent.transport.actions.ClipboardTool;
import com.bebebe.agent.transport.messages.ClipboardRequest;
import com.bebebe.agent.transport.messages.ClipboardResult;
import com.bebebe.agent.transport.messages.RunScriptRequest;
import com.bebebe.agent.transport.messages.RunScriptResult;
import com.bebebe.agent.transport.messages.StatusPush;
import com.bebebe.agent.transport.messages.VoiceAudioPush;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientRuntimeTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    @TempDir
    Path temp;

    private TransportServer server;
    private PairingTokens tokens;
    private ClientRuntime client;
    private final BlockingQueue<Envelope> inbox = new LinkedBlockingQueue<>();
    private final BlockingQueue<TransportServer.ClientInfo> connected = new LinkedBlockingQueue<>();

    @BeforeEach
    void setUp() {
        TransportConfig tc = TransportConfig.inDirectory(temp.resolve("srv"), 0, Duration.ofSeconds(1), Duration.ofSeconds(2));
        tokens = new PairingTokens(tc.clientsFile());
        server = new TransportServer(tc, tokens, new TransportServer.Listener() {
            @Override
            public void onClientConnected(TransportServer.ClientInfo c) {
                connected.add(c);
            }

            @Override
            public void onMessage(TransportServer.ClientInfo c, Envelope message) {
                inbox.add(message);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        server.close();
    }

    private ClientConfig config(PairingTokens.Pairing pairing) {
        return ClientConfig.from(AppConfig.fromToml("""
                [server]
                url = "wss://127.0.0.1:%d"
                fingerprint = "%s"
                client_id = "%s"
                token = "%s"
                [client]
                name = "тестовый ноут"
                tray = false
                [hotkey]
                helper = "%s"
                [scripts]
                venv_dir = "%s"
                scripts_dir = "%s"
                timeout_seconds = 5
                """.formatted(server.port(), server.fingerprint(), pairing.clientId(), pairing.token(),
                temp.resolve("нет-хелпера"), temp.resolve("venv"), temp.resolve("scripts"))));
    }

    private ClipboardTool clipboardWith(String text) {
        return new ClipboardTool() {
            @Override
            public String name() {
                return "тест";
            }

            @Override
            public boolean isReady() {
                return text != null;
            }

            @Override
            public Optional<String> read() {
                return Optional.ofNullable(text);
            }
        };
    }

    private TransportServer.ClientInfo start(ClipboardTool clipboard) throws Exception {
        client = ClientRuntime.start(config(tokens.issue("ноут")), clipboard, false, () -> { });
        assertTrue(client.transport().awaitConnected(WAIT), "client did not connect: " + client.transport().state());
        TransportServer.ClientInfo info = connected.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        assertNotNull(info);
        return info;
    }

    @Test
    void configParsesAndRequiresWss() {
        ClientConfig cfg = config(new PairingTokens.Pairing("abcd1234", "t0k"));
        assertEquals("abcd1234", cfg.clientId());
        assertEquals("тестовый ноут", cfg.name());
        assertEquals("KEY_HOME", cfg.hotkey().key());
        assertEquals(Duration.ofSeconds(120), cfg.maxRecording());
        assertFalse(cfg.toString().contains("t0k"), "the token is not printed");

        var thrown = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ClientConfig.from(AppConfig.fromToml("""
                        [server]
                        url = "ws://127.0.0.1:1"
                        fingerprint = "x"
                        client_id = "y"
                        token = "z"
                        """)));
        assertTrue(thrown.getMessage().contains("wss://"));
    }

    @Test
    void serverAsksForScriptClientRunsRealPython() throws Exception {
        TransportServer.ClientInfo info = start(clipboardWith(null));

        Envelope reply = server.request(info.clientId(), Envelope.of(MessageType.RUN_SCRIPT_REQUEST,
                new RunScriptRequest("import sys; print('привет с клиента'); print('diag', file=sys.stderr)", Map.of(), 5)),
                WAIT).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);

        assertEquals(MessageType.RUN_SCRIPT_RESULT, reply.type());
        RunScriptResult result = reply.payloadAs(RunScriptResult.class);
        assertTrue(result.isSuccess(), result.stderr());
        assertEquals("привет с клиента", result.stdout().strip());
        assertEquals("diag", result.stderr().strip(), "streams are separate on the client too");
        assertTrue(result.durationMs() >= 0);
    }

    @Test
    void failedAndHungScriptsReturnCodesNotExceptions() throws Exception {
        TransportServer.ClientInfo info = start(clipboardWith(null));

        RunScriptResult failed = server.request(info.clientId(), Envelope.of(MessageType.RUN_SCRIPT_REQUEST,
                new RunScriptRequest("raise RuntimeError('упс')", Map.of(), 5)), WAIT)
                .get(WAIT.toMillis(), TimeUnit.MILLISECONDS).payloadAs(RunScriptResult.class);
        assertEquals(1, failed.exitCode());
        assertTrue(failed.stderr().contains("RuntimeError"));

        RunScriptResult hung = server.request(info.clientId(), Envelope.of(MessageType.RUN_SCRIPT_REQUEST,
                new RunScriptRequest("import time; time.sleep(30)", Map.of(), 5)), Duration.ofSeconds(20))
                .get(20, TimeUnit.SECONDS).payloadAs(RunScriptResult.class);
        assertTrue(hung.timeout(), "the script timeout (5 s from [scripts]) triggers on the client");
    }

    @Test
    void clipboardIsReadAndTruncatedOnServerRequest() throws Exception {
        TransportServer.ClientInfo info = start(clipboardWith("скопированный текст на ноуте"));

        ClipboardResult full = server.request(info.clientId(), Envelope.of(MessageType.CLIPBOARD_REQUEST,
                ClipboardRequest.standard()), WAIT).get(WAIT.toMillis(), TimeUnit.MILLISECONDS)
                .payloadAs(ClipboardResult.class);
        assertTrue(full.available());
        assertEquals("скопированный текст на ноуте", full.text());

        ClipboardResult cut = server.request(info.clientId(), Envelope.of(MessageType.CLIPBOARD_REQUEST,
                new ClipboardRequest(5)), WAIT).get(WAIT.toMillis(), TimeUnit.MILLISECONDS)
                .payloadAs(ClipboardResult.class);
        assertEquals("скопи", cut.text());
        assertTrue(cut.truncated());

        assertTrue(info.status() == null || info.status().capabilities().contains("clipboard")
                || waitStatus(info.clientId()).capabilities().contains("clipboard"));
    }

    @Test
    void withoutWlPasteClipboardIsHonestlyUnavailableAndNotDeclared() throws Exception {
        TransportServer.ClientInfo info = start(clipboardWith(null));

        ClipboardResult result = server.request(info.clientId(), Envelope.of(MessageType.CLIPBOARD_REQUEST,
                ClipboardRequest.standard()), WAIT).get(WAIT.toMillis(), TimeUnit.MILLISECONDS)
                .payloadAs(ClipboardResult.class);
        assertFalse(result.available());
        assertTrue(result.error().contains("wl-paste"));

        StatusPush status = waitStatus(info.clientId());
        assertTrue(status.capabilities().contains("scripts"));
        assertFalse(status.capabilities().contains("clipboard"));
        assertFalse(status.capabilities().contains("voice"), "without the evdev helper voice is not declared");
        assertEquals("тестовый ноут", server.clients().getFirst().name());
    }

    @Test
    void recordingGoesToServerAsVoiceAudioPushWithSameTraceId() throws Exception {
        start(clipboardWith(null));

        byte[] wav = JavaSoundRecorder.toWav(new byte[32_000]);

        client.pushToTalk().send(wav, "trace-ptt-1");

        Envelope voice = nextOfType(MessageType.VOICE_AUDIO_PUSH);
        assertNotNull(voice);
        assertEquals(MessageType.VOICE_AUDIO_PUSH, voice.type());
        assertEquals("trace-ptt-1", voice.traceId());
        VoiceAudioPush push = voice.payloadAs(VoiceAudioPush.class);
        assertEquals(wav.length, push.audio().length);
        assertEquals("wav", push.format());
        assertEquals(1000, push.durationMs());

        client.pushToTalk().send(new byte[100], "trace-ptt-2");
        Thread.sleep(300);
        assertTrue(inbox.stream().noneMatch(e -> e.type() == MessageType.VOICE_AUDIO_PUSH), "a short recording is not sent");
    }

    private Envelope nextOfType(MessageType type) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            Envelope e = inbox.poll(200, TimeUnit.MILLISECONDS);
            if (e != null && e.type() == type) {
                return e;
            }
        }
        return null;
    }

    @Test
    void trayStateDescriptions() {
        assertEquals("connected", ClientTray.describe(TransportClient.State.CONNECTED));
        assertTrue(ClientTray.describe(TransportClient.State.REJECTED).contains("pairing"));
        assertEquals(16, ClientTray.dot(java.awt.Color.GRAY).getWidth());
    }

    private StatusPush waitStatus(String clientId) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            for (TransportServer.ClientInfo c : server.clients()) {
                if (c.clientId().equals(clientId) && c.status() != null) {
                    return c.status();
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("client status did not arrive");
    }
}
