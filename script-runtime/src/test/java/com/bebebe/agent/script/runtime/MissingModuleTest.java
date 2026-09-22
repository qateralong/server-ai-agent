package com.bebebe.agent.script.runtime;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MissingModuleTest {

    private static final Set<String> STDLIB = Set.of("os", "sys", "json", "asyncio", "sqlite3");

    private static Optional<MissingModule> detect(String stderr) {
        return MissingModule.detect(stderr, STDLIB);
    }

    @Test
    void findsModuleNotFoundError() {
        String stderr = """
                Traceback (most recent call last):
                  File "/tmp/s.py", line 1, in <module>
                    import requests
                ModuleNotFoundError: No module named 'requests'
                """;

        assertEquals("requests", detect(stderr).orElseThrow().packageName());
    }

    @Test
    void findsOldImportErrorWording() {
        assertEquals("requests",
                detect("ImportError: No module named 'requests'").orElseThrow().packageName());
    }

    @Test
    void installsTopLevelPackage() {

        MissingModule module = detect("ModuleNotFoundError: No module named 'google.protobuf'")
                .orElseThrow();

        assertEquals("google", module.moduleName());
    }

    @Test
    void knowsModuleAndPackageNamesDiffer() {
        assertEquals("opencv-python", detect("ModuleNotFoundError: No module named 'cv2'")
                .orElseThrow().packageName());
        assertEquals("Pillow", detect("ModuleNotFoundError: No module named 'PIL'")
                .orElseThrow().packageName());
        assertEquals("beautifulsoup4", detect("ModuleNotFoundError: No module named 'bs4'")
                .orElseThrow().packageName());
        assertEquals("PyYAML", detect("ModuleNotFoundError: No module named 'yaml'")
                .orElseThrow().packageName());
        assertEquals("scikit-learn", detect("ModuleNotFoundError: No module named 'sklearn'")
                .orElseThrow().packageName());
    }

    @Test
    void doesNotInstallStdlibModule() {

        assertTrue(detect("ModuleNotFoundError: No module named 'sqlite3'").isEmpty());
        assertTrue(detect("ModuleNotFoundError: No module named 'asyncio'").isEmpty());
    }

    @Test
    void ordinaryScriptErrorIsNotMistakenForDependency() {
        assertTrue(detect("ZeroDivisionError: division by zero").isEmpty());
        assertTrue(detect("SyntaxError: invalid syntax").isEmpty());
        assertTrue(detect("").isEmpty());
        assertTrue(detect(null).isEmpty());
    }

    @Test
    void rejectsUnsafeName() {

        assertTrue(MissingModule.detect(
                "ModuleNotFoundError: No module named '--upgrade'", STDLIB).isEmpty());
        assertTrue(MissingModule.detect(
                "ModuleNotFoundError: No module named '../../etc'", STDLIB).isEmpty());
    }

    @Test
    void takesFirstMention() {
        String stderr = """
                ModuleNotFoundError: No module named 'requests'
                ModuleNotFoundError: No module named 'numpy'
                """;

        assertEquals("requests", detect(stderr).orElseThrow().packageName());
    }
}
