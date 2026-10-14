package dev.everest8000.cinema.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.everest8000.cinema.Cinema;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hides the local player (model, shadow, shader shadow pass) when the puppet is set invisible. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Inject(method = "renderEntity", at = @At("HEAD"), cancellable = true)
    private void cinema$hidePlayer(Entity entity, double camX, double camY, double camZ, float partialTick, PoseStack poseStack,
                                   MultiBufferSource buffers, CallbackInfo ci) {
        if (Cinema.enabled && Cinema.playerHidden && entity == Minecraft.getInstance().player) ci.cancel();
    }
}
