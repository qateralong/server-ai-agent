package com.bebebe.agent.clipboard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClipboardBridgeTest {

    @TempDir
    Path temp;

    private Path fake(String body) throws IOException {
        Path script = temp.resolve("wl-paste");
        Files.writeString(script, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return script;
    }

    @Test
    void readsTextWithoutNewlineAndWithTextType() throws IOException {
        Path script = fake("""
                case "$1" in --version) echo wl-paste 2.2; exit 0;; esac
                echo "$@" > "%s"
                printf 'скопировано'""".formatted(temp.resolve("args")));

        ClipboardBridge bridge = new ClipboardBridge(List.of(script.toString()));

        assertTrue(bridge.isReady());
        assertEquals(Optional.of("скопировано"), bridge.read());
        assertEquals("--no-newline --type text", Files.readString(temp.resolve("args")).strip());
    }

    @Test
    void emptyClipboardOrImageIsEmpty() throws IOException {
        Path script = fake("case \"$1\" in --version) exit 0;; esac\necho 'No selection' >&2; exit 1");

        assertEquals(Optional.empty(), new ClipboardBridge(List.of(script.toString())).read());
    }

    @Test
    void withoutWlPasteBridgeNotReadyAndDoesNotCrash() {
        ClipboardBridge bridge = new ClipboardBridge(List.of(temp.resolve("нет-такого").toString()));

        assertFalse(bridge.isReady());
        assertEquals(Optional.empty(), bridge.read());
    }
}
