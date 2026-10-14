package dev.everest8000.cinema.camera;

import dev.everest8000.cinema.spec.Easing;
import dev.everest8000.cinema.spec.Keyframe;
import dev.everest8000.cinema.spec.Move;
import dev.everest8000.cinema.spec.Pose;
import dev.everest8000.cinema.spec.Target;
import dev.everest8000.cinema.spec.Vec3d;

import java.util.List;

/**
 * Evaluates one {@link Move} over time. Holds the smoothing state of follow/look-at moves, so sample it with
 * non-decreasing times; going back in time resets the smoothing. Pure math apart from the {@link TargetResolver}.
 */
public final class MovePlayer {
    private final Move move;
    private final double duration;
    private double lastT = Double.NaN;
    private Vec3d smoothPos;
    private Vec3d smoothLook;
    private double[] arcU;
    private double[] arcLen;

    /**
     * @param fallbackDuration used when the move has no durationSec of its own (the shot's length, or +infinity for an
     *                         open-ended API move)
     */
    public MovePlayer(Move move, double fallbackDuration) {
        this.move = move;
        this.duration = Double.isNaN(move.durationSec()) ? fallbackDuration : move.durationSec();
    }

    public Move move() {
        return move;
    }

    /** Seconds the move lasts (may be infinite for open-ended moves). */
    public double duration() {
        return duration;
    }

    /** Pose at time t (seconds since the move started). A null pos means the player's own first-person view. */
    public Pose sample(double t, float partialTick, TargetResolver r) {
        double dt = Double.isNaN(lastT) || t < lastT ? -1 : t - lastT;
        if (dt < 0) {
            smoothPos = null;
            smoothLook = null;
        }
        lastT = t;
        Pose p = switch (move) {
            case Move.Static s -> {
                double yaw = s.yaw(), pitch = s.pitch();
                if (s.lookAt() != null) {
                    Vec3d look = smoothLook(r.position(s.lookAt(), partialTick), s.lookSmoothSec(), dt);
                    yaw = s.pos().yawTo(look);
                    pitch = s.pos().pitchTo(look);
                }
                yield new Pose(s.pos(), yaw, pitch, s.roll(), s.fov());
            }
            case Move.Keyframes k -> keyframes(k, t, partialTick, r, dt);
            case Move.Orbit o -> {
                double e = ease(o.easing(), t);
                double a = Math.toRadians(lerp(o.startDeg(), o.endDeg(), e));
                double rad = lerp(o.radius(), o.radiusEnd(), e);
                double h = lerp(o.height(), Double.isNaN(o.heightEnd()) ? o.height() : o.heightEnd(), e);
                Vec3d c = r.position(o.center(), partialTick);
                Vec3d pos = c.add(new Vec3d(-Math.sin(a) * rad, h, Math.cos(a) * rad));
                Vec3d look = smoothLook(o.lookAt() == null ? c : r.position(o.lookAt(), partialTick), o.lookSmoothSec(), dt);
                yield new Pose(pos, pos.yawTo(look), pos.pitchTo(look), o.roll(), lerpFov(o.fov(), o.fovEnd(), e));
            }
            case Move.Dolly d -> {
                double e = ease(d.easing(), t);
                Vec3d target = r.position(d.target(), partialTick);
                Vec3d pos = target.add(bearing(d.azimuthDeg(), d.elevationDeg()).scale(lerp(d.distanceStart(), d.distanceEnd(), e)));
                yield new Pose(pos, pos.yawTo(target), pos.pitchTo(target), d.roll(), lerpFov(d.fov(), d.fovEnd(), e));
            }
            case Move.Truck k -> {
                double e = ease(k.easing(), t);
                Vec3d target = r.position(k.target(), partialTick);
                double a = Math.toRadians(k.azimuthDeg());
                Vec3d right = new Vec3d(Math.cos(a), 0, Math.sin(a));
                Vec3d pos = target.add(bearing(k.azimuthDeg(), k.elevationDeg()).scale(k.distance()))
                        .add(right.scale(lerp(k.slideStart(), k.slideEnd(), e)));
                double yaw, pitch;
                if (k.track()) {
                    yaw = pos.yawTo(target);
                    pitch = pos.pitchTo(target);
                } else {
                    yaw = k.azimuthDeg() + 180;
                    pitch = k.elevationDeg();
                }
                yield new Pose(pos, yaw, pitch, k.roll(), lerpFov(k.fov(), k.fovEnd(), e));
            }
            case Move.Follow f -> {
                Vec3d target = r.position(f.target(), partialTick);
                Vec3d off = f.offset();
                if (f.space().equals("targetYaw")) {
                    double y = Math.toRadians(r.yaw(f.target(), partialTick));
                    Vec3d fwd = new Vec3d(-Math.sin(y), 0, Math.cos(y));
                    Vec3d left = new Vec3d(Math.cos(y), 0, Math.sin(y));
                    off = left.scale(off.x()).add(new Vec3d(0, off.y(), 0)).add(fwd.scale(off.z()));
                }
                Vec3d desired = target.add(off);
                smoothPos = smooth(smoothPos, desired, f.smoothSec(), dt);
                Vec3d look = smoothLook(f.lookAt() == null ? target : r.position(f.lookAt(), partialTick), f.lookSmoothSec(), dt);
                yield new Pose(smoothPos, smoothPos.yawTo(look), smoothPos.pitchTo(look), f.roll(), f.fov());
            }
            case Move.FirstPerson fp -> new Pose(null, 0, 0, 0, fp.fov());
        };
        Move.Shake shake = move.shake();
        if (shake != null && p.pos() != null && shake.amplitudeDeg() > 0) {
            double w = 2 * Math.PI * shake.frequencyHz() * t;
            double amp = shake.amplitudeDeg();
            p = new Pose(p.pos(), p.yaw() + amp * noise(w, 0.0), p.pitch() + 0.7 * amp * noise(w, 1.7),
                    p.roll() + 0.35 * amp * noise(w, 3.1), p.fov());
        }
        return p;
    }

