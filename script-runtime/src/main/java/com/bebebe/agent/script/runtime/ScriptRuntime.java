package com.bebebe.agent.script.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ScriptRuntime {

    private static final Logger log = LoggerFactory.getLogger(ScriptRuntime.class);

    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static final int AUDIT_LIMIT = 16_000;

    private final ScriptConfig config;
    private final PythonEnvironment environment;
    private final ResourceLimits limits;
    private final AtomicInteger counter = new AtomicInteger();
    private final boolean runningAsRoot;

    public ScriptRuntime(ScriptConfig config) {
        this(config, new PythonEnvironment(config), new ResourceLimits(config));
    }

    ScriptRuntime(ScriptConfig config, PythonEnvironment environment) {
        this(config, environment, new ResourceLimits(config));
    }

    ScriptRuntime(ScriptConfig config, PythonEnvironment environment, ResourceLimits limits) {
        this.config = config;
        this.environment = environment;
        this.limits = limits;
        this.runningAsRoot = isRoot();
        if (runningAsRoot) {
            log.error("Process runs as root -- scripts will NOT be executed. "
                    + "Run the agent as a regular user.");
        }
        log.info("Scripts: {}; {}", config, limits.describe());
    }

    static boolean isRoot() {
        return "root".equals(System.getProperty("user.name"))
                || "0".equals(System.getenv("EUID"));
    }

    public ResourceLimits limits() {
        return limits;
    }

    public String name() {
        return "script-runtime";
    }

    public ScriptConfig config() {
        return config;
    }

    public ScriptResult run(String pythonCode) {
        if (pythonCode == null || pythonCode.isBlank()) {
            return ScriptResult.launchFailed("Пустой код скрипта");
        }
        if (runningAsRoot) {
            return ScriptResult.launchFailed(
                    "Скрипты не выполняются от root: запустите агента от обычного пользователя");
        }

        Path script;
        try {
            environment.ensureReady();
            script = save(pythonCode);
        } catch (ScriptRuntimeException e) {
            log.error("Environment not ready: {}", e.getMessage());
            return ScriptResult.launchFailed(e.getMessage());
        } catch (IOException e) {
            log.error("Cannot save script", e);
            return ScriptResult.launchFailed("Не удалось сохранить скрипт: " + e.getMessage());
        }

        ScriptResult first = execute(script);
        audit(script, pythonCode, first, false);
        if (first.isSuccess() || !config.autoInstallDeps()) {
            return first;
        }

        Optional<MissingModule> missing = MissingModule.detect(first.stderr(), environment.stdlibModules());
        if (missing.isEmpty()) {
            return first;
        }

        MissingModule module = missing.get();
        log.info("Script failed due to missing module {} -- installing and retrying", module);
        if (!environment.install(module.packageName())) {
            return first;
        }

        ScriptResult second = execute(script).withInstalled(List.of(module.packageName()));
        audit(script, pythonCode, second, true);
        if (!second.isSuccess()) {
            log.info("Script still fails after installing {} -- it is a script bug", module.packageName());
        }
        return second;
    }

    private void audit(Path script, String code, ScriptResult result, boolean retry) {
        log.atInfo()
                .addKeyValue("event", "script.audit")
                .addKeyValue("file", script.toString())
                .addKeyValue("retry", retry)
                .addKeyValue("exit_code", result.exitCode())
                .addKeyValue("timeout", result.isTimeout())
                .addKeyValue("duration_ms", result.duration().toMillis())
                .addKeyValue("installed", String.join(",", result.installedPackages()))
                .addKeyValue("code", clip(code))
                .addKeyValue("stdout", clip(result.stdout()))
                .addKeyValue("stderr", clip(result.stderr()))
                .log("Run audit: {} -> exit={}", script.getFileName(),
                        result.isTimeout() ? "timeout" : result.exitCode());
    }

    private static String clip(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= AUDIT_LIMIT
                ? text
                : text.substring(0, AUDIT_LIMIT) + "\n...(truncated, total " + text.length() + " chars)";
    }

    public Path scriptsDir() {
        return config.scriptsDir();
    }

    private Path save(String code) throws IOException {
        Files.createDirectories(config.scriptsDir());
        String name = "%s-%03d.py".formatted(
                LocalDateTime.now().format(FILE_STAMP), counter.incrementAndGet());
        Path file = config.scriptsDir().resolve(name);
        Files.writeString(file, code, StandardCharsets.UTF_8);
        log.info("Script saved: {}", file);
        return file;
    }

    private ScriptResult execute(Path script) {
        long started = System.nanoTime();
        Process process;
        try {
            List<String> command = limits.wrap(List.of(environment.python().toString(), script.toString()));
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(config.scriptsDir().toFile());

            builder.redirectErrorStream(false);
            process = builder.start();
        } catch (IOException e) {
            return ScriptResult.launchFailed("Не удалось запустить " + environment.python() + ": " + e.getMessage());
        }

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        Thread outPump = pump(process.getInputStream(), stdout, "script-stdout");
        Thread errPump = pump(process.getErrorStream(), stderr, "script-stderr");

        boolean finished;
        try {
            finished = process.waitFor(config.timeout().toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                log.warn("Script exceeded {} s -- killing", config.timeout().toSeconds());
                process.destroyForcibly();
                process.waitFor(3, TimeUnit.SECONDS);
            }
            outPump.join(2000);
            errPump.join(2000);
        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();
            terminateGracefully(process);
            log.atWarn().addKeyValue("event", "script.interrupted").log("Script interrupted externally");
            return ScriptResult.launchFailed("Запуск прерван: агент останавливается");
        }

        Duration duration = Duration.ofNanos(System.nanoTime() - started);
        int exitCode = finished ? process.exitValue() : ScriptResult.TIMEOUT_EXIT_CODE;

        ScriptResult result = ScriptResult.of(exitCode, stdout.toString(), stderr.toString(), duration);
        log.info("Script finished: exit={}, {} ms",
                result.isTimeout() ? "timeout" : exitCode, duration.toMillis());
        return result;
    }

    static final int TERM_GRACE_SECONDS = 3;

    static void terminateGracefully(Process process) {
        if (!process.isAlive()) {
            return;
        }
        process.destroy();
        boolean interrupted = Thread.interrupted();
        try {
            if (!process.waitFor(TERM_GRACE_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            interrupted = true;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static Thread pump(java.io.InputStream stream, StringBuilder target, String name) {
        Thread thread = new Thread(() -> {
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    target.append(line).append('\n');
                }
            } catch (IOException e) {

                log.debug("Stream {} closed", name);
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
}
