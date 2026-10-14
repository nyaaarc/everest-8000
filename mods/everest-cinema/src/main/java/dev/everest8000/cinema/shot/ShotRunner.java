package dev.everest8000.cinema.shot;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.everest8000.cinema.ActionRunner;
import dev.everest8000.cinema.ChatLog;
import dev.everest8000.cinema.Cinema;
import dev.everest8000.cinema.CommandTask;
import dev.everest8000.cinema.LoadWaitTask;
import dev.everest8000.cinema.Task;
import dev.everest8000.cinema.camera.McTargets;
import dev.everest8000.cinema.capture.Recorder;
import dev.everest8000.cinema.rpc.RpcException;
import dev.everest8000.cinema.spec.Action;
import dev.everest8000.cinema.spec.PreviewSettings;
import dev.everest8000.cinema.spec.RenderSettings;
import dev.everest8000.cinema.spec.ShotSpec;
import dev.everest8000.cinema.state.Iris;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.GameType;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * capture.render: prepares, warms up, records and tears down one shot, frame-stepped. Runs as a Task (one step per
 * frame); timed events fire from the client tick at the shot time of the frame being made.
 */
public final class ShotRunner extends Task {
    private enum Phase { RESIZE, PREPARE, WARMUP, SCOUT, ROLL, FINISH, TEARDOWN }

    private final ShotSpec shot;
    private final RenderSettings rs;
    private final PreviewSettings preview;
    private final Path outDir;
    private final int fps;
    private final int totalFrames;
    private final JsonArray warnings = new JsonArray();
    private final Deque<Supplier<Task>> queue = new ArrayDeque<>();
    private Phase phase = Phase.RESIZE;
    private long phaseStart = System.nanoTime();
    private Task current;
    private int width, height;
    private int eventIndex;
    private ChatLog.Collector rollFeedback;
    private CompletableFuture<JsonObject> stopping;
    private JsonObject captureResult;
    private JsonObject warmupResult;
    private int scoutStage;
    private float tickRateBefore = 20f;
    private long lastSyncNanos;
    private int lastSyncFrame;
    private double appliedRate = -1;
    private int correctionsAtRoll;
    private com.google.gson.JsonArray actorReport;
    private String[] particleBlockBefore;

    public ShotRunner(ShotSpec shot, RenderSettings rs, PreviewSettings preview, Path outDir) {
        super("capture.render " + shot.name());
        this.shot = shot;
        this.rs = rs;
        this.preview = preview;
        this.outDir = outDir;
        this.fps = preview != null ? preview.fps() : rs.fps();
        this.totalFrames = Math.max(1, (int) Math.round(shot.durationSec() * fps));
    }

    public String name() {
        return shot.name();
    }

    public String phase() {
        return phase.name().toLowerCase(Locale.ROOT);
    }

    public JsonObject status() {
        JsonObject o = new JsonObject();
        o.addProperty("shot", shot.name());
        o.addProperty("phase", phase());
        o.addProperty("frame", Cinema.recorder.active() ? Cinema.recorder.frame() : 0);
        o.addProperty("totalFrames", totalFrames);
        o.addProperty("elapsedSec", Math.round(elapsedSec() * 10) / 10.0);
        return o;
    }

    private void setPhase(Phase p) {
        phase = p;
        phaseStart = System.nanoTime();
    }

    private double phaseSec() {
        return (System.nanoTime() - phaseStart) / 1e9;
    }

    /** Runs queued tasks in order; true when the queue is empty. Failures abort the shot. */
    private boolean drain(Minecraft mc) throws Exception {
        while (true) {
            if (current == null) {
                Supplier<Task> next = queue.poll();
                if (next == null) return true;
                current = next.get();
                if (current == null) continue;
            }
            if (!current.future.isDone() && !current.update(mc)) return false;
            try {
                current.future.join();
            } catch (RuntimeException e) {
                Throwable c = e.getCause() != null ? e.getCause() : e;
                if (c instanceof RpcException r) throw r;
                throw new RpcException(RpcException.INTERNAL, current.name + " failed: " + c);
            }
            current = null;
        }
    }

