package dev.everest8000.cinema.actor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.everest8000.cinema.Cinema;
import dev.everest8000.cinema.camera.McTargets;
import dev.everest8000.cinema.camera.TargetResolver;
import dev.everest8000.cinema.nav.LookControl;
import dev.everest8000.cinema.nav.Locomotion;
import dev.everest8000.cinema.nav.PathFinder;
import dev.everest8000.cinema.spec.ActorAction;
import dev.everest8000.cinema.spec.ActorSpec;
import dev.everest8000.cinema.spec.Vec3d;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The director for one walker: a client-side {@link ActorPlayer}, or the real local player (id "player"). Each client
 * tick it fires due cues, runs their actions, and computes the next tick's controls with {@link Locomotion} and
 * {@link LookControl}. All timing is in ticks since the timeline started, so takes are deterministic under
 * frame-stepped capture.
 */
public final class Actor {
    private enum CueState { PENDING, RUNNING, DONE, FAILED }

    private final class Cue {
        final ActorSpec.Cue spec;
        CueState state = CueState.PENDING;
        int doneTick = -1;
        int endTick = -1;
        String error;

        Cue(ActorSpec.Cue spec) {
            this.spec = spec;
        }
    }

    final ActorSpec spec;
    final ActorPlayer body; // null for the player
    private Locomotion loco;
    private LookControl look;
    private final List<Cue> cues = new ArrayList<>();
    private final List<String> log = new ArrayList<>();
    private final List<Arrow> arrows = new ArrayList<>();
    private int tick = -1;
    private boolean running;
    private Cue walkCue;
    private dev.everest8000.cinema.spec.Target lookTarget;
    private boolean braced;
    private double braceYaw = Double.NaN;
    private double faceYaw = Double.NaN;
    private int useTicksLeft = -1;
    private InteractionHand useHand = InteractionHand.MAIN_HAND;
    private boolean using;
    private boolean radioKeyed;
    final HumanoidModel.ArmPose[] armPoses = new HumanoidModel.ArmPose[2];
    private double bodyYaw;
    private boolean pendingJump;
    private int repaths;
    private int walks;
    private boolean grounded;
    private int settleTries;

    Actor(ActorSpec spec, ActorPlayer body) {
        this.spec = spec;
        this.body = body;
        this.loco = new Locomotion(spec.gait(), spec.seed());
        this.look = new LookControl(spec.seed(), spec.gait().idleLook());
        LivingEntity e = entity();
        this.bodyYaw = e == null ? 0 : e.getYRot();
    }

    public String id() {
        return spec.id();
    }

    public LivingEntity entity() {
        if (body != null) return body;
        return Minecraft.getInstance().player;
    }

    public boolean isPlayer() {
        return body == null;
    }

    /**
     * Replaces the timeline and starts it now (tick 0). Walking, looking and their noise start afresh from the seed, so a
     * take plays the same however long the actor stood idle before it (a warm-up runs in real time).
     */
    public void start(List<ActorSpec.Cue> timeline) {
        cues.clear();
        for (ActorSpec.Cue c : timeline) cues.add(new Cue(c));
        tick = 0;
        running = true;
        loco = new Locomotion(spec.gait(), spec.seed());
        look = new LookControl(spec.seed(), spec.gait().idleLook());
        walkCue = null;
        walks = 0;
        repaths = 0;
        LivingEntity e = entity();
        if (e != null) bodyYaw = e.getYRot();
    }

    public boolean timelineDone() {
        for (Cue c : cues) if (c.state == CueState.PENDING || c.state == CueState.RUNNING) return false;
        return true;
    }

    public int cueCount() {
        return cues.size();
    }

    /** pending | running | done | failed for the cue at index i. */
    public String cueState(int i) {
        return i < cues.size() ? cues.get(i).state.name().toLowerCase(Locale.ROOT) : "pending";
    }

    public String cueError(int i) {
        return i < cues.size() ? cues.get(i).error : null;
    }

    /** Runs one action right away (outside the timeline). */
    public void act(ActorAction a) {
        Cue c = new Cue(new ActorSpec.Cue(null, ActorSpec.Trigger.AT_START, a));
        cues.add(c);
        begin(c);
    }

