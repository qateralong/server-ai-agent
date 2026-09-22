package com.bebebe.agent.capture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public record HotkeyConfig(Path helper, String key, String device) {

    public HotkeyConfig {
        key = key == null || key.isBlank() ? "KEY_HOME" : key.strip();
        device = device == null ? "" : device.strip();
    }

    public Optional<String> whatIsMissing() {
        if (helper == null || helper.toString().isEmpty() || !Files.isExecutable(helper)) {
            return Optional.of("evdev helper not built (" + (helper == null ? "" : helper.toAbsolutePath())
                    + "). Build it: cd native/evdev-hotkey && make");
        }
        return Optional.empty();
    }
}
