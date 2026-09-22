package com.bebebe.agent.stt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Anything that is not a 16 kHz mono WAV, converted into one with ffmpeg.
 *
 * <p>Telegram voice messages are Ogg/Opus, and whisper.cpp takes nothing but WAV -- the same
 * ffmpeg that {@code tts-bridge} uses in the other direction does the job here.
 */
public final class AudioConverter {

    private static final Logger log = LoggerFactory.getLogger(AudioConverter.class);

    private static final long TIMEOUT_SECONDS = 60;

    private final String ffmpegBinary;

    public AudioConverter(String ffmpegBinary) {
        this.ffmpegBinary = ffmpegBinary;
    }

    /** The command is a separate method so a test can check the flags without running ffmpeg. */
    List<String> command(Path input, Path output) {
        return List.of(ffmpegBinary, "-y", "-loglevel", "error",
                "-i", input.toString(),
                "-ar", Integer.toString(SttConfig.SAMPLE_RATE),
                "-ac", "1",
                "-c:a", "pcm_s16le",
                output.toString());
    }

    public boolean isAvailable() {
        try {
            Process p = new ProcessBuilder(ffmpegBinary, "-version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * @return the WAV bytes, or an empty array if the conversion failed -- the caller decides
     *         what to tell the user; a broken voice message must not take the process down.
     */
    public byte[] toWav(byte[] encoded, String extension) {
        Path input = null;
        Path output = null;
        try {
            input = Files.createTempFile("bebebe-voice-in-", extension);
            output = Files.createTempFile("bebebe-voice-out-", ".wav");
            Files.write(input, encoded);
            Process process = new ProcessBuilder(command(input, output))
                    .redirectErrorStream(true)
                    .start();

            byte[] diagnostics = process.getInputStream().readAllBytes();
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("ffmpeg did not finish in {} s -- voice message discarded", TIMEOUT_SECONDS);
                return new byte[0];
            }
            if (process.exitValue() != 0) {
                log.warn("ffmpeg exited with {}: {}", process.exitValue(),
                        new String(diagnostics).strip());
                return new byte[0];
            }
            return Files.readAllBytes(output);
        } catch (IOException e) {
            log.warn("Cannot convert voice message: {}", e.getMessage());
            return new byte[0];
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new byte[0];
        } finally {
            discard(input);
            discard(output);
        }
    }

    private static void discard(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.debug("Temporary file {} not deleted: {}", file, e.getMessage());
        }
    }
}
