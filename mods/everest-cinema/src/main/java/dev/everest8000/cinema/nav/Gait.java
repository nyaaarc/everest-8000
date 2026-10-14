package dev.everest8000.cinema.nav;

/**
 * How a walker moves. pace scales the walk input (1 = a normal player walk, 4.3 m/s); the rest shapes the motion so it
 * doesn't look like a robot on rails.
 *
 * @param pace              forward input at full stride on gentle ground (0.1..1.3)
 * @param turnRateDeg       max body turn, degrees per second
 * @param accelSec          seconds to go from standing to full pace (and back)
 * @param wander            lateral drift amplitude in blocks (low frequency, kept on walkable ground)
 * @param wanderWavelength  blocks of travel per wander swing (10..30 looks natural)
 * @param lookahead         pure-pursuit lookahead distance in blocks
 * @param uphillSlowdown    fraction of pace lost on a 45-degree climb (steps up dip a little more)
 * @param snowSlowdown      fraction of pace lost in deep snow
 * @param breatherEvery     seconds of easy walking between breathers [min, max]; null for none. Effort makes them come
 *                          sooner: climbing (breatherSlope) and altitude (breatherAltitude)
 * @param breatherFor       breather length [min, max] seconds
 * @param breatherSlope     extra breather rate per unit of uphill grade (2 = three times as often on a 45-degree climb)
 * @param breatherAltitude  piecewise-linear [y, rate multiplier] pairs, sorted by y; null = 1 everywhere
 * @param paceAltitude      piecewise-linear [y, pace multiplier] pairs (a slower trudge higher up); null = 1
 * @param idleLook          occasional glances around while walking or standing
 */
public record Gait(double pace, double turnRateDeg, double accelSec, double wander, double wanderWavelength, double lookahead,
                   double uphillSlowdown, double snowSlowdown, double[] breatherEvery, double[] breatherFor, double breatherSlope,
                   double[][] breatherAltitude, double[][] paceAltitude, boolean idleLook) {
    public static final Gait DEFAULT = new Gait(0.75, 150, 0.6, 1.2, 18, 2.2, 0.45, 0.35, null, new double[]{2.0, 5.0}, 1.0, null, null, true);

    /** Pace multiplier at height y. */
    public double paceFactor(double y) {
        return curve(paceAltitude, y);
    }

    /** Breather rate multiplier at height y. */
    public double altitudeFactor(double y) {
        return curve(breatherAltitude, y);
    }

    private static double curve(double[][] c, double y) {
        if (c == null || c.length == 0) return 1;
        if (y <= c[0][0]) return c[0][1];
        for (int i = 1; i < c.length; i++) {
            double[] a = c[i - 1], b = c[i];
            if (y <= b[0]) return a[1] + (b[1] - a[1]) * (y - a[0]) / Math.max(1e-9, b[0] - a[0]);
        }
        return c[c.length - 1][1];
    }
}