    @Override
    public boolean update(Minecraft mc) throws Exception {
        Cinema.requireWorld();
        switch (phase) {
            case RESIZE -> {
                width = rs.width();
                height = rs.height();
                if (preview != null) {
                    width = Math.max(16, (int) Math.round(rs.width() * preview.scale() / 2) * 2);
                    height = Math.max(16, (int) Math.round(rs.height() * preview.scale() / 2) * 2);
                }
                var w = mc.getWindow();
                boolean offscreen = rs.offscreen().equals("always") || dev.everest8000.cinema.capture.RenderSize.active()
                        && !rs.offscreen().equals("never");
                if (offscreen) {
                    // Offscreen: the framebuffer takes the capture size whatever the window is.
                    dev.everest8000.cinema.capture.RenderSize.set(mc, width, height);
                } else if (w.getWidth() != width || w.getHeight() != height) {
                    if (phaseSec() < 0.05) {
                        if (w.isFullscreen()) w.toggleFullScreen();
                        GLFW.glfwSetWindowSize(w.getWindow(), width, height);
                    }
                    if (phaseSec() < 6) return false;
                    if (rs.offscreen().equals("auto")) {
                        dev.everest8000.cinema.capture.RenderSize.set(mc, width, height);
                        warnings.add("the screen can't hold a " + width + "x" + height + " window; rendering offscreen at that size");
                    } else {
                        warnings.add("window is " + w.getWidth() + "x" + w.getHeight() + " instead of " + width + "x" + height
                                + " (the screen may be too small); recording at that size");
                    }
                }
                if (dev.everest8000.cinema.capture.RenderSize.active() && phaseSec() < 1.0) return false; // let Iris/DH rebuild
                prepare(mc);
                setPhase(Phase.PREPARE);
            }
            case PREPARE -> {
                if (!drain(mc)) return false;
                setPhase(Phase.WARMUP);
                queueWarmup(shot.warmup().chunksTimeoutSec(), true);
            }
            case WARMUP -> {
                if (!drain(mc)) return false;
                if (Cinema.director.error() != null) {
                    throw new RpcException(RpcException.NOT_FOUND, "camera target: " + Cinema.director.error());
                }
                if (shot.warmup().scout() && scoutStage == 0) {
                    scoutStage = 1;
                    Cinema.director.restart(shot.warmup().scoutSpeed());
                    setPhase(Phase.SCOUT);
                    return false;
                }
                startRoll(mc);
                setPhase(Phase.ROLL);
            }
            case SCOUT -> {
                if (scoutStage == 1) {
                    if (!Cinema.director.finished()) return false;
                    Cinema.director.holdAtStart(true);
                    scoutStage = 2;
                    queueWarmup(Math.max(5, shot.warmup().chunksTimeoutSec() / 2), false);
                }
                if (!drain(mc)) return false;
                startRoll(mc);
                setPhase(Phase.ROLL);
            }
            case ROLL -> {
                String err = Cinema.recorder.error();
                if (err != null) throw new RpcException(RpcException.CAPTURE_FAILED, err);
                if (rs.serverTickSync()) syncTickRate();
                if (Cinema.recorder.frame() < totalFrames) return false;
                stopping = Cinema.recorder.stop();
                Cinema.puppet.reset();
                Cinema.tickRateCompensation = false;
                if (rs.serverTickSync() && appliedRate > 0) {
                    Cinema.sendCommand(String.format(Locale.ROOT, "tick rate %.3f", tickRateBefore));
                }
                if (rollFeedback != null) {
                    for (ChatLog.Msg m : rollFeedback.messages) if (m.error()) warnings.add("during the roll, the server said: " + m.text());
                    rollFeedback.close();
                    rollFeedback = null;
                }
                if (Cinema.director.error() != null) warnings.add("camera target lost during the roll: " + Cinema.director.error());
                int corrections = Cinema.serverPositions - correctionsAtRoll;
                if (corrections > 0) {
                    warnings.add("the server moved the player " + corrections + " time(s) during the roll (a rejected move or a"
                            + " teleport); anything that follows the camera entity may have flickered");
                }
                actorReport = dev.everest8000.cinema.actor.Actors.list();
                setPhase(Phase.FINISH);
            }
            case FINISH -> {
                restoreParticles();
                if (!stopping.isDone()) return false;
                try {
                    captureResult = stopping.join();
                } catch (RuntimeException e) {
                    Throwable c = e.getCause() != null ? e.getCause() : e;
                    throw c instanceof RpcException r ? r : new RpcException(RpcException.CAPTURE_FAILED, c.toString());
                }
                if (!shot.keepActors()) {
                    for (dev.everest8000.cinema.spec.ActorSpec as : shot.actors()) {
                        if (dev.everest8000.cinema.actor.Actors.find(as.id()) != null) dev.everest8000.cinema.actor.Actors.despawn(as.id());
                    }
                }
                for (Action a : shot.teardown()) queue.add(() -> ActionRunner.start(a, true));
                setPhase(Phase.TEARDOWN);
            }
            case TEARDOWN -> {
                if (!drain(mc)) return false;
                JsonObject r = captureResult.deepCopy();
                r.addProperty("shot", shot.name());
                r.addProperty("preview", preview != null);
                if (warmupResult != null) r.add("warmup", warmupResult);
                if (actorReport != null && actorReport.size() > 0) r.add("actors", actorReport);
                r.add("warnings", warnings);
                r.addProperty("totalSec", Math.round(elapsedSec() * 10) / 10.0);
                writeReport(r);
                Cinema.render = null;
                future.complete(r);
                return true;
            }
        }
        return false;
    }

