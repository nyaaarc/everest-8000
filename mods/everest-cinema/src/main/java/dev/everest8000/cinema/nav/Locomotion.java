package dev.everest8000.cinema.nav;

import dev.everest8000.cinema.spec.Vec3d;

import java.util.List;

/**
 * Input-style steering for a walker driven by real player physics: each tick it turns the body (rate-limited) toward a
 * pure-pursuit lookahead point on the path, offset by slow noise so the line isn't laser-straight, and sets a forward
 * input that ramps up and down, slows for climbs, snow and sharp turns, eases into the last metre, and drops to zero
 * for breathers. Step-ups get a jump; ladders are climbed by walking into them. Deterministic for a given seed.
 */
public final class Locomotion {
    /** What the body is doing this tick. yaw is the body heading in Minecraft degrees. */
    public record State(double x, double y, double z, double yaw, boolean onGround, boolean horizontalCollision,
                        boolean onClimbable) {
        Vec3d pos() {
            return new Vec3d(x, y, z);
        }
    }

    /** forward/strafe in -1..1 (strafe positive = left), yaw = new body heading. */
    public record Control(float forward, float strafe, boolean jump, boolean sneak, boolean sprint, double yaw, boolean climbing) {}

    public enum Outcome { IDLE, WALKING, ARRIVED, STUCK }

    private static final double DT = 0.05;
    private final Gait gait;
    private final Noise noise;
    private final long seed;
    private List<Vec3d> path;
    private int index;
    private String speed = "walk";
    private double arriveRadius = 0.6;
    private double forward;
    private double yawVel;
    private Outcome outcome = Outcome.IDLE;
    private int tick;
    private int breatherLeft;
    private int breatherPhase;
    private int breatherTick;
    private int breatherHold;
    private static final double BREATHER_EASE_SEC = 0.6;
    private int breathers;
    private double bestRemaining;
    private int sinceProgress;
    private int jumpsSinceProgress;
    private boolean lastJump;
    private boolean sneakHeld;
    private PathFinder walkable;
    private double paceSmoothed = Double.NaN;
    private int collisionTicks;
    private double wanderWeight = 1;
    private double travel;
    private double lastX = Double.NaN, lastZ;
    private double wanderOffset;
    private double effort;
    private double effortThreshold = -1;
    private String stuckInfo;
    private boolean shaped;

    public Locomotion(Gait gait, long seed) {
        this.gait = gait;
        this.noise = new Noise(seed);
        this.seed = seed;
    }

    public Gait gait() {
        return gait;
    }

    public void setWalkable(PathFinder pf) {
        this.walkable = pf;
    }

    /** Starts following points (feet positions). speed: walk | stroll | sprint | sneak. */
    public void follow(List<Vec3d> points, String speed, double arriveRadius) {
        follow(points, speed, arriveRadius, false);
    }

    /** shaped: the route already meanders (see {@link RouteShaper}), so steering adds no wander of its own. */
    public void follow(List<Vec3d> points, String speed, double arriveRadius, boolean shaped) {
        this.shaped = shaped;
        this.path = List.copyOf(points);
        this.index = 0;
        this.speed = speed;
        this.arriveRadius = arriveRadius;
        this.outcome = path.isEmpty() ? Outcome.ARRIVED : Outcome.WALKING;
        this.bestRemaining = Double.MAX_VALUE;
        this.sinceProgress = 0;
        this.jumpsSinceProgress = 0;
    }

    public void stop() {
        path = null;
        if (outcome == Outcome.WALKING) outcome = Outcome.IDLE;
    }

    public void setSneak(boolean on) {
        sneakHeld = on;
    }

    public boolean sneakHeld() {
        return sneakHeld;
    }

    /** Where and why the last walk got stuck (for logs), or null. */
    public String stuckInfo() {
        return stuckInfo;
    }

    public Outcome outcome() {
        return outcome;
    }

    public boolean walking() {
        return path != null && outcome == Outcome.WALKING;
    }

    public boolean inBreather() {
        return breatherPhase != 0;
    }

    public double currentForward() {
        return forward;
    }

    /** Starts a breather now (ease to a stop, hold, carry on). */
    public void breather(double seconds) {
        if (breatherPhase == 0) startBreather(seconds);
        else breatherHold = Math.max(breatherHold, (int) Math.round(seconds * 20));
    }

