package com.bebebe.agent.notes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class NotesGit {

    private static final Logger log = LoggerFactory.getLogger(NotesGit.class);
    private static final List<String> IDENTITY = List.of(
            "-c", "user.name=bebebe-agent", "-c", "user.email=agent@localhost",
            "-c", "commit.gpgsign=false");

    private final Path dir;
    private final boolean enabled;
    private volatile boolean available;

    public NotesGit(Path dir, boolean enabled) {
        this.dir = dir;
        this.enabled = enabled;
        this.available = enabled && probe();
    }

    public boolean isAvailable() {
        return available;
    }

    public void ensureRepository() throws IOException {
        if (!available) {
            return;
        }
        Path ignore = dir.resolve(".gitignore");
        if (!Files.exists(ignore)) {
            Files.writeString(ignore, "# the index is rebuilt from files; it does not belong in history\n.index.db\n.index.db-*\n",
                    StandardCharsets.UTF_8);
        }
        if (Files.isDirectory(dir.resolve(".git"))) {
            return;
        }
        if (run("init", "-q") != 0) {
            available = false;
            return;
        }
        run("checkout", "-q", "-b", "main");
        commit("Notes storage created");
        log.info("Notes git repository created: {}", dir);
    }

    public boolean commit(String message) {
        if (!available) {
            return false;
        }
        if (run("add", "-A") != 0) {
            return false;
        }
        if (run("diff", "--cached", "--quiet") == 0) {
            return false;
        }
        String safe = message.replace('\n', ' ').strip();
        if (safe.isEmpty()) {
            safe = "Change";
        }
        int code = run("commit", "-q", "-m", safe);
        if (code != 0) {
            log.warn("git commit failed (code {}): {}", code, safe);
            return false;
        }
        log.debug("git commit: {}", safe);
        return true;
    }

    public List<String> recentLog(int limit) {
        if (!available) {
            return List.of();
        }
        try {
            Process p = start("log", "--pretty=%ad %s", "--date=short", "-n", String.valueOf(limit));
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor(10, TimeUnit.SECONDS);
            return out.lines().filter(l -> !l.isBlank()).toList();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        }
    }

    List<String> command(String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(IDENTITY);
        cmd.addAll(List.of(args));
        return cmd;
    }

    private Process start(String... args) throws IOException {
        return new ProcessBuilder(command(args))
                .directory(dir.toFile())
                .redirectErrorStream(false)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    private int run(String... args) {
        try {
            Process p = start(args);
            p.getInputStream().readAllBytes();
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return -1;
            }
            return p.exitValue();
        } catch (IOException e) {
            log.warn("git {} failed to start: {}", args.length > 0 ? args[0] : "", e.getMessage());
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private boolean probe() {
        try {
            Process p = new ProcessBuilder("git", "--version")
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException e) {
            log.warn("git not found -- notes history will not be kept");
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
