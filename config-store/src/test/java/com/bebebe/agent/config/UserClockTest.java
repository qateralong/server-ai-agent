package com.bebebe.agent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserClockTest {

    private static final Instant MOMENT = Instant.parse("2026-09-19T14:00:00Z");

    /** The machine the process runs on -- not where the user is. */
    private static final Clock SERVER = Clock.fixed(MOMENT, ZoneId.of("UTC"));

    @TempDir
    Path temp;

    private AppSettings settings(String toml) throws IOException {
        Path file = temp.resolve("agent.toml");
        Files.writeString(file, toml);
        return AppSettings.from(AppConfig.load(file));
    }

    @Test
    void withoutASettingTheMachineZoneIsUsedAndTheInterfaceIsToldSo() throws IOException {
        AppSettings settings = settings("[agent]\n");

        assertFalse(settings.timezoneChosen(), "so that the window and the chat can warn about it");
        assertEquals("", settings.timezoneSetting());
        assertEquals(ZoneId.of("UTC"), UserClock.following(SERVER, settings).getZone(),
                "falls back to the clock it wraps");
    }

    @Test
    void theChosenZoneWinsOverTheMachine() throws IOException {
        AppSettings settings = settings("[agent]\ntimezone = \"Europe/Moscow\"\n");
        Clock clock = UserClock.following(SERVER, settings);

        assertTrue(settings.timezoneChosen());
        assertEquals(ZoneId.of("Europe/Moscow"), clock.getZone());
        assertEquals(MOMENT, clock.instant(), "the instant is still the server's -- only the zone differs");
        assertEquals(17, ZonedDateTime.now(clock).getHour(), "14:00 UTC is 17:00 in Moscow");
    }

    @Test
    void changingTheSettingIsPickedUpWithoutRebuildingTheClock() throws IOException {
        AppSettings settings = settings("[agent]\ntimezone = \"Europe/Moscow\"\n");
        Clock clock = UserClock.following(SERVER, settings);
        assertEquals(17, ZonedDateTime.now(clock).getHour());

        settings.setTimezone("Asia/Tokyo");

        assertEquals(23, ZonedDateTime.now(clock).getHour(), "the same clock object must follow the setting");
    }

    @Test
    void anExplicitZoneArgumentStillOverridesTheSetting() throws IOException {

        AppSettings settings = settings("[agent]\ntimezone = \"Europe/Moscow\"\n");
        Clock clock = UserClock.following(SERVER, settings).withZone(ZoneId.of("Asia/Tokyo"));

        assertEquals(ZoneId.of("Asia/Tokyo"), clock.getZone());
    }

    @Test
    void nonsenseIsRefusedInsteadOfSilentlyFallingBack() throws IOException {
        AppSettings settings = settings("[agent]\n");

        assertThrows(java.time.DateTimeException.class, () -> settings.setTimezone("Марс/Кратер"));
        assertFalse(settings.timezoneChosen(), "a rejected value must not be stored");

        settings.setTimezone("");
        assertFalse(settings.timezoneChosen(), "empty means \"follow the machine\"");
    }

    @Test
    void theZoneIsWrittenToTheFile() throws IOException {
        AppSettings settings = settings("[agent]\n");
        settings.setTimezone("Asia/Tokyo");
        settings.save();

        assertEquals(ZoneId.of("Asia/Tokyo"),
                AppSettings.from(AppConfig.load(settings.file())).zone());
    }
}
