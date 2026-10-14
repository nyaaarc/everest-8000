package dev.everest8000.cinema.lod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.everest8000.cinema.Cinema;
import dev.everest8000.cinema.LoadWaitTask;
import dev.everest8000.cinema.Task;
import dev.everest8000.cinema.capture.Recorder;
import dev.everest8000.cinema.spec.LodGateSpec;
import dev.everest8000.cinema.spec.Move;
import dev.everest8000.cinema.spec.Pose;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Waits until the world is fully drawn for a take (see {@link LodGateSpec}), with Distant Horizons' own progress
 * ({@link DhProbe}) as the signal and chunk sections plus picture stability as cross-checks. Never throws: the result
 * says "ready" or "timeout", how long each part took, and DH's counts over time (a row a second), so the caller decides
 * what a timeout means and the real numbers are on record.
 */
public final class LodGate extends Task {
    private static final long SAMPLE_NANOS = 250_000_000L;
    private static final String[] TIMELINE_FIELDS = {"t", "phase", "activity", "outstanding", "missingNear", "coarse", "building",
            "syncQueue", "retrieval", "renderTasks", "executors", "frameDiff", "missingAll"};

    private final LodGateSpec spec;
    private final List<Pose> warmPoses;
    private final Runnable atStart;
    private final boolean ride;
    private final boolean wasRiding;
    private final Path stillsBase;
    private int point = -1;
    private long phaseStart = System.nanoTime();
    private final JsonArray warmLog = new JsonArray();
    private boolean finalPhase;
    private long finalStart;
    private double warmSec;
    // Distant Horizons
    private DhProbe.Status last;
    private long lastExecCompleted = -1;
    private long dhReadyAt = -1;
    private boolean frameNeverSettled;
    private int lastActivity;
    private long lastSample;
    private long dhIdleSince;
    private int lastOutstanding = Integer.MIN_VALUE;
    private long outstandingChangedAt;
    // chunks and frame
    private long chunksQuietSince;
    private int chunkCount = -1;
    private long chunkCountSince;
    private long frameQuietSince;
    // record
    private final JsonArray timeline = new JsonArray();
    private long lastRow;
    private int stillStartIn = -1;
    private Recorder.StillGrab doneStill;
    private String finishStatus;
    private final JsonObject stills = new JsonObject();

    /**
     * @param warmPoses  camera poses to visit first (may be empty)
     * @param atStart    puts the camera back at the take's start pose before the final wait (null = leave it)
     * @param ride       move the spectator player with the camera during the warm pass
     * @param stillsBase with spec.stills, writes stillsBase + "_gate_start.png" / "_gate_done.png" (null = none)
     */
    public LodGate(LodGateSpec spec, List<Pose> warmPoses, Runnable atStart, boolean ride, Path stillsBase) {
        super("lod gate");
        this.spec = spec;
        this.warmPoses = warmPoses;
        this.atStart = atStart;
        this.ride = ride;
        this.wasRiding = Cinema.ride;
        this.stillsBase = spec.stills() ? stillsBase : null;
        DhProbe.nearRadius = spec.nearRadius();
        long now = System.nanoTime();
        dhIdleSince = now;
        outstandingChangedAt = now;
    }

    public LodGate(LodGateSpec spec, List<Pose> warmPoses, Runnable atStart, boolean ride) {
        this(spec, warmPoses, atStart, ride, null);
    }

    private double phaseSec() {
        return (System.nanoTime() - phaseStart) / 1e9;
    }

