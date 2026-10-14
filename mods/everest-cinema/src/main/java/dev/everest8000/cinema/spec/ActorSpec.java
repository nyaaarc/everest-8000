package dev.everest8000.cinema.spec;

import dev.everest8000.cinema.nav.Gait;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A client-side actor: a player clone that exists only in this game (no server state), with a skin, an outfit and a
 * timeline of cues. Id "player" means the real local player instead (no skin/outfit/pos; the timeline drives it).
 */
public record ActorSpec(String id, Skin skin, Vec3d pos, double yaw, double pitch, Outfit outfit, Gait gait, long seed,
                        List<Cue> timeline) {
    public static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    public static final String PLAYER = "player";

    public boolean isPlayer() {
        return PLAYER.equals(id);
    }

    /** type player: copy the local player's skin; file: a 64x64 PNG on disk. */
    public record Skin(String type, String path, String model) {
        public static final Skin PLAYER = new Skin("player", null, null);
    }

    /**
     * The down suit (all four pieces when suit is true), hood style and position, oxygen mask, a worn Traveler's Backpack
     * dyed packRgb (negative = none), and items in hand. Items are ids, optionally with [components].
     */
    /**
     * The down suit and pack. hoodStyle: a look as "shape/goggles" (Everest Expedition 0.3.5+: shapes expedition, parka,
     * low, low_fur, sherpa, helmet; goggles none, hood, forehead, eyes, neck) or one of the old names (expedition,
     * parka, forehead, goggles_down). jacketRgb / pantsRgb: dye colours, -1 for the undyed gold suit (the hood takes the
     * jacket's colour; boots stay undyed).
     */
    public record Outfit(boolean suit, String hoodStyle, boolean hoodDown, boolean mask, int packRgb, String mainhand, String offhand,
                         int jacketRgb, int pantsRgb) {
        public static final String[] HOOD_STYLES = {"expedition", "parka", "forehead", "goggles_down"};
        public static final String[] HOOD_SHAPES = {"expedition", "parka", "low", "low_fur", "sherpa", "helmet"};
        public static final String[] GOGGLES = {"none", "hood", "forehead", "eyes", "neck"};
        public static final Pattern LOOK = Pattern.compile("(expedition|parka|forehead|goggles_down)"
                + "|(expedition|parka|low|low_fur|sherpa|helmet)/(none|hood|forehead|eyes|neck)");
        public static final Outfit DEFAULT = new Outfit(true, "expedition", false, false, 13383222, null, null, -1, -1);
    }

    /** When a cue starts: at a shot time, after another cue finished (plus a delay), or when the actor comes near a point. */
    public record Trigger(String kind, double timeSec, String after, double delaySec, Vec3d near, double radius) {
        public static final Trigger AT_START = new Trigger("time", 0, null, 0, null, 0);
    }

    public record Cue(String id, Trigger at, ActorAction action) {}

    public static ActorSpec parse(JObj o) {
        String id = o.string("id", ID, "1-32 characters of A-Z a-z 0-9 _ -");
        boolean player = PLAYER.equals(id);
        Skin skin = null;
        Vec3d pos = null;
        double yaw = 0, pitch = 0;
        Outfit outfit = null;
        if (!player) {
            JObj s = o.optObj("skin");
            if (s == null) {
                skin = Skin.PLAYER;
            } else {
                String type = s.choice("type", "player", "file");
                if (type.equals("file")) {
                    String path = s.string("path");
                    if (!path.toLowerCase(java.util.Locale.ROOT).endsWith(".png")) throw new SpecException(s.at("path"), "must be a .png");
                    skin = new Skin("file", path, s.choiceOr("model", "wide", "wide", "slim"));
                } else {
                    skin = Skin.PLAYER;
                }
                s.finish();
            }
            pos = o.vec3("pos");
            yaw = o.num("yaw", -36000, 36000, 0);
            pitch = o.num("pitch", -90, 90, 0);
            outfit = Outfit.DEFAULT;
            JObj f = o.optObj("outfit");
            if (f != null) {
                int rgb = f.integer("packRgb", -1, 0xFFFFFF, Outfit.DEFAULT.packRgb());
                String look = f.has("hoodStyle") ? f.string("hoodStyle", Outfit.LOOK,
                        "\"shape/goggles\" (shapes " + String.join(", ", Outfit.HOOD_SHAPES) + "; goggles "
                                + String.join(", ", Outfit.GOGGLES) + ") or expedition, parka, forehead, goggles_down") : "expedition";
                outfit = new Outfit(f.bool("suit", true), look, f.bool("hoodDown", false), f.bool("mask", false), rgb,
                        f.optString("mainhand", ActorAction.ITEM, "an item id"), f.optString("offhand", ActorAction.ITEM, "an item id"),
                        f.integer("jacketRgb", -1, 0xFFFFFF, -1), f.integer("pantsRgb", -1, 0xFFFFFF, -1));
                f.finish();
            }
        } else {
            for (String k : new String[]{"skin", "pos", "yaw", "pitch", "outfit"}) {
                if (o.has(k)) throw new SpecException(o.at(k), "not allowed for the player (set the player up with shot.player and setup actions)");
            }
        }
        Gait gait = parseGait(o.optObj("gait"));
        long seed = o.has("seed") ? o.longValue("seed", 0, Long.MAX_VALUE / 4) : Math.abs((long) id.hashCode());
        List<Cue> timeline = parseTimeline(o, "timeline", player);
        o.finish();
        return new ActorSpec(id, skin, pos, yaw, pitch, outfit, gait, seed, timeline);
    }

    /** The cues under key (an array), checked: unique ids, known "after" references, actor-only actions not on the player. */
    public static List<Cue> parseTimeline(JObj o, String key, boolean player) {
        List<Cue> timeline = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JObj c : o.optObjList(key, 10000)) {
            String cid = c.has("id") ? c.string("id", ID, "1-32 characters of A-Z a-z 0-9 _ -") : null;
            if (cid != null && !ids.add(cid)) throw new SpecException(c.at("id"), "duplicate cue id \"" + cid + "\"");
            Trigger t = Trigger.AT_START;
            JObj at = c.optObj("at");
            if (at != null) {
                String kind = at.choice("type", "time", "after", "near");
                t = switch (kind) {
                    case "time" -> new Trigger("time", at.num("sec", 0, 3600), null, 0, null, 0);
                    case "after" -> new Trigger("after", 0, at.string("cue", Pattern.compile("([A-Za-z0-9_-]{1,32}\\.)?[A-Za-z0-9_-]{1,32}"),
                            "a cue id, or actorId.cueId"), at.num("delaySec", 0, 3600, 0), null, 0);
                    case "near" -> new Trigger("near", 0, null, 0, at.vec3("pos"), at.num("radius", 0.2, 1000, 2));
                    default -> throw new IllegalStateException(kind);
                };
                at.finish();
            }
            ActorAction a = ActorAction.parse(c.obj("action"));
            if (player && (a instanceof ActorAction.Equip || a instanceof ActorAction.Hood || a instanceof ActorAction.Mask)) {
                throw new SpecException(c.at("action"), "\"" + a.typeName() + "\" is for actors; give the player items with setup actions");
            }
            c.finish();
            timeline.add(new Cue(cid, t, a));
        }
        for (int i = 0; i < timeline.size(); i++) {
            Trigger t = timeline.get(i).at();
            if (t.kind().equals("after") && !t.after().contains(".") && !ids.contains(t.after())) {
                throw new SpecException(o.at(key) + "[" + i + "].at.cue", "no cue \"" + t.after() + "\" in this actor's timeline"
                        + " (use actorId.cueId for another actor's cue)");
            }
        }
        return List.copyOf(timeline);
    }

    static Gait parseGait(JObj g) {
        Gait d = Gait.DEFAULT;
        if (g == null) return d;
        double[] every = null, dur = d.breatherFor();
        double slopeK = d.breatherSlope();
        double[][] alt = null;
        JObj b = g.optObj("breathers");
        if (b != null) {
            double emin = b.num("everyMinSec", 1, 600), emax = b.num("everyMaxSec", 1, 600);
            double dmin = b.num("minSec", 0.2, 60, 2.0), dmax = b.num("maxSec", 0.2, 60, 5.0);
            if (emax < emin) throw new SpecException(b.at("everyMaxSec"), "must be >= everyMinSec");
            if (dmax < dmin) throw new SpecException(b.at("maxSec"), "must be >= minSec");
            every = new double[]{emin, emax};
            dur = new double[]{dmin, dmax};
            slopeK = b.num("slopeFactor", 0, 20, d.breatherSlope());
            if (b.has("altitude")) {
                List<JObj> pts = b.objList("altitude", 1, 64);
                alt = new double[pts.size()][];
                for (int i = 0; i < pts.size(); i++) {
                    JObj pt = pts.get(i);
                    alt[i] = new double[]{pt.num("y", -4096, 4096), pt.num("rate", 0.05, 50)};
                    pt.finish();
                    if (i > 0 && alt[i][0] <= alt[i - 1][0]) throw new SpecException(pt.at("y"), "altitude points must have increasing y");
                }
            }
            b.finish();
        }
        double[][] paceAlt = null;
        if (g.has("paceAltitude")) {
            List<JObj> pts = g.objList("paceAltitude", 1, 64);
            paceAlt = new double[pts.size()][];
            for (int i = 0; i < pts.size(); i++) {
                JObj pt = pts.get(i);
                paceAlt[i] = new double[]{pt.num("y", -4096, 4096), pt.num("factor", 0.1, 2)};
                pt.finish();
                if (i > 0 && paceAlt[i][0] <= paceAlt[i - 1][0]) throw new SpecException(pt.at("y"), "points must have increasing y");
            }
        }
        Gait r = new Gait(g.num("pace", 0.1, 1.3, d.pace()), g.num("turnRateDegPerSec", 20, 720, d.turnRateDeg()),
                g.num("accelSec", 0.05, 5, d.accelSec()), g.num("wander", 0, 3, d.wander()),
                g.num("wanderWavelength", 2, 200, d.wanderWavelength()), g.num("lookahead", 0.8, 8, d.lookahead()),
                g.num("uphillSlowdown", 0, 0.95, d.uphillSlowdown()), g.num("snowSlowdown", 0, 0.95, d.snowSlowdown()),
                every, dur, slopeK, alt, paceAlt, g.bool("idleLook", d.idleLook()));
        g.finish();
        return r;
    }
}
