package dev.everest8000.cinema.spec;

import java.util.ArrayList;
import java.util.List;

/**
 * A camera move. {@link #durationSec()} is NaN when the move is open-ended: in a shot it then lasts the whole shot;
 * through the API it runs until stopped or replaced.
 */
public sealed interface Move {
    double DEFAULT_FOV = 70;

    double durationSec();

    Shake shake();

    String typeName();

    record Shake(double amplitudeDeg, double frequencyHz) {
        static Shake parse(JObj o) {
            if (o == null) return null;
            Shake s = new Shake(o.num("amplitudeDeg", 0, 10), o.num("frequencyHz", 0.01, 10, 0.5));
            o.finish();
            return s;
        }
    }

    /** Holds one pose (or keeps looking at a moving target). */
    record Static(Vec3d pos, double yaw, double pitch, Target lookAt, double lookSmoothSec, double roll, double fov,
                  double durationSec, Shake shake) implements Move {
        public String typeName() {
            return "static";
        }
    }

    record Keyframes(List<Keyframe> keyframes, String interpolation, Easing easing, boolean constantSpeed, Target lookAt,
                     double lookSmoothSec, double fov, double durationSec, Shake shake) implements Move {
        public String typeName() {
            return "keyframes";
        }
    }

    /** Circles a center; angles are bearings from the center to the camera, as Minecraft yaw (0 = camera south (+Z) of it). */
    record Orbit(Target center, double radius, double radiusEnd, double height, double heightEnd, double startDeg, double endDeg,
                 Easing easing, Target lookAt, double lookSmoothSec, double roll, double fov, double fovEnd, double durationSec,
                 Shake shake) implements Move {
        public String typeName() {
            return "orbit";
        }
    }

    /** Moves along the line of sight toward (or away from) a target, always looking at it. */
    record Dolly(Target target, double azimuthDeg, double elevationDeg, double distanceStart, double distanceEnd, Easing easing,
                 double roll, double fov, double fovEnd, double durationSec, Shake shake) implements Move {
        public String typeName() {
            return "dolly";
        }
    }

    /** Slides sideways past a target; with {@code track} it keeps aiming at it, otherwise the heading stays fixed. */
    record Truck(Target target, double azimuthDeg, double elevationDeg, double distance, double slideStart, double slideEnd,
                 boolean track, Easing easing, double roll, double fov, double fovEnd, double durationSec, Shake shake) implements Move {
        public String typeName() {
            return "truck";
        }
    }

    /** Rides along with a target at an offset (world axes, or rotated with the target's body yaw), looking at it. */
    record Follow(Target target, Vec3d offset, String space, double smoothSec, Target lookAt, double lookSmoothSec,
                  double roll, double fov, double durationSec, Shake shake) implements Move {
        public String typeName() {
            return "follow";
        }
    }

    /** The player's own first-person view (needed for HUD shots). fov NaN keeps the game's FOV setting. */
    record FirstPerson(double fov, double durationSec, Shake shake) implements Move {
        public String typeName() {
            return "firstPerson";
        }
    }

    static Move parse(JObj o) {
        String type = o.choice("type", "static", "keyframes", "orbit", "dolly", "truck", "follow", "firstPerson");
        Move m = switch (type) {
            case "static" -> parseStatic(o);
            case "keyframes" -> parseKeyframes(o);
            case "orbit" -> {
                Target center = Target.parse(o.obj("center"));
                double r = o.num("radius", 0.1, 100000);
                JObj look = o.optObj("lookAt");
                yield new Orbit(center, r, o.num("radiusEnd", 0.1, 100000, r), o.num("height", -10000, 10000, 0),
                        o.optNum("heightEnd", -10000, 10000), o.num("startDeg", -36000, 36000), o.num("endDeg", -36000, 36000),
                        Easing.read(o, "easing", Easing.LINEAR), look == null ? null : Target.parse(look),
                        o.num("lookSmoothSec", 0, 10, 0), o.num("roll", -180, 180, 0), o.num("fov", 1, 170, DEFAULT_FOV),
                        o.optNum("fovEnd", 1, 170), duration(o), Shake.parse(o.optObj("shake")));
            }
            case "dolly" -> new Dolly(Target.parse(o.obj("target")), o.num("azimuthDeg", -36000, 36000),
                    o.num("elevationDeg", -89, 89, 0), o.num("distanceStart", 0.1, 100000), o.num("distanceEnd", 0.1, 100000),
                    Easing.read(o, "easing", Easing.LINEAR), o.num("roll", -180, 180, 0), o.num("fov", 1, 170, DEFAULT_FOV),
                    o.optNum("fovEnd", 1, 170), duration(o), Shake.parse(o.optObj("shake")));
            case "truck" -> new Truck(Target.parse(o.obj("target")), o.num("azimuthDeg", -36000, 36000),
                    o.num("elevationDeg", -89, 89, 0), o.num("distance", 0.1, 100000), o.num("slideStart", -100000, 100000),
                    o.num("slideEnd", -100000, 100000), o.bool("track", false), Easing.read(o, "easing", Easing.LINEAR),
                    o.num("roll", -180, 180, 0), o.num("fov", 1, 170, DEFAULT_FOV), o.optNum("fovEnd", 1, 170), duration(o),
                    Shake.parse(o.optObj("shake")));
            case "follow" -> {
                Target target = Target.parse(o.obj("target"));
                JObj look = o.optObj("lookAt");
                yield new Follow(target, o.vec3("offset"), o.choiceOr("space", "world", "world", "targetYaw"),
                        o.num("smoothSec", 0, 10, 0.25), look == null ? null : Target.parse(look), o.num("lookSmoothSec", 0, 10, 0),
                        o.num("roll", -180, 180, 0), o.num("fov", 1, 170, DEFAULT_FOV), duration(o), Shake.parse(o.optObj("shake")));
            }
            case "firstPerson" -> new FirstPerson(o.optNum("fov", 1, 170), duration(o), Shake.parse(o.optObj("shake")));
            default -> throw new IllegalStateException(type);
        };
        o.finish();
        return m;
    }

