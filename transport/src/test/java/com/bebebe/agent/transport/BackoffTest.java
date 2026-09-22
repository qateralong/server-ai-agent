package com.bebebe.agent.transport;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackoffTest {

    private static final RandomGenerator MIDDLE = new RandomGenerator() {
        @Override
        public long nextLong() {
            return 0;
        }

        @Override
        public double nextDouble() {
            return 0.5;
        }
    };

    @Test
    void pauseDoublesUpToCeilingAndResets() {
        Backoff backoff = new Backoff(Duration.ofSeconds(1), Duration.ofSeconds(10), MIDDLE);

        assertEquals(Duration.ofSeconds(1), backoff.next());
        assertEquals(Duration.ofSeconds(2), backoff.next());
        assertEquals(Duration.ofSeconds(4), backoff.next());
        assertEquals(Duration.ofSeconds(8), backoff.next());
        assertEquals(Duration.ofSeconds(10), backoff.next(), "ceiling");
        assertEquals(Duration.ofSeconds(10), backoff.next(), "still the ceiling");

        backoff.reset();
        assertEquals(Duration.ofSeconds(1), backoff.next());
    }

    @Test
    void jitterWithinTwentyPercent() {
        Backoff backoff = new Backoff(Duration.ofSeconds(10), Duration.ofSeconds(10));
        for (int i = 0; i < 200; i++) {
            long ms = backoff.next().toMillis();
            assertTrue(ms >= 8000 && ms <= 12000, "pause " + ms + " ms outside 10 s ±20 %");
        }
    }

    @Test
    void meaninglessBoundsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Backoff(Duration.ZERO, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new Backoff(Duration.ofSeconds(5), Duration.ofSeconds(1)));
    }
}
