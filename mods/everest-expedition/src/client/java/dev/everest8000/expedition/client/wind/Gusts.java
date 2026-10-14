package dev.everest8000.expedition.client.wind;

/**
 * The wind's gustiness as a field over the ground: blobs of stronger and weaker air, about a hundred blocks across,
 * carried downwind at about the wind's speed. Everything that shows the wind reads it at its own position, so a
 * gust is seen coming (snow lifting off upwind), then felt (the push, the roar, the buffet), then seen leaving.
 *
 * <p>A multiplier on the mean wind, around 1: bursts reach about 1.6, lulls about 0.6. The field's drift is
 * integrated tick by tick, so a change in wind speed or direction bends it smoothly instead of jumping it.
 */
public final class Gusts {
    /** Gust size in blocks. */
    private static final double SIZE = 120.0;

    private static double travelX;
    private static double travelZ;
    private static double lastTravelX;
    private static double lastTravelZ;
    private static float storminess;

    private Gusts() {}

    /** Carries the field one tick downwind. */
    public static void tick(float windX, float windZ, float kmh, float stormLevel) {
        lastTravelX = travelX;
        lastTravelZ = travelZ;
        float speed = blocksPerTick(kmh) * 0.8f;
        travelX += windX * speed;
        travelZ += windZ * speed;
        storminess = stormLevel;
    }

    /**
     * Gust multiplier at a position.
     *
     * @param ticks game time in ticks (with the partial tick for per-frame use); sets how the gusts evolve
     * @param partial 0..1 between the last tick's drift and this one's
     */
    public static float at(double x, double z, double ticks, float partial) {
        double tx = lastTravelX + (travelX - lastTravelX) * partial;
        double tz = lastTravelZ + (travelZ - lastTravelZ) * partial;
        double u = (x - tx) / SIZE;
        double v = (z - tz) / SIZE;
        double w = ticks / 260.0;
        double n = Noise.at(u, v, w) + 0.5 * Noise.at(u * 2.1 + 17.3, v * 2.1 - 4.1, w * 1.7 + 3.0);
        n /= 1.1;
        float amount = 0.33f + 0.17f * storminess;
        // Bursts are sharper and stronger than lulls are deep.
        float shape = n > 0 ? (float) Math.pow(n, 1.25) * 1.7f : (float) n * 0.8f;
        // A slow swell over a minute or two on top.
        double swell = 0.10 * Math.sin(ticks * 0.0021) * Math.sin(ticks * 0.0013 + 1.0);
        return (float) Math.max(0.45, Math.min(1.9, 1.0 + amount * shape + swell));
    }

    /** How fast snow, gusts and texture should travel for a wind speed, in blocks per tick. */
    public static float blocksPerTick(float kmh) {
        return Math.min(2.4f, kmh / 100f);
    }

    /** Smooth 3D gradient noise, roughly -1..1. */
    static final class Noise {
        private static final int[] P = new int[512];

        static {
            java.util.Random r = new java.util.Random(8849);
            int[] p = new int[256];
            for (int i = 0; i < 256; i++) p[i] = i;
            for (int i = 255; i > 0; i--) {
                int j = r.nextInt(i + 1);
                int t = p[i];
                p[i] = p[j];
                p[j] = t;
            }
            for (int i = 0; i < 512; i++) P[i] = p[i & 255];
        }

        private Noise() {}

        static double at(double x, double y, double z) {
            int xi = (int) Math.floor(x), yi = (int) Math.floor(y), zi = (int) Math.floor(z);
            double xf = x - xi, yf = y - yi, zf = z - zi;
            int X = xi & 255, Y = yi & 255, Z = zi & 255;
            double u = fade(xf), v = fade(yf), w = fade(zf);
            int a = P[X] + Y, aa = P[a] + Z, ab = P[a + 1] + Z, b = P[X + 1] + Y, ba = P[b] + Z, bb = P[b + 1] + Z;
            double r = lerp(w,
                    lerp(v, lerp(u, grad(P[aa], xf, yf, zf), grad(P[ba], xf - 1, yf, zf)),
                            lerp(u, grad(P[ab], xf, yf - 1, zf), grad(P[bb], xf - 1, yf - 1, zf))),
                    lerp(v, lerp(u, grad(P[aa + 1], xf, yf, zf - 1), grad(P[ba + 1], xf - 1, yf, zf - 1)),
                            lerp(u, grad(P[ab + 1], xf, yf - 1, zf - 1), grad(P[bb + 1], xf - 1, yf - 1, zf - 1))));
            return r * 1.4;
        }

        private static double fade(double t) {
            return t * t * t * (t * (t * 6 - 15) + 10);
        }

        private static double lerp(double t, double a, double b) {
            return a + t * (b - a);
        }

        private static double grad(int hash, double x, double y, double z) {
            int h = hash & 15;
            double u = h < 8 ? x : y;
            double v = h < 4 ? y : h == 12 || h == 14 ? x : z;
            return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
        }
    }
}