    private Pose keyframes(Move.Keyframes k, double t, float pt, TargetResolver r, double dt) {
        List<Keyframe> keys = k.keyframes();
        int n = keys.size();
        double[] times = new double[n];
        double[] x = new double[n], y = new double[n], z = new double[n];
        double[] yaw = new double[n], pitch = new double[n], roll = new double[n], fov = new double[n];
        for (int i = 0; i < n; i++) {
            Keyframe kf = keys.get(i);
            times[i] = kf.t();
            Vec3d p = kf.relativeTo() == null ? kf.pos() : r.position(kf.relativeTo(), pt).add(kf.pos());
            x[i] = p.x();
            y[i] = p.y();
            z[i] = p.z();
            if (k.lookAt() == null) {
                if (kf.hasAngles()) {
                    yaw[i] = kf.yaw();
                    pitch[i] = kf.pitch();
                } else {
                    Vec3d look = r.position(kf.lookAt(), pt);
                    double a = p.yawTo(look);
                    yaw[i] = i == 0 ? a : unwrapNear(a, yaw[i - 1]);
                    pitch[i] = p.pitchTo(look);
                }
            }
            roll[i] = kf.roll();
            fov[i] = Double.isNaN(kf.fov()) ? k.fov() : kf.fov();
        }
        double total = times[n - 1];
        double u;
        if (n == 1 || total <= 0) {
            u = 0;
        } else if (k.constantSpeed()) {
            u = arcParam(k, times, x, y, z, ease(k.easing(), t));
        } else {
            u = total * ease(k.easing(), t);
        }
        boolean linear = k.interpolation().equals("linear");
        Vec3d pos = new Vec3d(ch(linear, times, x, u), ch(linear, times, y, u), ch(linear, times, z, u));
        double yy, pp;
        if (k.lookAt() != null) {
            Vec3d look = smoothLook(r.position(k.lookAt(), pt), k.lookSmoothSec(), dt);
            yy = pos.yawTo(look);
            pp = pos.pitchTo(look);
        } else {
            yy = ch(linear, times, yaw, u);
            pp = Math.max(-90, Math.min(90, ch(linear, times, pitch, u)));
        }
        return new Pose(pos, yy, pp, ch(linear, times, roll, u), Math.max(1, Math.min(170, ch(linear, times, fov, u))));
    }

