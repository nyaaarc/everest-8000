package dev.everest8000.cinema.spec;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Strict reader for one JSON object: every getter checks type and range, and {@link #finish()} rejects any key that
 * no getter asked for. Errors carry the full path of the offending value.
 */
public final class JObj {
    private final JsonObject o;
    private final String path;
    private final Set<String> used = new HashSet<>();

    private JObj(JsonObject o, String path) {
        this.o = o;
        this.path = path;
    }

    public static JObj of(JsonElement e, String path) {
        if (e == null || e.isJsonNull()) return new JObj(new JsonObject(), path);
        if (!e.isJsonObject()) throw new SpecException(path, "expected an object, got " + kind(e));
        return new JObj(e.getAsJsonObject(), path);
    }

    public String path() {
        return path;
    }

    public String at(String key) {
        return path.isEmpty() ? key : path + "." + key;
    }

    public boolean has(String key) {
        JsonElement e = o.get(key);
        return e != null && !e.isJsonNull();
    }

    private JsonElement get(String key) {
        used.add(key);
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e;
    }

    private JsonElement require(String key) {
        JsonElement e = get(key);
        if (e == null) throw new SpecException(at(key), "required");
        return e;
    }

    // ---- numbers

    public double num(String key) {
        return asNum(require(key), at(key));
    }

    public double num(String key, double min, double max) {
        return range(num(key), min, max, at(key));
    }

    public double num(String key, double min, double max, double def) {
        JsonElement e = get(key);
        return e == null ? def : range(asNum(e, at(key)), min, max, at(key));
    }

    /** Optional number, NaN when absent. */
    public double optNum(String key, double min, double max) {
        JsonElement e = get(key);
        return e == null ? Double.NaN : range(asNum(e, at(key)), min, max, at(key));
    }

    public int integer(String key, int min, int max) {
        return asInt(require(key), at(key), min, max);
    }

    public int integer(String key, int min, int max, int def) {
        JsonElement e = get(key);
        return e == null ? def : asInt(e, at(key), min, max);
    }

    public long longValue(String key, long min, long max) {
        JsonElement e = require(key);
        double d = asNum(e, at(key));
        if (d != Math.rint(d)) throw new SpecException(at(key), "expected an integer, got " + d);
        if (d < min || d > max) throw new SpecException(at(key), "must be between " + min + " and " + max + ", got " + (long) d);
        return (long) d;
    }

    // ---- booleans and strings

    public boolean bool(String key) {
        return asBool(require(key), at(key));
    }

    public boolean bool(String key, boolean def) {
        JsonElement e = get(key);
        return e == null ? def : asBool(e, at(key));
    }

    public Boolean optBool(String key) {
        JsonElement e = get(key);
        return e == null ? null : asBool(e, at(key));
    }

    public String string(String key) {
        return asString(require(key), at(key));
    }

    public String string(String key, Pattern pattern, String what) {
        String s = string(key);
        if (!pattern.matcher(s).matches()) throw new SpecException(at(key), "must be " + what + ", got \"" + s + "\"");
        return s;
    }

    public String optString(String key, Pattern pattern, String what) {
        JsonElement e = get(key);
        if (e == null) return null;
        String s = asString(e, at(key));
        if (pattern != null && !pattern.matcher(s).matches()) throw new SpecException(at(key), "must be " + what + ", got \"" + s + "\"");
        return s;
    }

    /** One of a fixed set of strings. */
    public String choice(String key, String... allowed) {
        return checkChoice(string(key), at(key), allowed);
    }

    public String choiceOr(String key, String def, String... allowed) {
        JsonElement e = get(key);
        return e == null ? def : checkChoice(asString(e, at(key)), at(key), allowed);
    }

    public UUID uuid(String key) {
        String s = string(key);
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException ex) {
            throw new SpecException(at(key), "expected a UUID, got \"" + s + "\"");
        }
    }

    // ---- vectors, objects, arrays

    public Vec3d vec3(String key) {
        return asVec3(require(key), at(key));
    }

    public Vec3d vec3(String key, Vec3d def) {
        JsonElement e = get(key);
        return e == null ? def : asVec3(e, at(key));
    }

    public JObj obj(String key) {
        return of(requireType(key, "an object"), at(key));
    }

    public JObj optObj(String key) {
        JsonElement e = get(key);
        if (e == null) return null;
        return of(e, at(key));
    }

    public List<JObj> objList(String key, int minItems, int maxItems) {
        JsonArray a = array(key, minItems, maxItems);
        List<JObj> out = new ArrayList<>(a.size());
        for (int i = 0; i < a.size(); i++) out.add(of(a.get(i), at(key) + "[" + i + "]"));
        return out;
    }

    /** Optional list: empty when absent. */
    public List<JObj> optObjList(String key, int maxItems) {
        if (!has(key)) {
            used.add(key);
            return List.of();
        }
        return objList(key, 0, maxItems);
    }

    public List<Vec3d> vec3List(String key, int minItems, int maxItems) {
        JsonArray a = array(key, minItems, maxItems);
        List<Vec3d> out = new ArrayList<>(a.size());
        for (int i = 0; i < a.size(); i++) out.add(asVec3(a.get(i), at(key) + "[" + i + "]"));
        return out;
    }

    public List<String> stringList(String key, int minItems, int maxItems) {
        JsonArray a = array(key, minItems, maxItems);
        List<String> out = new ArrayList<>(a.size());
        for (int i = 0; i < a.size(); i++) out.add(asString(a.get(i), at(key) + "[" + i + "]"));
        return out;
    }

    private JsonArray array(String key, int minItems, int maxItems) {
        JsonElement e = requireType(key, "an array");
        if (!e.isJsonArray()) throw new SpecException(at(key), "expected an array, got " + kind(e));
        JsonArray a = e.getAsJsonArray();
        if (a.size() < minItems) throw new SpecException(at(key), "needs at least " + minItems + " item(s), got " + a.size());
        if (a.size() > maxItems) throw new SpecException(at(key), "allows at most " + maxItems + " items, got " + a.size());
        return a;
    }

    private JsonElement requireType(String key, String what) {
        JsonElement e = get(key);
        if (e == null) throw new SpecException(at(key), "required (" + what + ")");
        return e;
    }

    /** Rejects keys no getter consumed. Call after reading every field. */
    public void finish() {
        Set<String> unknown = new LinkedHashSet<>(o.keySet());
        unknown.removeAll(used);
        if (!unknown.isEmpty()) {
            throw new SpecException(path.isEmpty() ? "(root)" : path, "unknown field(s) " + unknown + "; allowed: " + used);
        }
    }

    /** Marks a key as allowed without reading it (used where the value is read by another pass). */
    public void allow(String key) {
        used.add(key);
    }

    public JsonObject raw() {
        return o;
    }

    // ---- primitives

    static double asNum(JsonElement e, String path) {
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
            double d = e.getAsDouble();
            if (Double.isNaN(d) || Double.isInfinite(d)) throw new SpecException(path, "must be a finite number");
            return d;
        }
        throw new SpecException(path, "expected a number, got " + kind(e));
    }

    static int asInt(JsonElement e, String path, int min, int max) {
        double d = asNum(e, path);
        if (d != Math.rint(d)) throw new SpecException(path, "expected an integer, got " + d);
        if (d < min || d > max) throw new SpecException(path, "must be between " + min + " and " + max + ", got " + (long) d);
        return (int) d;
    }

    static boolean asBool(JsonElement e, String path) {
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) return e.getAsBoolean();
        throw new SpecException(path, "expected true or false, got " + kind(e));
    }

    static String asString(JsonElement e, String path) {
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) return e.getAsString();
        throw new SpecException(path, "expected a string, got " + kind(e));
    }

    static Vec3d asVec3(JsonElement e, String path) {
        if (!e.isJsonArray() || e.getAsJsonArray().size() != 3) {
            throw new SpecException(path, "expected [x, y, z] (an array of 3 numbers), got " + kind(e));
        }
        JsonArray a = e.getAsJsonArray();
        return new Vec3d(asNum(a.get(0), path + "[0]"), asNum(a.get(1), path + "[1]"), asNum(a.get(2), path + "[2]"));
    }

    static double range(double v, double min, double max, String path) {
        if (v < min || v > max) throw new SpecException(path, "must be between " + fmt(min) + " and " + fmt(max) + ", got " + fmt(v));
        return v;
    }

    static String checkChoice(String s, String path, String... allowed) {
        for (String a : allowed) if (a.equals(s)) return s;
        throw new SpecException(path, "must be one of " + String.join(", ", allowed) + "; got \"" + s + "\"");
    }

    static String kind(JsonElement e) {
        if (e == null || e.isJsonNull()) return "null";
        if (e.isJsonObject()) return "an object";
        if (e.isJsonArray()) return "an array of " + e.getAsJsonArray().size();
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isBoolean()) return "a boolean";
        if (p.isNumber()) return "the number " + p.getAsString();
        return "the string \"" + p.getAsString() + "\"";
    }

    static String fmt(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : Double.toString(d);
    }
}
