package dev.everest8000.expedition.time;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DayClockTest {
    @Test
    void keepsTheFractionBetweenTicks() {
        DayClock clock = new DayClock();
        long time = 6000;
        double expected = 6000;
        for (int i = 0; i < 1200; i++) {
            expected += DayPace.rate(expected);
            time = clock.advance(time);
        }
        assertEquals(expected, clock.exact(time), 1e-6, "a minute at noon");
        assertEquals((long) Math.floor(expected), time);
        assertEquals(6000 + 1200 / DayPace.DAY_SLOWNESS, expected, 1, "about 290 day ticks a minute at noon");
    }

    /** /time set and sleeping through the night both write the world's time directly; the clock follows. */
    @Test
    void followsTimeSetAndSleep() {
        DayClock clock = new DayClock();
        long time = clock.advance(1000);
        assertTrue(clock.consumeJump(), "first start counts as a jump");
        time = clock.advance(time);
        assertFalse(clock.consumeJump(), "steady running is no jump");

        long set = 18000;
        time = clock.advance(set);
        assertTrue(clock.consumeJump(), "/time set 18000");
        assertEquals(set + DayPace.rate(set), clock.exact(time), 1e-9);

        // Everyone slept: vanilla jumps to the next morning, a whole multiple of 24000.
        long morning = (time + 24000) - (time + 24000) % 24000;
        time = clock.advance(morning);
        assertTrue(clock.consumeJump());
        assertEquals(morning, time, "picks up at the morning (the first step is a fraction)");
        assertTrue(DayPace.sunUp(clock.exact(time)));
    }

    @Test
    void reportsOutsideTimeAsIs() {
        DayClock clock = new DayClock();
        long time = clock.advance(500);
        assertEquals(500 + DayPace.rate(500), clock.exact(time), 1e-9);
        assertEquals(9999, clock.exact(9999), 0, "a time this clock didn't write is reported as it is");
    }
}