    /** Called at the end of each client tick. */
    void tick(Minecraft mc) {
        LivingEntity e = entity();
        if (e == null) return;
        if (body != null && !grounded) {
            // Hold the actor where it was spawned, without gravity, until the ground under it is really there (a chunk can
            // exist before its blocks have arrived; physics then would drop the actor through the terrain), then settle
            // it onto the ground once: a spec y a block off would otherwise bury it or drop it.
            body.setNoGravity(true);
            body.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            body.setPos(spec.pos().x(), spec.pos().y(), spec.pos().z());
            if (!e.level().hasChunkAt(e.blockPosition()) || !settleOnGround(e)) {
                if (++settleTries == 200) log("still waiting for ground under the spawn point after 10 s");
                return;
            }
            grounded = true;
            body.setNoGravity(false);
        }
        if (running) tick++;
        // Cues whose trigger has come.
        if (running) {
            for (Cue c : cues) {
                if (c.state != CueState.PENDING) continue;
                if (due(c, e)) begin(c);
            }
        }
        // Running cues that finish on their own.
        for (Cue c : cues) {
            if (c.state != CueState.RUNNING) continue;
            if (c.endTick >= 0 && tick >= c.endTick) finish(c, null);
        }
        if (useTicksLeft > 0 && --useTicksLeft == 0) endUse(false, 0);

        // Locomotion.
        McBlockQuery q = new McBlockQuery(e.level());
        loco.setWalkable(new PathFinder(q, PathFinder.Options.DEFAULT));
        Locomotion.State st = new Locomotion.State(e.getX(), e.getY(), e.getZ(), bodyYaw, e.onGround(), e.horizontalCollision, e.onClimbable());
        Locomotion.Control ctl = loco.step(st, q);
        if (walkCue != null && walkCue.state == CueState.RUNNING) {
            if (loco.outcome() == Locomotion.Outcome.ARRIVED) {
                finish(walkCue, null);
                walkCue = null;
            } else if (loco.outcome() == Locomotion.Outcome.STUCK) {
                ActorAction.WalkTo w = (ActorAction.WalkTo) walkCue.spec.action();
                if (repaths < 3 && w.path().equals("astar")) {
                    // Route again from here to the remaining points.
                    repaths++;
                    log("t=" + tick + " stuck (" + loco.stuckInfo() + "), routing again");
                    try {
                        loco.follow(route(e, new ActorAction.WalkTo(remainingTargets(w, e), w.speed(), w.path(), w.arriveRadius(),
                                w.maxNodes())), w.speed(), w.arriveRadius(), true);
                    } catch (RuntimeException ex) {
                        finish(walkCue, "stuck and no new route: " + ex.getMessage());
                        walkCue = null;
                    }
                } else {
                    finish(walkCue, "stuck " + loco.stuckInfo());
                    walkCue = null;
                }
            }
        }
        bodyYaw = ctl.yaw();
        boolean moving = Math.abs(ctl.forward()) > 0.05;
        if (!moving && !Double.isNaN(faceYaw)) bodyYaw = turnToward(bodyYaw, faceYaw, 120 * 0.05);
        if (braced && !Double.isNaN(braceYaw) && !moving) bodyYaw = turnToward(bodyYaw, braceYaw, 90 * 0.05);

        // Head.
        if (lookTarget != null) {
            try {
                Vec3d t = McTargets.INSTANCE.position(lookTarget, 1f);
                Vec3d eye = new Vec3d(e.getX(), e.getEyeY(), e.getZ());
                look.updateTarget(eye.yawTo(t), eye.pitchTo(t));
            } catch (TargetResolver.TargetNotFoundException ex) {
                look.clear();
                lookTarget = null;
            }
        }
        double aheadPitch = braced ? 28 : ctl.climbing() ? -35 : moving ? 10 : 6;
        LookControl.Look lk = look.step(bodyYaw, moving, aheadPitch,
                new LookControl.Breather(loco.breatherPhase(), loco.breatherProgress(), loco.breatherCount(), teammateYaw(e)));
        bodyYaw = lk.bodyYaw();
        // Standing idle on a ladder (before a climb starts, or between cues): hold on, as a player does by sneaking,
        // instead of sliding down.
        boolean holdLadder = e.onClimbable() && !loco.walking();
        if (holdLadder) e.setDeltaMovement(e.getDeltaMovement().multiply(1, 0, 1));
        boolean sneak = ctl.sneak() || braced || lk.crouch() || holdLadder;
        apply(e, ctl, lk, sneak);
    }

