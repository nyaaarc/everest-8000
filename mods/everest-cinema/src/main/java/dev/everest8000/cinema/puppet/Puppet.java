package dev.everest8000.cinema.puppet;

import com.google.gson.JsonObject;
import dev.everest8000.cinema.camera.McTargets;
import dev.everest8000.cinema.camera.TargetResolver;
import dev.everest8000.cinema.rpc.RpcException;
import dev.everest8000.cinema.spec.Easing;
import dev.everest8000.cinema.spec.Target;
import dev.everest8000.cinema.spec.Vec3d;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Drives the local player like a puppet: walking through points with real physics (steering the yaw, jumping over
 * blocks), smooth look-arounds, raw input, and the freezing meter. Movement input replaces the keyboard's (see
 * KeyboardInputMixin) while the puppet is engaged; rotations are applied after each tick so they render interpolated.
 */
public final class Puppet {
    /** Max turn per tick while walking, degrees. */
    private static final float TURN_PER_TICK = 14f;

    private boolean engaged;
    // held input
    private float forward;
    private float strafe;
    private boolean jumpHeld;
    private boolean sneak;
    private boolean sprint;
    private boolean use;
    private int jumpTicks;
    // walking
    private List<Vec3d> path = List.of();
    private int pathIndex;
    private String speed = "walk";
    private double arriveRadius = 0.6;
    private double bestDist;
    private int stuckTicks;
    private CompletableFuture<JsonObject> walkDone;
    // looking
    private float lookFromYaw, lookFromPitch, lookToYaw, lookToPitch;
    private Target lookTarget;
    private int lookTick, lookTicks;
    private Easing lookEasing = Easing.SINE_IN_OUT;
    private boolean looking;
    private CompletableFuture<JsonObject> lookDone;
    // freezing meter
    private float frozenFrom, frozenTo;
    private int frozenTick, frozenTicks;
    private boolean frozenActive;

    public boolean engaged() {
        return engaged;
    }

    public boolean walking() {
        return pathIndex < path.size();
    }

    public void setInput(float forward, float strafe, boolean jump, boolean sneak, boolean sprint, boolean use) {
        engaged = true;
        this.forward = forward;
        this.strafe = strafe;
        this.jumpHeld = jump;
        this.sneak = sneak;
        this.sprint = sprint;
        this.use = use;
    }

    public void clearInput() {
        forward = strafe = 0;
        jumpHeld = sneak = sprint = false;
        setUse(false);
    }

    public void jump() {
        engaged = true;
        jumpTicks = 2;
    }

