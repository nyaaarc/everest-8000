package dev.everest8000.cinema.spec;

/** A plain 3D vector in world coordinates (blocks), independent of Minecraft classes. */
public record Vec3d(double x, double y, double z) {
    public static final Vec3d ZERO = new Vec3d(0, 0, 0);

    public Vec3d add(Vec3d o) {
        return new Vec3d(x + o.x, y + o.y, z + o.z);
    }

    public Vec3d sub(Vec3d o) {
        return new Vec3d(x - o.x, y - o.y, z - o.z);
    }

    public Vec3d scale(double s) {
        return new Vec3d(x * s, y * s, z * s);
    }

    public double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    public double distance(Vec3d o) {
        return sub(o).length();
    }

    public static Vec3d lerp(Vec3d a, Vec3d b, double t) {
        return new Vec3d(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t);
    }

    /** Minecraft yaw (degrees; 0 = facing +Z/south, 90 = facing -X/west) of the direction from this point to {@code to}. */
    public double yawTo(Vec3d to) {
        return Math.toDegrees(Math.atan2(-(to.x - x), to.z - z));
    }

    /** Minecraft pitch (degrees; positive = looking down) of the direction from this point to {@code to}. */
    public double pitchTo(Vec3d to) {
        double dx = to.x - x, dy = to.y - y, dz = to.z - z;
        return -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
    }

    /** Unit direction for a Minecraft yaw/pitch. */
    public static Vec3d direction(double yawDeg, double pitchDeg) {
        double yaw = Math.toRadians(yawDeg), pitch = Math.toRadians(pitchDeg);
        double c = Math.cos(pitch);
        return new Vec3d(-Math.sin(yaw) * c, -Math.sin(pitch), Math.cos(yaw) * c);
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT, "[%.2f, %.2f, %.2f]", x, y, z);
    }
}
