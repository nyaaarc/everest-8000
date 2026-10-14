package dev.everest8000.expedition.client.wind;

/**
 * Which terrain stands between the camera and the summit plume. For every azimuth around the camera it holds the
 * highest elevation angle of the terrain seen so far, at a ladder of distances: a point of the plume at horizontal
 * distance d in that direction is hidden when it sits below the angle stored for d. Far terrain is drawn by Distant
 * Horizons into its own depth buffer, which nothing else can read, so the plume's ray marcher tests against this
 * instead; terrain close to the camera is handled by the real depth buffer.
 *
 * <p>The shader interpolates between neighbouring azimuths and distances and compares elevations exactly, so the
 * plume meets a ridge along a smooth line rather than the steps of a coarse grid. The baked heights are each cell's
 * highest point, so they are lowered by {@link #BAKED_BIAS} to keep the occluder from standing above the real ridge.
 *
 * <p>Built on a worker thread from a snapshot of the surface around the camera plus the baked {@link TerrainHeights}.
 */
public final class HorizonMap {
    public static final int AZIMUTHS = 2048;
    public static final int DISTANCES = 96;
    /** Nearest distance bin, in blocks; the ladder from there to the farthest is logarithmic. */
    public static final float MIN_DISTANCE = 4f;
    /** Blocks the baked surface is lowered by: its cells hold their highest point, a few blocks above most of them. */
    static final float BAKED_BIAS = 2.5f;

    /** Surface height at a block position, or NaN when unknown. */
    public interface Surface {
        float at(double x, double z);
    }

    /**
     * The table plus where it was taken from: azimuths {@code azMin .. azMin + azSpan} (radians), distances
     * {@code exp(logMin) .. exp(logMin + logSpan)}. {@code angles[az * DISTANCES + d]} is the highest terrain
     * elevation angle (radians) closer than distance bin d.
     */
    public record Result(double camX, double camY, double camZ, float azMin, float azSpan, float logMin, float logSpan,
                         float[] angles) {}

    private HorizonMap() {}

    /**
     * @param corners xyz triples of every point the plume volumes reach; the table only covers the azimuths toward
     *                them, which keeps its columns fine when the plume is far away
     * @param maxDist how far to look for terrain (blocks)
     */
    public static Result compute(double camX, double camY, double camZ, double[] corners, double maxDist,
                                 Surface near, Surface far) {
        float[] az = new float[2];
        azimuthBounds(camX, camZ, corners, az);
        float azMin = az[0], azSpan = az[1];
        float logMin = (float) Math.log(MIN_DISTANCE);
        float logSpan = (float) Math.log(Math.max(maxDist, MIN_DISTANCE * 2)) - logMin;
        double[] binDistance = new double[DISTANCES];
        for (int j = 0; j < DISTANCES; j++) binDistance[j] = Math.exp(logMin + logSpan * j / (DISTANCES - 1));

        float[] angles = new float[AZIMUTHS * DISTANCES];
        for (int i = 0; i < AZIMUTHS; i++) {
            double a = azMin + (i + 0.5) / AZIMUTHS * azSpan;
            double dx = Math.cos(a), dz = Math.sin(a);
            int row = i * DISTANCES;
            int j = 0;
            double highest = -Math.PI / 2;
            // Steps grow with distance: half a percent of where a far ridge sits is well under a pixel.
            for (double d = 1.0; j < DISTANCES; d += Math.max(0.75, d * 0.005)) {
                while (j < DISTANCES && binDistance[j] <= d) angles[row + j++] = (float) highest;
                if (d > maxDist) continue;
                double x = camX + dx * d, z = camZ + dz * d;
                float h = near.at(x, z);
                if (Float.isNaN(h)) {
                    h = far.at(x, z);
                    if (Float.isNaN(h)) continue;
                    h -= BAKED_BIAS;
                }
                double angle = Math.atan2(h - camY, d);
                if (angle > highest) highest = angle;
            }
        }
        return new Result(camX, camY, camZ, azMin, azSpan, logMin, logSpan, angles);
    }

    /** The smallest azimuth range (with a margin) that sees every corner from the camera; the full circle up close. */
    static void azimuthBounds(double camX, double camZ, double[] corners, float[] az) {
        int n = corners.length / 3;
        az[0] = (float) -Math.PI;
        az[1] = (float) (2 * Math.PI);
        if (n == 0) return;
        double[] angles = new double[n];
        for (int c = 0; c < n; c++) {
            double x = corners[c * 3] - camX, z = corners[c * 3 + 2] - camZ;
            if (x * x + z * z < 64 * 64) return;
            angles[c] = Math.atan2(z, x);
        }
        // The corners' angular extent: the widest gap between neighbouring azimuths is the side facing away.
        java.util.Arrays.sort(angles);
        double gap = angles[0] + 2 * Math.PI - angles[n - 1];
        double start = angles[0];
        for (int c = 1; c < n; c++) {
            double g = angles[c] - angles[c - 1];
            if (g > gap) {
                gap = g;
                start = angles[c];
            }
        }
        double span = 2 * Math.PI - gap;
        if (span > Math.PI * 1.2) return;
        double margin = Math.max(0.02, span * 0.06);
        az[0] = (float) (start - margin);
        az[1] = (float) (span + 2 * margin);
    }
}