    @Override
    public boolean update(Minecraft mc) {
        Cinema.requireWorld();
        long now = System.nanoTime();
        sample(now);
        if (doneStill != null) {
            // Finishing: wait for the "done" still to be written (it is the frame the gate judged ready).
            if (!doneStill.done.isDone() && elapsedSinceDoneStill(now) < 10) return false;
            stills.addProperty("done", doneStill.path.toString());
            complete(mc, finishStatus, now);
            return true;
        }
        if (!finalPhase) {
            if (!spec.warm() || warmPoses.isEmpty()) {
                enterFinal(now);
                return false;
            }
            if (point < 0 || pointDone(now)) {
                if (point >= 0) {
                    JsonObject j = new JsonObject();
                    j.addProperty("point", point);
                    j.addProperty("sec", round1(phaseSec()));
                    j.addProperty("idle", idleFor(now) >= spec.warmQuietSec());
                    if (last != null) {
                        j.addProperty("outstanding", last.outstanding());
                        j.addProperty("activity", lastActivity);
                    }
                    warmLog.add(j);
                }
                point++;
                if (point >= warmPoses.size()) {
                    enterFinal(now);
                } else {
                    Pose p = warmPoses.get(point);
                    Cinema.director.play(new Move.Static(p.pos(), p.yaw(), p.pitch(), null, 0, 0, p.fov(), Double.NaN, null),
                            Double.POSITIVE_INFINITY);
                    if (ride && mc.player.isSpectator()) Cinema.ride = true;
                    phaseStart = now;
                    dhIdleSince = now;
                    outstandingChangedAt = now;
                }
            }
            return false;
        }
        // Final wait at the start pose.
        if (stillStartIn > 0 && --stillStartIn == 0) {
            Recorder.StillGrab g = new Recorder.StillGrab(Path.of(stillsBase + "_gate_start.png"));
            Cinema.requestStill(g);
            stills.addProperty("start", g.path.toString());
        }
        boolean sectionsDone = LoadWaitTask.sectionsDone(mc);
        int count = LoadWaitTask.loadedChunks(mc);
        if (count != chunkCount) {
            chunkCount = count;
            chunkCountSince = now;
        }
        if (!(sectionsDone && (now - chunkCountSince) / 1e9 >= 1.0)) chunksQuietSince = now;
        double fd = FrameProbe.lastDiff();
        if (spec.frameStability() && (Double.isNaN(fd) || fd >= spec.frameThreshold())) frameQuietSince = now;
        boolean dhOk = dhReady(now) && (now - chunksQuietSince) / 1e9 >= Math.min(spec.quietSec(), 2);
        if (dhOk && dhReadyAt < 0) dhReadyAt = now;
        if (!dhOk) dhReadyAt = -1;
        // The picture is a cross-check, not a gate: moving clouds, weather and particles can keep it changing forever,
        // so once DH is ready it gets at most frameMaxWaitSec to hold still.
        boolean frameOk = !spec.frameStability() || (now - frameQuietSince) / 1e9 >= spec.frameQuietSec();
        boolean frameGaveUp = dhReadyAt >= 0 && (now - dhReadyAt) / 1e9 >= spec.frameMaxWaitSec();
        if (dhOk && !frameOk && frameGaveUp) frameNeverSettled = true;
        boolean ready = dhOk && (frameOk || frameGaveUp) && (now - finalStart) / 1e9 >= spec.minSec();
        boolean timedOut = elapsedSec() >= spec.timeoutSec();
        if (ready || timedOut) {
            finishStatus = ready ? "ready" : "timeout";
            if (stillsBase != null) {
                doneStill = new Recorder.StillGrab(Path.of(stillsBase + "_gate_done.png"));
                doneStillAt = now;
                Cinema.requestStill(doneStill);
                return false;
            }
            complete(mc, finishStatus, now);
            return true;
        }
        return false;
    }

    private long doneStillAt;

    private double elapsedSinceDoneStill(long now) {
        return (now - doneStillAt) / 1e9;
    }

    private void complete(Minecraft mc, String status, long now) {
        future.complete(result(mc, status, now));
        cleanup();
    }

    private void enterFinal(long now) {
        finalPhase = true;
        warmSec = elapsedSec();
        if (atStart != null) atStart.run();
        Cinema.ride = wasRiding;
        phaseStart = now;
        finalStart = now;
        dhIdleSince = now;
        outstandingChangedAt = now;
        chunksQuietSince = now;
        frameQuietSince = now;
        chunkCountSince = now;
        if (spec.frameStability()) FrameProbe.start(0.25);
        if (stillsBase != null) stillStartIn = 3; // a couple of frames, so the camera is at the start pose
    }

    /** DH has had nothing in flight for quietSec, and nothing more is coming (or the remainder stopped changing). */
    private boolean dhReady(long now) {
        if (last == null) return false;
        if (!last.available()) return true; // no DH: chunks and the picture decide
        boolean idle = idleFor(now) >= spec.quietSec();
        boolean settled = last.outstanding() == 0 || (now - outstandingChangedAt) / 1e9 >= spec.stallSec();
        return idle && settled;
    }

    private boolean pointDone(long now) {
        if (phaseSec() >= spec.warmPointMaxSec()) return true;
        if (phaseSec() < 1.0 || last == null) return false;
        if (!last.available()) return phaseSec() >= spec.warmQuietSec();
        boolean idle = idleFor(now) >= spec.warmQuietSec();
        boolean settled = last.outstanding() == 0 || (now - outstandingChangedAt) / 1e9 >= spec.warmQuietSec() * 2;
        return idle && settled;
    }

    private double idleFor(long now) {
        return (now - dhIdleSince) / 1e9;
    }