    private void prepare(Minecraft mc) {
        McTargets.INSTANCE.clearLocks();
        if (!shot.particles().block().isEmpty()) {
            particleBlockBefore = Cinema.particleBlock;
            Cinema.particleBlock = shot.particles().block().toArray(new String[0]);
        }
        Cinema.puppet.reset();
        ShotSpec.HudSpec h = shot.hud();
        ActionRunner.applyHud(mc, h.visible(), h.chat(), h.crosshair(), h.hand());
        if (shot.shaders() != null && !shot.shaders().equals(Iris.enabled())) {
            queue.add(() -> {
                Iris.set(shot.shaders());
                return ActionRunner.waitTask(3);
            });
        }
        ShotSpec.PlayerSetup p = shot.player();
        // Stop riding the previous shot's camera before this shot moves the player (else every frame puts the player
        // back at the camera and a teleport never "arrives").
        if (!p.ride()) Cinema.ride = false;
        String mode = p.gamemode();
        if (p.ride() && mode == null) mode = "spectator";
        if (p.ride() && !"spectator".equals(mode)) {
            throw new RpcException(RpcException.INVALID_PARAMS, "shot.player.ride needs spectator mode, but gamemode is " + mode);
        }
        if (mode != null && mc.gameMode.getPlayerMode() != GameType.byName(mode)) {
            String m = mode;
            queue.add(() -> new CommandTask("gamemode " + m, 3, 200, true));
        }
        if (p.pos() != null) {
            Action.Teleport tp = new Action.Teleport(p.pos(), p.yaw(), p.pitch());
            queue.add(() -> new CommandTask(ActionRunner.command(tp), 3, 200, true));
        }
        queue.add(() -> {
            Cinema.playerHidden = !p.visible();
            Cinema.ride = p.ride();
            Cinema.director.play(shot.camera(), shot.durationSec());
            Cinema.director.holdAtStart(true);
            return null;
        });
        for (Action a : shot.setup()) queue.add(() -> ActionRunner.start(a, true));
        if (!shot.actors().isEmpty()) {
            queue.add(() -> {
                for (dev.everest8000.cinema.spec.ActorSpec as : shot.actors()) {
                    JsonObject r = dev.everest8000.cinema.actor.Actors.spawn(as, false);
                    for (var w : r.getAsJsonArray("warnings")) warnings.add("actor " + as.id() + ": " + w.getAsString());
                }
                return null;
            });
        }
    }

