package com.bebebe.agent.script.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ResourceLimits {

    private static final Logger log = LoggerFactory.getLogger(ResourceLimits.class);

    private static final List<Path> CANDIDATES = List.of(
            Path.of("/usr/bin/prlimit"),
            Path.of("/bin/prlimit"),
            Path.of("/usr/local/bin/prlimit"));

    private final ScriptConfig config;
    private final Path prlimit;
    private volatile boolean warnedMissing;

    public ResourceLimits(ScriptConfig config) {
        this(config, findPrlimit());
    }

    ResourceLimits(ScriptConfig config, Path prlimit) {
        this.config = config;
        this.prlimit = prlimit;
    }

    public boolean isAvailable() {
        return prlimit != null;
    }

    public boolean isEnabled() {
        return config.maxMemoryMb() > 0 || config.maxFileSizeMb() > 0 || config.effectiveCpuSeconds() > 0;
    }

    public List<String> wrap(List<String> command) {
        if (!isEnabled()) {
            return command;
        }
        if (prlimit == null) {
            if (!warnedMissing) {
                warnedMissing = true;
                log.warn("prlimit not found -- scripts run without resource limits "
                        + "(util-linux is usually installed; check /usr/bin/prlimit)");
            }
            return command;
        }

        List<String> wrapped = new ArrayList<>();
        wrapped.add(prlimit.toString());
        if (config.maxMemoryMb() > 0) {
            wrapped.add("--as=" + config.maxMemoryMb() * 1024 * 1024);
        }
        if (config.maxFileSizeMb() > 0) {
            wrapped.add("--fsize=" + config.maxFileSizeMb() * 1024 * 1024);
        }
        long cpu = config.effectiveCpuSeconds();
        if (cpu > 0) {
            wrapped.add("--cpu=" + cpu);
        }
        wrapped.add("--");
        wrapped.addAll(command);
        return wrapped;
    }

    public String describe() {
        if (!isEnabled()) {
            return "resource limits disabled";
        }
        if (prlimit == null) {
            return "limits configured, but prlimit not found -- not applied";
        }
        return "prlimit: memory %s, cpu %d s, file %s".formatted(
                config.maxMemoryMb() > 0 ? config.maxMemoryMb() + " MB" : "unlimited",
                config.effectiveCpuSeconds(),
                config.maxFileSizeMb() > 0 ? config.maxFileSizeMb() + " MB" : "unlimited");
    }

    private static Path findPrlimit() {
        for (Path candidate : CANDIDATES) {
            if (Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
