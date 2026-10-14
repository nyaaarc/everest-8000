package dev.everest8000.cinema.camera;

import dev.everest8000.cinema.spec.Move;
import dev.everest8000.cinema.spec.Pose;
import dev.everest8000.cinema.spec.Vec3d;
import net.minecraft.Util;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Owns the cinema camera. In CINEMA mode the vanilla camera is detached and placed at the current move's pose every
 * frame (the player model renders because the camera is detached); in FIRST_PERSON mode the vanilla first-person view
 * is used with an optional FOV override; in PLAYER mode nothing is changed. Main thread only.
 */
public final class Director {
    public enum Mode { PLAYER, CINEMA, FIRST_PERSON }

    private Mode mode = Mode.PLAYER;
    private MovePlayer move;
    private long startNanos;
    private double timeScale = 1;
    private boolean hold;
    private Pose lastPose;
    private CameraType savedCameraType;
    private String error;

    public Mode mode() {
        return mode;
    }

    public MovePlayer current() {
        return move;
    }

    /** Last pose rendered (null before the first cinema frame). */
    public Pose lastPose() {
        return lastPose;
    }

    /** An error from the last frame's evaluation (e.g. a target entity vanished), or null. */
    public String error() {
        return error;
    }

    /** Starts a move now. fallbackDuration is used when the move has no duration of its own. */
    public void play(Move m, double fallbackDuration) {
        move = new MovePlayer(m, fallbackDuration);
        startNanos = Util.getNanos();
        timeScale = 1;
        hold = false;
        error = null;
        setMode(m instanceof Move.FirstPerson ? Mode.FIRST_PERSON : Mode.CINEMA);
    }

    /** Freezes the move at t = 0 (warm-up) or lets it run from t = 0 now. */
    public void holdAtStart(boolean h) {
        hold = h;
        startNanos = Util.getNanos();
    }

    /** Restarts the move clock at t = 0, at the given speed (scout passes fly faster). */
    public void restart(double scale) {
        startNanos = Util.getNanos();
        timeScale = scale;
        hold = false;
    }

    /** Seconds into the current move. */
    public double time() {
        if (move == null || hold) return 0;
        return (Util.getNanos() - startNanos) / 1e9 * timeScale;
    }

    public boolean finished() {
        return move != null && !hold && time() >= move.duration();
    }

    /** Stops the move where it is; the camera keeps its last pose. */
    public void stop() {
        if (mode == Mode.CINEMA && lastPose != null && lastPose.pos() != null) {
            Pose p = lastPose;
            play(new Move.Static(p.pos(), p.yaw(), p.pitch(), null, 0, p.roll(), p.fov(), Double.NaN, null), Double.POSITIVE_INFINITY);
        } else if (mode == Mode.FIRST_PERSON) {
            move = null;
        } else {
            move = null;
        }
    }

    /** Back to the normal player camera. */
    public void release() {
        move = null;
        lastPose = null;
        setMode(Mode.PLAYER);
    }

    private void setMode(Mode m) {
        Minecraft mc = Minecraft.getInstance();
        if (m == mode) return;
        if (mode == Mode.PLAYER) savedCameraType = mc.options.getCameraType();
        switch (m) {
            case CINEMA -> {
                // Third person keeps the hand and crosshair away; the pose itself comes from apply().
                mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                mc.gameRenderer.setRenderBlockOutline(false);
            }
            case FIRST_PERSON -> {
                mc.options.setCameraType(CameraType.FIRST_PERSON);
                mc.gameRenderer.setRenderBlockOutline(false);
            }
            case PLAYER -> {
                mc.options.setCameraType(savedCameraType == null ? CameraType.FIRST_PERSON : savedCameraType);
                mc.gameRenderer.setRenderBlockOutline(true);
            }
        }
        mode = m;
    }

    /** The pose for this frame; null keeps vanilla (PLAYER mode, or first person). */
    public Pose sample(float partialTick) {
        if (move == null || mode == Mode.PLAYER) return null;
        try {
            Pose p = move.sample(Math.min(time(), move.duration()), partialTick, McTargets.INSTANCE);
            error = null;
            if (p.pos() == null) return p;
            lastPose = p;
            return p;
        } catch (TargetResolver.TargetNotFoundException e) {
            error = e.getMessage();
            return lastPose;
        }
    }

    /** FOV override for this frame, or NaN. */
    public double fovOverride() {
        if (mode == Mode.PLAYER || move == null) return Double.NaN;
        if (mode == Mode.FIRST_PERSON) return move.move() instanceof Move.FirstPerson fp ? fp.fov() : Double.NaN;
        return lastPose == null ? Double.NaN : lastPose.fov();
    }

    /** Where the camera is (cinema pose, or the player's eyes). */
    public Vec3d position() {
        Minecraft mc = Minecraft.getInstance();
        if (mode == Mode.CINEMA && lastPose != null && lastPose.pos() != null) return lastPose.pos();
        Player p = mc.player;
        if (p == null) return Vec3d.ZERO;
        Vec3 e = p.getEyePosition();
        return new Vec3d(e.x, e.y, e.z);
    }
}
