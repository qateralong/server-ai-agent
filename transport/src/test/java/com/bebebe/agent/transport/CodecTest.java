package com.bebebe.agent.transport;

import com.bebebe.agent.transport.messages.AuthRequest;
import com.bebebe.agent.transport.messages.ClipboardResult;
import com.bebebe.agent.transport.messages.RunScriptRequest;
import com.bebebe.agent.transport.messages.RunScriptResult;
import com.bebebe.agent.transport.messages.StatusPush;
import com.bebebe.agent.transport.messages.VoiceAudioPush;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodecTest {

    @Test
    void envelopeSurvivesRoundTrip() {
        Envelope original = Envelope.of(MessageType.RUN_SCRIPT_REQUEST,
                new RunScriptRequest("print(1)", Map.of("path", "/tmp", "n", 3), 15));

        Envelope decoded = Codec.decode(Codec.encode(original));

        assertEquals(original.type(), decoded.type());
        assertEquals(original.id(), decoded.id());
        assertNull(decoded.replyTo());
        assertEquals(original.sentAt().toEpochMilli(), decoded.sentAt().toEpochMilli());
        RunScriptRequest request = decoded.payloadAs(RunScriptRequest.class);
        assertEquals("print(1)", request.code());
        assertEquals(3, request.arguments().get("n"));
        assertEquals(15, request.timeoutSeconds());
    }

    @Test
    void replyInheritsTraceIdAndReferencesRequest() {
        Envelope request = new Envelope(MessageType.CLIPBOARD_REQUEST, "req1", null, "trace-abc", null, null);

        Envelope reply = Codec.decode(Codec.encode(request.reply(MessageType.CLIPBOARD_RESULT,
                ClipboardResult.of("текст", false))));

        assertTrue(reply.isReply());
        assertEquals("req1", reply.replyTo());
        assertEquals("trace-abc", reply.traceId());
        assertEquals("текст", reply.payloadAs(ClipboardResult.class).text());
    }

    @Test
    void allMessageTypesEncodeAndDecode() {
        for (MessageType type : MessageType.values()) {
            Envelope decoded = Codec.decode(Codec.encode(Envelope.of(type)));
            assertEquals(type, decoded.type());
            assertTrue(decoded.payload().isObject(), type + " without payload -- empty object");
        }
        assertEquals(MessageType.RUN_SCRIPT_RESULT, MessageType.fromWire(" run_script_result ").orElseThrow());
        assertTrue(MessageType.fromWire("WHATEVER").isEmpty());
    }

    @Test
    void codecIsStrictAboutNonProtocolInput() {
        assertThrows(ProtocolException.class, () -> Codec.decode(""));
        assertThrows(ProtocolException.class, () -> Codec.decode("not json"));
        assertThrows(ProtocolException.class, () -> Codec.decode("[1,2]"));
        assertThrows(ProtocolException.class, () -> Codec.decode("{\"id\":\"1\",\"payload\":{}}"), "no type");
        assertThrows(ProtocolException.class, () -> Codec.decode("{\"type\":\"TELEPORT\",\"id\":\"1\"}"), "foreign type");
        assertThrows(ProtocolException.class, () -> Codec.decode("{\"type\":\"PING\"}"), "no id");
        assertThrows(ProtocolException.class, () -> Codec.decode("{\"type\":\"PING\",\"id\":\"1\",\"payload\":7}"), "payload not an object");
    }

    @Test
    void unknownEnvelopeFieldsAreIgnored() {
        Envelope decoded = Codec.decode("{\"type\":\"PONG\",\"id\":\"x\",\"payload\":{},\"future_field\":1}");
        assertEquals(MessageType.PONG, decoded.type());
    }

    @Test
    void voiceTravelsAsBase64AndIsRestoredByteForByte() {
        byte[] audio = {0, 1, 2, (byte) 0xFF, 42};
        Envelope decoded = Codec.decode(Codec.encode(Envelope.of(MessageType.VOICE_AUDIO_PUSH,
                VoiceAudioPush.wav(audio, 1200))));

        VoiceAudioPush push = decoded.payloadAs(VoiceAudioPush.class);
        assertArrayEquals(audio, push.audio());
        assertEquals(16_000, push.sampleRate());
        assertEquals("wav", push.format());
    }

    @Test
    void payloadsKeepAllFields() {
        StatusPush status = new StatusPush("host", "Linux 7", "me", "wayland-0", List.of("scripts", "clipboard"), "1.0", 42);
        assertEquals(status, Codec.decode(Codec.encode(Envelope.of(MessageType.STATUS_PUSH, status))).payloadAs(StatusPush.class));

        AuthRequest auth = new AuthRequest("c1", "ноут", "tok", 1);
        assertEquals(auth, Codec.decode(Codec.encode(Envelope.of(MessageType.AUTH, auth))).payloadAs(AuthRequest.class));

        RunScriptResult result = new RunScriptResult(1, "out", "Traceback", 340, false);
        RunScriptResult back = Codec.decode(Codec.encode(Envelope.of(MessageType.RUN_SCRIPT_RESULT, result))).payloadAs(RunScriptResult.class);
        assertEquals(result, back);
        assertFalse(back.isSuccess());
    }
}