    /** Spline parameter u at which the path has covered fraction f of its length. */
    private double arcParam(Move.Keyframes k, double[] times, double[] x, double[] y, double[] z, double f) {
        boolean linear = k.interpolation().equals("linear");
        if (arcU == null) {
            int samples = 2048;
            double total = times[times.length - 1];
            arcU = new double[samples + 1];
            arcLen = new double[samples + 1];
            double px = ch(linear, times, x, 0), py = ch(linear, times, y, 0), pz = ch(linear, times, z, 0);
            for (int i = 1; i <= samples; i++) {
                double u = total * i / samples;
                double cx = ch(linear, times, x, u), cy = ch(linear, times, y, u), cz = ch(linear, times, z, u);
                arcU[i] = u;
                arcLen[i] = arcLen[i - 1] + Math.sqrt((cx - px) * (cx - px) + (cy - py) * (cy - py) + (cz - pz) * (cz - pz));
                px = cx;
                py = cy;
                pz = cz;
            }
        }
        double target = f * arcLen[arcLen.length - 1];
        int lo = 0, hi = arcLen.length - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (arcLen[mid] <= target) lo = mid;
            else hi = mid;
        }
        double span = arcLen[hi] - arcLen[lo];
        double frac = span <= 1e-12 ? 0 : (target - arcLen[lo]) / span;
        return arcU[lo] + (arcU[hi] - arcU[lo]) * frac;
    }

    private static double ch(boolean linear, double[] times, double[] v, double u) {
        return linear ? Spline.linear(times, v, u) : Spline.catmullRom(times, v, u);
    }

    private double ease(Easing e, double t) {
        if (Double.isInfinite(duration) || duration <= 0) return 0;
        return e.apply(t / duration);
    }

    private Vec3d smoothLook(Vec3d target, double tau, double dt) {
        smoothLook = smooth(smoothLook, target, tau, dt);
        return smoothLook;
    }

    /** First-order exponential smoothing, frame-rate independent. */
    private static Vec3d smooth(Vec3d current, Vec3d target, double tau, double dt) {
        if (current == null || dt < 0 || tau <= 0) return target;
        double a = 1 - Math.exp(-dt / tau);
        return Vec3d.lerp(current, target, a);
    }

    /** Direction from a target to a camera at a bearing (Minecraft yaw) and elevation above the horizon. */
    static Vec3d bearing(double azimuthDeg, double elevationDeg) {
        double a = Math.toRadians(azimuthDeg), e = Math.toRadians(elevationDeg);
        return new Vec3d(-Math.sin(a) * Math.cos(e), Math.sin(e), Math.cos(a) * Math.cos(e));
    }

    static double unwrapNear(double angle, double reference) {
        double d = angle - reference;
        d -= 360 * Math.floor((d + 180) / 360);
        return reference + d;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double lerpFov(double a, double b, double t) {
        return Double.isNaN(b) ? a : lerp(a, b, t);
    }

    private static double noise(double w, double phase) {
        return (Math.sin(w + phase) + 0.6 * Math.sin(2.17 * w + 0.4 + phase * 1.3) + 0.3 * Math.sin(4.31 * w + 2.1 + phase * 0.7)) / 1.9;
    }

    /** Targets this move depends on (for validation of entity presence before rolling). */
    public static List<Target> targets(Move m) {
        java.util.ArrayList<Target> out = new java.util.ArrayList<>();
        switch (m) {
            case Move.Static s -> { if (s.lookAt() != null) out.add(s.lookAt()); }
            case Move.Keyframes k -> {
                if (k.lookAt() != null) out.add(k.lookAt());
                for (Keyframe kf : k.keyframes()) {
                    if (kf.lookAt() != null) out.add(kf.lookAt());
                    if (kf.relativeTo() != null) out.add(kf.relativeTo());
                }
            }
            case Move.Orbit o -> {
                out.add(o.center());
                if (o.lookAt() != null) out.add(o.lookAt());
            }
            case Move.Dolly d -> out.add(d.target());
            case Move.Truck t -> out.add(t.target());
            case Move.Follow f -> {
                out.add(f.target());
                if (f.lookAt() != null) out.add(f.lookAt());
            }
            case Move.FirstPerson fp -> { }
        }
        return out;
    }
}
