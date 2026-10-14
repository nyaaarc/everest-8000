package dev.everest8000.cinema.spec;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** One shot: a camera move over a fixed duration, with player setup, warm-up, timed events and teardown. */
public record ShotSpec(String name, double durationSec, Move camera, PlayerSetup player, HudSpec hud, Boolean shaders,
                       List<Action> setup, Warmup warmup, List<Event> events, List<Action> teardown, List<ActorSpec> actors,
                       boolean keepActors, Particles particles) {
    /** Particle classes (name prefixes) kept out of the take, and whether to clear live particles before it rolls. */
    public record Particles(List<String> block, boolean clear) {
        public static final Particles NONE = new Particles(List.of(), false);
    }

    public static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    /** Player state applied before warm-up. pos null = stay; gamemode null = keep. */
    public record PlayerSetup(boolean visible, boolean ride, String gamemode, Vec3d pos, double yaw, double pitch) {
        public static final PlayerSetup DEFAULT = new PlayerSetup(true, false, null, null, Double.NaN, Double.NaN);
    }

    public record HudSpec(boolean visible, boolean chat, boolean crosshair, boolean hand) {
        public static final HudSpec HIDDEN = new HudSpec(false, false, false, true);
    }

    /**
     * Before rolling: wait until every chunk section in view is built (at most chunksTimeoutSec), then give Distant
     * Horizons lodSettleSec more, never less than minSec in total. With scout, the move is first flown once in real
     * time at scoutSpeed (to pull in chunks and LODs along the way), then the wait repeats at the start pose.
     */
    public record Warmup(double chunksTimeoutSec, double lodSettleSec, double minSec, boolean scout, double scoutSpeed, LodGateSpec lod) {
        public static final Warmup DEFAULT = new Warmup(45, 4, 1, false, 1, LodGateSpec.DEFAULT);
    }

    public record Event(double atSec, Action action) {}

    public static ShotSpec parse(JObj o) {
        String name = o.string("name", NAME, "1-64 characters of A-Z a-z 0-9 _ . -");
        double duration = o.num("durationSec", 0.05, 3600);
        Move camera = Move.parse(o.obj("camera"));
        if (!Double.isNaN(camera.durationSec()) && camera.durationSec() > duration + 1e-6) {
            throw new SpecException(o.at("camera.durationSec"), "the camera move (" + JObj.fmt(camera.durationSec())
                    + " s) is longer than the shot (" + JObj.fmt(duration) + " s)");
        }
        PlayerSetup player = PlayerSetup.DEFAULT;
        JObj p = o.optObj("player");
        if (p != null) {
            boolean ride = p.bool("ride", false);
            boolean visible = p.bool("visible", !ride);
            if (ride && visible) throw new SpecException(p.at("visible"), "a riding player is hidden; set visible to false or drop it");
            if (ride && camera instanceof Move.FirstPerson) throw new SpecException(p.at("ride"), "can't ride along with a firstPerson camera");
            String mode = p.has("gamemode") ? p.choice("gamemode", Action.GAMEMODES) : null;
            Vec3d pos = p.vec3("pos", null);
            boolean y = p.has("yaw"), pi = p.has("pitch");
            if (y != pi) throw new SpecException(p.at(y ? "pitch" : "yaw"), "yaw and pitch must be given together");
            if (y && pos == null) throw new SpecException(p.at("yaw"), "yaw/pitch need pos (they are applied by the teleport)");
            player = new PlayerSetup(visible, ride, mode, pos, y ? p.num("yaw", -36000, 36000) : Double.NaN,
                    pi ? p.num("pitch", -90, 90) : Double.NaN);
            p.finish();
        }
        HudSpec hud = HudSpec.HIDDEN;
        JObj h = o.optObj("hud");
        if (h != null) {
            hud = new HudSpec(h.bool("visible"), h.bool("chat", false), h.bool("crosshair", false), h.bool("hand", true));
            h.finish();
        }
        Boolean shaders = o.optBool("shaders");
        List<Action> setup = Action.parseList(o, "setup", true);
        Warmup warmup = Warmup.DEFAULT;
        JObj w = o.optObj("warmup");
        if (w != null) {
            warmup = new Warmup(w.num("chunksTimeoutSec", 0, 3600, 45), w.num("lodSettleSec", 0, 600, 4), w.num("minSec", 0, 600, 1),
                    w.bool("scout", false), w.num("scoutSpeed", 0.1, 20, 1), LodGateSpec.parse(w.optObj("lod")));
            w.finish();
        }
        List<Event> events = new ArrayList<>();
        for (JObj e : o.optObjList("events", 10000)) {
            double at = e.num("atSec", 0, duration);
            Action a = Action.parse(e.obj("action"));
            if (a.blocking()) throw new SpecException(e.at("action"), "\"" + a.typeName() + "\" waits, so it can't be a timed event");
            e.finish();
            events.add(new Event(at, a));
        }
        events.sort((a, b) -> Double.compare(a.atSec(), b.atSec()));
        List<Action> teardown = Action.parseList(o, "teardown", true);
        List<ActorSpec> actors = new ArrayList<>();
        java.util.Set<String> actorIds = new java.util.HashSet<>();
        for (JObj a : o.optObjList("actors", 64)) {
            ActorSpec as = ActorSpec.parse(a);
            if (!actorIds.add(as.id())) throw new SpecException(a.at("id"), "duplicate actor id \"" + as.id() + "\"");
            actors.add(as);
        }
        for (ActorSpec as : actors) {
            for (int i = 0; i < as.timeline().size(); i++) {
                ActorSpec.Trigger t = as.timeline().get(i).at();
                if (t.kind().equals("after") && t.after().contains(".") && !actorIds.contains(t.after().substring(0, t.after().indexOf('.')))) {
                    throw new SpecException("shot.actors." + as.id() + ".timeline[" + i + "].at.cue", "no actor \""
                            + t.after().substring(0, t.after().indexOf('.')) + "\" in this shot");
                }
            }
        }
        if (actorIds.contains(ActorSpec.PLAYER) && player.ride()) {
            throw new SpecException("shot.player.ride", "the player is an actor in this shot, so it can't ride with the camera");
        }
        boolean keepActors = o.bool("keepActors", false);
        Particles particles = Particles.NONE;
        JObj pj = o.optObj("particles");
        if (pj != null) {
            particles = new Particles(pj.has("block") ? List.copyOf(pj.stringList("block", 0, 64)) : List.of(), pj.bool("clear", true));
            pj.finish();
        }
        o.finish();
        return new ShotSpec(name, duration, camera, player, hud, shaders, setup, warmup, List.copyOf(events), teardown,
                List.copyOf(actors), keepActors, particles);
    }
}
