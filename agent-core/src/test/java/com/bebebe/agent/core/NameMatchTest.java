package com.bebebe.agent.core;

import com.bebebe.agent.memory.Entity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NameMatchTest {

    private static Entity person(long id, String name, String... aliases) {
        return new Entity(id, name, List.of(aliases), "друг", "", Instant.EPOCH);
    }

    @Test
    void aTypoInALongNameIsRecognised() {
        List<Entity> known = List.of(person(1, "Александр"));

        assertEquals(1, NameMatch.fuzzy("Алексанрд", known).orElseThrow().id(),
                "two letters swapped is still the same person");
    }

    @Test
    void latinLookAlikesAreFolded() {
        List<Entity> known = List.of(person(1, "Саша"));

        // "Cawa" typed with the keyboard in the wrong layout: c, a look like с, а.
        assertTrue(NameMatch.close(NameMatch.normalize("Саша"), NameMatch.normalize("Сaшa")),
                "a Latin 'a' inside a Cyrillic name is a typo, not a different person");
        assertTrue(NameMatch.fuzzy("Сaшa", known).isPresent());
    }

    @Test
    void caseAndYoAreIgnored() {
        List<Entity> known = List.of(person(1, "Алёна"));

        assertTrue(NameMatch.fuzzy("алена", known).isPresent());
    }

    @Test
    void anAliasCountsAsMuchAsTheName() {
        List<Entity> known = List.of(person(1, "Саша", "Александр"));

        assertEquals(1, NameMatch.fuzzy("Алексанрд", known).orElseThrow().id(),
                "the alias is as good an anchor as the canonical name");
    }

    @Test
    void fiveLetterNamesAreStillTooShortToGuessAt() {
        List<Entity> known = List.of(person(1, "Дарья"));

        assertTrue(NameMatch.fuzzy("Марья", known).isEmpty(),
                "one letter apart at five letters is still two different people");
    }

    @Test
    void shortNamesOneLetterApartAreNeverMerged() {
        List<Entity> known = List.of(person(1, "Саша"));

        assertTrue(NameMatch.fuzzy("Маша", known).isEmpty(),
                "Маша is a different person and merging them would be silent and irreversible");
        assertTrue(NameMatch.fuzzy("Даша", known).isEmpty());
        assertTrue(NameMatch.fuzzy("Паша", known).isEmpty());
    }

    @Test
    void aNameCloseToTwoKnownPeopleIsLeftToTheUser() {
        List<Entity> known = List.of(person(1, "Анастасия"), person(2, "Анастасья"));

        assertTrue(NameMatch.fuzzy("Анастасиа", known).isEmpty(),
                "ambiguity must fall through to the question, not pick one at random");
    }

    @Test
    void genuinelyDifferentNamesDoNotMatch() {
        List<Entity> known = List.of(person(1, "Александр"));

        assertTrue(NameMatch.fuzzy("Владимир", known).isEmpty());
        assertFalse(NameMatch.close("александр", "алексей"));
    }

    @Test
    void theDistanceGivesUpEarlyInsteadOfScanningEverything() {

        assertEquals(2, NameMatch.distance("абвгде", "жзиклм", 1),
                "over the limit it returns limit + 1 rather than the true distance");
    }
}