    private void queueWarmup(double chunksTimeout, boolean full) {
        ShotSpec.Warmup w = shot.warmup();
        ShotSpec.PlayerSetup p = shot.player();
        if (p.pos() != null && !p.ride()) {
            queue.add(() -> Task.poll("teleport arrival", 15, () -> {
                var pl = Minecraft.getInstance().player;
                double dx = pl.getX() - p.pos().x(), dz = pl.getZ() - p.pos().z(), dy = pl.getY() - p.pos().y();
                // Horizontal match; the player may land a little above or below the requested height.
                return dx * dx + dz * dz < 6.25 && Math.abs(dy) < 16 ? new JsonObject() : null;
            }));
        }
        if (w.lod().enabled()) {
            // Previews carry on past a timeout (framing checks don't need every LOD); takes fail unless told otherwise.
            dev.everest8000.cinema.spec.LodGateSpec gate = preview != null ? w.lod().withOnTimeout("continue") : w.lod();
            queue.add(() -> {
                java.util.List<dev.everest8000.cinema.spec.Pose> poses = full
                        ? dev.everest8000.cinema.lod.LodGate.warmPoses(new dev.everest8000.cinema.camera.MovePlayer(shot.camera(), shot.durationSec()),
                        shot.durationSec(), gate.warmSpacing(), gate.warmMaxPoints())
                        : java.util.List.of();
                Path stillsBase = outDir.resolve(shot.name() + (preview != null ? "_preview" : "") + (full ? "" : "_rescout"));
                dev.everest8000.cinema.lod.LodGate g = new dev.everest8000.cinema.lod.LodGate(gate, poses, () -> {
                    Cinema.director.play(shot.camera(), shot.durationSec());
                    Cinema.director.holdAtStart(true);
                }, p.ride(), stillsBase);
                g.future.thenAccept(r -> {
                    warmupResult = r.getAsJsonObject();
                    if ("timeout".equals(warmupResult.get("status").getAsString())) {
                        warnings.add("the LOD gate timed out after " + gate.timeoutSec() + " s (see warmup.dh and warmup.timeline)");
                    }
                });
                return g;
            });
            queue.add(() -> {
                if (gate.failOnTimeout() && warmupResult != null && "timeout".equals(warmupResult.get("status").getAsString())) {
                    throw new RpcException(RpcException.TIMEOUT, "LOD gate: Distant Horizons wasn't ready after " + gate.timeoutSec()
                            + " s (warmup.onTimeout \"continue\" to film anyway)", warmupResult);
                }
                return null;
            });
            double min = w.minSec();
            long start0 = System.nanoTime();
            queue.add(() -> ActionRunner.waitTask(Math.max(0, min - (System.nanoTime() - start0) / 1e9)));
            return;
        }
        double settle = full ? w.lodSettleSec() : Math.min(2, w.lodSettleSec());
        queue.add(() -> {
            LoadWaitTask t = new LoadWaitTask(chunksTimeout, settle > 0, settle);
            t.future.thenAccept(r -> {
                warmupResult = r.getAsJsonObject();
                if ("timeout".equals(warmupResult.get("status").getAsString())) {
                    warnings.add("chunks were still loading after " + chunksTimeout + " s of warm-up");
                }
            });
            return t;
        });
        double min = w.minSec();
        long start = System.nanoTime();
        queue.add(() -> ActionRunner.waitTask(Math.max(0, min - (System.nanoTime() - start) / 1e9)));
    }

    private void startRoll(Minecraft mc) {
        if (shot.particles().clear() && !shot.particles().block().isEmpty()) ((dev.everest8000.cinema.mixin.ParticleEngineAccess) mc.particleEngine).cinema$clearParticles();
        String base = shot.name() + (preview != null ? "_preview" : "");
        Path video = null;
        if (preview == null || preview.video()) {
            video = outDir.resolve(base + (rs.codec().equals("png") ? "" : ".mp4"));
        }
        RenderSettings settings = preview == null ? rs
                : new RenderSettings(width, height, fps, "h264_nvenc", 26, false, rs.serverTickSync(), rs.sheetFrames(), rs.offscreen());
        Map<Integer, Path> stills = new HashMap<>();
        if (preview != null) {
            int n = preview.stills();
            for (int k = 0; k < n; k++) {
                int f = n == 1 ? 0 : (int) Math.round(k * (totalFrames - 1) / (double) (n - 1));
                stills.put(f, outDir.resolve(String.format(Locale.ROOT, "%s_still%02d.png", base, k)));
            }
        }
        List<Integer> sheet = new ArrayList<>();
        int sf = rs.sheetFrames();
        for (int k = 0; k < sf; k++) sheet.add(sf == 1 ? 0 : (int) Math.round(k * (totalFrames - 1) / (double) (sf - 1)));
        Recorder.Plan plan = new Recorder.Plan(video, settings, fps, totalFrames, stills, sheet,
                sf > 0 ? outDir.resolve(base + "_sheet.png") : null, shot.name(), Cinema.config.ffmpeg());
        try {
            Files.createDirectories(outDir);
        } catch (IOException e) {
            throw new RpcException(RpcException.CAPTURE_FAILED, "couldn't create " + outDir + ": " + e.getMessage());
        }
        rollFeedback = Cinema.chat.open();
        tickRateBefore = mc.level.tickRateManager().tickrate();
        Cinema.tickRateCompensation = rs.serverTickSync();
        Cinema.recorder.start(plan);
        Cinema.director.restart(1.0);
        correctionsAtRoll = Cinema.serverPositions;
        for (dev.everest8000.cinema.spec.ActorSpec as : shot.actors()) {
            dev.everest8000.cinema.actor.Actor a = dev.everest8000.cinema.actor.Actors.find(as.id());
            if (a != null) a.start(as.timeline());
        }
        eventIndex = 0;
        lastSyncNanos = System.nanoTime();
        lastSyncFrame = 0;
    }

