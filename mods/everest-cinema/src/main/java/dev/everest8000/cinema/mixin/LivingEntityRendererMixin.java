package dev.everest8000.cinema.mixin;

import dev.everest8000.cinema.Cinema;
import dev.everest8000.cinema.camera.Director;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** No name tag over the puppet when the cinema camera looks at it. */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
    @Inject(method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;)Z", at = @At("HEAD"), cancellable = true)
    private void cinema$noOwnName(LivingEntity entity, CallbackInfoReturnable<Boolean> cir) {
        if (!Cinema.enabled) return;
        if (dev.everest8000.cinema.actor.Actors.isActor(entity)
                || Cinema.director.mode() == Director.Mode.CINEMA && entity == Minecraft.getInstance().player) {
            cir.setReturnValue(false);
        }
    }
}
