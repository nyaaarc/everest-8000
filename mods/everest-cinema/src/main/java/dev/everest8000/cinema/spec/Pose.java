package dev.everest8000.cinema.spec;

import java.util.Locale;

/** A camera pose: position, Minecraft yaw/pitch, roll (degrees, positive banks the camera left) and vertical FOV. */
public record Pose(Vec3d pos, double yaw, double pitch, double roll, double fov) {
    public Pose withFov(double f) {
        return new Pose(pos, yaw, pitch, roll, f);
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%s yaw %.2f pitch %.2f roll %.2f fov %.1f", pos, yaw, pitch, roll, fov);
    }
}
