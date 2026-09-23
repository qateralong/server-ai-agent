package com.bebebe.agent.core;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The model computes the firing moment itself, so the only thing that tells it which "now" and
 * which offset to count from is this block. It used to be built from the machine's zone.
 */
class PromptTimeZoneTest {

    private static final Instant MOMENT = Instant.parse("2026-09-19T14:00:00Z");

    @Test
    void theNowBlockIsWrittenInTheUsersZoneNotTheProcessZone() {
        String forMoscow = DecisionProtocol.nowBlock(Clock.fixed(MOMENT, ZoneId.of("Europe/Moscow")));

        assertTrue(forMoscow.contains("17:00"), "14:00 UTC is 17:00 in Moscow: " + forMoscow);
        assertTrue(forMoscow.contains("Europe/Moscow"), forMoscow);
        assertTrue(forMoscow.contains("+03:00"), "the offset is what set_reminder echoes back: " + forMoscow);
    }

    @Test
    void thesameInstantReadsDifferentlyInAnotherZone() {
        String forTokyo = DecisionProtocol.nowBlock(Clock.fixed(MOMENT, ZoneId.of("Asia/Tokyo")));

        assertTrue(forTokyo.contains("23:00"), forTokyo);
        assertTrue(forTokyo.contains("+09:00"), forTokyo);
    }
}
