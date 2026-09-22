package com.bebebe.agent.watchdog;

import java.time.Instant;
import java.util.Optional;

public interface HeartbeatSource {

    record Busy(String reason, Instant until) { }

    record InFlight(String what, Optional<String> conversation, Instant startedAt, Instant lastBeat,
                    Optional<Busy> busy) { }

    Optional<InFlight> inFlight();
}
