package dev.everest8000.expedition.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.everest8000.expedition.client.wind.CameraBuffet;
import dev.everest8000.expedition.client.wind.PlumeRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The summit plume is drawn as soon as the world is finished (the depth buffer still holds the world, and Iris has
 * already composited its frame), before the hand. The wind buffet rides on the hurt-shake, which both the world and
 * the hand use. Neither is required: if another mod moves these calls the effects just don't show.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererWindMixin {
    @Inject(method = "renderLevel", require = 0, at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V"))
    private void everest$summitPlume(DeltaTracker deltaTracker, CallbackInfo ci) {
        PlumeRenderer.render(deltaTracker.getGameTimeDeltaPartialTick(true));
    }

    @Inject(method = "bobHurt", require = 0, at = @At("TAIL"))
    private void everest$windBuffet(PoseStack poseStack, float partialTick, CallbackInfo ci) {
        CameraBuffet.apply(poseStack, partialTick);
    }
}
