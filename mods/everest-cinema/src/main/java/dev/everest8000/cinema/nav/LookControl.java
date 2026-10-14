package dev.everest8000.cinema.nav;

/**
 * Where a walker looks: an explicit target (tracked, or glanced at for a while), otherwise ahead along the way with
 * occasional idle glances (wider ones during breathers). The head leads with a damped, speed-limited motion; it never
 * turns more than {@link #MAX_HEAD_OFFSET} from the body, and a standing body slowly turns to follow a head held far to
 * one side. Deterministic for a given seed.
 */
public final class LookControl {
    public static final double MAX_HEAD_OFFSET = 70;
    private static final double DT = 0.05;

    /** headYaw and pitch for the head; bodyYaw is the (possibly adjusted) body heading; crouch for a breather cue. */
    public record Look(double headYaw, double pitch, double bodyYaw, boolean crouch) {}

    /**
     * What a breather looks like on camera, chosen per breather: 0 head down at the feet then up at the slope ahead,
     * 1 a look back down the route (at a teammate when backYaw is given), 2 a slight crouch with the head down.
     */
    public record Breather(int phase, double progress, int count, double backYaw) {
        public static final Breather NONE = new Breather(0, 0, 0, Double.NaN);
    }

    private final Noise noise;
    private final boolean idle;
    private double headYaw = Double.NaN, pitch;
    private double yawVel, pitchVel;
    private double targetYaw = Double.NaN, targetPitch;
    private int glanceTicks;
    private boolean track;
    private int tick;
    private int idleGlanceLeft;
    private double idleOffset, idlePitch;
    private int nextIdle = 60;
    private int idleCount;

    public LookControl(long seed, boolean idleLook) {
        this.noise = new Noise(seed * 31 + 7);
        this.idle = idleLook;
    }

    /** Keep looking at this direction until cleared (track) or for seconds (glance). */
    public void lookAt(double yaw, double pitch, boolean track, double seconds) {
        this.targetYaw = yaw;
        this.targetPitch = pitch;
        this.track = track;
        this.glanceTicks = track ? -1 : Math.max(1, (int) Math.round(seconds * 20));
    }

    /** Update a tracked target's direction each tick. */
    public void updateTarget(double yaw, double pitch) {
        if (Double.isNaN(targetYaw)) return;
        this.targetYaw = yaw;
        this.targetPitch = pitch;
    }

    public void clear() {
        targetYaw = Double.NaN;
        glanceTicks = 0;
    }

    public boolean hasTarget() {
        return !Double.isNaN(targetYaw);
    }

    public boolean tracking() {
        return hasTarget() && track;
    }

    /**
     * @param bodyYaw    current body heading
     * @param moving     walking (idle glances are smaller and the body leads)
     * @param aheadPitch pitch toward the way ahead (looking a little down, more on descents)
     * @param breather   a breather is on: look around more widely
     */
    public Look step(double bodyYaw, boolean moving, double aheadPitch, boolean breather) {
        return step(bodyYaw, moving, aheadPitch, breather ? new Breather(2, 0.5, 0, Double.NaN) : Breather.NONE);
    }

    public Look step(double bodyYaw, boolean moving, double aheadPitch, Breather br) {
        boolean breather = br.phase() != 0;
        boolean crouch = false;
        tick++;
        if (Double.isNaN(headYaw)) {
            headYaw = bodyYaw;
            pitch = aheadPitch;
        }
        double wantYaw, wantPitch;
        if (hasTarget()) {
            wantYaw = targetYaw;
            wantPitch = targetPitch;
            if (glanceTicks > 0 && --glanceTicks == 0) clear();
        } else {
            wantYaw = bodyYaw;
            wantPitch = aheadPitch;
            if (idle || breather) {
                if (idleGlanceLeft > 0) {
                    idleGlanceLeft--;
                    wantYaw += idleOffset;
                    wantPitch = idlePitch;
                } else if (--nextIdle <= 0) {
                    idleCount++;
                    double amp = breather ? 65 : moving ? 30 : 50;
                    idleOffset = amp * (noise.uniform(idleCount * 3L) * 2 - 1);
                    if (Math.abs(idleOffset) < amp * 0.3) idleOffset = Math.copySign(amp * 0.3, idleOffset == 0 ? 1 : idleOffset);
                    idlePitch = aheadPitch + (breather ? -25 : -8) * noise.uniform(idleCount * 3L + 1) + (breather ? 10 : 0);
                    idleGlanceLeft = (int) Math.round((breather ? 25 : 16) + 20 * noise.uniform(idleCount * 3L + 2));
                    nextIdle = (int) Math.round((breather ? 20 : moving ? 90 : 60) + 80 * noise.uniform(idleCount * 3L + 3));
                }
            }
            if (br.phase() == 2 || br.phase() == 3) {
                // The breather's own cue overrides idle glances.
                double p = br.phase() == 3 ? 1 : br.progress();
                int variant = (int) Math.floor(noise.uniform(br.count() * 13L + 5) * 3);
                switch (variant) {
                    case 0 -> {
                        wantYaw = bodyYaw;
                        wantPitch = p < 0.55 ? 38 : -8;
                    }
                    case 1 -> {
                        if (p > 0.15 && p < 0.75) {
                            wantYaw = Double.isNaN(br.backYaw()) ? bodyYaw + 160 : br.backYaw();
                            wantPitch = 14;
                        } else {
                            wantYaw = bodyYaw;
                            wantPitch = 20;
                        }
                    }
                    default -> {
                        crouch = p > 0.1 && p < 0.7;
                        wantYaw = bodyYaw;
                        wantPitch = 30;
                    }
                }
            }
            // A slow, small sway so a held look isn't frozen.
            wantYaw += 3 * noise.at(tick * DT * 0.4);
            wantPitch += 2 * noise.at(tick * DT * 0.33 + 50);
        }
        // Clamp the head to the body.
        double rel = Locomotion.wrap(wantYaw - bodyYaw);
        double clamped = Math.max(-MAX_HEAD_OFFSET, Math.min(MAX_HEAD_OFFSET, rel));
        // A standing body turns to follow a head held far to one side by a target (one behind it, say). Idle glances
        // never turn the body: repeated glances to one side would otherwise walk a standing actor round in circles.
        double body = bodyYaw;
        if (!moving && hasTarget() && Math.abs(rel) > 45) body = bodyYaw + Math.copySign(Math.min(90 * DT, Math.abs(rel) - 45), rel);
        wantYaw = body + clamped;
        // Damped spring toward the wanted direction, limited to 300 deg/s.
        double dy = Locomotion.wrap(wantYaw - headYaw);
        yawVel += (dy * 0.35 - yawVel) * 0.6;
        yawVel = Math.max(-15, Math.min(15, yawVel));
        headYaw += yawVel;
        double dp = wantPitch - pitch;
        pitchVel += (dp * 0.3 - pitchVel) * 0.6;
        pitchVel = Math.max(-10, Math.min(10, pitchVel));
        pitch = Math.max(-80, Math.min(80, pitch + pitchVel));
        return new Look(headYaw, pitch, body, crouch);
    }
}
