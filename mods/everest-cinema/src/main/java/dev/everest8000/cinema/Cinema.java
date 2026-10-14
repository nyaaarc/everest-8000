package dev.everest8000.cinema;

import com.google.gson.JsonElement;
import dev.everest8000.cinema.camera.Director;
import dev.everest8000.cinema.capture.Recorder;
import dev.everest8000.cinema.puppet.Puppet;
import dev.everest8000.cinema.rpc.RpcException;
import dev.everest8000.cinema.shot.ShotRunner;
import dev.everest8000.cinema.spec.Pose;
import dev.everest8000.cinema.state.Snapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Shared state of the cinema tool and the per-frame/per-tick hooks. Everything here runs on the render thread. */
public final class Cinema {
    public static volatile boolean enabled;
    public static CinemaConfig config = CinemaConfig.DISABLED;
    public static final Director director = new Director();
    public static final Puppet puppet = new Puppet();
    public static final Recorder recorder = new Recorder();
    public static final ChatLog chat = new ChatLog();
    public static Snapshot snapshot;
    public static ShotRunner render;

    // Display flags read by the mixins.
    public static boolean chatHidden;
    public static boolean crosshairHidden;
    public static boolean playerHidden;
    public static boolean ride;
    public static boolean tickRateCompensation;

    private static final List<Task> tasks = new ArrayList<>();
    private static final List<Recorder.StillGrab> stills = new ArrayList<>();
    private static long lastFrameNanos;
    private static double realFps;
    private static int frames;

    private Cinema() {}

    // ---- hooks

    public static void onFrameStart(Minecraft mc) {
        if (!enabled) return;
        dev.everest8000.cinema.capture.FrameClock.ensureInstalled();
        recorder.beginFrame();
        if (tasks.isEmpty()) return;
        for (Task t : new ArrayList<>(tasks)) {
            boolean done;
            try {
                done = t.future.isDone() || t.update(mc);
            } catch (Throwable e) {
                t.future.completeExceptionally(e);
                t.onCancel("failed: " + e);
                done = true;
            }
            if (done) {
                tasks.remove(t);
                if (!t.future.isDone()) t.future.completeExceptionally(new RpcException(RpcException.INTERNAL, t.name + " ended without a result"));
            }
        }
    }

    public static void onFrameRendered(Minecraft mc) {
        if (!enabled) return;
        recorder.endFrame();
        dev.everest8000.cinema.lod.FrameProbe.onFrame();
        if (!stills.isEmpty()) {
            for (Recorder.StillGrab s : stills) {
                try {
                    s.grab();
                } catch (RuntimeException e) {
                    s.done.completeExceptionally(e);
                }
            }
            stills.clear();
        }
        long now = System.nanoTime();
        if (lastFrameNanos != 0) {
            double inst = 1e9 / Math.max(1, now - lastFrameNanos);
            realFps = realFps == 0 ? inst : realFps * 0.9 + inst * 0.1;
        }
        lastFrameNanos = now;
        frames++;
    }

    /** Server position packets received (handleMovePlayer runs on the network thread first, then on the main thread). */
    public static volatile int serverPositions;
    /** Particle classes (name prefixes) that are not added while set: e.g. weather snow in a calm scenery take. */
    public static volatile String[] particleBlock = new String[0];
    public static volatile long particlesBlocked;

    public static boolean particleBlocked(Object particle) {
        String[] block = particleBlock;
        if (block.length == 0) return false;
        String n = particle.getClass().getName();
        for (String b : block) {
            if (n.startsWith(b)) {
                particlesBlocked++;
                return true;
            }
        }
        return false;
    }
    /** While a server teleport for the rider is in flight, where it was sent. */
    private static net.minecraft.world.phys.Vec3 rideTeleport;
    private static long rideTeleportAt;

    public static void onServerPosition() {
        if (Minecraft.getInstance().isSameThread()) serverPositions++;
    }

