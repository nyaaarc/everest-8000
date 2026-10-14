package dev.everest8000.cinema.nav;

/** Deterministic smooth 1D value noise in [-1, 1] (seeded), for wander and idle glances. */
public final class Noise {
    private final long seed;

    public Noise(long seed) {
        this.seed = seed;
    }

    private double hash(long i) {
        long h = i * 0x9E3779B97F4A7C15L + seed * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return ((h >>> 11) * 0x1.0p-53) * 2 - 1;
    }

    /** Smooth noise at t (period ~1 unit). */
    public double at(double t) {
        long i = (long) Math.floor(t);
        double f = t - i;
        double u = f * f * f * (f * (f * 6 - 15) + 10);
        return hash(i) + (hash(i + 1) - hash(i)) * u;
    }

    /** Two octaves, a little rougher. */
    public double fbm(double t) {
        return (at(t) * 0.7 + at(t * 2.13 + 17.3) * 0.3);
    }

    /** Uniform in [0, 1) from an integer key. */
    public double uniform(long key) {
        return (hash(key * 7919 + 13) + 1) / 2;
    }
}
