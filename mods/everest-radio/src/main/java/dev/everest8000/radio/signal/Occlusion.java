package dev.everest8000.radio.signal;

import dev.everest8000.radio.RadioConfig;

/**
 * Estimates how much terrain lies between two radios: samples the straight line between them and checks each point
 * against the ground height (the heightmap) at that column. The result is the fraction of samples below ground.
 */
public final class Occlusion {
    private Occlusion() {
    }

    /** Ground height lookup: the lowest free Y above the terrain at a column, or {@link #UNKNOWN}. */
    @FunctionalInterface
    public interface Ground {
        int UNKNOWN = Integer.MIN_VALUE;

        int groundY(int x, int z);
    }

    /**
     * @param fraction blocked samples / known samples (0 when nothing is known yet)
     * @param known    samples whose ground height was available
     * @param samples  samples taken
     */
    public record Result(double fraction, int known, int samples) {
        public static final Result CLEAR = new Result(0, 0, 0);
    }

    public static Result between(double ax, double ay, double az, double bx, double by, double bz,
                                 Ground ground, RadioConfig config) {
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double skip = config.get(RadioConfig.ENDPOINT_SKIP);
        double span = length - 2 * skip;
        if (span <= 0) {
            return Result.CLEAR;
        }
        int samples = (int) Math.max(1, Math.min(config.getInt(RadioConfig.MAX_SAMPLES),
                Math.ceil(span / config.get(RadioConfig.SAMPLE_SPACING))));
        int known = 0, blocked = 0;
        for (int k = 0; k < samples; k++) {
            double t = (skip + (k + 0.5) * span / samples) / length;
            double x = ax + dx * t, y = ay + dy * t, z = az + dz * t;
            int g = ground.groundY((int) Math.floor(x), (int) Math.floor(z));
            if (g == Ground.UNKNOWN) {
                continue;
            }
            known++;
            if (y < g) {
                blocked++;
            }
        }
        return new Result(known == 0 ? 0 : (double) blocked / known, known, samples);
    }
}
