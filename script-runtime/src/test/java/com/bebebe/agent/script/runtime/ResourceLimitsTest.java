package com.bebebe.agent.script.runtime;

import com.bebebe.agent.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceLimitsTest {

    private static ScriptConfig config(String extra) {
        return ScriptConfig.from(AppConfig.fromToml("[scripts]\n" + extra).section(ScriptConfig.SECTION));
    }

    static boolean prlimitAvailable() {
        return Files.isExecutable(Path.of("/usr/bin/prlimit"));
    }

    @Test
    void wrapsCommandInPrlimitWithAllLimits() {
        ResourceLimits limits = new ResourceLimits(config("""
                max_memory_mb = 256
                max_file_size_mb = 10
                max_cpu_seconds = 7
                """), Path.of("/usr/bin/prlimit"));

        List<String> wrapped = limits.wrap(List.of("python", "a.py"));

        assertEquals("/usr/bin/prlimit", wrapped.getFirst());
        assertTrue(wrapped.contains("--as=" + 256L * 1024 * 1024), wrapped.toString());
        assertTrue(wrapped.contains("--fsize=" + 10L * 1024 * 1024));
        assertTrue(wrapped.contains("--cpu=7"));

        int dash = wrapped.indexOf("--");
        assertEquals(List.of("python", "a.py"), wrapped.subList(dash + 1, wrapped.size()));
    }

    @Test
    void zeroLimitIsNotInCommand() {
        ResourceLimits limits = new ResourceLimits(config("""
                max_memory_mb = 0
                max_file_size_mb = 0
                timeout_seconds = 5
                """), Path.of("/usr/bin/prlimit"));

        List<String> wrapped = limits.wrap(List.of("python"));

        assertFalse(wrapped.stream().anyMatch(a -> a.startsWith("--as=")));
        assertFalse(wrapped.stream().anyMatch(a -> a.startsWith("--fsize=")));

        assertTrue(wrapped.contains("--cpu=10"), wrapped.toString());
    }

    @Test
    void withoutPrlimitCommandIsUnchanged() {
        ResourceLimits limits = new ResourceLimits(config("max_memory_mb = 256"), null);

        assertFalse(limits.isAvailable());
        assertEquals(List.of("python", "a.py"), limits.wrap(List.of("python", "a.py")));
        assertTrue(limits.describe().contains("not found"));
    }

    @Test
    void limitsCannotBeNegative() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> config("max_memory_mb = -1"));
    }

    @Test
    void nprocIsNotLimited() {

        ResourceLimits limits = new ResourceLimits(config("max_memory_mb = 256"), Path.of("/usr/bin/prlimit"));

        assertFalse(limits.wrap(List.of("python")).stream().anyMatch(a -> a.startsWith("--nproc")));
    }

    @Test
    @EnabledIf("prlimitAvailable")
    void memoryLimitReallyWorks(@TempDir Path temp) {

        ScriptRuntime runtime = new ScriptRuntime(config("""
                venv_dir = "%s"
                scripts_dir = "%s"
                timeout_seconds = 20
                max_memory_mb = 256
                auto_install_deps = false
                """.formatted(temp.resolve("venv"), temp.resolve("scripts"))));

        ScriptResult result = runtime.run("""
                data = bytearray(1024 * 1024 * 1024)
                print("выделил гигабайт")
                """);

        assertFalse(result.isSuccess(), "the script bypassed the memory limit: " + result.stdout());
        assertTrue(result.stderr().contains("MemoryError") || result.exitCode() != 0,
                "expected MemoryError, got: " + result.stderr());
    }

    @Test
    @EnabledIf("prlimitAvailable")
    void ordinaryScriptWorksUnderLimits(@TempDir Path temp) {
        ScriptRuntime runtime = new ScriptRuntime(config("""
                venv_dir = "%s"
                scripts_dir = "%s"
                max_memory_mb = 512
                auto_install_deps = false
                """.formatted(temp.resolve("venv"), temp.resolve("scripts"))));

        ScriptResult result = runtime.run("import json, os; print(json.dumps({'ok': True}))");

        assertTrue(result.isSuccess(), result.stderr());
        assertTrue(result.stdout().contains("ok"), result.stdout());
    }
}