    /** 0 walking, 1 easing down, 2 stopped, 3 easing back up. */
    public int breatherPhase() {
        return breatherPhase;
    }

    /** How far through the stopped part of the breather, 0..1. */
    public double breatherProgress() {
        return breatherPhase == 2 ? Math.min(1, breatherTick / (double) Math.max(1, breatherHold)) : breatherPhase == 3 ? 1 : 0;
    }

    /** How many breathers have started (to vary what each one looks like). */
    public int breatherCount() {
        return breathers;
    }

    private void startBreather(double seconds) {
        breatherPhase = 1;
        breatherTick = 0;
        breatherHold = (int) Math.round(seconds * 20);
        breathers++;
    }

    private void next(int phase) {
        breatherPhase = phase;
        breatherTick = 0;
    }

    private static double smooth(double t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }

    /** Remaining path points from the current one on (for debugging and the API). */
    public List<Vec3d> remainingPath() {
        return path == null ? List.of() : path.subList(Math.min(index, path.size()), path.size());
    }

    public Control step(State s, BlockQuery q) {
        tick++;
        if (path == null || outcome != Outcome.WALKING) {
            forward = approach(forward, 0, DT / Math.max(0.05, gait.accelSec()) * 1.5);
            return new Control((float) forward, 0, false, sneakHeld, false, s.yaw(), false);
        }
        Vec3d pos = s.pos();
        if (!Double.isNaN(lastX)) travel += Math.hypot(pos.x() - lastX, pos.z() - lastZ);
        lastX = pos.x();
        lastZ = pos.z();
        advanceIndex(pos);
        Vec3d end = path.get(path.size() - 1);
        double remaining = remainingAlongPath(pos);
        if (horiz(pos, end) <= arriveRadius && Math.abs(end.y() - pos.y()) < 1.6) {
            outcome = Outcome.ARRIVED;
            path = null;
            forward = approach(forward, 0, 0.5);
            return new Control((float) forward, 0, false, sneakHeld, false, s.yaw(), false);
        }
        // Stuck: no progress toward the end for 5 s, or for 1.5 s while jumping at the same spot (a step too high to
        // clear: give up on it quickly and route again, rather than hop in place on camera).
        if (remaining < bestRemaining - 0.25) {
            bestRemaining = remaining;
            sinceProgress = 0;
            jumpsSinceProgress = 0;
        } else if (breatherPhase == 0 && (++sinceProgress > 100 || jumpsSinceProgress >= 3 && sinceProgress > 30)) {
            outcome = Outcome.STUCK;
            stuckInfo = String.format(java.util.Locale.ROOT, "at %.2f %.2f %.2f yaw %.0f, next path points %s", pos.x(), pos.y(), pos.z(),
                    s.yaw(), path.subList(Math.max(0, index - 1), Math.min(path.size(), index + 4)));
            path = null;
            return new Control(0, 0, false, sneakHeld, false, s.yaw(), false);
        }

        // Ladder: the path goes straight up a climbable column here.
        boolean climbing = climbAhead(pos);
        boolean recovering = sinceProgress > 30 && !climbing;
        Vec3d target;
        double desiredYaw;
        if (climbing) {
            target = path.get(Math.min(index + 1, path.size() - 1));
            desiredYaw = faceWall(q, (int) Math.floor(pos.x()), (int) Math.floor(pos.y()), (int) Math.floor(pos.z()), s.yaw());
        } else {
            target = recovering ? recoveryTarget(pos) : lookaheadPoint(pos, gait.lookahead());
            // Don't cut a corner across a step too high to jump: aim at the next route point instead.
            if (!recovering && walkable != null && !clearLine(pos, target)) target = path.get(Math.min(index + 1, path.size() - 1));
            Vec3d dir = new Vec3d(target.x() - pos.x(), 0, target.z() - pos.z());
            double len = dir.length();
            if (len > 1e-3) {
                // Wander: a slow sideways drift over tens of blocks of travel (not time), eased in and out, held to
                // walkable ground, straighter near steps and after bumping into things.
                boolean stepNear = stepAhead(pos, 2.0);
                collisionTicks = s.horizontalCollision() ? Math.min(60, collisionTicks + 3) : Math.max(0, collisionTicks - 1);
                double wantWeight = collisionTicks > 0 || sinceProgress > 30 ? 0 : stepNear ? 0.35 : 1;
                wanderWeight += (wantWeight - wanderWeight) * 0.06;
                double w = shaped ? 0 : gait.wander() * noise.fbm(travel / Math.max(2, gait.wanderWavelength())) * wanderWeight;
                w *= Math.min(1, remaining / 6.0) * Math.min(1, travel / 4.0);
                Vec3d perp = new Vec3d(-dir.z() / len, 0, dir.x() / len);
                // Shrink the offset until the drifted aim point (and the way to it) is walkable at about this height.
                double ok = 0;
                for (double f : new double[]{1, 0.6, 0.3}) {
                    if (walkable == null || offsetWalkable(pos, target, perp, w * f)) {
                        ok = w * f;
                        break;
                    }
                }
                wanderOffset += (ok - wanderOffset) * 0.12;
                target = new Vec3d(target.x() + perp.x() * wanderOffset, target.y(), target.z() + perp.z() * wanderOffset);
            }
            desiredYaw = Math.toDegrees(Math.atan2(-(target.x() - pos.x()), target.z() - pos.z()));
        }

        // Body turn: a critically damped approach, limited to the gait's turn rate.
        double delta = wrap(desiredYaw - s.yaw());
        double maxRate = gait.turnRateDeg() * DT;
        double want = clamp(delta * 0.45, -maxRate, maxRate);
        yawVel = yawVel + clamp(want - yawVel, -maxRate * 0.5, maxRate * 0.5);
        double newYaw = s.yaw() + yawVel;

        // Pace.
        double pace = switch (speed) {
            case "sprint" -> 1.0;
            case "sneak" -> 0.3;
            case "stroll" -> gait.pace() * 0.6;
            default -> gait.pace();
        };
        double slope = grade(pos, 5.0);
        pace *= 1 - gait.uphillSlowdown() * clamp(slope, 0, 1);
        pace *= 1 - 0.15 * clamp(-slope, 0, 1);
        if (Math.abs(slope) < 0.12) pace *= 1.08; // a little quicker on the flat
        if (stepUpWithin(pos, 1.3)) pace *= 0.75; // and a hitch at each step up
        pace *= 1 - gait.snowSlowdown() * clamp(q.snowDepth((int) Math.floor(pos.x()), (int) Math.floor(pos.y() + 0.01), (int) Math.floor(pos.z())), 0, 1);
        double absDelta = Math.abs(delta);
        if (!climbing) pace *= absDelta > 70 ? 0.15 : absDelta > 35 ? 0.55 : 1.0;
        if (remaining < 2.5) pace *= Math.max(0.35, remaining / 2.5);
        if (climbing) pace = 1.0;
        // Low-pass the wanted pace so block-by-block terrain doesn't make the stride jitter.
        pace *= gait.paceFactor(pos.y()); // a slower trudge higher up
        if (Double.isNaN(paceSmoothed)) paceSmoothed = pace;
        paceSmoothed += (pace - paceSmoothed) * (1 - Math.exp(-DT / 0.7));
        pace = climbing || absDelta > 70 ? Math.min(pace, paceSmoothed) : paceSmoothed;
        // Breathers: effort builds with time walking, faster on climbs and higher up; a breather comes when it
        // crosses a randomised threshold, so they're irregular and cluster where the going is hard.
        if (gait.breatherEvery() != null && !climbing && breatherPhase == 0 && forward > 0.2) {
            if (effortThreshold < 0) effortThreshold = nextThreshold();
            double rate = gait.altitudeFactor(pos.y()) * (1 + gait.breatherSlope() * clamp(slope, 0, 1.5));
            effort += DT * rate;
            if (effort >= effortThreshold) {
                double len = range(gait.breatherFor(), 991 + breathers) * (0.85 + 0.3 * noise.uniform(4243L + breathers));
                startBreather(len);
                effort = 0;
                effortThreshold = nextThreshold();
            }
        }
        // Breather gate: ease down into the stop, hold, ease back up (S-curves, never an instant stop or start).
        double gate = 1;
        if (breatherPhase != 0) {
            breatherTick++;
            int ease = (int) Math.round(BREATHER_EASE_SEC * 20);
            switch (breatherPhase) {
                case 1 -> {
                    gate = 1 - smooth(breatherTick / (double) ease);
                    if (breatherTick >= ease) next(2);
                }
                case 2 -> {
                    gate = 0;
                    if (breatherTick >= breatherHold) next(3);
                }
                default -> {
                    gate = smooth(breatherTick / (double) ease);
                    if (breatherTick >= ease) next(0);
                }
            }
        }
        breatherLeft = breatherPhase == 2 ? Math.max(1, breatherHold - breatherTick) : 0;
        double accel = DT / Math.max(0.05, gait.accelSec());
        if (breatherPhase != 0) {
            forward = pace * gate; // the gate shapes the ramp itself
        } else {
            forward = approach(forward, pace, pace < forward ? accel * 1.4 : accel);
        }

        boolean jump = false;
        if (!climbing && s.onGround() && forward > 0.1) {
            Vec3d next = path.get(Math.min(index + 1, path.size() - 1));
            if (s.horizontalCollision() || next.y() > pos.y() + 0.55 && horiz(pos, next) < 1.4) jump = true;
            if (recovering && sinceProgress % 10 == 0) jump = true;
        }
        if (jump && !lastJump) jumpsSinceProgress++;
        lastJump = jump;
        boolean sprint = speed.equals("sprint") && forward > 0.8;
        boolean sneak = sneakHeld || speed.equals("sneak");
        return new Control((float) forward, 0, jump, sneak, sprint, newYaw, climbing);
    }

