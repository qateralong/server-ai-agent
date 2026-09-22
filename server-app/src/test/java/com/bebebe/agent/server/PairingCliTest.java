package com.bebebe.agent.server;

import com.bebebe.agent.config.AppConfig;
import com.bebebe.agent.transport.PairingTokens;
import com.bebebe.agent.transport.TransportConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PairingCliTest {

    @TempDir
    Path temp;

    private AppConfig config() {
        return AppConfig.fromToml("""
                [transport]
                port = 8765
                data_dir = "%s"
                """.formatted(temp));
    }

    private String run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = PairingCli.run(config(), args, new PrintStream(out, true, StandardCharsets.UTF_8), System.err);
        assertEquals(0, code, "exit code");
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void pairPrintsEverythingForClientConfigAndTokenVerifies() {
        String printed = run("pair", "рабочий", "ноут");

        assertTrue(printed.contains("url = \"wss://<this-server-address>:8765\""), printed);
        Matcher fp = Pattern.compile("fingerprint = \"([0-9A-F:]{95})\"").matcher(printed);
        assertTrue(fp.find(), "SHA-256 fingerprint: " + printed);
        Matcher id = Pattern.compile("client_id = \"([0-9a-f]{8})\"").matcher(printed);
        Matcher token = Pattern.compile("token = \"([0-9a-f]{64})\"").matcher(printed);
        assertTrue(id.find() && token.find(), printed);

        PairingTokens tokens = new PairingTokens(TransportConfig.from(config().section("transport")).clientsFile());
        assertEquals("рабочий ноут", tokens.verify(id.group(1), token.group(1)).orElseThrow().name());

        String list = run("clients");
        assertTrue(list.contains(id.group(1)) && list.contains("рабочий ноут"), list);

        assertTrue(run("revoke", id.group(1)).contains("revoked"));
        assertTrue(tokens.verify(id.group(1), token.group(1)).isEmpty());
    }
}
