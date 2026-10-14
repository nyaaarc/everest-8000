package dev.everest8000.cinema.spec;

import java.util.List;
import java.util.regex.Pattern;

/**
 * One step a shot runs in its setup/teardown lists or at a timestamp. {@link #blocking()} steps (wait, waitLoaded) are
 * only allowed in setup and teardown, never as timed events.
 */
public sealed interface Action {
    Pattern ITEM_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    String[] SLOTS = slotNames();
    String[] SPEEDS = {"walk", "sprint", "sneak"};
    String[] GAMEMODES = {"survival", "creative", "adventure", "spectator"};

    default boolean blocking() {
        return false;
    }

    String typeName();

    record Command(String command) implements Action {
        public String typeName() { return "command"; }
    }

    record Wait(double seconds) implements Action {
        public boolean blocking() { return true; }
        public String typeName() { return "wait"; }
    }

    record WaitLoaded(double timeoutSec, boolean includeLods, double lodSettleSec) implements Action {
        public boolean blocking() { return true; }
        public String typeName() { return "waitLoaded"; }
    }

    /** Server-side teleport of the player. yaw/pitch NaN keep the current rotation. */
    record Teleport(Vec3d pos, double yaw, double pitch) implements Action {
        public String typeName() { return "teleport"; }
    }

    record Gamemode(String mode) implements Action {
        public String typeName() { return "gamemode"; }
    }

    /** Sets the time of day; with freeze the daylight cycle is stopped (restored by state.restore). */
    record Time(long dayTime, boolean freeze) implements Action {
        public String typeName() { return "time"; }
    }

    record Weather(String weather) implements Action {
        public String typeName() { return "weather"; }
    }

    record TickRate(double rate) implements Action {
        public String typeName() { return "tickRate"; }
    }

    /** Puts an item in a slot with /item replace (restored by state.restore). */
    record Equip(String slot, String item, int count) implements Action {
        public String typeName() { return "equip"; }
    }

    record ClearSlot(String slot) implements Action {
        public String typeName() { return "clearSlot"; }
    }

    record SelectSlot(int slot) implements Action {
        public String typeName() { return "selectSlot"; }
    }

    /** Walks the player through points on the ground (y is only used for reporting); physics, step-ups and jumps included. */
    record Walk(List<Vec3d> points, String speed, double arriveRadius) implements Action {
        public String typeName() { return "walk"; }
    }

    record Look(double yaw, double pitch, double durationSec, Easing easing) implements Action {
        public String typeName() { return "look"; }
    }

    record LookAt(Target target, double durationSec, Easing easing) implements Action {
        public String typeName() { return "lookAt"; }
    }

    /** Raw movement input, held until clearInput or stop. forward/strafe in -1..1 (strafe positive = left). */
    record Input(double forward, double strafe, boolean jump, boolean sneak, boolean sprint, boolean use) implements Action {
        public String typeName() { return "input"; }
    }

    record ClearInput() implements Action {
        public String typeName() { return "clearInput"; }
    }

    record Jump() implements Action {
        public String typeName() { return "jump"; }
    }

    record Swing(String hand) implements Action {
        public String typeName() { return "swing"; }
    }

    /** Stops walking, looking and held input. */
    record Stop() implements Action {
        public String typeName() { return "stop"; }
    }

    /** Client-side freezing meter (vanilla powder-snow frost; mods may draw their own overlay from it), eased over durationSec. */
    record Frozen(double fraction, double durationSec) implements Action {
        public String typeName() { return "frozen"; }
    }

    record Hud(boolean visible, boolean chat, boolean crosshair, boolean hand) implements Action {
        public String typeName() { return "hud"; }
    }

    record Shaders(boolean enabled) implements Action {
        public String typeName() { return "shaders"; }
    }

    record PlayerVisible(boolean visible) implements Action {
        public String typeName() { return "playerVisible"; }
    }