    private void apply(LivingEntity e, Locomotion.Control ctl, LookControl.Look lk, boolean sneak) {
        if (body != null) {
            body.setYRot((float) bodyYaw);
            body.setYHeadRot((float) lk.headYaw());
            body.setXRot((float) lk.pitch());
            body.forward = ctl.forward();
            body.strafe = ctl.strafe();
            body.jump = ctl.jump() || pendingJump;
            pendingJump = false;
            body.sneak = sneak;
            body.sprint = ctl.sprint();
        } else {
            // The local player: view yaw is the head; move along the body heading by splitting the input.
            LocalPlayer p = (LocalPlayer) e;
            double rel = Math.toRadians(bodyYaw - lk.headYaw());
            float f = ctl.forward();
            float fwd = (float) (Math.cos(rel) * f);
            float str = (float) (-Math.sin(rel) * f);
            p.setYRot((float) lk.headYaw());
            p.setYHeadRot((float) lk.headYaw());
            p.setXRot((float) lk.pitch());
            Cinema.puppet.setInput(fwd, str, ctl.jump(), sneak, ctl.sprint(), using);
        }
    }

    private boolean due(Cue c, LivingEntity e) {
        ActorSpec.Trigger t = c.spec.at();
        return switch (t.kind()) {
            case "time" -> tick >= Math.round(t.timeSec() * 20);
            case "after" -> {
                int doneAt = Actors.cueDoneTick(this, t.after());
                yield doneAt >= 0 && tick >= doneAt + Math.round(t.delaySec() * 20);
            }
            case "near" -> e.position().distanceTo(new net.minecraft.world.phys.Vec3(t.near().x(), t.near().y(), t.near().z())) <= t.radius();
            default -> false;
        };
    }

    /** Tick (in this actor's timeline clock) at which a cue finished, or -1. */
    int doneTick(String cueId) {
        for (Cue c : cues) if (cueId.equals(c.spec.id()) && (c.state == CueState.DONE || c.state == CueState.FAILED)) return c.doneTick;
        return -1;
    }

    int tickNow() {
        return tick;
    }

