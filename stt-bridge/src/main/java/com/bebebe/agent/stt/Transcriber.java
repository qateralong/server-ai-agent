package com.bebebe.agent.stt;

import java.nio.file.Path;

public interface Transcriber {

    String transcribe(Path wav);
}