    public void swing(boolean offhand) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) p.swing(offhand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
    }

    /** Starts walking; the future completes when the last point is reached, or fails if blocked for 5 s. */
    public CompletableFuture<JsonObject> walk(List<Vec3d> points, String speed, double arriveRadius) {
        cancelWalk("replaced by a new walk");
        engaged = true;
        this.path = new ArrayList<>(points);
        this.pathIndex = 0;
        this.speed = speed;
        this.arriveRadius = arriveRadius;
        this.bestDist = Double.MAX_VALUE;
        this.stuckTicks = 0;
        looking = false;
        walkDone = new CompletableFuture<>();
        return walkDone;
    }

    public CompletableFuture<JsonObject> look(float yaw, float pitch, double seconds, Easing easing) {
        return startLook(yaw, pitch, null, seconds, easing);
    }

    public CompletableFuture<JsonObject> lookAt(Target target, double seconds, Easing easing) {
        return startLook(0, 0, target, seconds, easing);
    }

    private CompletableFuture<JsonObject> startLook(float yaw, float pitch, Target target, double seconds, Easing easing) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) throw RpcException.notInWorld();
        if (lookDone != null && !lookDone.isDone()) lookDone.completeExceptionally(new RpcException(RpcException.CANCELLED, "replaced by a new look"));
        engaged = true;
        lookFromYaw = p.getYRot();
        lookFromPitch = p.getXRot();
        lookToYaw = yaw;
        lookToPitch = pitch;
        lookTarget = target;
        lookTick = 0;
        lookTicks = Math.max(0, (int) Math.round(seconds * 20));
        lookEasing = easing;
        looking = true;
        lookDone = new CompletableFuture<>();
        return lookDone;
    }

    public void setFrozen(float fraction, double seconds) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) throw RpcException.notInWorld();
        frozenFrom = p.getPercentFrozen();
        frozenTo = fraction;
        frozenTick = 0;
        frozenTicks = Math.max(0, (int) Math.round(seconds * 20));
        frozenActive = true;
    }

    public float frozenTarget() {
        return frozenActive ? frozenTo : 0f;
    }

    /** Stops walking, looking and held input; leaves the freezing meter alone. */
    public void stop() {
        cancelWalk("stopped");
        if (lookDone != null && !lookDone.isDone()) lookDone.completeExceptionally(new RpcException(RpcException.CANCELLED, "stopped"));
        looking = false;
        lookTarget = null;
        clearInput();
        jumpTicks = 0;
        engaged = false;
    }

    /** stop() plus clearing the freezing meter. */
    public void reset() {
        stop();
        if (frozenActive) {
            frozenActive = false;
            LocalPlayer p = Minecraft.getInstance().player;
            if (p != null) p.setTicksFrozen(0);
        }
    }

    private void cancelWalk(String why) {
        if (walkDone != null && !walkDone.isDone()) walkDone.completeExceptionally(new RpcException(RpcException.CANCELLED, "walk " + why));
        path = List.of();
        pathIndex = 0;
    }

    private void setUse(boolean on) {
        use = on;
        Minecraft.getInstance().options.keyUse.setDown(on);
    }

    /** Start of a client tick: steering and input for this tick's movement. */
    public void tickStart(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || !engaged) return;
        if (use) mc.options.keyUse.setDown(true);
        if (walking()) {
            Vec3d target = path.get(pathIndex);
            double dx = target.x() - p.getX(), dz = target.z() - p.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist <= arriveRadius) {
                pathIndex++;
                bestDist = Double.MAX_VALUE;
                stuckTicks = 0;
                if (!walking()) {
                    JsonObject r = new JsonObject();
                    r.addProperty("arrived", true);
                    r.addProperty("x", p.getX());
                    r.addProperty("y", p.getY());
                    r.addProperty("z", p.getZ());
                    if (walkDone != null) walkDone.complete(r);
                }
            } else {
                if (dist < bestDist - 0.05) {
                    bestDist = dist;
                    stuckTicks = 0;
                } else if (++stuckTicks > 100) {
                    String msg = String.format(java.util.Locale.ROOT, "blocked for 5 s, %.1f blocks from point %d %s", dist, pathIndex, target);
                    cancelWalkWith(new RpcException(RpcException.STUCK, msg));
                }
            }
        }
    }

    private void cancelWalkWith(RpcException e) {
        if (walkDone != null && !walkDone.isDone()) walkDone.completeExceptionally(e);
        path = List.of();
        pathIndex = 0;
    }

    /** End of a client tick: rotations (so the renderer interpolates them) and the freezing meter. */
    public void tickEnd(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) return;
        if (frozenActive) {
            frozenTick = Math.min(frozenTick + 1, Math.max(1, frozenTicks));
            float f = frozenTicks == 0 ? frozenTo
                    : Mth.lerp((float) Easing.SINE_IN_OUT.apply(frozenTick / (double) frozenTicks), frozenFrom, frozenTo);
            p.setTicksFrozen(Math.round(f * p.getTicksRequiredToFreeze()));
        }
        if (!engaged) return;
        if (walking()) {
            Vec3d target = path.get(pathIndex);
            float want = (float) Math.toDegrees(Math.atan2(-(target.x() - p.getX()), target.z() - p.getZ()));
            float yaw = p.getYRot() + Mth.clamp(Mth.wrapDegrees(want - p.getYRot()), -TURN_PER_TICK, TURN_PER_TICK);
            setRotation(p, yaw, p.getXRot());
        }
        if (looking) {
            float toYaw = lookToYaw, toPitch = lookToPitch;
            if (lookTarget != null) {
                try {
                    Vec3d eye = new Vec3d(p.getX(), p.getEyeY(), p.getZ());
                    Vec3d t = McTargets.INSTANCE.position(lookTarget, 1f);
                    toYaw = (float) eye.yawTo(t);
                    toPitch = (float) eye.pitchTo(t);
                } catch (TargetResolver.TargetNotFoundException e) {
                    looking = false;
                    if (lookDone != null) lookDone.completeExceptionally(new RpcException(RpcException.NOT_FOUND, e.getMessage()));
                    return;
                }
            }
            toYaw = lookFromYaw + Mth.wrapDegrees(toYaw - lookFromYaw);
            lookTick++;
            double f = lookTicks == 0 ? 1 : lookEasing.apply(Math.min(1, lookTick / (double) lookTicks));
            setRotation(p, (float) (lookFromYaw + (toYaw - lookFromYaw) * f), (float) (lookFromPitch + (toPitch - lookFromPitch) * f));
            if (lookTick >= lookTicks && lookDone != null && !lookDone.isDone()) {
                JsonObject r = new JsonObject();
                r.addProperty("yaw", p.getYRot());
                r.addProperty("pitch", p.getXRot());
                lookDone.complete(r);
                // A fixed look ends here; a target keeps being tracked until stop or another look.
                if (lookTarget == null) looking = false;
            }
        }
    }

    private static void setRotation(LocalPlayer p, float yaw, float pitch) {
        p.setYRot(yaw);
        p.setXRot(Mth.clamp(pitch, -90f, 90f));
        p.setYHeadRot(yaw);
    }

    /** Called at the end of KeyboardInput.tick: replaces the keyboard's movement with the puppet's. */
    public void applyInput(Input in, boolean slow, float slowFactor) {
        if (!engaged) return;
        float f = forward, s = strafe;
        boolean sneaking = sneak;
        boolean sprinting = sprint;
        boolean jumping = jumpHeld || jumpTicks > 0;
        if (jumpTicks > 0) jumpTicks--;
        if (walking()) {
            LocalPlayer p = Minecraft.getInstance().player;
            f = 1f;
            s = 0f;
            sneaking = speed.equals("sneak");
            sprinting = speed.equals("sprint");
            if (p != null) {
                Vec3d target = path.get(pathIndex);
                float want = (float) Math.toDegrees(Math.atan2(-(target.x() - p.getX()), target.z() - p.getZ()));
                float off = Math.abs(Mth.wrapDegrees(want - p.getYRot()));
                // Slow down for sharp turns so corners don't overshoot.
                f = off > 60 ? 0.2f : off > 25 ? 0.6f : 1f;
                if (p.horizontalCollision && p.onGround()) jumping = true;
            }
        }
        in.forwardImpulse = f;
        in.leftImpulse = s;
        in.up = f > 0;
        in.down = f < 0;
        in.left = s > 0;
        in.right = s < 0;
        in.jumping = jumping;
        in.shiftKeyDown = sneaking;
        if (slow) {
            in.forwardImpulse *= slowFactor;
            in.leftImpulse *= slowFactor;
        }
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) {
            if (sprinting && f > 0.8f && !sneaking) p.setSprinting(true);
            else if (!sprinting && p.isSprinting()) p.setSprinting(false);
        }
    }

    public JsonObject status() {
        JsonObject o = new JsonObject();
        o.addProperty("engaged", engaged);
        o.addProperty("walking", walking());
        o.addProperty("pathIndex", pathIndex);
        o.addProperty("pathLength", path.size());
        o.addProperty("looking", looking);
        o.addProperty("forward", forward);
        o.addProperty("strafe", strafe);
        o.addProperty("sneak", sneak);
        o.addProperty("sprint", sprint);
        o.addProperty("use", use);
        o.addProperty("frozenTarget", frozenTarget());
        return o;
    }
}
