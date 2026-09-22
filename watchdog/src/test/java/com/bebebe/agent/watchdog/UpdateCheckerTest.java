package com.bebebe.agent.watchdog;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateCheckerTest {

    private static final BuildInfo BUILD = new BuildInfo("5af699bc8ec3", "5af699b", "2026-09-19T01:20:47+05:00",
            "master", false, "git@github.com:qateralong/Bebebe.git", "");

    @TempDir
    Path temp;

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String body = """
            {"status":"ahead","ahead_by":2,"commits":[
              {"sha":"aaa111","commit":{"message":"Планировщик\\n\\nподробности"}},
              {"sha":"bbb222","commit":{"message":"Заметки и списки"}}]}""";

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestURI().getPath() + " auth=" + exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private UpdateChecker checker(String token) {
        UpdateChecker.Config cfg = new UpdateChecker.Config(true, "", "", token, Duration.ofHours(24),
                "http://127.0.0.1:" + server.getAddress().getPort());
        return new UpdateChecker(cfg, BUILD, temp.resolve("state.json"));
    }

    @Test
    void repositoryAndBranchComeFromBuildAndCommitFromPath() {
        UpdateChecker checker = checker("tok");

        UpdateChecker.Status s = checker.check();

        assertEquals("/repos/qateralong/Bebebe/compare/5af699bc8ec3...master auth=Bearer tok", requests.getFirst());
        assertTrue(s.hasUpdate());
        assertEquals(2, s.aheadBy());
        assertEquals("bbb222", s.latestSha());
        assertEquals(List.of("Планировщик", "Заметки и списки"), s.newCommits());
        assertTrue(s.describe().contains("new commits available: 2"), s.describe());
    }

    @Test
    void notifyOncePerCommit() {
        UpdateChecker checker = checker("tok");
        UpdateChecker.Status s = checker.check();

        assertTrue(checker.shouldNotify(s), "first time -- notify");
        assertFalse(checker.shouldNotify(s), "same commit second time -- stay silent");
        body = body.replace("bbb222", "ccc333");
        assertTrue(checker.shouldNotify(checker.check()), "new commit -- notify again");
    }

    @Test
    void upToDateBuildDoesNotNotify() {
        body = "{\"status\":\"identical\",\"ahead_by\":0,\"commits\":[]}";
        UpdateChecker checker = checker("tok");

        UpdateChecker.Status s = checker.check();

        assertFalse(s.hasUpdate());
        assertEquals("build is up to date", s.describe());
        assertFalse(checker.shouldNotify(s));
    }

    @Test
    void apiErrorIsStatusWithHintNotException() throws IOException {
        status = 404;
        body = "{\"message\":\"Not Found\"}";

        UpdateChecker.Status s = checker("").check();

        assertNotNull(s.error());
        assertTrue(s.error().contains("HTTP 404"), s.error());
        assertTrue(s.describe().contains("check failed"));
        assertTrue(Files.readString(temp.resolve("state.json")).contains("last_check"), "check time is recorded even on error");
    }

    @Test
    void slugFromSshAndHttps() {
        assertEquals("qateralong/Bebebe", BUILD.repoSlug());
        assertEquals("qateralong/Bebebe", new BuildInfo("x", "x", "", "master", false,
                "https://github.com/qateralong/Bebebe.git", "").repoSlug());
        assertEquals("", BuildInfo.unknown().repoSlug());
        assertTrue(BuildInfo.unknown().describe().contains("unknown"));
    }
}
