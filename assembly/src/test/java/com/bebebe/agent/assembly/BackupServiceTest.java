package com.bebebe.agent.assembly;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupServiceTest {

    @TempDir
    Path temp;

    private Map<String, String> unzip(Path archive) throws IOException {
        Map<String, String> out = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                out.put(e.getName(), new String(zip.readAllBytes(), StandardCharsets.ISO_8859_1));
            }
        }
        return out;
    }

    @Test
    void secretsAreRedactedButKeysRemain() {
        List<String> redacted = new ArrayList<>();
        String toml = """
                [ollama]
                api_key = "sk-very-secret"   # key
                model = "gpt-oss:120b"
                [telegram]
                bot_token = "123:ABC"
                allowed_usernames = ["me"]
                [tools.web_search]
                brave_api_key = ""
                [updates]
                github_token = "ghp_x"
                """;

        String out = BackupService.sanitizeConfig(toml, redacted);

        assertTrue(out.contains("api_key = \"<excluded from backup>\"   # key"), out);
        assertTrue(out.contains("bot_token = \"<excluded from backup>\""), out);
        assertTrue(out.contains("github_token = \"<excluded from backup>\""), out);
        assertTrue(out.contains("brave_api_key = \"\""), "an empty secret stays empty");
        assertTrue(out.contains("model = \"gpt-oss:120b\""));
        assertFalse(out.contains("sk-very-secret"));
        assertFalse(out.contains("123:ABC"));
        assertEquals(List.of("api_key", "bot_token", "github_token"), redacted);
    }

    @Test
    void archiveContainsDatabasesNotesAndConfigWithoutSecrets() throws Exception {
        Path db = temp.resolve("memory.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db); Statement s = c.createStatement()) {
            s.executeUpdate("PRAGMA journal_mode=WAL");
            s.executeUpdate("CREATE TABLE facts (text TEXT)");
            s.executeUpdate("INSERT INTO facts VALUES ('Саша не ест мясо')");
        }
        Path notes = temp.resolve("notes");
        Files.createDirectories(notes.resolve("lists"));
        Files.writeString(notes.resolve("lists/покупки.md"), "- [ ] молоко\n");
        Files.createDirectories(notes.resolve(".git"));
        Path config = temp.resolve("agent.toml");
        Files.writeString(config, "[ollama]\napi_key = \"sk-1\"\nmodel = \"m\"\n");

        BackupService.Result result = new BackupService(db, temp.resolve("нет.db"), null, notes, config)
                .create(temp.resolve("out"));

        Map<String, String> entries = unzip(result.archive());
        assertTrue(entries.containsKey("memory.db"));
        assertFalse(entries.containsKey("personas.db"), "a missing database is skipped");
        assertTrue(entries.containsKey("notes/lists/покупки.md"));
        assertTrue(entries.keySet().stream().noneMatch(k -> k.contains(".git")));
        String cfg = new String(entries.get("config.toml").getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        assertFalse(cfg.contains("sk-1"));
        assertTrue(cfg.contains("model = \"m\""));
        assertEquals(List.of("api_key"), result.redactedKeys());
        assertTrue(result.summary().contains("Secrets excluded: api_key"), result.summary());
        String readme = new String(entries.get("README.txt").getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        assertTrue(readme.contains("separate git repository"), readme);

        Path restored = temp.resolve("restored.db");
        Files.write(restored, entries.get("memory.db").getBytes(StandardCharsets.ISO_8859_1));
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + restored); Statement s = c.createStatement()) {
            assertTrue(s.executeQuery("SELECT text FROM facts").next());
        }
    }
}
