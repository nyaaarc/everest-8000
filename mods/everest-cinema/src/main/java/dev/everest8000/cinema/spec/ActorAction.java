package dev.everest8000.cinema.spec;

import java.util.List;
import java.util.regex.Pattern;

/**
 * One thing an actor (or the player, as actor "player") does. Long actions (walkTo, use with a duration, breather,
 * glance) finish later; their cue id fires when they do, which later cues can wait for.
 */
public sealed interface ActorAction {
    Pattern ITEM = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+(\\[.*])?");
    String[] SPEEDS = {"walk", "stroll", "sprint", "sneak"};
    String[] HANDS = {"main", "off"};
    String[] ARM_POSES = {"none", "raise", "spyglass", "bow", "crossbow", "block", "brush"};
    String[] EQUIP_SLOTS = {"head", "chest", "legs", "feet", "mainhand", "offhand"};

    String typeName();

    /** Walk with real physics to a point or through points. path astar: route over the blocks; direct: straight lines. */
    record WalkTo(List<Vec3d> points, String speed, String path, double arriveRadius, int maxNodes) implements ActorAction {
        public String typeName() { return "walkTo"; }
    }

    /** Turn the head toward a target: track keeps following it, glance holds for durationSec then lets go. */
    record LookAt(Target target, String mode, double durationSec) implements ActorAction {
        public String typeName() { return "lookAt"; }
    }

    /** Look in a direction (absolute yaw/pitch). */
    record Look(double yaw, double pitch, String mode, double durationSec) implements ActorAction {
        public String typeName() { return "look"; }
    }

    /** Back to looking ahead (and idle glances). */
    record LookAhead() implements ActorAction {
        public String typeName() { return "lookAhead"; }
    }

    /** Turn the whole body to a heading while standing. */
    record Face(double yaw) implements ActorAction {
        public String typeName() { return "face"; }
    }

    record Stop() implements ActorAction {
        public String typeName() { return "stop"; }
    }

    /** Stop for a breath, look around, then carry on with any walk. */
    record Breather(double durationSec) implements ActorAction {
        public String typeName() { return "breather"; }
    }

    record Jump() implements ActorAction {
        public String typeName() { return "jump"; }
    }

    record Crouch(boolean on) implements ActorAction {
        public String typeName() { return "crouch"; }
    }

    /** Crouch into the wind: sneak, head down, body turned to windYaw (NaN = keep). */
    record Brace(boolean on, double windYaw) implements ActorAction {
        public String typeName() { return "brace"; }
    }

    record Swing(String hand) implements ActorAction {
        public String typeName() { return "swing"; }
    }

    /** Hold "use" on the item in hand (draw a bow, raise a shield...); durationSec 0 = until release. */
    record Use(String hand, double durationSec) implements ActorAction {
        public String typeName() { return "use"; }
    }

    /** Let go of "use"; with arrow, a client-side arrow flies where the actor looks (bows). */
    record Release(boolean arrow, double power) implements ActorAction {
        public String typeName() { return "release"; }
    }

    /**
     * Arm pose: raise (Everest Radio's raise-to-face on the arm holding the radio; amount 0..1 pins it, NaN = its own
     * eased raise; the vanilla horn pose without the radio mod), spyglass (to the eye), bow, crossbow, block, brush, none.
     */
    record Pose(String hand, String pose, double amount) implements ActorAction {
        public String typeName() { return "pose"; }
    }

    /** Actors only (the player uses commands): put an item in a slot, or empty it with "minecraft:air". */
    record Equip(String slot, String item) implements ActorAction {
        public String typeName() { return "equip"; }
    }

    /** Actors only: down-suit hood up or down. */
    record Hood(boolean down) implements ActorAction {
        public String typeName() { return "hood"; }
    }

    /** Actors only: oxygen mask on or off. */
    /** Gravity off holds the walker at its height (crossing a span its physics won't hold up); on puts it back. */
    record Gravity(boolean on) implements ActorAction {
        public String typeName() { return "gravity"; }
    }

    record Mask(boolean on) implements ActorAction {
        public String typeName() { return "mask"; }
    }

    record Teleport(Vec3d pos, double yaw) implements ActorAction {
        public String typeName() { return "teleport"; }
    }

    static ActorAction parse(JObj o) {
        String type = o.choice("type", "walkTo", "lookAt", "look", "lookAhead", "face", "stop", "breather", "jump", "crouch",
                "brace", "swing", "use", "release", "pose", "equip", "hood", "mask", "teleport", "gravity");
        ActorAction a = switch (type) {
            case "walkTo" -> {
                boolean hasTo = o.has("to"), hasPoints = o.has("points");
                if (hasTo == hasPoints) throw new SpecException(o.path(), "give exactly one of to or points");
                List<Vec3d> pts = hasTo ? List.of(o.vec3("to")) : List.copyOf(o.vec3List("points", 1, 10000));
                yield new WalkTo(pts, o.choiceOr("speed", "walk", SPEEDS), o.choiceOr("path", "astar", "astar", "direct"),
                        o.num("arriveRadius", 0.2, 16, 0.6), o.integer("maxNodes", 100, 1000000, 60000));
            }
            case "lookAt" -> new LookAt(Target.parse(o.obj("target")), o.choiceOr("mode", "track", "track", "glance"),
                    o.num("durationSec", 0, 600, 1.5));
            case "look" -> new Look(o.num("yaw", -36000, 36000), o.num("pitch", -90, 90), o.choiceOr("mode", "track", "track", "glance"),
                    o.num("durationSec", 0, 600, 1.5));
            case "lookAhead" -> new LookAhead();
            case "face" -> new Face(o.num("yaw", -36000, 36000));
            case "stop" -> new Stop();
            case "breather" -> new Breather(o.num("durationSec", 0.2, 60, 3));
            case "jump" -> new Jump();
            case "crouch" -> new Crouch(o.bool("on"));
            case "brace" -> new Brace(o.bool("on"), o.optNum("windYaw", -36000, 36000));
            case "swing" -> new Swing(o.choiceOr("hand", "main", HANDS));
            case "use" -> new Use(o.choiceOr("hand", "main", HANDS), o.num("durationSec", 0, 600, 0));
            case "release" -> new Release(o.bool("arrow", false), o.num("power", 0.1, 3, 2.5));
            case "pose" -> {
                String hand = o.choiceOr("hand", "main", HANDS), pose = o.choice("pose", ARM_POSES);
                double amount = o.optNum("amount", 0, 1);
                if (!Double.isNaN(amount) && !pose.equals("raise")) throw new SpecException(o.at("amount"), "only for pose \"raise\"");
                yield new Pose(hand, pose, amount);
            }
            case "equip" -> new Equip(o.choice("slot", EQUIP_SLOTS), o.string("item", ITEM, "an item id, optionally with [components]"));
            case "hood" -> new Hood(o.bool("down"));
            case "mask" -> new Mask(o.bool("on"));
            case "gravity" -> new Gravity(o.bool("on"));
            case "teleport" -> new Teleport(o.vec3("pos"), o.optNum("yaw", -36000, 36000));
            default -> throw new IllegalStateException(type);
        };
        o.finish();
        return a;
    }
}