    /**
     * Keeps the hidden spectator player exactly at the camera, every frame (so everything derived from the camera
     * entity is continuous). Moves the server would reject (more than 8 blocks in one go) are made with /tp instead.
     */
    public static void ridePlayer(dev.everest8000.cinema.spec.Pose pose) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || pose == null || pose.pos() == null || mc.getConnection() == null) return;
        double x = pose.pos().x(), y = pose.pos().y() - p.getEyeHeight(), z = pose.pos().z();
        if (rideTeleport != null) {
            boolean arrived = p.distanceToSqr(rideTeleport) < 4;
            if (!arrived && System.nanoTime() - rideTeleportAt < 3_000_000_000L) return;
            rideTeleport = null;
        }
        double d2 = p.distanceToSqr(x, y, z);
        if (d2 > 64) {
            rideTeleport = new net.minecraft.world.phys.Vec3(x, y, z);
            rideTeleportAt = System.nanoTime();
            mc.getConnection().sendCommand(String.format(java.util.Locale.ROOT, "tp @s %.4f %.4f %.4f", x, y, z));
            return;
        }
        p.setPos(x, y, z);
        p.xo = x;
        p.yo = y;
        p.zo = z;
        p.xOld = x;
        p.yOld = y;
        p.zOld = z;
        p.setDeltaMovement(Vec3.ZERO);
        if (p.isSpectator() || p.isCreative()) p.getAbilities().flying = true;
    }

    public static void onTickStart(Minecraft mc) {
        if (!enabled) return;
        mc.options.pauseOnLostFocus = false;
        if (mc.screen instanceof AccessibilityOnboardingScreen s) s.onClose();
        if (recorder.active() && (mc.screen instanceof PauseScreen || mc.screen instanceof ChatScreen)) mc.setScreen(null);
        puppet.tickStart(mc);
        if (render != null) render.tick(mc);
        if (ride && mc.player != null) ridePlayer(director.lastPose());
    }

    public static void onTickEnd(Minecraft mc) {
        if (!enabled) return;
        dev.everest8000.cinema.actor.Actors.tick(mc);
        puppet.tickEnd(mc);
    }

    public static void onDisconnect() {
        for (Task t : new ArrayList<>(tasks)) {
            t.future.completeExceptionally(new RpcException(RpcException.CANCELLED, "left the world"));
            t.onCancel("left the world");
        }
        tasks.clear();
        recorder.abort();
        dev.everest8000.cinema.actor.Actors.onDisconnect();
        puppet.stop();
        director.release();
        ride = false;
        playerHidden = false;
        render = null;
    }

    // ---- helpers

    public static double realFps() {
        return realFps;
    }

    public static int framesRendered() {
        return frames;
    }

    public static CompletableFuture<JsonElement> addTask(Task t) {
        tasks.add(t);
        return t.future;
    }

    public static List<Task> tasks() {
        return tasks;
    }

    public static void requestStill(Recorder.StillGrab g) {
        stills.add(g);
    }

    /** Runs on the render thread and returns its result. */
    public static CompletableFuture<JsonElement> onMain(Supplier<JsonElement> work) {
        return Minecraft.getInstance().submit(work);
    }

    /** Runs on the render thread; the work returns a future that completes later (e.g. a Task's). */
    public static CompletableFuture<JsonElement> onMainAsync(Supplier<CompletableFuture<JsonElement>> work) {
        return Minecraft.getInstance().submit(work).thenCompose(f -> f);
    }

    public static Minecraft requireWorld() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.getConnection() == null) throw RpcException.notInWorld();
        return mc;
    }

    /** Throws BUSY while a render or capture runs. */
    public static void requireIdle() {
        if (render != null) throw RpcException.busy("capture.render of shot \"" + render.name() + "\"");
        if (recorder.active()) throw RpcException.busy("a capture");
    }

    /** Sends a command as the player (without the slash). */
    public static void sendCommand(String command) {
        Minecraft mc = requireWorld();
        mc.getConnection().sendCommand(command.startsWith("/") ? command.substring(1) : command);
    }
}
