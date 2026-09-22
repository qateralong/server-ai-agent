package com.bebebe.agent.script.runtime;

import com.bebebe.agent.config.AppConfig;
import com.bebebe.agent.transport.actions.ActionExecutor;
import com.bebebe.agent.transport.actions.ActionResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalActionExecutorTest {

    @TempDir
    static Path shared;

    private ScriptRuntime runtime;
    private ActionExecutor executor;

    @BeforeAll
    void setUp() {
        runtime = new ScriptRuntime(ScriptConfig.from(AppConfig.fromToml("""
                [scripts]
                venv_dir = "%s"
                scripts_dir = "%s"
                timeout_seconds = 3
                """.formatted(shared.resolve("venv"), shared.resolve("scripts"))).section(ScriptConfig.SECTION)));
        executor = new LocalActionExecutor(runtime);
    }

    @Test
    void successfulRunPassesThroughUnchanged() {
        ActionResult result = executor.run("import sys; print('ok'); print('diag', file=sys.stderr)");

        assertTrue(result.isSuccess(), result.stderr());
        assertEquals("ok", result.stdout().strip());
        assertEquals("diag", result.stderr().strip(), "streams are still separate");
        assertTrue(result.installedPackages().isEmpty());
        assertEquals("local", executor.name());
    }

    @Test
    void errorAndTimeoutArriveWithSameCodes() {
        ActionResult failed = executor.run("raise RuntimeError('упс')");
        assertEquals(1, failed.exitCode());
        assertTrue(failed.describeForModel().contains("RuntimeError"));
        assertEquals(runtime.run("raise RuntimeError('упс')").describeForModel().length(),
                failed.describeForModel().length(), "description for the model is the same as ScriptRuntime's");

        ActionResult timeout = executor.run("import time; time.sleep(30)");
        assertTrue(timeout.isTimeout());
        assertEquals(ActionResult.TIMEOUT_EXIT_CODE, timeout.exitCode());
        assertEquals(ScriptResult.TIMEOUT_EXIT_CODE, timeout.exitCode(), "codes shared with ScriptResult");
    }

    @Test
    void emptyCodeIsLaunchFailedNotException() {
        ActionResult result = executor.run("   ");

        assertFalse(result.isSuccess());
        assertEquals(ActionResult.LAUNCH_FAILED_EXIT_CODE, result.exitCode());
        assertFalse(result.stderr().isBlank());
    }

    @Test
    void waitCeilingIsScriptTimeoutPlusAutoInstallMargin() {
        assertEquals(Duration.ofSeconds(3).plus(LocalActionExecutor.INSTALL_GRACE), executor.timeout());
        assertEquals(Duration.ofSeconds(93), executor.timeout(), "exactly what the core used to compute: timeout + 90 s");
    }
}
