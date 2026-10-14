package dev.everest8000.cinema.mixin;

import dev.everest8000.cinema.Cinema;
import dev.everest8000.cinema.actor.Actors;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Director arm poses (raise a radio to the mouth, a spyglass to the eye...) for actors and the puppeted player. */
@Mixin(PlayerRenderer.class)
public abstract class PlayerRendererMixin {
    @Inject(method = "getArmPose", at = @At("HEAD"), cancellable = true)
    private static void cinema$armPose(AbstractClientPlayer player, InteractionHand hand, CallbackInfoReturnable<HumanoidModel.ArmPose> cir) {
        if (!Cinema.enabled) return;
        HumanoidModel.ArmPose pose = Actors.armPose(player, hand);
        if (pose != null) cir.setReturnValue(pose);
    }
}
