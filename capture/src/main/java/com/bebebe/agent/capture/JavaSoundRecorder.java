package com.bebebe.agent.capture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class JavaSoundRecorder {

    private static final Logger log = LoggerFactory.getLogger(JavaSoundRecorder.class);

    public static final AudioFormat FORMAT = new AudioFormat(16_000f, 16, 1, true, false);

    private final String mixerName;
    private final Duration maxDuration;
    private final Object lock = new Object();
    private TargetDataLine line;
    private ByteArrayOutputStream buffer;
    private Thread pump;
    private long startedNanos;

    public JavaSoundRecorder(String mixerName, Duration maxDuration) {
        this.mixerName = mixerName == null ? "" : mixerName.strip();
        this.maxDuration = maxDuration;
    }

    public Optional<String> whatIsMissing() {
        try {
            openLine().close();
            return Optional.empty();
        } catch (LineUnavailableException | IllegalArgumentException e) {
            return Optional.of("no 16 kHz/mono/16-bit recording device" + (mixerName.isEmpty() ? "" : " «" + mixerName + "»")
                    + ": " + e.getMessage() + ". Available: " + inputDevices());
        }
    }

    public boolean isRecording() {
        synchronized (lock) {
            return line != null;
        }
    }

    public void start() throws LineUnavailableException {
        synchronized (lock) {
            if (line != null) {
                return;
            }
            TargetDataLine opened = openLine();
            opened.start();
            line = opened;
            buffer = new ByteArrayOutputStream(1 << 20);
            startedNanos = System.nanoTime();
            long maxBytes = (long) (FORMAT.getFrameRate() * FORMAT.getFrameSize() * maxDuration.toSeconds());
            ByteArrayOutputStream target = buffer;
            pump = new Thread(() -> pump(opened, target, maxBytes), "audio-capture");
            pump.setDaemon(true);
            pump.start();
        }
        log.info("Recording started ({} Hz, mono, 16 bit)", (int) FORMAT.getSampleRate());
    }

    public Optional<byte[]> stop() {
        TargetDataLine current;
        Thread thread;
        ByteArrayOutputStream captured;
        long started;
        synchronized (lock) {
            if (line == null) {
                return Optional.empty();
            }
            current = line;
            thread = pump;
            captured = buffer;
            started = startedNanos;
            line = null;
            pump = null;
            buffer = null;
        }
        current.stop();
        current.close();
        try {
            thread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        byte[] pcm = captured.toByteArray();
        log.info("Recording stopped: {} bytes, {} ms", pcm.length, (System.nanoTime() - started) / 1_000_000);
        return Optional.of(toWav(pcm));
    }

    public static byte[] toWav(byte[] pcm) {
        try (AudioInputStream stream = new AudioInputStream(new ByteArrayInputStream(pcm), FORMAT,
                pcm.length / FORMAT.getFrameSize());
             ByteArrayOutputStream out = new ByteArrayOutputStream(pcm.length + 64)) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot build WAV in memory", e);
        }
    }

    public static long durationMs(byte[] wav) {
        long dataBytes = Math.max(0, wav.length - 44);
        return (long) (dataBytes * 1000.0 / (FORMAT.getFrameRate() * FORMAT.getFrameSize()));
    }

    public static List<String> inputDevices() {
        List<String> names = new ArrayList<>();
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
        for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
            try {
                if (AudioSystem.getMixer(mixerInfo).isLineSupported(info)) {
                    names.add(mixerInfo.getName());
                }
            } catch (RuntimeException ignored) {

            }
        }
        return names;
    }

    private TargetDataLine openLine() throws LineUnavailableException {
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
        TargetDataLine opened;
        if (mixerName.isEmpty()) {
            opened = (TargetDataLine) AudioSystem.getLine(info);
        } else {
            Mixer.Info match = null;
            for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
                if (mixerInfo.getName().toLowerCase().contains(mixerName.toLowerCase())) {
                    match = mixerInfo;
                    break;
                }
            }
            if (match == null) {
                throw new LineUnavailableException("device «" + mixerName + "» not found");
            }
            opened = (TargetDataLine) AudioSystem.getMixer(match).getLine(info);
        }
        opened.open(FORMAT);
        return opened;
    }

    private static void pump(TargetDataLine source, ByteArrayOutputStream target, long maxBytes) {
        byte[] chunk = new byte[4096];
        long total = 0;
        while (source.isOpen()) {
            int n = source.read(chunk, 0, chunk.length);
            if (n <= 0) {
                if (!source.isOpen()) {
                    break;
                }
                continue;
            }
            if (total < maxBytes) {
                int allowed = (int) Math.min(n, maxBytes - total);
                target.write(chunk, 0, allowed);
                total += allowed;
                if (total >= maxBytes) {
                    log.warn("Recording ceiling reached -- further audio is discarded");
                }
            }
        }
    }
}
