package dev.everest8000.cinema.mixin;

import dev.everest8000.cinema.Cinema;
import dev.everest8000.cinema.camera.Director;
import dev.everest8000.cinema.spec.Pose;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Places the camera at the cinema pose (with roll) and marks it detached, so the player model renders. */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow @Final private static Vector3f FORWARDS;
    @Shadow @Final private static Vector3f UP;
    @Shadow @Final private static Vector3f LEFT;
    @Shadow @Final private Vector3f forwards;
    @Shadow @Final private Vector3f up;
    @Shadow @Final private Vector3f left;
    @Shadow @Final private Quaternionf rotation;
    @Shadow private float xRot;
    @Shadow private float yRot;

    @Shadow protected abstract void setPosition(Vec3 pos);

    @ModifyVariable(method = "setup", at = @At("HEAD"), ordinal = 0, argsOnly = true)
    private boolean cinema$detached(boolean detached) {
        return Cinema.enabled && Cinema.director.mode() == Director.Mode.CINEMA || detached;
    }

    @Inject(method = "setup", at = @At("TAIL"))
    private void cinema$pose(BlockGetter level, Entity entity, boolean detached, boolean mirrored, float partialTick, CallbackInfo ci) {
        if (!Cinema.enabled || Cinema.director.mode() != Director.Mode.CINEMA) return;
        Pose p = Cinema.director.sample(partialTick);
        if (p == null || p.pos() == null) return;
        setPosition(new Vec3(p.pos().x(), p.pos().y(), p.pos().z()));
        xRot = (float) p.pitch();
        yRot = (float) p.yaw();
        rotation.rotationYXZ((float) Math.PI - yRot * ((float) Math.PI / 180f), -xRot * ((float) Math.PI / 180f),
                (float) Math.toRadians(p.roll()));
        FORWARDS.rotate(rotation, forwards);
        UP.rotate(rotation, up);
        LEFT.rotate(rotation, left);
        if (Cinema.ride) Cinema.ridePlayer(p);
    }
}
