package com.bebebe.agent.assembly;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    private static final Pattern SECRET_LINE = Pattern.compile(
            "^(\\s*)([A-Za-z0-9_.]*(?:token|api_key|secret|password)[A-Za-z0-9_]*)(\\s*=\\s*)\"[^\"]*\"(.*)$",
            Pattern.CASE_INSENSITIVE);
    static final String REDACTED = "\"<excluded from backup>\"";

    public record Result(Path archive, List<String> included, List<String> redactedKeys, long bytes) {

        public String summary() {
            return "Archive: " + archive + " (" + bytes / 1024 + " KB)\nIncluded: " + String.join(", ", included)
                    + (redactedKeys.isEmpty() ? "\nNo secrets found in the config."
                    : "\n⚠️ Secrets excluded: " + String.join(", ", redactedKeys)
                    + " -- enter them again after restoring.");
        }
    }

    private final Path memoryDb;
    private final Path personasDb;
    private final Path schedulerDb;
    private final Path notesDir;
    private final Path configFile;

    public BackupService(Path memoryDb, Path personasDb, Path schedulerDb, Path notesDir, Path configFile) {
        this.memoryDb = memoryDb;
        this.personasDb = personasDb;
        this.schedulerDb = schedulerDb;
        this.notesDir = notesDir;
        this.configFile = configFile;
    }

    public Result create(Path targetDir) {
        try {
            Files.createDirectories(targetDir);
            Path archive = targetDir.resolve("bebebe-backup-"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".zip");
            List<String> included = new ArrayList<>();
            List<String> redacted = new ArrayList<>();
            Path work = Files.createTempDirectory("bebebe-backup-");
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive), StandardCharsets.UTF_8)) {
                addDatabase(zip, memoryDb, "memory.db", work, included);
                addDatabase(zip, personasDb, "personas.db", work, included);
                addDatabase(zip, schedulerDb, "scheduler.db", work, included);
                addNotes(zip, included);
                if (configFile != null && Files.isRegularFile(configFile)) {
                    String sanitized = sanitizeConfig(Files.readString(configFile, StandardCharsets.UTF_8), redacted);
                    addText(zip, "config.toml", sanitized);
                    included.add("config.toml (no secrets)");
                }
                addText(zip, "README.txt", readme(included, redacted));
            } finally {
                try (Stream<Path> files = Files.list(work)) {
                    files.forEach(f -> f.toFile().delete());
                }
                Files.deleteIfExists(work);
            }
            long bytes = Files.size(archive);
            log.atInfo().addKeyValue("event", "backup.created").addKeyValue("archive", archive.toString())
                    .addKeyValue("bytes", bytes).addKeyValue("redacted", redacted.size())
                    .log("Backup created: {} ({} KB)", archive, bytes / 1024);
            return new Result(archive, List.copyOf(included), List.copyOf(redacted), bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot build backup: " + e.getMessage(), e);
        }
    }

    static String sanitizeConfig(String toml, List<String> redactedKeys) {
        StringBuilder out = new StringBuilder();
        for (String line : toml.split("\n", -1)) {
            Matcher m = SECRET_LINE.matcher(line);
            if (m.matches() && !m.group(4).contains("\"") ) {
                String key = m.group(2);
                boolean empty = line.matches(".*=\\s*\"\"\\s*(#.*)?$");
                out.append(m.group(1)).append(key).append(m.group(3)).append(empty ? "\"\"" : REDACTED).append(m.group(4));
                if (!empty && !redactedKeys.contains(key)) {
                    redactedKeys.add(key);
                }
            } else {
                out.append(line);
            }
            out.append('\n');
        }
        String result = out.toString();
        return result.endsWith("\n\n") ? result.substring(0, result.length() - 1) : result;
    }

    private void addDatabase(ZipOutputStream zip, Path db, String name, Path work, List<String> included) throws IOException {
        if (db == null || !Files.isRegularFile(db)) {
            return;
        }
        Path copy = work.resolve(name);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db); Statement s = c.createStatement()) {
            s.executeUpdate("VACUUM INTO '" + copy.toString().replace("'", "''") + "'");
        } catch (SQLException e) {
            log.warn("VACUUM INTO for {} failed ({}), copying the file as is", db, e.getMessage());
            Files.copy(db, copy);
        }
        zip.putNextEntry(new ZipEntry(name));
        Files.copy(copy, zip);
        zip.closeEntry();
        included.add(name);
    }

    private void addNotes(ZipOutputStream zip, List<String> included) throws IOException {
        if (notesDir == null || !Files.isDirectory(notesDir)) {
            return;
        }
        int count = 0;
        for (String folder : List.of("lists", "notes")) {
            Path dir = notesDir.resolve(folder);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".md")).sorted().toList()) {
                    zip.putNextEntry(new ZipEntry("notes/" + folder + "/" + f.getFileName()));
                    Files.copy(f, zip);
                    zip.closeEntry();
                    count++;
                }
            }
        }
        included.add("notes/ (" + count + " files; full history -- git in " + notesDir + ")");
    }

    private static void addText(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private String readme(List<String> included, List<String> redacted) {
        StringBuilder sb = new StringBuilder("Server AI Agent backup, ").append(LocalDateTime.now()).append("\n\n");
        sb.append("Contents:\n");
        for (String i : included) {
            sb.append("  - ").append(i).append('\n');
        }
        sb.append("\nmemory.db    -- session log, entities (people), facts\n");
        sb.append("personas.db  -- personas (system prompts)\n");
        sb.append("scheduler.db -- reminders: active and history\n");
        sb.append("notes/       -- notes and lists (markdown). This is a snapshot; the full change history is\n");
        sb.append("               a separate git repository ").append(notesDir).append(" (git bundle it entirely).\n");
        sb.append("config.toml  -- config WITHOUT secrets.\n\n");
        if (redacted.isEmpty()) {
            sb.append("No secrets found in the config.\n");
        } else {
            sb.append("ATTENTION: secrets were excluded from the config: ").append(String.join(", ", redacted))
                    .append(".\nEnter them again after restoring (Ollama key, bot token, etc.).\n");
        }
        sb.append("\nRestore: put *.db into ~/.local/share/bebebe-agent/, notes/ into ")
                .append(notesDir).append(", config.toml into its place (see README).\n");
        return sb.toString();
    }
}
