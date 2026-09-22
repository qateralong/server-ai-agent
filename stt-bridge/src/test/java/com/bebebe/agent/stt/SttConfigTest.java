package com.bebebe.agent.stt;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SttConfigTest {

    @Test
    void configUnderstandsTildeInPaths() {
        Path expanded = SttConfig.expand("~/whisper/model.bin");

        assertEquals(Path.of(System.getProperty("user.home"), "whisper/model.bin"), expanded);
        assertEquals(Duration.ofSeconds(120), SttConfig.DEFAULT_MAX_RECORDING);
    }
}
