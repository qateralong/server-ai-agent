package com.bebebe.agent.stt;

import java.nio.file.Path;
import java.util.Optional;

public interface Recorder {

    boolean start();

    Optional<Path> stop();

    boolean isRecording();
}
