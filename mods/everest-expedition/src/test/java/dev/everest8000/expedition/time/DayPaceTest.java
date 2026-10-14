package dev.everest8000.expedition.time;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DayPaceTest {
    /** Steps the clock tick by tick from {@code from} until it has passed {@code to}, returning the real ticks taken. */
    private static long realTicks(double from, double to) {
        DayClock clock = new DayClock();
        long time = (long) from;
        long ticks = 0;
        while (clock.exact(time) < to) {
            time = clock.advance(time);
            ticks++;
        }
        return ticks;
    }

    @Test
    void fortyMinuteDayTwentyMinuteNight() {
        long sunUp = realTicks(DayPace.SUNRISE, DayPace.SUNSET + DayPace.DAY_TICKS);
        long sunDown = realTicks(DayPace.SUNSET, DayPace.SUNRISE);
        long cycle = realTicks(0, DayPace.DAY_TICKS);
        long morningToEvening = realTicks(0, 12000);
        System.out.printf("sun up %.2f min, sun down %.2f min, full day %.2f min, day ticks 0-12000 %.2f min%n",
                sunUp / 1200.0, sunDown / 1200.0, cycle / 1200.0, morningToEvening / 1200.0);
        System.out.printf("pace (day ticks per tick): day %.3f, twilight %.3f, night %.3f%n",
                1 / DayPace.DAY_SLOWNESS, 1 / DayPace.TWILIGHT_SLOWNESS, 1 / DayPace.NIGHT_SLOWNESS);
        assertEquals(DayPace.SUN_UP_REAL_TICKS, sunUp, 3, "sun up for 40 minutes");
        assertEquals(DayPace.SUN_DOWN_REAL_TICKS, sunDown, 3, "sun down for 20 minutes");
        assertEquals(DayPace.CYCLE_REAL_TICKS, cycle, 3, "an hour a day");
    }

    /** Sunset and sunrise glow last a few minutes each: long enough to watch, short of vanilla's quarter of the day. */
    @Test
    void twilightsTakeAboutThreeAndAHalfMinutes() {
        long dusk = realTicks(DayPace.DUSK_START, DayPace.DUSK_END);
        long dawn = realTicks(DayPace.DAWN_START, DayPace.DAWN_END + DayPace.DAY_TICKS);
        System.out.printf("dusk %.2f min, dawn %.2f min%n", dusk / 1200.0, dawn / 1200.0);
        assertEquals(dusk, dawn, 3, "dusk and dawn mirror each other");
        assertTrue(dusk > 3.5 * 1200 && dusk < 4 * 1200, "dusk " + dusk / 1200.0 + " min");
    }

    /** The pace eases between stretches: no tick-to-tick change bigger than a sliver, and never stopping. */
    @Test
    void paceChangesSmoothly() {
        double maxChange = 0;
        double min = Double.MAX_VALUE;
        double max = 0;
        for (double t = 0; t < DayPace.DAY_TICKS; t += 0.25) {
            double r = DayPace.rate(t);
            min = Math.min(min, r);
            max = Math.max(max, r);
            maxChange = Math.max(maxChange, Math.abs(DayPace.rate(t + 0.25) - r));
        }
        System.out.printf("rate %.3f..%.3f, biggest change per quarter day tick %.5f%n", min, max, maxChange);
        assertTrue(min > 0.2 && max < 1, "rate " + min + ".." + max);
        assertTrue(maxChange < 0.001, "pace jumps by " + maxChange);
        assertEquals(DayPace.rate(-1000), DayPace.rate(DayPace.DAY_TICKS - 1000), 1e-12, "wraps around midnight");
        assertEquals(DayPace.rate(5000), DayPace.rate(5000 + 37L * DayPace.DAY_TICKS), 1e-12, "same pace every day");
    }

    /** The fractional sun angle matches vanilla's at whole ticks. */
    @Test
    void timeOfDayMatchesVanilla() {
        for (long t : new long[]{0, 1000, 6000, 12000, 12785, 18000, 23215, 23999, 24000 * 5 + 300}) {
            double d = frac(t / 24000.0 - 0.25);
            float vanilla = (float) (d * 2.0 + (0.5 - Math.cos(d * Math.PI) / 2.0)) / 3.0F;
            assertEquals(vanilla, DayPace.timeOfDay(t), 1e-6, "at " + t);
        }
        assertEquals(0.75f, DayPace.timeOfDay(DayPace.SUNRISE), 1e-4, "sun on the horizon at sunrise");
        assertEquals(0.25f, DayPace.timeOfDay(DayPace.SUNSET), 1e-4, "and at sunset");
        assertTrue(DayPace.sunUp(6000) && !DayPace.sunUp(18000) && DayPace.sunUp(23500) && !DayPace.sunUp(13000));
    }

    private static double frac(double x) {
        return x - Math.floor(x);
    }
}
