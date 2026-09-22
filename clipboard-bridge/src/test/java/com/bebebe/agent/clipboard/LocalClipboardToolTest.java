package com.bebebe.agent.clipboard;

import com.bebebe.agent.transport.actions.ClipboardTool;
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

class LocalClipboardToolTest {

    @TempDir
    Path temp;

    private ClipboardBridge bridgeWith(String body) throws IOException {
        Path script = temp.resolve("wl-paste");
        Files.writeString(script, "#!/bin/sh\ncase \"$1\" in --version) exit 0;; esac\n" + body + "\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return new ClipboardBridge(List.of(script.toString()));
    }

    @Test
    void interfaceReturnsSameAsBridge() throws IOException {
        ClipboardBridge bridge = bridgeWith("printf 'из буфера'");
        ClipboardTool tool = new LocalClipboardTool(bridge);

        assertEquals("local", tool.name());
        assertTrue(tool.isReady());
        assertEquals(bridge.read(), tool.read());
        assertEquals(Optional.of("из буфера"), tool.read());
    }

    @Test
    void withoutWlPasteInterfaceNotReadyAndDoesNotCrash() {
        ClipboardTool tool = new LocalClipboardTool(new ClipboardBridge(List.of(temp.resolve("нет").toString())));

        assertFalse(tool.isReady());
        assertEquals(Optional.empty(), tool.read());
    }
}
