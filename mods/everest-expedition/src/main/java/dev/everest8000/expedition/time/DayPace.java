package dev.everest8000.expedition.time;

/**
 * How fast the day runs: one full day takes an hour of real time instead of vanilla's 20 minutes, with the sun up for
 * 40 minutes and down for 20.
 *
 * <p>The day is cut into four stretches at the edges of the sunrise and sunset glow (where vanilla's sky starts and
 * stops turning orange): the day, dusk, the night and dawn. Dusk and dawn run at a pace that would cross the glow in
 * {@link #TWILIGHT_REAL_TICKS} (3.5 minutes), so sunsets don't drag on for a quarter of an hour; the day and the night
 * take whatever is left of the 40 sun-up and 20 sun-down minutes. Where two stretches meet, the pace eases from one to
 * the other over {@link #BLEND} day ticks either side, so the sun never visibly changes speed. The ease is symmetric
 * about each edge, so the sun-up and sun-down totals come out exact; the glow itself, whose edges are mid-ease, takes
 * about 3.7 minutes.
 *
 * <p>Everything here is in day ticks (vanilla's 0..24000 time of day, 0 = just after sunrise, 6000 = noon) and
 * real ticks (20 a second). Kept free of world access so the timing can be checked in tests.
 */
public final class DayPace {
    public static final int DAY_TICKS = 24000;
    /** One full day: an hour. */
    public static final int CYCLE_REAL_TICKS = 72000;
    /** The sun is above the horizon for this long (40 minutes), and below it for the rest (20 minutes). */
    public static final int SUN_UP_REAL_TICKS = 48000;
    public static final int SUN_DOWN_REAL_TICKS = CYCLE_REAL_TICKS - SUN_UP_REAL_TICKS;
    /** Dusk and dawn pace: the glow would take this long at it (the eases at its edges add about 10 seconds). */
    public static final int TWILIGHT_REAL_TICKS = 4200;

    /** Where vanilla's sun crosses the horizon (its celestial angle is 0.75 at sunrise, 0.25 at sunset). */
    public static final double SUNRISE = 23215;
    public static final double SUNSET = 12785;
    /** Vanilla's sunrise/sunset sky colour shows while cos(celestial angle) is within ±0.4: these are its edges. */
    public static final double DUSK_START = 11270;
    public static final double DUSK_END = 14212;
    public static final double DAWN_START = 21788;
    public static final double DAWN_END = 730;
    /** Half-width, in day ticks, of the ease between two paces. */
    static final double BLEND = 300;

    /** Real ticks per day tick in each stretch. */
    static final double TWILIGHT_SLOWNESS;
    static final double DAY_SLOWNESS;
    static final double NIGHT_SLOWNESS;

    /** Stretch edges in day order, starting from the end of dawn, and the slowness from each edge to the next. */
    private static final double[] EDGES;
    private static final double[] SLOWNESS;

    static {
        double twilightLength = DUSK_END - DUSK_START;
        TWILIGHT_SLOWNESS = TWILIGHT_REAL_TICKS / twilightLength;
        double twilightSunUp = (DAWN_END + DAY_TICKS - SUNRISE) + (SUNSET - DUSK_START);
        double twilightSunDown = (DUSK_END - SUNSET) + (SUNRISE - DAWN_START);
        DAY_SLOWNESS = (SUN_UP_REAL_TICKS - twilightSunUp * TWILIGHT_SLOWNESS) / (DUSK_START - DAWN_END);
        NIGHT_SLOWNESS = (SUN_DOWN_REAL_TICKS - twilightSunDown * TWILIGHT_SLOWNESS) / (DAWN_START - DUSK_END);
        EDGES = new double[]{DAWN_END, DUSK_START, DUSK_END, DAWN_START};
        SLOWNESS = new double[]{DAY_SLOWNESS, TWILIGHT_SLOWNESS, NIGHT_SLOWNESS, TWILIGHT_SLOWNESS};
    }

    private DayPace() {}

    /** Day ticks that pass in one real tick at this moment. Vanilla is 1; here it ranges about 0.24 (midday) to 0.70 (dusk). */
    public static double rate(double dayTime) {
        return 1.0 / slowness(dayTime);
    }

    /** Real ticks per day tick at this moment. */
    static double slowness(double dayTime) {
        double t = wrap(dayTime);
        int n = EDGES.length;
        for (int i = 0; i < n; i++) {
            double edge = EDGES[i];
            double before = SLOWNESS[(i + n - 1) % n];
            double after = SLOWNESS[i];
            double d = wrapSigned(t - edge);
            if (Math.abs(d) < BLEND) return before + (after - before) * smoothstep((d + BLEND) / (2 * BLEND));
        }
        // Outside every ease: the stretch whose start edge is the last one at or before t.
        for (int i = n - 1; i >= 0; i--) {
            if (t >= EDGES[i]) return SLOWNESS[i];
        }
        return SLOWNESS[n - 1];
    }

    /** Whether the sun is above the horizon at this moment. */
    public static boolean sunUp(double dayTime) {
        double t = wrap(dayTime);
        return t >= SUNRISE || t < SUNSET;
    }

    /**
     * Vanilla's celestial angle (0 = noon, 0.25 = sunset, 0.5 = midnight) for a fractional day time: the same curve as
     * {@code DimensionType.timeOfDay}, which only takes whole ticks.
     */
    public static float timeOfDay(double dayTime) {
        double d = frac(dayTime / DAY_TICKS - 0.25);
        double e = 0.5 - Math.cos(d * Math.PI) / 2.0;
        return (float) (d * 2.0 + e) / 3.0F;
    }

    /** Time of day in 0..24000. */
    public static double wrap(double dayTime) {
        double t = dayTime % DAY_TICKS;
        return t < 0 ? t + DAY_TICKS : t;
    }

    /** A day-time difference brought into -12000..12000. */
    private static double wrapSigned(double d) {
        double t = wrap(d);
        return t >= DAY_TICKS / 2.0 ? t - DAY_TICKS : t;
    }

    private static double frac(double x) {
        return x - Math.floor(x);
    }

    private static double smoothstep(double x) {
        return x * x * (3 - 2 * x);
    }
}