    private static double duration(JObj o) {
        return o.optNum("durationSec", 0.001, 3600);
    }

    private static Static parseStatic(JObj o) {
        Vec3d pos = o.vec3("pos");
        boolean hasYaw = o.has("yaw"), hasPitch = o.has("pitch"), hasLook = o.has("lookAt");
        if (hasYaw != hasPitch) throw new SpecException(o.at(hasYaw ? "pitch" : "yaw"), "yaw and pitch must be given together");
        if (hasYaw == hasLook) throw new SpecException(o.path(), "give exactly one of yaw+pitch or lookAt");
        double yaw = hasYaw ? o.num("yaw", -36000, 36000) : Double.NaN;
        double pitch = hasPitch ? o.num("pitch", -90, 90) : Double.NaN;
        JObj look = o.optObj("lookAt");
        return new Static(pos, yaw, pitch, look == null ? null : Target.parse(look), o.num("lookSmoothSec", 0, 10, 0),
                o.num("roll", -180, 180, 0), o.num("fov", 1, 170, DEFAULT_FOV), duration(o), Shake.parse(o.optObj("shake")));
    }

    static Keyframes parseKeyframes(JObj o) {
        JObj look = o.optObj("lookAt");
        Target lookAt = look == null ? null : Target.parse(look);
        List<JObj> raw = o.objList("keyframes", 1, 1000);
        List<Keyframe> keys = new ArrayList<>(raw.size());
        for (JObj k : raw) keys.add(Keyframe.parse(k, lookAt != null));
        String interpolation = o.choiceOr("interpolation", "catmullRom", "catmullRom", "linear");
        Easing easing = Easing.read(o, "easing", Easing.LINEAR);
        boolean constantSpeed = o.bool("constantSpeed", false);
        double fov = o.num("fov", 1, 170, DEFAULT_FOV);
        double duration = duration(o);
        double lookSmooth = o.num("lookSmoothSec", 0, 10, 0);
        Shake shake = Shake.parse(o.optObj("shake"));
        if (constantSpeed) {
            for (int i = 0; i < keys.size(); i++) {
                if (keys.get(i).relativeTo() != null) {
                    throw new SpecException(o.at("keyframes") + "[" + i + "].relativeTo", "can't be combined with constantSpeed");
                }
            }
        }
        keys = assignTimes(keys, duration, o);
        double total = keys.size() == 1 ? duration : keys.get(keys.size() - 1).t();
        return new Keyframes(List.copyOf(keys), interpolation, easing, constantSpeed, lookAt, lookSmooth, fov, total, shake);
    }

    /** All keyframes carry t (first 0, strictly increasing, no durationSec), or none do (durationSec spreads them by distance). */
    private static List<Keyframe> assignTimes(List<Keyframe> keys, double duration, JObj o) {
        int timed = 0;
        for (Keyframe k : keys) if (!Double.isNaN(k.t())) timed++;
        String kp = o.at("keyframes");
        if (keys.size() == 1) {
            if (timed == 1 && keys.get(0).t() != 0) throw new SpecException(kp + "[0].t", "the first keyframe must be at t = 0");
            return List.of(keys.get(0).withT(0));
        }
        if (timed == keys.size()) {
            if (keys.get(0).t() != 0) throw new SpecException(kp + "[0].t", "the first keyframe must be at t = 0");
            for (int i = 1; i < keys.size(); i++) {
                if (keys.get(i).t() <= keys.get(i - 1).t()) {
                    throw new SpecException(kp + "[" + i + "].t", "keyframe times must increase strictly");
                }
            }
            if (!Double.isNaN(duration)) {
                throw new SpecException(o.at("durationSec"), "omit durationSec when keyframes carry t (the last t is the duration)");
            }
            return keys;
        }
        if (timed != 0) throw new SpecException(kp, "either every keyframe has t or none does");
        if (Double.isNaN(duration)) throw new SpecException(o.at("durationSec"), "required when keyframes have no t");
        double[] cum = new double[keys.size()];
        for (int i = 1; i < keys.size(); i++) cum[i] = cum[i - 1] + Math.max(1e-6, keys.get(i).pos().distance(keys.get(i - 1).pos()));
        List<Keyframe> out = new ArrayList<>(keys.size());
        for (int i = 0; i < keys.size(); i++) out.add(keys.get(i).withT(duration * cum[i] / cum[keys.size() - 1]));
        return out;
    }
}
