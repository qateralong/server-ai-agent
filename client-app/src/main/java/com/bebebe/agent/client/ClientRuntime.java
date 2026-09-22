package com.bebebe.agent.client;

import com.bebebe.agent.clipboard.ClipboardBridge;
import com.bebebe.agent.clipboard.LocalClipboardTool;
import com.bebebe.agent.script.runtime.LocalActionExecutor;
import com.bebebe.agent.script.runtime.ScriptRuntime;
import com.bebebe.agent.transport.TransportClient;
import com.bebebe.agent.transport.actions.ClipboardTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

public final class ClientRuntime implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ClientRuntime.class);

    private final TransportClient transport;
    private final PushToTalk pushToTalk;
    private final ClientTray tray;

    private ClientRuntime(TransportClient transport, PushToTalk pushToTalk, ClientTray tray) {
        this.transport = transport;
        this.pushToTalk = pushToTalk;
        this.tray = tray;
    }

    public static ClientRuntime start(ClientConfig config, ClipboardTool clipboard, boolean withTray, Runnable onExit) {
        LocalActionExecutor executor = new LocalActionExecutor(new ScriptRuntime(config.scripts()));
        ClipboardTool clip = clipboard != null ? clipboard : new LocalClipboardTool(new ClipboardBridge());
        ClientTray tray = withTray && config.tray() ? new ClientTray(config.serverUrl().toString(), onExit) : null;
        Consumer<TransportClient.State> onState = state -> {
            if (tray != null) {
                tray.update(state);
            }
        };

        PushToTalk ptt = new PushToTalk(config.hotkey(), config.audioDevice(), config.maxRecording());
        boolean voice = ptt.start();

        ClientHandler handler = new ClientHandler(executor, clip, voice, version(), onState);
        TransportClient transport = new TransportClient(config.transport(), handler);
        ptt.attach(transport);
        ClientRuntime runtime = new ClientRuntime(transport, ptt, tray);
        transport.start();
        log.info("Client started: {} -> {}; scripts {}, clipboard {}, voice {}", config.name(), config.serverUrl(),
                "yes", clip.isReady() ? "yes" : "no (wl-paste)", voice ? "yes" : "no");
        return runtime;
    }

    static String version() {
        String v = ClientRuntime.class.getPackage() == null ? null : ClientRuntime.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }

    public TransportClient transport() {
        return transport;
    }

    public PushToTalk pushToTalk() {
        return pushToTalk;
    }

    @Override
    public void close() {
        pushToTalk.close();
        transport.close();
        if (tray != null) {
            tray.close();
        }
    }
}