    /** Seconds of easy-going effort until the next breather, randomised. */
    private double nextThreshold() {
        return range(gait.breatherEvery(), 577 + breathers) * (0.65 + 0.7 * noise.uniform(8111L + breathers * 3L));
    }

    private boolean offsetWalkable(Vec3d pos, Vec3d target, Vec3d perp, double w) {
        if (Math.abs(w) < 0.05) return true;
        for (double t : new double[]{0.5, 1.0}) {
            double x = pos.x() + (target.x() - pos.x()) * t + perp.x() * w * t;
            double z = pos.z() + (target.z() - pos.z()) * t + perp.z() * w * t;
            int y = (int) Math.floor(pos.y() + (target.y() - pos.y()) * t + 0.01);
            int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
            if (!(walkable.standable(bx, y, bz) || walkable.standable(bx, y + 1, bz) || walkable.standable(bx, y - 1, bz))) return false;
        }
        return true;
    }

    /** The straight line from pos to t never meets ground more than a jump above the current feet. */
    private boolean clearLine(Vec3d pos, Vec3d t) {
        double d = horiz(pos, t);
        int steps = (int) Math.ceil(d / 0.4);
        int fy = (int) Math.floor(pos.y() + 0.01);
        int lastY = fy;
        for (int i = 1; i <= steps; i++) {
            double f = i / (double) steps;
            int x = (int) Math.floor(pos.x() + (t.x() - pos.x()) * f), z = (int) Math.floor(pos.z() + (t.z() - pos.z()) * f);
            int g = walkable.snapToGround(x, lastY, z, 2);
            if (g == Integer.MIN_VALUE || g > lastY + 1) return false;
            lastY = g;
        }
        return true;
    }

