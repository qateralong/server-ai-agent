package com.bebebe.agent.script.runtime;

import com.bebebe.agent.config.AppConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScriptRuntimeTest {

    @TempDir
    static Path shared;

    private static ScriptConfig config;
    private static ScriptRuntime runtime;

    @BeforeAll
    void setUp() {
        config = ScriptConfig.from(AppConfig.fromToml("""
                [scripts]
                venv_dir = "%s"
                scripts_dir = "%s"
                timeout_seconds = 10
                """.formatted(shared.resolve("venv"), shared.resolve("scripts")))
                .section(ScriptConfig.SECTION));
        runtime = new ScriptRuntime(config);
    }

    @Test
    void runsScriptAndCapturesStdout() {
        ScriptResult result = runtime.run("print('привет из скрипта')");

        assertTrue(result.isSuccess(), result.stderr());
        assertEquals("привет из скрипта", result.stdout().strip());
        assertEquals(0, result.exitCode());
    }

    @Test
    void scriptRunsFromVenvNotSystem() {
        ScriptResult result = runtime.run("import sys; print(sys.prefix)");

        assertTrue(result.stdout().strip().startsWith(shared.resolve("venv").toString()),
                "the script did not run in the venv: " + result.stdout());
    }

    @Test
    void catchesNonZeroExitCode() {
        ScriptResult result = runtime.run("import sys; sys.exit(3)");

        assertFalse(result.isSuccess());
        assertEquals(3, result.exitCode());
    }

    @Test
    void catchesTracebackInStderr() {
        ScriptResult result = runtime.run("raise ValueError('так нельзя')");

        assertFalse(result.isSuccess());
        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("ValueError"), result.stderr());
        assertTrue(result.stderr().contains("так нельзя"));
    }

    @Test
    void streamsAreSeparate() {

        ScriptResult result = runtime.run("""
                import sys
                print('это результат')
                print('это диагностика', file=sys.stderr)
                """);

        assertEquals("это результат", result.stdout().strip());
        assertEquals("это диагностика", result.stderr().strip());
    }

    @Test
    void killsScriptOnTimeout() {
        long started = System.currentTimeMillis();

        ScriptResult result = runtime.run("import time; time.sleep(60)");

        long elapsed = System.currentTimeMillis() - started;
        assertTrue(result.isTimeout(), "expected timeout, got exit=" + result.exitCode());
        assertFalse(result.isSuccess());
        assertTrue(elapsed < 20_000, "timeout did not trigger, elapsed " + elapsed + " мс");
    }

    @Test
    void savesEveryRunScript() throws IOException {

        runtime.run("print('сохрани меня')");

        try (var files = Files.list(config.scriptsDir())) {
            assertTrue(files.anyMatch(path -> {
                try {
                    return Files.readString(path).contains("сохрани меня");
                } catch (IOException e) {
                    return false;
                }
            }), "script not saved in " + config.scriptsDir());
        }
    }

    @Test
    void emptyCodeDoesNotRun() {
        assertEquals(ScriptResult.LAUNCH_FAILED_EXIT_CODE, runtime.run("").exitCode());
        assertEquals(ScriptResult.LAUNCH_FAILED_EXIT_CODE, runtime.run(null).exitCode());
    }

    @Test
    void venvIsReusedBetweenRuns() {

        Path marker = config.venvPython();
        runtime.run("print(1)");
        long createdAt = marker.toFile().lastModified();

        runtime.run("print(2)");

        assertEquals(createdAt, marker.toFile().lastModified());
    }

    @Test
    void descriptionForModelContainsCodeAndOutput() {
        ScriptResult result = runtime.run("raise RuntimeError('упс')");

        String described = result.describeForModel();
        assertTrue(described.contains("exit code: 1"), described);
        assertTrue(described.contains("RuntimeError"), described);
    }

    @Test
    void missingInterpreterDoesNotCrashProcess() {
        ScriptConfig broken = ScriptConfig.from(AppConfig.fromToml("""
                [scripts]
                python_binary = "/no/such/python"
                venv_dir = "%s"
                scripts_dir = "%s"
                """.formatted(shared.resolve("broken-venv"), shared.resolve("scripts")))
                .section(ScriptConfig.SECTION));

        ScriptResult result = new ScriptRuntime(broken).run("print(1)");

        assertEquals(ScriptResult.LAUNCH_FAILED_EXIT_CODE, result.exitCode());
        assertTrue(result.stderr().contains("venv"), result.stderr());
    }

    @Test
    void withoutAutoInstallMissingModuleIsReturnedToModelAsIs() {
        ScriptConfig manual = ScriptConfig.from(AppConfig.fromToml("""
                [scripts]
                venv_dir = "%s"
                scripts_dir = "%s"
                auto_install_deps = false
                """.formatted(shared.resolve("venv"), shared.resolve("scripts")))
                .section(ScriptConfig.SECTION));

        ScriptResult result = new ScriptRuntime(manual).run("import definitely_missing_module_bebebe");

        assertFalse(result.isSuccess());
        assertTrue(result.stderr().contains("ModuleNotFoundError"), result.stderr());
        assertTrue(result.installedPackages().isEmpty(), "pip was not called");
    }

    @Test
    void stdlibModuleIsNotTreatedAsMissingPackage() {

        ScriptResult result = runtime.run("from json import definitely_no_such_name");

        assertFalse(result.isSuccess());
        assertTrue(result.installedPackages().isEmpty(), "nothing was installed: " + result.installedPackages());
        assertTrue(result.duration().toSeconds() < 5, "no trip to pip");
    }

    @Test
    void largeOutputToBothStreamsDoesNotHang() {

        ScriptResult result = runtime.run("""
                import sys
                sys.stdout.write('o' * 600000)
                sys.stderr.write('e' * 600000)
                """);

        assertTrue(result.isSuccess(), "timeout=" + result.isTimeout() + " stderr=" + result.stderr().length());
        assertEquals(600000, result.stdout().strip().length());
        assertEquals(600000, result.stderr().strip().length());
    }

    @Test
    void scriptRunsInUserEnvironmentNotSandbox() {

        ScriptResult result = runtime.run("import os; print(os.environ.get('HOME', ''))");

        assertTrue(result.isSuccess(), result.stderr());
        assertEquals(System.getenv("HOME"), result.stdout().strip());
    }

    @Test
    void cyrillicInCodeAndOutputWorks() {
        ScriptResult result = runtime.run("s = 'тест — ёлка'; print(s.upper()); print(len(s))");

        assertTrue(result.isSuccess(), result.stderr());
        assertEquals(List.of("ТЕСТ — ЁЛКА", "11"), result.stdout().strip().lines().toList());
    }
}