    private void begin(Cue c) {
        c.state = CueState.RUNNING;
        LivingEntity e = entity();
        ActorAction a = c.spec.action();
        log("t=" + tick + " start " + a.typeName() + (c.spec.id() == null ? "" : " (" + c.spec.id() + ")"));
        try {
            switch (a) {
                case ActorAction.WalkTo w -> {
                    List<Vec3d> pts = route(e, w);
                    if (walkCue != null && walkCue.state == CueState.RUNNING) finish(walkCue, "replaced by a new walk");
                    loco.follow(pts, w.speed(), w.arriveRadius(), w.path().equals("astar"));
                    walkCue = c;
                    repaths = 0;
                    return;
                }
                case ActorAction.LookAt l -> {
                    lookTarget = l.target();
                    Vec3d t = McTargets.INSTANCE.position(l.target(), 1f);
                    Vec3d eye = new Vec3d(e.getX(), e.getEyeY(), e.getZ());
                    boolean track = l.mode().equals("track");
                    look.lookAt(eye.yawTo(t), eye.pitchTo(t), track, l.durationSec());
                    if (!track) {
                        c.endTick = tick + (int) Math.round(l.durationSec() * 20);
                        return;
                    }
                }
                case ActorAction.Look l -> {
                    lookTarget = null;
                    boolean track = l.mode().equals("track");
                    look.lookAt(l.yaw(), l.pitch(), track, l.durationSec());
                    if (!track) {
                        c.endTick = tick + (int) Math.round(l.durationSec() * 20);
                        return;
                    }
                }
                case ActorAction.LookAhead l -> {
                    lookTarget = null;
                    look.clear();
                }
                case ActorAction.Face f -> faceYaw = f.yaw();
                case ActorAction.Stop s -> {
                    loco.stop();
                    if (walkCue != null) finish(walkCue, "stopped");
                    walkCue = null;
                }
                case ActorAction.Breather b -> {
                    loco.breather(b.durationSec());
                    c.endTick = tick + (int) Math.round(b.durationSec() * 20);
                    return;
                }
                case ActorAction.Jump j -> {
                    if (body != null) pendingJump = true;
                    else Cinema.puppet.jump();
                }
                case ActorAction.Crouch cr -> loco.setSneak(cr.on());
                case ActorAction.Brace br -> {
                    braced = br.on();
                    braceYaw = br.windYaw();
                }
                case ActorAction.Swing s -> e.swing(s.hand().equals("off") ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
                case ActorAction.Use u -> {
                    useHand = u.hand().equals("off") ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
                    using = true;
                    if (body != null) body.beginUse(useHand);
                    if (u.durationSec() > 0) {
                        useTicksLeft = (int) Math.round(u.durationSec() * 20);
                        c.endTick = tick + useTicksLeft;
                        return;
                    }
                }
                case ActorAction.Release r -> endUse(r.arrow(), r.power());
                case ActorAction.Pose p -> pose(e, p);
                case ActorAction.Equip q -> {
                    EquipmentSlot slot = switch (q.slot()) {
                        case "head" -> EquipmentSlot.HEAD;
                        case "chest" -> EquipmentSlot.CHEST;
                        case "legs" -> EquipmentSlot.LEGS;
                        case "feet" -> EquipmentSlot.FEET;
                        case "offhand" -> EquipmentSlot.OFFHAND;
                        default -> EquipmentSlot.MAINHAND;
                    };
                    body.setItemSlot(slot, q.item().equals("minecraft:air") ? ItemStack.EMPTY : Outfits.item(q.item()));
                }
                case ActorAction.Hood h -> body.setItemSlot(EquipmentSlot.HEAD, Outfits.item(Outfits.hood(h.down(), spec.outfit().jacketRgb())));
                case ActorAction.Mask m -> Outfits.setBreathing(body.getId(), m.on());
                case ActorAction.Gravity g -> {
                    e.setNoGravity(!g.on());
                    if (!g.on()) e.setDeltaMovement(e.getDeltaMovement().multiply(1, 0, 1));
                }
                case ActorAction.Teleport t -> {
                    if (body != null) {
                        body.moveTo(t.pos().x(), t.pos().y(), t.pos().z(), Double.isNaN(t.yaw()) ? body.getYRot() : (float) t.yaw(), body.getXRot());
                        body.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                    } else {
                        Cinema.sendCommand(String.format(Locale.ROOT, "tp @s %.4f %.4f %.4f", t.pos().x(), t.pos().y(), t.pos().z()));
                    }
                    if (!Double.isNaN(t.yaw())) bodyYaw = t.yaw();
                }
            }
            finish(c, null);
        } catch (RuntimeException ex) {
            finish(c, ex.getMessage());
        }
    }

    private List<Vec3d> route(LivingEntity e, ActorAction.WalkTo w) {
        if (w.path().equals("direct")) return w.points();
        McBlockQuery q = new McBlockQuery(e.level());
        PathFinder pf = new PathFinder(q, new PathFinder.Options(w.maxNodes(), 3, 0.8, 0.6, 0.6, 0.6));
        List<Vec3d> out = new ArrayList<>();
        Vec3d from = new Vec3d(e.getX(), e.getY(), e.getZ());
        for (Vec3d to : w.points()) {
            PathFinder.Result r = pf.find(from, to);
            if (r.status() == PathFinder.Status.NO_START || r.status() == PathFinder.Status.NO_PATH) {
                throw new IllegalStateException("no path from " + from + " to " + to + " (" + r.status() + ")");
            }
            if (r.status() == PathFinder.Status.PARTIAL) log("partial path to " + to + " after " + r.expanded() + " nodes");
            List<Vec3d> pts = r.points();
            out.addAll(out.isEmpty() ? pts : pts.subList(Math.min(1, pts.size()), pts.size()));
            from = pts.isEmpty() ? from : pts.get(pts.size() - 1);
        }
        // Meander like a person instead of following grid lines (seeded per actor and walk).
        return dev.everest8000.cinema.nav.RouteShaper.shape(out, pf, spec.seed() * 31 + walks++, spec.gait().wander(),
                spec.gait().wanderWavelength());
    }

    /** Yaw toward the nearest other actor within 40 blocks (for a breather's look back), or NaN. */
    private double teammateYaw(LivingEntity e) {
        LivingEntity best = null;
        double bd = 40 * 40;
        for (net.minecraft.world.entity.Entity o : e.level().getEntities(e, e.getBoundingBox().inflate(40))) {
            if (!(o instanceof ActorPlayer) && !(o instanceof net.minecraft.client.player.LocalPlayer && Actors.find(ActorSpec.PLAYER) != null)) continue;
            double d = o.distanceToSqr(e);
            if (d < bd) {
                bd = d;
                best = (LivingEntity) o;
            }
        }
        if (best == null) return Double.NaN;
        return new Vec3d(e.getX(), e.getEyeY(), e.getZ()).yawTo(new Vec3d(best.getX(), best.getEyeY(), best.getZ()));
    }

    /** The walk's targets not yet reached (the last one at least). */
    private static List<Vec3d> remainingTargets(ActorAction.WalkTo w, LivingEntity e) {
        List<Vec3d> pts = w.points();
        int first = pts.size() - 1;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < pts.size(); i++) {
            double d = e.position().distanceToSqr(pts.get(i).x(), pts.get(i).y(), pts.get(i).z());
            if (d < best) {
                best = d;
                first = i;
            }
        }
        return pts.subList(first, pts.size());
    }

