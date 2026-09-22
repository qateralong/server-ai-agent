package com.bebebe.agent.watchdog;

import com.bebebe.agent.config.ConfigSection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class UpdateChecker implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(UpdateChecker.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Config(boolean enabled, String repo, String branch, String githubToken,
                         Duration checkEvery, String apiBase) {

        public static final String SECTION = "updates";

        public static Config from(ConfigSection section) {
            return new Config(
                    section.bool("enabled", true),
                    section.string("repo", ""),
                    section.string("branch", ""),
                    section.string("github_token", ""),
                    Duration.ofHours(Math.max(1, section.integer("check_hours", 24))),
                    section.string("api_base", "https://api.github.com"));
        }
    }

    public record Status(Instant checkedAt, int aheadBy, String latestSha, List<String> newCommits, String error) {

        public boolean hasUpdate() {
            return error == null && aheadBy > 0;
        }

        public String describe() {
            if (error != null) {
                return "check failed: " + error;
            }
            if (aheadBy == 0) {
                return "build is up to date";
            }
            return "new commits available: " + aheadBy
                    + (newCommits.isEmpty() ? "" : " — " + String.join("; ", newCommits));
        }
    }

    private final Config config;
    private final BuildInfo build;
    private final Path stateFile;
    private final HttpClient http;
    private final List<Consumer<Status>> listeners = new CopyOnWriteArrayList<>();
    private volatile Status last;
    private ScheduledExecutorService executor;

    public UpdateChecker(Config config, BuildInfo build, Path stateFile) {
        this.config = config;
        this.build = build;
        this.stateFile = stateFile;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public Optional<Status> last() {
        return Optional.ofNullable(last);
    }

    public void addListener(Consumer<Status> listener) {
        listeners.add(listener);
    }

    public String repoSlug() {
        return config.repo().isBlank() ? build.repoSlug() : config.repo();
    }

    public String branch() {
        return config.branch().isBlank() ? (build.branch().isBlank() ? "master" : build.branch()) : config.branch();
    }

    public synchronized void start() {
        if (!config.enabled()) {
            log.info("Update check disabled in config");
            return;
        }
        if (!build.isKnown()) {
            log.info("Update check skipped: build version unknown (no build-info.properties)");
            return;
        }
        if (repoSlug().isBlank()) {
            log.info("Update check skipped: repository unknown (updates.repo)");
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "update-check");
            t.setDaemon(true);
            return t;
        });
        long period = config.checkEvery().toSeconds();
        executor.scheduleAtFixedRate(this::checkIfDue, 30, period, TimeUnit.SECONDS);
        log.info("Update check: {} ({}), every {} h", repoSlug(), branch(), config.checkEvery().toHours());
    }

    private void checkIfDue() {
        try {
            Optional<Instant> lastCheck = readState().map(n -> n.path("last_check").asText(""))
                    .filter(s -> !s.isEmpty()).map(Instant::parse);
            if (lastCheck.isPresent() && Duration.between(lastCheck.get(), Instant.now()).compareTo(config.checkEvery()) < 0) {
                log.debug("Updates were checked at {} -- too early", lastCheck.get());
                return;
            }
            check();
        } catch (RuntimeException e) {
            log.warn("Update check failed: {}", e.toString());
        }
    }

    public Status check() {
        Status status = fetch();
        last = status;
        writeState(status);
        log.atInfo().addKeyValue("event", "update.check").addKeyValue("ahead_by", status.aheadBy())
                .addKeyValue("error", status.error() == null ? "" : status.error())
                .log("Update check: {}", status.describe());
        for (Consumer<Status> l : listeners) {
            try {
                l.accept(status);
            } catch (RuntimeException e) {
                log.warn("Update listener failed: {}", e.toString());
            }
        }
        return status;
    }

    public boolean shouldNotify(Status status) {
        if (!status.hasUpdate()) {
            return false;
        }
        String notified = readState().map(n -> n.path("notified_sha").asText("")).orElse("");
        if (notified.equals(status.latestSha())) {
            return false;
        }
        ObjectNode node = readState().orElse(JSON.createObjectNode());
        node.put("notified_sha", status.latestSha());
        writeStateNode(node);
        return true;
    }

    private Status fetch() {
        String url = config.apiBase() + "/repos/" + repoSlug() + "/compare/" + build.commit() + "..." + branch();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "bebebe-agent")
                .GET();
        resolveToken().ifPresent(t -> request.header("Authorization", "Bearer " + t));
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                String hint = switch (response.statusCode()) {
                    case 401, 403 -> " (token required: updates.github_token, $GITHUB_TOKEN or gh auth login)";
                    case 404 -> " (private repository without a token, or commit " + build.shortCommit() + " not pushed)";
                    default -> "";
                };
                return new Status(Instant.now(), 0, "", List.of(), "HTTP " + response.statusCode() + hint);
            }
            JsonNode node = JSON.readTree(response.body());
            int ahead = node.path("ahead_by").asInt(0);
            JsonNode commits = node.path("commits");
            java.util.ArrayList<String> messages = new java.util.ArrayList<>();
            String latest = "";
            for (JsonNode c : commits) {
                latest = c.path("sha").asText("");
                String msg = c.path("commit").path("message").asText("").lines().findFirst().orElse("");
                if (messages.size() < 3 && !msg.isBlank()) {
                    messages.add(msg.length() > 60 ? msg.substring(0, 59) + "…" : msg);
                }
            }
            return new Status(Instant.now(), ahead, latest, List.copyOf(messages), null);
        } catch (IOException e) {
            return new Status(Instant.now(), 0, "", List.of(), "network: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Status(Instant.now(), 0, "", List.of(), "interrupted");
        }
    }

    Optional<String> resolveToken() {
        if (!config.githubToken().isBlank()) {
            return Optional.of(config.githubToken().strip());
        }
        String env = System.getenv("GITHUB_TOKEN");
        if (env != null && !env.isBlank()) {
            return Optional.of(env.strip());
        }
        try {
            Process p = new ProcessBuilder("gh", "auth", "token").redirectError(ProcessBuilder.Redirect.DISCARD).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0 && !out.isBlank()) {
                return Optional.of(out);
            }
        } catch (IOException e) {
            log.debug("gh unavailable: {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Optional.empty();
    }

    private Optional<ObjectNode> readState() {
        try {
            if (!Files.exists(stateFile)) {
                return Optional.empty();
            }
            JsonNode node = JSON.readTree(Files.readString(stateFile, StandardCharsets.UTF_8));
            return node instanceof ObjectNode o ? Optional.of(o) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private void writeState(Status status) {
        ObjectNode node = readState().orElse(JSON.createObjectNode());
        node.put("last_check", status.checkedAt().toString());
        node.put("last_ahead_by", status.aheadBy());
        writeStateNode(node);
    }

    private void writeStateNode(ObjectNode node) {
        try {
            if (stateFile.getParent() != null) {
                Files.createDirectories(stateFile.getParent());
            }
            Files.writeString(stateFile, node.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.debug("Update check state not saved: {}", e.getMessage());
        }
    }

    @Override
    public synchronized void close() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
