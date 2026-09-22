package com.bebebe.agent.transport.messages;

public record VoiceAudioPush(String format, int sampleRate, int channels, String audioBase64, long durationMs) {

    public byte[] audio() {
        return audioBase64 == null ? new byte[0] : java.util.Base64.getDecoder().decode(audioBase64);
    }

    public static VoiceAudioPush wav(byte[] bytes, long durationMs) {
        return new VoiceAudioPush("wav", 16_000, 1, java.util.Base64.getEncoder().encodeToString(bytes), durationMs);
    }
}
