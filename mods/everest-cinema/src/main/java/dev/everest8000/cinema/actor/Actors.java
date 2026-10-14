package dev.everest8000.cinema.actor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import dev.everest8000.cinema.Cinema;
import dev.everest8000.cinema.rpc.RpcException;
import dev.everest8000.cinema.spec.ActorSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The live actors, by id. Render thread only. */
public final class Actors {
    private static final Map<String, Actor> ACTORS = new LinkedHashMap<>();
    private static int nextId = -2_000_000;

    private Actors() {}

    static int nextEntityId() {
        return nextId--;
    }

    /** Spawns (or for "player", takes control of) an actor and starts its timeline now. Returns warnings. */
    public static JsonObject spawn(ActorSpec spec) {
        return spawn(spec, true);
    }

    /** start false: the actor stands idle until {@link Actor#start} (shots start timelines at the first frame). */
    public static JsonObject spawn(ActorSpec spec, boolean start) {
        Minecraft mc = Cinema.requireWorld();
        if (ACTORS.containsKey(spec.id())) despawn(spec.id());
        Actor a;
        List<String> warnings = new ArrayList<>();
        if (spec.isPlayer()) {
            a = new Actor(spec, null);
        } else {
            UUID uuid = UUID.nameUUIDFromBytes(("cinema-actor:" + spec.id()).getBytes(StandardCharsets.UTF_8));
            String name = ("~" + spec.id());
            if (name.length() > 16) name = name.substring(0, 16);
            ActorPlayer p = new ActorPlayer(mc.level, new GameProfile(uuid, name));
            p.setId(nextEntityId());
            p.setSkin(Skins.resolve(spec.skin()));
            p.moveTo(spec.pos().x(), spec.pos().y(), spec.pos().z(), (float) spec.yaw(), (float) spec.pitch());
            p.setYHeadRot((float) spec.yaw());
            p.yBodyRot = (float) spec.yaw();
            p.yBodyRotO = p.yBodyRot;
            p.yHeadRotO = p.getYHeadRot();
            mc.level.addEntity(p);
            warnings.addAll(Outfits.apply(p, spec.outfit()));
            a = new Actor(spec, p);
        }
        ACTORS.put(spec.id(), a);
        if (start) a.start(spec.timeline());
        JsonObject r = a.json();
        JsonArray w = new JsonArray();
        warnings.forEach(w::add);
        r.add("warnings", w);
        return r;
    }

    public static void despawn(String id) {
        Actor a = ACTORS.remove(id);
        if (a == null) throw new RpcException(RpcException.NOT_FOUND, "no actor \"" + id + "\"");
        remove(a);
    }

    private static void remove(Actor a) {
        a.discard();
        if (a.body != null) {
            Outfits.forget(a.body);
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) mc.level.removeEntity(a.body.getId(), Entity.RemovalReason.DISCARDED);
        }
    }

    public static void clear() {
        for (Actor a : new ArrayList<>(ACTORS.values())) remove(a);
        ACTORS.clear();
    }

    public static Actor get(String id) {
        Actor a = ACTORS.get(id);
        if (a == null) throw new RpcException(RpcException.NOT_FOUND, "no actor \"" + id + "\"");
        return a;
    }

    public static Actor find(String id) {
        return ACTORS.get(id);
    }

    public static Entity entity(String id) {
        Actor a = ACTORS.get(id);
        if (a == null) return null;
        return a.entity();
    }

    public static boolean any() {
        return !ACTORS.isEmpty();
    }

    public static boolean allTimelinesDone() {
        for (Actor a : ACTORS.values()) if (!a.timelineDone()) return false;
        return true;
    }

    public static boolean isActor(Entity e) {
        return e instanceof ActorPlayer;
    }

    /** End of each client tick. */
    public static void tick(Minecraft mc) {
        if (ACTORS.isEmpty()) return;
        for (Actor a : new ArrayList<>(ACTORS.values())) a.tick(mc);
    }

    /** The tick (in the asking actor's clock) when a cue finished: "cue" in its own timeline, or "actor.cue". */
    static int cueDoneTick(Actor asking, String ref) {
        int dot = ref.indexOf('.');
        if (dot < 0) return asking.doneTick(ref);
        Actor other = ACTORS.get(ref.substring(0, dot));
        if (other == null) return -1;
        int t = other.doneTick(ref.substring(dot + 1));
        if (t < 0) return -1;
        // Convert to the asking actor's clock (timelines may have started on different ticks).
        return t - other.tickNow() + asking.tickNow();
    }

    /** Arm pose override for the renderer, or null. */
    public static HumanoidModel.ArmPose armPose(AbstractClientPlayer p, InteractionHand hand) {
        for (Actor a : ACTORS.values()) {
            if (a.entity() == p) return a.armPoses[hand == InteractionHand.OFF_HAND ? 1 : 0];
        }
        return null;
    }

    public static JsonArray list() {
        JsonArray arr = new JsonArray();
        for (Actor a : ACTORS.values()) arr.add(a.json());
        return arr;
    }

    public static void onDisconnect() {
        ACTORS.clear();
    }
}
