package com.bebebe.agent.server;

import com.bebebe.agent.stt.RemoteVoiceIngest;
import com.bebebe.agent.transport.Envelope;
import com.bebebe.agent.transport.MessageType;
import com.bebebe.agent.transport.ProtocolException;
import com.bebebe.agent.transport.TransportServer;
import com.bebebe.agent.transport.messages.VoiceAudioPush;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class VoiceReceiver implements TransportServer.Listener {

    private static final Logger log = LoggerFactory.getLogger(VoiceReceiver.class);

    private final RemoteVoiceIngest ingest;

    VoiceReceiver(RemoteVoiceIngest ingest) {
        this.ingest = ingest;
    }

    @Override
    public void onMessage(TransportServer.ClientInfo client, Envelope message) {
        if (message.type() != MessageType.VOICE_AUDIO_PUSH) {
            return;
        }
        VoiceAudioPush push;
        try {
            push = message.payloadAs(VoiceAudioPush.class);
        } catch (ProtocolException e) {
            log.warn("Voice from «{}» cannot be parsed: {}", client.name(), e.getMessage());
            return;
        }
        if (!"wav".equalsIgnoreCase(push.format())) {
            log.warn("Voice from «{}» in format {} -- only wav is accepted", client.name(), push.format());
            return;
        }
        ingest.accept(push.audio(), message.traceId(), client.name());
    }
}