    private void endUse(boolean arrow, double power) {
        if (!using) return;
        using = false;
        useTicksLeft = -1;
        LivingEntity e = entity();
        if (body != null) body.endUse();
        if (arrow && body != null && e.getItemInHand(useHand).is(Items.BOW)) {
            Arrow a = new Arrow(EntityType.ARROW, e.level());
            a.setPos(e.getX(), e.getEyeY() - 0.1, e.getZ());
            a.shootFromRotation(e, e.getXRot(), e.getYHeadRot(), 0f, (float) power, 0.5f);
            a.setId(Actors.nextEntityId());
            Minecraft.getInstance().level.addEntity(a);
            arrows.add(a);
        }
    }

    private void finish(Cue c, String error) {
        if (c.state == CueState.DONE || c.state == CueState.FAILED) return;
        c.state = error == null ? CueState.DONE : CueState.FAILED;
        c.error = error;
        c.doneTick = tick;
        log("t=" + tick + (error == null ? " done " : " FAILED ") + c.spec.action().typeName() + (error == null ? "" : ": " + error));
    }

    /** True once the actor stands on real ground (moved onto it if the spawn y was a little off). */
    private boolean settleOnGround(LivingEntity e) {
        McBlockQuery q = new McBlockQuery(e.level());
        int bx = (int) Math.floor(e.getX()), bz = (int) Math.floor(e.getZ());
        int gy = new PathFinder(q, PathFinder.Options.DEFAULT).snapToGround(bx, (int) Math.floor(e.getY() + 0.01), bz, 6);
        if (gy == Integer.MIN_VALUE) return false; // blocks not here yet (or no ground near the spawn point): keep holding
        double top = q.collisionTop(bx, gy, bz);
        double y = gy + (top > 0 && top < 1 ? top : 0) + 0.01;
        if (Math.abs(y - e.getY()) > 0.05) {
            log(String.format(Locale.ROOT, "spawn y %.2f moved to the ground at %.2f", e.getY(), y));
            e.moveTo(e.getX(), y, e.getZ(), e.getYRot(), e.getXRot());
            e.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        }
        return true;
    }

    /**
     * Arm poses. "raise" keys Everest Radio's raise-to-face animation (the arm holding the radio, main hand first, eases
     * up in client ticks); without the radio mod it falls back to the vanilla horn pose. Any other pose lowers the radio.
     */
    private void pose(LivingEntity e, ActorAction.Pose p) {
        int hand = p.hand().equals("off") ? 1 : 0;
        if (p.pose().equals("raise")) {
            String err = RadioHook.setKeyed(e.getId(), true);
            if (err == null) {
                radioKeyed = true;
                armPoses[0] = armPoses[1] = null; // the radio mod poses the arm that holds the radio
                RadioHook.setAmount(e.getId(), Double.isNaN(p.amount()) ? Float.NaN : (float) p.amount());
                if (!holdsRadio(e)) log("t=" + tick + " raise: no " + RADIO_ITEM + " in either hand; the main arm comes up empty");
                return;
            }
            log("t=" + tick + " raise: " + err + "; using the horn pose");
            armPoses[hand] = HumanoidModel.ArmPose.TOOT_HORN;
            return;
        }
        if (radioKeyed) lowerRadio(e);
        armPoses[hand] = switch (p.pose()) {
            case "spyglass" -> HumanoidModel.ArmPose.SPYGLASS;
            case "bow" -> HumanoidModel.ArmPose.BOW_AND_ARROW;
            case "crossbow" -> HumanoidModel.ArmPose.CROSSBOW_HOLD;
            case "block" -> HumanoidModel.ArmPose.BLOCK;
            case "brush" -> HumanoidModel.ArmPose.BRUSH;
            default -> null;
        };
    }

