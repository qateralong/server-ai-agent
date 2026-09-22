package com.bebebe.agent.script.runtime;

import com.bebebe.agent.config.ConfigSection;

import java.nio.file.Path;
import java.time.Duration;

public record ScriptConfig(
        String pythonBinary,
        Path venvDir,
        Path scriptsDir,
        Duration timeout,
        boolean autoInstallDeps,
        Duration pipTimeout,
        long maxMemoryMb,
        long maxFileSizeMb,
        long maxCpuSeconds
) {

    public static final String SECTION = "scripts";

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    public static final Duration DEFAULT_PIP_TIMEOUT = Duration.ofSeconds(120);

    public static final long DEFAULT_MAX_MEMORY_MB = 2048;

    public static final long DEFAULT_MAX_FILE_SIZE_MB = 512;

    public ScriptConfig {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("scripts.timeout_seconds must be > 0");
        }
        if (pipTimeout == null || pipTimeout.isZero() || pipTimeout.isNegative()) {
            throw new IllegalArgumentException("scripts.pip_timeout_seconds must be > 0");
        }
        if (maxMemoryMb < 0 || maxFileSizeMb < 0 || maxCpuSeconds < 0) {
            throw new IllegalArgumentException("scripts.max_* cannot be negative");
        }
    }

    public static ScriptConfig from(ConfigSection section) {
        return new ScriptConfig(
                section.string("python_binary", WINDOWS ? "python" : "python3"),
                expand(section.string("venv_dir", "~/.local/share/bebebe-agent/venv")),
                expand(section.string("scripts_dir", "~/.local/share/bebebe-agent/scripts")),
                section.seconds("timeout_seconds", DEFAULT_TIMEOUT),
                section.bool("auto_install_deps", true),
                section.seconds("pip_timeout_seconds", DEFAULT_PIP_TIMEOUT),
                section.longValue("max_memory_mb").orElse(DEFAULT_MAX_MEMORY_MB),
                section.longValue("max_file_size_mb").orElse(DEFAULT_MAX_FILE_SIZE_MB),
                section.longValue("max_cpu_seconds").orElse(0L));
    }

    public long effectiveCpuSeconds() {
        return maxCpuSeconds > 0 ? maxCpuSeconds : Math.max(1, timeout.toSeconds() * 2);
    }

    static Path expand(String raw) {
        if (raw == null || raw.isBlank()) {
            return Path.of("");
        }
        String value = raw.trim();
        if (value.equals("~")) {
            return Path.of(System.getProperty("user.home"));
        }
        if (value.startsWith("~/")) {
            return Path.of(System.getProperty("user.home"), value.substring(2));
        }
        return Path.of(value);
    }

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");

    public Path venvPython() {
        return WINDOWS ? venvDir.resolve("Scripts").resolve("python.exe") : venvDir.resolve("bin").resolve("python");
    }

    @Override
    public String toString() {
        return "ScriptConfig[venv=%s, timeout=%ds, autoInstall=%s, memory=%s, cpu=%ds]"
                .formatted(venvDir, timeout.toSeconds(), autoInstallDeps,
                        maxMemoryMb == 0 ? "unlimited" : maxMemoryMb + " MB",
                        effectiveCpuSeconds());
    }
}
