package dev.everest8000.expedition.client.wind;

import dev.everest8000.expedition.weather.WeatherField;
import net.minecraft.util.Mth;

/**
 * The banner clouds the jet stream pulls off the highest summits, and the cap of cloud a storm puts on the upper
 * mountain. This is the state the plume shader draws: where each plume starts, which way it streams, how long and
 * dense it is, and how far its texture has been carried downwind. It follows the weather the server sends, eased so
 * a change of wind grows or shrinks a plume over a minute or so instead of popping.
 *
 * <p>The envelope numbers in {@link #box} mirror {@code plumeEnv} in plume.fsh; change them together.
 */
public final class PlumeField {
    /** A summit that flies a plume. {@code size} scales the plume's cross-section and length. */
    public record Peak(String name, double x, double y, double z, float size) {}

    /** Everest's top is cut flat by the world's height limit; the plume starts from the middle of that plateau. */
    public static final Peak[] PEAKS = {
            new Peak("everest", 20716, 2032, 7776, 1.0f),
            new Peak("lhotse", 21124, 1851, 9120, 0.72f),
            new Peak("nuptse", 18756, 1498, 8860, 0.55f),
    };
    public static final int MAX = 3;

    /** Wind at a summit (km/h) where a plume first shows, and where it is fully formed. */
    private static final float FORM_KMH = 38f;
    private static final float FULL_KMH = 92f;

    private final float[] strength = new float[MAX];
    private final float[] length = new float[MAX];
    private final float[] storm = new float[MAX];
    private final double[] advect = new double[MAX];
    private final float[] meander = new float[MAX];
    private float windX = 1f, windZ = 0f;
    private float capStrength;
    private float capBaseY = 2000f;
    private double capAdvect;
    private boolean primed;