    private boolean stepUpWithin(Vec3d pos, double dist) {
        for (int i = index + 1; i < path.size(); i++) {
            Vec3d p = path.get(i);
            if (horiz(pos, p) > dist) break;
            if (p.y() > pos.y() + 0.55) return true;
        }
        return false;
    }

    private double range(double[] r, long key) {
        return r[0] + (r[1] - r[0]) * noise.uniform(key ^ seed);
    }

    /** Moves index past points we've reached or passed. */
    private void advanceIndex(Vec3d pos) {
        while (index < path.size() - 1) {
            Vec3d a = path.get(index), b = path.get(index + 1);
            if (a.x() == b.x() && a.z() == b.z()) {
                // Vertical (ladder) segment: done once we're at its height.
                if (pos.y() >= b.y() - 0.1 || horiz(pos, a) > 1.5) {
                    index++;
                    continue;
                }
                break;
            }
            double t = projT(pos, a, b);
            if (t >= 1 || horiz(pos, a) < 0.35) {
                index++;
                continue;
            }
            break;
        }
    }

    private boolean climbAhead(Vec3d pos) {
        for (int i = index; i < Math.min(path.size() - 1, index + 2); i++) {
            Vec3d a = path.get(i), b = path.get(i + 1);
            if (a.x() == b.x() && a.z() == b.z() && b.y() > a.y() && horiz(pos, a) < 1.0) return true;
        }
        return false;
    }

