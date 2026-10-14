package dev.everest8000.cinema.spec;

/**
 * The LOD-ready gate before a take. Distant Horizons' own progress is the signal (see lod.DhProbe): after an optional
 * warm pass along the camera path (the spectator riding along, since DH centres its detail on the player), the gate
 * waits at the start pose until DH has had no work in flight for quietSec (executors, retrieval and network request
 * queues, render-thread uploads, quadtree sections building) and nothing is still to come: no positions missing data
 * and no section drawn coarser than wanted, or those counts have stopped changing for stallSec (there is nothing more
 * to get, e.g. past the edge of the map). Chunk sections must be built and, as a cross-check, the picture must hold
 * still for frameQuietSec. Never less than minSec; after timeoutSec it fails (onTimeout "fail") or carries on with a
 * warning ("continue").
 */
public record LodGateSpec(boolean enabled, double quietSec, double stallSec, double minSec, double timeoutSec, String onTimeout,
                          boolean warm, double warmSpacing, double warmPointMaxSec, int warmMaxPoints, double warmQuietSec,
                          boolean frameStability, double frameThreshold, double frameQuietSec, boolean stills, double nearRadius,
                          boolean waitForSync, double frameMaxWaitSec) {
    public static final LodGateSpec DEFAULT = new LodGateSpec(true, 5, 10, 3, 300, "fail", true, 160, 60, 10, 2, true, 0.6, 3,
            false, 6000, false, 8);

    public boolean failOnTimeout() {
        return "fail".equals(onTimeout);
    }

    public LodGateSpec withOnTimeout(String v) {
        return new LodGateSpec(enabled, quietSec, stallSec, minSec, timeoutSec, v, warm, warmSpacing, warmPointMaxSec, warmMaxPoints,
                warmQuietSec, frameStability, frameThreshold, frameQuietSec, stills, nearRadius, waitForSync, frameMaxWaitSec);
    }

    public static LodGateSpec parse(JObj o) {
        if (o == null) return DEFAULT;
        LodGateSpec d = DEFAULT;
        LodGateSpec s = new LodGateSpec(o.bool("enabled", d.enabled()), o.num("quietSec", 0.5, 120, d.quietSec()),
                o.num("stallSec", 1, 600, d.stallSec()), o.num("minSec", 0, 600, d.minSec()),
                o.num("timeoutSec", 1, 3600, d.timeoutSec()), o.choiceOr("onTimeout", d.onTimeout(), "fail", "continue"),
                o.bool("warm", d.warm()), o.num("warmSpacing", 16, 5000, d.warmSpacing()),
                o.num("warmPointMaxSec", 1, 600, d.warmPointMaxSec()), o.integer("warmMaxPoints", 1, 64, d.warmMaxPoints()),
                o.num("warmQuietSec", 0.5, 60, d.warmQuietSec()), o.bool("frameStability", d.frameStability()),
                o.num("frameThreshold", 0.05, 20, d.frameThreshold()), o.num("frameQuietSec", 0.5, 120, d.frameQuietSec()),
                o.bool("stills", d.stills()), o.num("nearRadius", 100, 100000, d.nearRadius()),
                o.bool("waitForSync", d.waitForSync()), o.num("frameMaxWaitSec", 0, 600, d.frameMaxWaitSec()));
        o.finish();
        return s;
    }
}
