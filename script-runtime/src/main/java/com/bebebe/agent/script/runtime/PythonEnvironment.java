package com.bebebe.agent.script.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class PythonEnvironment {

    private static final Logger log = LoggerFactory.getLogger(PythonEnvironment.class);

    private final ScriptConfig config;

    private volatile Set<String> stdlibModules;
    private volatile boolean ready;

    public PythonEnvironment(ScriptConfig config) {
        this.config = config;
    }

    public Path python() {
        return config.venvPython();
    }

    public synchronized void ensureReady() {
        if (ready) {
            return;
        }
        if (Files.isExecutable(config.venvPython())) {
            log.debug("Using existing venv: {}", config.venvDir());
            ready = true;
            return;
        }

        log.info("Creating venv: {}", config.venvDir());
        try {
            Files.createDirectories(config.venvDir().getParent());
        } catch (IOException e) {
            throw new ScriptRuntimeException("Cannot create venv directory: " + config.venvDir(), e);
        }

        ProcessOutcome outcome = run(
                List.of(config.pythonBinary(), "-m", "venv", config.venvDir().toString()),
                Duration.ofMinutes(2));

        if (outcome.exitCode() != 0 || !Files.isExecutable(config.venvPython())) {
            throw new ScriptRuntimeException(
                    "Cannot create venv (" + config.venvDir() + "): " + outcome.mergedOutput());
        }
        log.info("venv created: {}", config.venvDir());
        ready = true;
    }

    public boolean install(String packageName) {
        ensureReady();
        log.info("Installing package: {}", packageName);

        ProcessOutcome outcome = run(
                List.of(config.venvPython().toString(), "-m", "pip", "install",
                        "--disable-pip-version-check", "--no-input", packageName),
                config.pipTimeout());

        if (outcome.exitCode() == 0) {
            log.info("Package {} installed", packageName);
            return true;
        }
        log.warn("Failed to install {}: {}", packageName, lastLines(outcome.mergedOutput(), 3));
        return false;
    }

    public Set<String> stdlibModules() {
        Set<String> cached = stdlibModules;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (stdlibModules != null) {
                return stdlibModules;
            }
            ensureReady();
            ProcessOutcome outcome = run(
                    List.of(config.venvPython().toString(), "-c",
                            "import sys; print('\\n'.join(sys.stdlib_module_names))"),
                    Duration.ofSeconds(20));

            Set<String> names = outcome.exitCode() == 0
                    ? outcome.stdout().lines().map(String::strip).filter(s -> !s.isEmpty())
                            .collect(java.util.stream.Collectors.toUnmodifiableSet())
                    : Set.of();
            if (names.isEmpty()) {
                log.warn("stdlib module list not obtained -- auto-install will be more cautious");
            } else {
                log.debug("stdlib modules: {}", names.size());
            }
            stdlibModules = names;
            return names;
        }
    }

    ProcessOutcome run(List<String> command, Duration timeout) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            Process process = builder.start();

            String output;
            try (var stream = process.getInputStream()) {
                output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }

            if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new ProcessOutcome(ScriptResult.TIMEOUT_EXIT_CODE, output, "");
            }
            return new ProcessOutcome(process.exitValue(), output, "");
        } catch (IOException e) {
            return new ProcessOutcome(ScriptResult.LAUNCH_FAILED_EXIT_CODE, "", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProcessOutcome(ScriptResult.LAUNCH_FAILED_EXIT_CODE, "", "interrupted");
        }
    }

    private static String lastLines(String text, int count) {
        List<String> lines = text.lines().filter(line -> !line.isBlank()).toList();
        return String.join(" | ", lines.subList(Math.max(0, lines.size() - count), lines.size()));
    }

    record ProcessOutcome(int exitCode, String stdout, String stderr) {

        String mergedOutput() {
            return (stdout + "\n" + stderr).strip();
        }
    }
}
