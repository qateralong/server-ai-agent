package com.bebebe.agent.telegram;

/**
 * Where a voice message from the chat goes after it has been downloaded.
 *
 * <p>The bridge deals with the transport only: it knows how to pull the file out of Telegram
 * and nothing about speech recognition. Decoding and transcription live in {@code stt-bridge},
 * and the wiring between the two is done in {@code assembly} -- the same split as
 * {@link AgentTextHandler} for ordinary text.
 */
@FunctionalInterface
public interface VoiceInput {

    /**
     * @param audio     the file as Telegram gave it, normally Ogg/Opus
     * @param extension the extension of that file including the dot, for the decoder
     * @param traceId   the trace of the update this voice message arrived in
     * @return false if nothing will come of it -- the agent is off, recognition is not
     *         configured, the recording is too short or could not be decoded
     */
    boolean accept(byte[] audio, String extension, String traceId);
}
