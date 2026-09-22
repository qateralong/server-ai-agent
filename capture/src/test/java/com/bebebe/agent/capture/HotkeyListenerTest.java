package com.bebebe.agent.capture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HotkeyListenerTest {

    @TempDir
    Path tempDir;

    private final List<String> events = new CopyOnWriteArrayList<>();

    private Path fakeHelper(String body) throws IOException {
        Path script = tempDir.resolve("evdev-hotkey");
        Files.writeString(script, "#!/bin/sh\n" + body + "\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return script;
    }

    private HotkeyConfig configWith(Path helper, String device) {
        return new HotkeyConfig(helper, "KEY_HOME", device);
    }

    private HotkeyListener listener(Path helper) {
        return new HotkeyListener(configWith(helper, ""),
                () -> events.add("press"),
                () -> events.add("release"));
    }

    private boolean await(int count) throws InterruptedException {
        for (int i = 0; i < 100 && events.size() < count; i++) {
            TimeUnit.MILLISECONDS.sleep(50);
        }
        return events.size() >= count;
    }

    @Test
    void commandContainsKeyAndDevice() throws IOException {
        HotkeyListener withDevice = new HotkeyListener(
                configWith(fakeHelper("true"), "/dev/input/event6"), () -> { }, () -> { });

        List<String> command = withDevice.command();

        assertEquals("KEY_HOME", command.get(command.indexOf("--key") + 1));
        assertTrue(command.contains("/dev/input/event6"));
    }

    @Test
    void withoutDeviceCommandHasNone() throws IOException {

        List<String> command = listener(fakeHelper("true")).command();

        assertEquals(3, command.size(), command.toString());
        assertTrue(command.stream().noneMatch(arg -> arg.startsWith("/dev/input")));
    }

    @Test
    void parsesDownAndUp() throws Exception {
        Path helper = fakeHelper("printf 'DOWN\\nUP\\nDOWN\\nUP\\n'\nsleep 5");

        try (HotkeyListener listener = listener(helper)) {
            listener.start();
            assertTrue(await(4), "events not received: " + events);
        }

        assertEquals(List.of("press", "release", "press", "release"), events);
    }

    @Test
    void ignoresGarbageInStream() throws Exception {
        Path helper = fakeHelper("printf 'что-то лишнее\\nDOWN\\n\\n  UP  \\n'\nsleep 5");

        try (HotkeyListener listener = listener(helper)) {
            listener.start();
            assertTrue(await(2), "events not received: " + events);
        }

        assertEquals(List.of("press", "release"), events);
    }

    @Test
    void restartsCrashedHelper() throws Exception {

        Path helper = fakeHelper("printf 'DOWN\\n'\nexit 1");

        try (HotkeyListener listener = listener(helper)) {
            listener.start();

            for (int i = 0; i < 100 && events.size() < 2; i++) {
                TimeUnit.MILLISECONDS.sleep(100);
            }
            assertTrue(events.size() >= 2, "helper did not restart: " + events);
        }
    }

    @Test
    void handlerErrorDoesNotBreakReading() throws Exception {
        Path helper = fakeHelper("printf 'DOWN\\nUP\\n'\nsleep 5");
        List<String> seen = new CopyOnWriteArrayList<>();

        HotkeyListener listener = new HotkeyListener(configWith(helper, ""),
                () -> {
                    throw new IllegalStateException("handler failed");
                },
                () -> seen.add("release"));

        try (listener) {
            listener.start();
            for (int i = 0; i < 60 && seen.isEmpty(); i++) {
                TimeUnit.MILLISECONDS.sleep(50);
            }
        }

        assertEquals(List.of("release"), seen);
    }

    @Test
    void missingHelperDoesNotCrashProcess() throws Exception {
        HotkeyListener listener = new HotkeyListener(
                configWith(tempDir.resolve("нет-такого"), ""), () -> { }, () -> { });

        try (listener) {
            listener.start();
            TimeUnit.MILLISECONDS.sleep(300);
            assertTrue(listener.isRunning(), "the listener must stay alive and wait for restart");
        }
    }
}
