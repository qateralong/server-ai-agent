package com.bebebe.agent.watchdog;

import com.bebebe.agent.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WatchdogTest {

    private static final Instant T0 = Instant.parse("2026-09-19T12:00:00Z");
    private static final WatchdogConfig CONFIG = new WatchdogConfig(true, Duration.ofSeconds(10), Duration.ofSeconds(90));

    private final AtomicReference<HeartbeatSource.InFlight> state = new AtomicReference<>();
    private final List<String> restarts = new ArrayList<>();
    private final List<String> alerts = new ArrayList<>();
    private final Watchdog watchdog = new Watchdog(CONFIG, () -> Optional.ofNullable(state.get()),
            reason -> {
                restarts.add(reason);
                return Optional.of("TELEGRAM:7");
            },
            (conv, text) -> alerts.add(conv.orElse("-") + " | " + text));

    private void inFlight(Instant lastBeat, HeartbeatSource.Busy busy) {
        state.set(new HeartbeatSource.InFlight("сколько файлов в /etc?", Optional.of("TELEGRAM:7"),
                T0, lastBeat, Optional.ofNullable(busy)));
    }

    @Test
    void idleIsNotHang() {
        state.set(null);

        assertFalse(watchdog.check(T0.plus(Duration.ofHours(5))));
        assertTrue(restarts.isEmpty());
    }

    @Test
    void freshHeartbeatIsLeftAlone() {
        inFlight(T0, null);

        assertFalse(watchdog.check(T0.plusSeconds(60)));
    }

    @Test
    void legitimatelyBusySilenceDoesNotCount() {

        inFlight(T0, new HeartbeatSource.Busy("script", T0.plusSeconds(200)));

        assertFalse(watchdog.check(T0.plusSeconds(150)));
        assertTrue(restarts.isEmpty(), "no restart while the signal is alive");
    }

    @Test
    void expiredSignalAndSilenceIsConfirmedHang() {
        inFlight(T0, new HeartbeatSource.Busy("script", T0.plusSeconds(100)));

        assertTrue(watchdog.check(T0.plusSeconds(200)));

        assertEquals(1, restarts.size());
        assertTrue(restarts.getFirst().contains("signal «script» expired"), restarts.getFirst());
        assertEquals(1, watchdog.restarts());
        assertEquals(1, alerts.size());
        assertTrue(alerts.getFirst().startsWith("TELEGRAM:7 | 🐶"), alerts.getFirst());
        assertTrue(alerts.getFirst().contains("сколько файлов"), alerts.getFirst());
    }

    @Test
    void silenceWithoutSignalIsHang() {
        inFlight(T0, null);

        assertFalse(watchdog.check(T0.plusSeconds(89)), "still within the timeout");
        assertTrue(watchdog.check(T0.plusSeconds(91)));
        assertEquals(1, restarts.size());
    }

    @Test
    void configChecksConsistency() {
        assertThrows(IllegalArgumentException.class, () -> WatchdogConfig.from(AppConfig.fromToml("""
                [watchdog]
                check_seconds = 30
                hang_timeout_seconds = 10
                """).section(WatchdogConfig.SECTION)));
        WatchdogConfig cfg = WatchdogConfig.from(AppConfig.fromToml("").section(WatchdogConfig.SECTION));
        assertEquals(Duration.ofSeconds(90), cfg.hangTimeout());
        assertTrue(cfg.enabled());
    }
}
