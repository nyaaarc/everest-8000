package dev.everest8000.cinema.nav;

import dev.everest8000.cinema.spec.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a grid path into a route a person would walk: resampled every block of travel, pushed sideways by low-frequency
 * noise (amplitude in blocks, one swing per wavelength blocks) so it meanders instead of running along grid lines, then
 * smoothed. Every shaped point is checked against the path finder's rules (standable, at most a one-block step from the
 * previous point, no squeezing past a corner); where the drift doesn't fit the ground it eases back toward the grid
 * path. Deterministic for a given seed.
 */
public final class RouteShaper {
    private RouteShaper() {}

    public static List<Vec3d> shape(List<Vec3d> grid, PathFinder pf, long seed, double amplitude, double wavelength) {
        if (grid.size() < 3 || amplitude <= 0) return grid;
        Noise noise = new Noise(seed * 977 + 31);
        // 1. Resample the grid path every ~1 block of horizontal travel, keeping each sample's grid height.
        List<double[]> samples = new ArrayList<>(); // x, y, z, s, tangent x, tangent z
        double s = 0;
        for (int i = 0; i < grid.size() - 1; i++) {
            Vec3d a = grid.get(i), b = grid.get(i + 1);
            double dx = b.x() - a.x(), dz = b.z() - a.z(), len = Math.sqrt(dx * dx + dz * dz);
            if (len < 1e-6) {
                samples.add(new double[]{a.x(), a.y(), a.z(), s, 0, 0}); // vertical (ladder) step
                continue;
            }
            int n = Math.max(1, (int) Math.round(len));
            for (int k = 0; k < n; k++) {
                double t = k / (double) n;
                samples.add(new double[]{a.x() + dx * t, a.y(), a.z() + dz * t, s + len * t, dx / len, dz / len});
            }
            s += len;
        }
        Vec3d last = grid.get(grid.size() - 1);
        samples.add(new double[]{last.x(), last.y(), last.z(), s, 0, 0});
        int n = samples.size();
        // Smooth the tangents so the sideways direction doesn't flip at every grid turn.
        double[][] tan = new double[n][2];
        for (int i = 0; i < n; i++) {
            double tx = 0, tz = 0;
            for (int j = Math.max(0, i - 4); j <= Math.min(n - 1, i + 4); j++) {
                tx += samples.get(j)[4];
                tz += samples.get(j)[5];
            }
            double l = Math.hypot(tx, tz);
            tan[i][0] = l < 1e-6 ? 0 : tx / l;
            tan[i][1] = l < 1e-6 ? 0 : tz / l;
        }
        // 2. Wanted sideways offset: noise over distance, faded in and out at the ends, none on ladders.
        double[] off = new double[n];
        for (int i = 0; i < n; i++) {
            double[] p = samples.get(i);
            double fade = Math.min(1, Math.min(p[3], s - p[3]) / Math.max(2, wavelength * 0.4));
            boolean vertical = tan[i][0] == 0 && tan[i][1] == 0;
            off[i] = vertical ? 0 : amplitude * noise.fbm(p[3] / Math.max(2, wavelength)) * 1.4 * fade;
        }
        // 3. Fit to the ground: shrink offsets that don't validate, smooth, repeat.
        double[] scale = new double[n];
        java.util.Arrays.fill(scale, 1);
        Vec3d[] pts = new Vec3d[n];
        for (int pass = 0; pass < 4; pass++) {
            double[] eff = new double[n];
            for (int i = 0; i < n; i++) eff[i] = off[i] * scale[i];
            double[] sm = new double[n];
            for (int i = 0; i < n; i++) {
                double sum = 0, wsum = 0;
                for (int j = Math.max(0, i - 3); j <= Math.min(n - 1, i + 3); j++) {
                    double w = 4 - Math.abs(i - j);
                    sum += eff[j] * w;
                    wsum += w;
                }
                sm[i] = sum / wsum;
            }
            boolean allOk = true;
            for (int i = 0; i < n; i++) {
                double[] p = samples.get(i);
                double x = p[0] - tan[i][1] * sm[i], z = p[2] + tan[i][0] * sm[i];
                int y = pf.snapToGround((int) Math.floor(x), (int) Math.floor(p[1] + 0.01), (int) Math.floor(z), 3);
                pts[i] = y == Integer.MIN_VALUE ? null : new Vec3d(x, y, z);
                boolean ok = pts[i] != null && (i == 0 || pts[i - 1] == null || step(pf, pts[i - 1], pts[i]));
                if (!ok) {
                    allOk = false;
                    scale[i] *= 0.4;
                    if (i > 0) scale[i - 1] *= 0.7;
                    if (i + 1 < n) scale[i + 1] *= 0.7;
                }
            }
            if (allOk) break;
        }
        List<Vec3d> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double[] p = samples.get(i);
            Vec3d q = pts[i];
            if (q == null || i > 0 && !step(pf, out.get(out.size() - 1), q)) q = new Vec3d(p[0], p[1], p[2]); // back on the grid path
            out.add(q);
        }
        out.set(0, grid.get(0));
        out.set(n - 1, last);
        return out;
    }

    /** One walking step from a to b fits the path finder's rules. */
    private static boolean step(PathFinder pf, Vec3d a, Vec3d b) {
        int ax = (int) Math.floor(a.x()), az = (int) Math.floor(a.z()), bx = (int) Math.floor(b.x()), bz = (int) Math.floor(b.z());
        int ay = (int) Math.floor(a.y() + 0.01), by = (int) Math.floor(b.y() + 0.01);
        if (Math.abs(bx - ax) > 1 || Math.abs(bz - az) > 1) return false;
        if (by - ay > 1 || ay - by > 2) return false;
        if (!pf.standable(bx, by, bz)) return false;
        if (pf.floorAt(bx, by, bz) - pf.floorAt(ax, ay, az) > PathFinder.MAX_CLIMB) return false; // real heights (snow layers)
        if (bx != ax && bz != az) {
            // Diagonal: both corner cells must be walkable at about this height.
            int hy = Math.max(ay, by);
            boolean c1 = pf.standable(bx, hy, az) || pf.standable(bx, hy - 1, az) || pf.standable(bx, hy + 1, az);
            boolean c2 = pf.standable(ax, hy, bz) || pf.standable(ax, hy - 1, bz) || pf.standable(ax, hy + 1, bz);
            if (!c1 || !c2) return false;
        }
        return true;
    }
}