    /** Every 2 s, slow the server to the render speed so mobs and weather keep pace with the recording. */
    private void syncTickRate() {
        long now = System.nanoTime();
        if (now - lastSyncNanos < 2_000_000_000L) return;
        int frames = Cinema.recorder.frame() - lastSyncFrame;
        double realFps = frames / ((now - lastSyncNanos) / 1e9);
        lastSyncNanos = now;
        lastSyncFrame = Cinema.recorder.frame();
        double rate = Math.max(1, Math.min(tickRateBefore, tickRateBefore * realFps / fps));
        if (appliedRate < 0 || Math.abs(rate - appliedRate) / appliedRate > 0.1) {
            appliedRate = rate;
            Cinema.sendCommand(String.format(Locale.ROOT, "tick rate %.2f", rate));
        }
    }

    /** Client tick: fire timed events due at the current shot time. */
    public void tick(Minecraft mc) {
        if (phase != Phase.ROLL) return;
        double t = Cinema.director.time();
        List<ShotSpec.Event> events = shot.events();
        while (eventIndex < events.size() && events.get(eventIndex).atSec() <= t + 1e-6) {
            ShotSpec.Event e = events.get(eventIndex++);
            try {
                ActionRunner.start(e.action(), false);
            } catch (RuntimeException ex) {
                warnings.add(String.format(Locale.ROOT, "event at %.2f s (%s) failed: %s", e.atSec(), e.action().typeName(), ex.getMessage()));
            }
        }
    }

    private void writeReport(JsonObject r) {
        try {
            Files.writeString(outDir.resolve(shot.name() + (preview != null ? "_preview" : "") + ".json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(r), StandardCharsets.UTF_8);
        } catch (IOException e) {
            warnings.add("couldn't write the report: " + e.getMessage());
        }
    }

    private void restoreParticles() {
        if (particleBlockBefore != null) {
            Cinema.particleBlock = particleBlockBefore;
            particleBlockBefore = null;
        }
    }

    @Override
    public void onCancel(String reason) {
        restoreParticles();
        onCancelInner(reason);
    }

    private void onCancelInner(String reason) {
        if (current != null) current.onCancel(reason);
        if (phase != Phase.TEARDOWN && Minecraft.getInstance().getConnection() != null) {
            // Best effort: undo what the shot set up (weather, tick rate, game mode...) even though it failed.
            for (Action a : shot.teardown()) {
                try {
                    ActionRunner.start(a, false);
                } catch (RuntimeException e) {
                    dev.everest8000.cinema.CinemaClient.LOG.warn("Cinema: teardown step {} failed after an aborted shot", a.typeName(), e);
                }
            }
        }
        Cinema.recorder.abort();
        Cinema.puppet.reset();
        Cinema.tickRateCompensation = false;
        if (rollFeedback != null) rollFeedback.close();
        if (appliedRate > 0 && Minecraft.getInstance().getConnection() != null) {
            Cinema.sendCommand(String.format(Locale.ROOT, "tick rate %.3f", tickRateBefore));
        }
        Cinema.director.holdAtStart(true);
        if (!shot.keepActors()) {
            for (dev.everest8000.cinema.spec.ActorSpec as : shot.actors()) {
                if (dev.everest8000.cinema.actor.Actors.find(as.id()) != null) dev.everest8000.cinema.actor.Actors.despawn(as.id());
            }
        }
        dev.everest8000.cinema.lod.FrameProbe.stop();
        Cinema.render = null;
    }

    /** For state.get. */
    public static JsonElement describe() {
        return Cinema.render == null ? null : Cinema.render.status();
    }
}