    static final String RADIO_ITEM = "everest_radio:radio";

    private static boolean holdsRadio(LivingEntity e) {
        for (ItemStack s : new ItemStack[]{e.getMainHandItem(), e.getOffhandItem()}) {
            if (!s.isEmpty() && net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals(RADIO_ITEM)) return true;
        }
        return false;
    }

    private void lowerRadio(LivingEntity e) {
        RadioHook.setAmount(e.getId(), Float.NaN);
        RadioHook.setKeyed(e.getId(), false);
        radioKeyed = false;
    }

    private void log(String s) {
        if (log.size() > 500) log.remove(0);
        log.add(s);
    }

    void discard() {
        LivingEntity e = entity();
        if (radioKeyed && e != null) lowerRadio(e);
        for (Arrow a : arrows) if (!a.isRemoved()) Minecraft.getInstance().level.removeEntity(a.getId(), net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
        arrows.clear();
        if (body == null) {
            Cinema.puppet.stop();
        }
    }

    public JsonObject json() {
        LivingEntity e = entity();
        JsonObject o = new JsonObject();
        o.addProperty("id", spec.id());
        o.addProperty("player", isPlayer());
        if (e != null) {
            JsonArray pos = new JsonArray();
            pos.add(Math.round(e.getX() * 1000) / 1000.0);
            pos.add(Math.round(e.getY() * 1000) / 1000.0);
            pos.add(Math.round(e.getZ() * 1000) / 1000.0);
            o.add("pos", pos);
            o.addProperty("entityId", e.getId());
            o.addProperty("bodyYaw", Math.round(bodyYaw * 10) / 10.0);
            o.addProperty("headYaw", Math.round(e.getYHeadRot() * 10) / 10.0);
            o.addProperty("pitch", Math.round(e.getXRot() * 10) / 10.0);
            o.addProperty("onGround", e.onGround());
            if (radioKeyed || RadioHook.available()) {
                o.addProperty("radioKeyed", radioKeyed);
                double ra = RadioHook.amount(e.getId(), 1f);
                if (!Double.isNaN(ra)) o.addProperty("radioAmount", Math.round(ra * 1000) / 1000.0);
            }
        }
        o.addProperty("tick", tick);
        o.addProperty("walking", loco.walking());
        o.addProperty("breather", loco.inBreather());
        o.addProperty("forward", Math.round(loco.currentForward() * 100) / 100.0);
        o.addProperty("timelineDone", timelineDone());
        JsonArray cs = new JsonArray();
        for (Cue c : cues) {
            JsonObject j = new JsonObject();
            if (c.spec.id() != null) j.addProperty("id", c.spec.id());
            j.addProperty("action", c.spec.action().typeName());
            j.addProperty("state", c.state.name().toLowerCase(Locale.ROOT));
            if (c.doneTick >= 0) j.addProperty("doneSec", c.doneTick / 20.0);
            if (c.error != null) j.addProperty("error", c.error);
            cs.add(j);
        }
        o.add("cues", cs);
        JsonArray lg = new JsonArray();
        log.subList(Math.max(0, log.size() - 20), log.size()).forEach(lg::add);
        o.add("log", lg);
        return o;
    }

    private static double turnToward(double from, double to, double max) {
        double d = wrapDeg(to - from);
        return from + Math.max(-max, Math.min(max, d));
    }

    private static double wrapDeg(double deg) {
        deg %= 360;
        if (deg >= 180) deg -= 360;
        if (deg < -180) deg += 360;
        return deg;
    }
}