    static Action parse(JObj o) {
        String type = o.choice("type", "command", "wait", "waitLoaded", "teleport", "gamemode", "time", "weather", "tickRate",
                "equip", "clearSlot", "selectSlot", "walk", "look", "lookAt", "input", "clearInput", "jump", "swing", "stop",
                "frozen", "hud", "shaders", "playerVisible");
        Action a = switch (type) {
            case "command" -> new Command(command(o));
            case "wait" -> new Wait(o.num("seconds", 0, 3600));
            case "waitLoaded" -> new WaitLoaded(o.num("timeoutSec", 1, 3600, 60), o.bool("includeLods", true), o.num("lodSettleSec", 0, 600, 4));
            case "teleport" -> {
                Vec3d pos = o.vec3("pos");
                boolean y = o.has("yaw"), p = o.has("pitch");
                if (y != p) throw new SpecException(o.at(y ? "pitch" : "yaw"), "yaw and pitch must be given together");
                yield new Teleport(pos, y ? o.num("yaw", -36000, 36000) : Double.NaN, p ? o.num("pitch", -90, 90) : Double.NaN);
            }
            case "gamemode" -> new Gamemode(o.choice("mode", GAMEMODES));
            case "time" -> new Time(o.longValue("dayTime", 0, 24000), o.bool("freeze", true));
            case "weather" -> new Weather(o.choice("weather", "clear", "rain", "thunder"));
            case "tickRate" -> new TickRate(o.num("rate", 1, 10000));
            case "equip" -> new Equip(o.choice("slot", SLOTS), o.string("item", ITEM_ID, "a namespaced item id like minecraft:bow"),
                    o.integer("count", 1, 99, 1));
            case "clearSlot" -> new ClearSlot(o.choice("slot", SLOTS));
            case "selectSlot" -> new SelectSlot(o.integer("slot", 0, 8));
            case "walk" -> new Walk(List.copyOf(o.vec3List("points", 1, 10000)), o.choiceOr("speed", "walk", SPEEDS),
                    o.num("arriveRadius", 0.1, 16, 0.6));
            case "look" -> new Look(o.num("yaw", -36000, 36000), o.num("pitch", -90, 90), o.num("durationSec", 0, 600, 0),
                    Easing.read(o, "easing", Easing.SINE_IN_OUT));
            case "lookAt" -> new LookAt(Target.parse(o.obj("target")), o.num("durationSec", 0, 600, 0),
                    Easing.read(o, "easing", Easing.SINE_IN_OUT));
            case "input" -> new Input(o.num("forward", -1, 1, 0), o.num("strafe", -1, 1, 0), o.bool("jump", false),
                    o.bool("sneak", false), o.bool("sprint", false), o.bool("use", false));
            case "clearInput" -> new ClearInput();
            case "jump" -> new Jump();
            case "swing" -> new Swing(o.choiceOr("hand", "main", "main", "off"));
            case "stop" -> new Stop();
            case "frozen" -> new Frozen(o.num("fraction", 0, 1), o.num("durationSec", 0, 600, 0));
            case "hud" -> new Hud(o.bool("visible"), o.bool("chat", false), o.bool("crosshair", false), o.bool("hand", true));
            case "shaders" -> new Shaders(o.bool("enabled"));
            case "playerVisible" -> new PlayerVisible(o.bool("visible"));
            default -> throw new IllegalStateException(type);
        };
        o.finish();
        return a;
    }

    static List<Action> parseList(JObj parent, String key, boolean allowBlocking) {
        List<JObj> raw = parent.optObjList(key, 10000);
        java.util.ArrayList<Action> out = new java.util.ArrayList<>(raw.size());
        for (JObj o : raw) {
            Action a = parse(o);
            if (a.blocking() && !allowBlocking) {
                throw new SpecException(o.path(), "\"" + a.typeName() + "\" waits, so it can't be used here");
            }
            out.add(a);
        }
        return List.copyOf(out);
    }

    private static String command(JObj o) {
        String c = o.string("command");
        if (c.startsWith("/")) c = c.substring(1);
        if (c.isBlank()) throw new SpecException(o.at("command"), "must not be empty");
        if (c.length() > 32000) throw new SpecException(o.at("command"), "is longer than 32000 characters");
        if (c.indexOf('\n') >= 0 || c.indexOf('\r') >= 0) throw new SpecException(o.at("command"), "must be a single line");
        return c;
    }

    /** /item replace slot name for one of {@link #SLOTS}. */
    static String itemSlot(String slot) {
        return switch (slot) {
            case "head" -> "armor.head";
            case "chest" -> "armor.chest";
            case "legs" -> "armor.legs";
            case "feet" -> "armor.feet";
            case "mainhand" -> "weapon.mainhand";
            case "offhand" -> "weapon.offhand";
            default -> slot;
        };
    }

    private static String[] slotNames() {
        String[] s = new String[6 + 9 + 27];
        String[] fixed = {"head", "chest", "legs", "feet", "mainhand", "offhand"};
        System.arraycopy(fixed, 0, s, 0, 6);
        for (int i = 0; i < 9; i++) s[6 + i] = "hotbar." + i;
        for (int i = 0; i < 27; i++) s[15 + i] = "inventory." + i;
        return s;
    }
}