    private void sample(long now) {
        if (now - lastSample < SAMPLE_NANOS) return;
        lastSample = now;
        DhProbe.Status s = DhProbe.status();
        last = s;
        // A pool thread that stays "running" while nothing completes and nothing is queued is a long-lived worker (the
        // file handler keeps one), not loading work: count running tasks only while completions move.
        boolean execMoving = s.execCompleted() != lastExecCompleted;
        lastExecCompleted = s.execCompleted();
        int act = spec.waitForSync() ? s.activity(true) - (execMoving ? 0 : s.execRunning()) : s.activity(false);
        lastActivity = act;
        if (act > 0) dhIdleSince = now;
        if (s.outstanding() != lastOutstanding) {
            lastOutstanding = s.outstanding();
            outstandingChangedAt = now;
        }
        if (now - lastRow >= 1_000_000_000L) {
            lastRow = now;
            JsonArray row = new JsonArray();
            row.add(round1(elapsedSec()));
            row.add(finalPhase ? "settle" : "warm" + Math.max(0, point));
            row.add(act);
            row.add(s.outstanding());
            row.add(s.missingNear() + s.queuedNear());
            row.add(s.sectionsCoarse());
            row.add(s.sectionsBuilding());
            row.add(s.networkWaiting() + s.networkInProgress());
            row.add(s.retrievalWaiting() + s.retrievalInProgress());
            row.add(s.renderTasksQueued());
            row.add(s.execQueued() + s.execRunning());
            double fd = FrameProbe.lastDiff();
            row.add(Double.isNaN(fd) ? -1 : Math.round(fd * 1000) / 1000.0);
            row.add(s.missingPositions());
            if (timeline.size() < 3600) timeline.add(row);
        }
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private JsonObject result(Minecraft mc, String status, long now) {
        JsonObject r = new JsonObject();
        r.addProperty("status", status);
        r.addProperty("elapsedSec", round1(elapsedSec()));
        r.addProperty("warmSec", round1(warmSec));
        r.addProperty("settleSec", round1((now - finalStart) / 1e9));
        r.addProperty("dhIdleForSec", round1(idleFor(now)));
        r.addProperty("dhIdleAfterSec", round1(Math.max(0, (dhIdleSince - finalStart) / 1e9)));
        r.addProperty("outstandingStableForSec", round1((now - outstandingChangedAt) / 1e9));
        r.addProperty("waitedForSync", spec.waitForSync());
        JsonArray waited = new JsonArray();
        waited.add(last != null && last.available() ? "distant horizons status" : "distant horizons (unavailable)");
        waited.add("chunk sections");
        if (spec.frameStability()) waited.add("frame stability");
        if (spec.minSec() > 0) waited.add(String.format(Locale.ROOT, "minimum %.0f s", spec.minSec()));
        r.add("waitedOn", waited);
        JsonObject warm = new JsonObject();
        warm.addProperty("points", Math.max(0, Math.min(point, warmPoses.size())));
        warm.add("log", warmLog);
        r.add("warm", warm);
        JsonObject chunks = new JsonObject();
        chunks.addProperty("renderedAllSections", LoadWaitTask.sectionsDone(mc));
        chunks.addProperty("loaded", LoadWaitTask.loadedChunks(mc));
        chunks.addProperty("quietSec", round1((now - chunksQuietSince) / 1e9));
        r.add("chunks", chunks);
        DhProbe.Status s = DhProbe.status();
        JsonObject d = s.json();
        d.add("f3", DhProbe.f3Lines());
        r.add("dh", d);
        if (spec.frameStability()) {
            JsonObject f = new JsonObject();
            double fd = FrameProbe.lastDiff(), p95 = FrameProbe.lastP95();
            f.addProperty("lastDiff", Double.isNaN(fd) ? -1 : Math.round(fd * 1000) / 1000.0);
            f.addProperty("lastP95", Double.isNaN(p95) ? -1 : Math.round(p95 * 1000) / 1000.0);
            f.addProperty("threshold", spec.frameThreshold());
            f.addProperty("quietSec", round1((now - frameQuietSince) / 1e9));
            f.addProperty("samples", FrameProbe.samples());
            f.addProperty("neverSettled", frameNeverSettled);
            r.add("frame", f);
        }
        JsonObject tl = new JsonObject();
        JsonArray fields = new JsonArray();
        for (String fName : TIMELINE_FIELDS) fields.add(fName);
        tl.add("fields", fields);
        tl.add("rows", timeline);
        r.add("timeline", tl);
        if (stills.size() > 0) r.add("stills", stills);
        return r;
    }

    private void cleanup() {
        FrameProbe.stop();
        Cinema.ride = wasRiding;
    }

    @Override
    public void onCancel(String reason) {
        cleanup();
    }

    /** Camera poses spaced along a move, for the warm pass. */
    public static List<Pose> warmPoses(dev.everest8000.cinema.camera.MovePlayer mp, double duration, double spacing, int maxPoints) {
        java.util.ArrayList<Pose> out = new java.util.ArrayList<>();
        if (Double.isInfinite(duration) || duration <= 0) return out;
        Pose last = null;
        int steps = 200;
        for (int i = 0; i <= steps; i++) {
            Pose p;
            try {
                p = mp.sample(duration * i / steps, 1f, dev.everest8000.cinema.camera.McTargets.INSTANCE);
            } catch (RuntimeException e) {
                continue;
            }
            if (p.pos() == null) return out;
            if (last == null || p.pos().distance(last.pos()) >= spacing || i == steps && p.pos().distance(last.pos()) > spacing * 0.3) {
                out.add(p);
                last = p;
            }
        }
        // Keep the farthest-apart subset if there are too many.
        while (out.size() > maxPoints) out.remove(out.size() / 2);
        // The start pose itself comes last in the final phase, so drop it from the warm list if it is first.
        if (!out.isEmpty()) out.remove(0);
        return out;
    }
}