    /** Distance still to walk: to the next point, then along the rest of the path (falls monotonically when following). */
    private double remainingAlongPath(Vec3d pos) {
        int next = Math.min(index + 1, path.size() - 1);
        double d = horiz(pos, path.get(next)) + Math.abs(path.get(next).y() - pos.y()) * 0.5;
        for (int i = next; i < path.size() - 1; i++) {
            d += horiz(path.get(i), path.get(i + 1)) + Math.abs(path.get(i + 1).y() - path.get(i).y()) * 0.5;
        }
        return d;
    }

    /**
     * Unsticking: walk to the centre of the nearest path cell, then cell by cell. Moves between neighbouring path cells
     * are always clear (the path finder checked them), unlike a cut corner.
     */
    private Vec3d recoveryTarget(Vec3d pos) {
        int best = index;
        double bd = Double.MAX_VALUE;
        for (int i = Math.max(0, index - 3); i <= Math.min(path.size() - 1, index + 3); i++) {
            double d = horiz(pos, path.get(i));
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        if (bd > 0.35) return path.get(best);
        index = Math.max(index, best);
        return path.get(Math.min(best + 1, path.size() - 1));
    }

    /** Average rise over run along the path for about dist blocks ahead. */
    private double grade(Vec3d pos, double dist) {
        Vec3d prev = pos;
        double run = 0;
        Vec3d last = pos;
        for (int i = index + 1; i < path.size() && run < dist; i++) {
            Vec3d p = path.get(i);
            run += horiz(prev, p);
            prev = p;
            last = p;
        }
        if (run < 0.5) return 0;
        return (last.y() - pos.y()) / run;
    }

    private boolean stepAhead(Vec3d pos, double dist) {
        for (int i = index + 1; i < path.size(); i++) {
            Vec3d p = path.get(i);
            if (horiz(pos, p) > dist) break;
            if (Math.abs(p.y() - path.get(i - 1).y()) >= 1) return true;
        }
        return false;
    }

    private Vec3d lookaheadPoint(Vec3d pos, double dist) {
        Vec3d prev = pos;
        double left = dist;
        for (int i = index + 1; i < path.size(); i++) {
            Vec3d p = path.get(i);
            double d = horiz(prev, p);
            // Don't look past a step up: aim at it so the jump lines up.
            if (p.y() > pos.y() + 0.55 && horiz(pos, p) < dist) return p;
            if (d >= left) {
                double f = left / d;
                return new Vec3d(prev.x() + (p.x() - prev.x()) * f, prev.y() + (p.y() - prev.y()) * f, prev.z() + (p.z() - prev.z()) * f);
            }
            left -= d;
            prev = p;
        }
        return path.get(path.size() - 1);
    }

    /** Yaw that faces the solid block the ladder at (x, y..y+1, z) hangs on. */
    private static double faceWall(BlockQuery q, int x, int y, int z, double fallback) {
        int[][] d = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};
        for (int dy = 0; dy <= 1; dy++) {
            for (int[] o : d) {
                if (q.collisionTop(x + o[0], y + dy, z + o[1]) > 0.6) return Math.toDegrees(Math.atan2(-o[0], o[1]));
            }
        }
        return fallback;
    }

    private static double projT(Vec3d p, Vec3d a, Vec3d b) {
        double dx = b.x() - a.x(), dz = b.z() - a.z();
        double l2 = dx * dx + dz * dz;
        if (l2 < 1e-9) return 1;
        return ((p.x() - a.x()) * dx + (p.z() - a.z()) * dz) / l2;
    }

    static double horiz(Vec3d a, Vec3d b) {
        double dx = a.x() - b.x(), dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dz * dz);
    }

    static double wrap(double deg) {
        deg %= 360;
        if (deg >= 180) deg -= 360;
        if (deg < -180) deg += 360;
        return deg;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double approach(double v, double target, double step) {
        return v < target ? Math.min(target, v + step) : Math.max(target, v - step);
    }
}
