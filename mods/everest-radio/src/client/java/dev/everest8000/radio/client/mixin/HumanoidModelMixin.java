package dev.everest8000.radio.client.mixin;

import dev.everest8000.radio.client.RadioPose;
import dev.everest8000.radio.client.RadioPosedModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A player talking into their radio holds it up to their mouth: after vanilla has posed the model, the radio arm is
 * blended toward the walkie-talkie pose. The player model copies it to the sleeves next, and armour and the down suit
 * copy the arms from the player model, so they follow.
 */
@Mixin(HumanoidModel.class)
abstract class HumanoidModelMixin implements RadioPosedModel {
    @Shadow
    @Final
    public ModelPart head;
    @Shadow
    @Final
    public ModelPart rightArm;
    @Shadow
    @Final
    public ModelPart leftArm;
    @Shadow
    public float swimAmount;
    @Unique
    private float everestRadio$amount;

    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void everestRadio$raiseRadio(LivingEntity entity, float limbSwing, float limbSwingAmount, float ageInTicks,
                                         float netHeadYaw, float headPitch, CallbackInfo ci) {
        everestRadio$amount = 0;
        if (!(entity instanceof Player) || entity.isFallFlying() || entity.getPose() == Pose.SLEEPING) {
            return;
        }
        float k = RadioPose.amount(entity.getId(), ageInTicks - entity.tickCount) * (1 - swimAmount);
        if (k <= 0) {
            return;
        }
        everestRadio$amount = k;
        boolean right = RadioPose.arm(entity) == HumanoidArm.RIGHT;
        RadioPose.poseArm(right ? rightArm : leftArm, head, right, k);
    }

    @Override
    public float everestRadio$amount() {
        return everestRadio$amount;
    }
}
