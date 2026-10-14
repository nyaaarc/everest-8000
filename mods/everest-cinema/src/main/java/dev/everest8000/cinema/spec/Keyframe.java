package dev.everest8000.cinema.spec;

/**
 * One keyframe of a keyframes move. {@code t} is seconds from the start of the move (NaN until assigned). Orientation is
 * either yaw/pitch or {@code lookAt} (or neither when the move has its own lookAt). {@code fov} is NaN to inherit the
 * move's FOV. With {@code relativeTo}, {@code pos} is an offset from that target's current position.
 */
public record Keyframe(double t, Vec3d pos, double yaw, double pitch, Target lookAt, double roll, double fov, Target relativeTo) {
    public boolean hasAngles() {
        return !Double.isNaN(yaw);
    }

    public Keyframe withT(double nt) {
        return new Keyframe(nt, pos, yaw, pitch, lookAt, roll, fov, relativeTo);
    }

    static Keyframe parse(JObj o, boolean moveHasLookAt) {
        double t = o.optNum("t", 0, 3600);
        Vec3d pos = o.vec3("pos");
        boolean hasYaw = o.has("yaw"), hasPitch = o.has("pitch"), hasLook = o.has("lookAt");
        if (hasYaw != hasPitch) throw new SpecException(o.at(hasYaw ? "pitch" : "yaw"), "yaw and pitch must be given together");
        if (moveHasLookAt && (hasYaw || hasLook)) {
            throw new SpecException(o.path(), "the move has lookAt, so keyframes must not set yaw/pitch/lookAt");
        }
        if (!moveHasLookAt && hasYaw == hasLook) {
            throw new SpecException(o.path(), "give exactly one of yaw+pitch or lookAt (or set lookAt on the move)");
        }
        double yaw = hasYaw ? o.num("yaw", -36000, 36000) : Double.NaN;
        double pitch = hasPitch ? o.num("pitch", -90, 90) : Double.NaN;
        JObj lookObj = o.optObj("lookAt");
        Target look = lookObj == null ? null : Target.parse(lookObj);
        double roll = o.num("roll", -180, 180, 0);
        double fov = o.optNum("fov", 1, 170);
        JObj relObj = o.optObj("relativeTo");
        Target rel = relObj == null ? null : Target.parse(relObj);
        o.finish();
        return new Keyframe(t, pos, yaw, pitch, look, roll, fov, rel);
    }
}