    /** Advances one client tick toward the weather in {@code field}. */
    public void tick(WeatherField field) {
        float angle = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(field.windZ(), field.windX()))) * Mth.DEG_TO_RAD;
        float ease = primed ? 0.02f : 1f;
        windX = Mth.lerp(ease, windX, Mth.cos(angle));
        windZ = Mth.lerp(ease, windZ, Mth.sin(angle));
        float norm = Mth.sqrt(windX * windX + windZ * windZ);
        if (norm > 1e-4f) {
            windX /= norm;
            windZ /= norm;
        }
        for (int i = 0; i < MAX; i++) {
            Peak peak = PEAKS[i];
            float kmh = field.windKmhAt(peak.y());
            float form = Mth.clamp((kmh - FORM_KMH) / (FULL_KMH - FORM_KMH), 0f, 1f);
            float targetStrength = form * form * (3f - 2f * form);
            float targetLength = peak.size() * Mth.clamp(300f + 26f * (kmh - FORM_KMH), 300f, 4000f);
            float targetStorm = field.stormAt(peak.y());
            float slow = primed ? 0.004f : 1f;
            strength[i] = Mth.lerp(slow, strength[i], targetStrength);
            length[i] = Mth.lerp(slow, length[i], targetLength);
            storm[i] = Mth.lerp(primed ? 0.01f : 1f, storm[i], targetStorm);
            // The texture streams downwind a little slower than the wind itself, as cloud features do.
            float blocksPerTick = kmh / 3.6f / 20f * 0.85f;
            double period = 16 * 150.0 * peak.size() * 5.0;
            advect[i] = (advect[i] + blocksPerTick) % period;
            meander[i] += 0.0004f;
        }
        Peak top = PEAKS[0];
        float capTarget = field.storm() * smooth(((float) top.y() - 120f - field.stormBaseY()) / 500f);
        capStrength = Mth.lerp(primed ? 0.008f : 1f, capStrength, capTarget);
        capBaseY = Mth.lerp(primed ? 0.01f : 1f, capBaseY, field.stormBaseY());
        capAdvect = (capAdvect + field.windKmhAt(top.y()) / 3.6f / 20f * 0.6f) % (16 * 520.0);
        primed = true;
    }

    public void reset() {
        primed = false;
    }

    public boolean visible() {
        if (capStrength > 0.01f) return true;
        for (int i = 0; i < MAX; i++) if (strength[i] > 0.01f) return true;
        return false;
    }

    public float strength(int i) {
        return strength[i];
    }

    public float length(int i) {
        return length[i];
    }

    public float windX() {
        return windX;
    }

    public float windZ() {
        return windZ;
    }

    public float capStrength() {
        return capStrength;
    }

    public float capRadius() {
        return 1300f + 700f * capStrength;
    }

    public float capBaseY() {
        return capBaseY;
    }

    public float capTopY() {
        return (float) PEAKS[0].y() + 60f;
    }

    /**
     * Uniform values relative to the camera, in the layout plume.fsh expects. Plumes too faint to see are left out.
     *
     * @return how many plumes were written
     */
    public int pack(double camX, double camY, double camZ, float[] a, float[] b, float[] c, float[] d,
                    float[] capA, float[] capB) {
        int n = 0;
        for (int i = 0; i < MAX; i++) {
            if (strength[i] < 0.01f) continue;
            Peak peak = PEAKS[i];
            float k = peak.size();
            a[n * 4] = (float) (peak.x() - camX);
            a[n * 4 + 1] = (float) (peak.y() - camY);
            a[n * 4 + 2] = (float) (peak.z() - camZ);
            a[n * 4 + 3] = length[i];
            b[n * 4] = windX;
            b[n * 4 + 1] = windZ;
            b[n * 4 + 2] = strength[i];
            b[n * 4 + 3] = k;
            c[n * 4] = (float) advect[i];
            c[n * 4 + 1] = storm[i];
            c[n * 4 + 2] = meander[i] + i * 2.1f;
            c[n * 4 + 3] = i * 0.371f;
            float[] box = box(i);
            d[n * 4] = box[0];
            d[n * 4 + 1] = box[1];
            d[n * 4 + 2] = box[2];
            d[n * 4 + 3] = box[3];
            n++;
        }
        Peak top = PEAKS[0];
        capA[0] = (float) (top.x() - camX);
        capA[1] = (float) (top.y() - camY);
        capA[2] = (float) (top.z() - camZ);
        capA[3] = capRadius();
        capB[0] = (float) (capBaseY - camY);
        capB[1] = (float) (capTopY() - camY);
        // The cap is cloud seen from outside it; from inside, the whiteout is the storm. Ray-marching kilometres of
        // cap from within also smears its texture into streaks, so it fades away as the camera reaches its box.
        double r = capRadius();
        double outside = Math.max(Math.max(Math.abs(camX - top.x()) - r, Math.abs(camZ - top.z()) - r),
                Math.max(capBaseY - 40 - camY, camY - (capTopY() + 100)));
        float fromInside = smooth((float) (outside + 50) / 200f);
        capB[2] = capStrength > 0.01f ? capStrength * fromInside : 0f;
        capB[3] = (float) capAdvect;
        return n;
    }

    /** Plume i's box in its own frame: half width across the wind, bottom, top, and how far upwind it starts. */
    float[] box(int i) {
        float k = PEAKS[i].size(), L = length[i], st = storm[i];
        float wcMax = k * (26f + 50f * st) + (0.11f + 0.08f * st) * L;
        float hvUp = k * (20f + 40f * st) + 0.06f * L;
        float hvDown = k * (45f + 110f * st) + 0.10f * L;
        float drift = (0.05f - 0.07f * st) * 0.5f * L;
        float hcTop = 14f * k + Math.max(0f, drift);
        float hcBottom = 14f * k + Math.min(0f, drift) - 60f * k * st;
        return new float[] {1.9f * wcMax + 32f * k, hcBottom - 1.9f * hvDown, hcTop + 1.9f * hvUp, -55f * k};
    }

    /** Every corner of every visible volume, as world xyz triples, for the {@link HorizonMap}. */
    public double[] corners() {
        int count = 0;
        for (int i = 0; i < MAX; i++) if (strength[i] >= 0.01f) count++;
        boolean cap = capStrength > 0.01f;
        double[] out = new double[(count + (cap ? 1 : 0)) * 8 * 3];
        int o = 0;
        for (int i = 0; i < MAX; i++) {
            if (strength[i] < 0.01f) continue;
            Peak peak = PEAKS[i];
            float[] box = box(i);
            for (int corner = 0; corner < 8; corner++) {
                double s = (corner & 1) == 0 ? box[3] : length[i];
                double cc = (corner & 2) == 0 ? -box[0] : box[0];
                double h = (corner & 4) == 0 ? box[1] : box[2];
                out[o++] = peak.x() + windX * s - windZ * cc;
                out[o++] = peak.y() + h;
                out[o++] = peak.z() + windZ * s + windX * cc;
            }
        }
        if (cap) {
            Peak top = PEAKS[0];
            double r = capRadius();
            for (int corner = 0; corner < 8; corner++) {
                out[o++] = top.x() + ((corner & 1) == 0 ? -r : r);
                out[o++] = (corner & 4) == 0 ? capBaseY - 40 : capTopY() + 100;
                out[o++] = top.z() + ((corner & 2) == 0 ? -r : r);
            }
        }
        return out;
    }

    /** Distance from the point to the farthest corner, which bounds how far the horizon needs to look. */
    public double reach(double x, double y, double z) {
        double[] c = corners();
        double best = 0;
        for (int i = 0; i < c.length; i += 3) {
            double dx = c[i] - x, dz = c[i + 2] - z;
            best = Math.max(best, Math.sqrt(dx * dx + dz * dz));
        }
        return best;
    }

    private static float smooth(float t) {
        t = Mth.clamp(t, 0f, 1f);
        return t * t * (3f - 2f * t);
    }
}
