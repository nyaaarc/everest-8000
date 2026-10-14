package dev.everest8000.cinema.camera;

/**
 * Catmull-Rom interpolation with the keyframe times as knots (Barry-Goldman form), so uneven key spacing still gives
 * smooth velocity across keys. The ends use reflected phantom points. Also piecewise-linear interpolation.
 */
public final class Spline {
    private Spline() {}

    /** Index i of the segment [times[i], times[i+1]] containing t (clamped). */
    public static int segment(double[] times, double t) {
        int n = times.length;
        if (t <= times[0]) return 0;
        if (t >= times[n - 1]) return n - 2;
        int lo = 0, hi = n - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (times[mid] <= t) lo = mid;
            else hi = mid;
        }
        return lo;
    }

    /** Catmull-Rom value of a scalar channel at time t. */
    public static double catmullRom(double[] times, double[] v, double t) {
        int n = times.length;
        if (n == 1) return v[0];
        t = Math.max(times[0], Math.min(times[n - 1], t));
        int i = segment(times, t);
        double t1 = times[i], t2 = times[i + 1];
        double p1 = v[i], p2 = v[i + 1];
        double t0, p0, t3, p3;
        if (i > 0) {
            t0 = times[i - 1];
            p0 = v[i - 1];
        } else {
            t0 = t1 - (t2 - t1);
            p0 = p1 - (p2 - p1);
        }
        if (i + 2 < n) {
            t3 = times[i + 2];
            p3 = v[i + 2];
        } else {
            t3 = t2 + (t2 - t1);
            p3 = p2 + (p2 - p1);
        }
        double a1 = lerpT(p0, p1, t0, t1, t);
        double a2 = lerpT(p1, p2, t1, t2, t);
        double a3 = lerpT(p2, p3, t2, t3, t);
        double b1 = lerpT(a1, a2, t0, t2, t);
        double b2 = lerpT(a2, a3, t1, t3, t);
        return lerpT(b1, b2, t1, t2, t);
    }

    public static double linear(double[] times, double[] v, double t) {
        int n = times.length;
        if (n == 1) return v[0];
        t = Math.max(times[0], Math.min(times[n - 1], t));
        int i = segment(times, t);
        return lerpT(v[i], v[i + 1], times[i], times[i + 1], t);
    }

    private static double lerpT(double a, double b, double ta, double tb, double t) {
        double span = tb - ta;
        if (span <= 1e-12) return b;
        return a + (b - a) * ((t - ta) / span);
    }
}
