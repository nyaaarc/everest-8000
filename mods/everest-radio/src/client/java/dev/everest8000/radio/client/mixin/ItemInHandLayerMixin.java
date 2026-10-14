package dev.everest8000.radio.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.everest8000.radio.EverestRadio;
import dev.everest8000.radio.client.RadioPose;
import dev.everest8000.radio.client.RadioPosedModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Third person: a radio being talked into moves from the hand to the corner of the mouth, anchored to the head like
 * vanilla's spyglass is to the eye, so it stays at the mouth wherever the player looks.
 */
@Mixin(ItemInHandLayer.class)
abstract class ItemInHandLayerMixin {
    @Shadow
    @Final
    private ItemInHandRenderer itemInHandRenderer;

    @Inject(method = "renderArmWithItem", at = @At("HEAD"), cancellable = true)
    private void everestRadio$radioAtMouth(LivingEntity entity, ItemStack stack, ItemDisplayContext context, HumanoidArm arm,
                                           PoseStack pose, MultiBufferSource buffers, int light, CallbackInfo ci) {
        if (!(entity instanceof Player) || !EverestRadio.isRadio(stack) || arm != RadioPose.arm(entity)
                || !(((RenderLayer<?, ?>) (Object) this).getParentModel() instanceof HumanoidModel<?> model)) {
            return;
        }
        float k = ((RadioPosedModel) model).everestRadio$amount(); // the amount the arm was just posed with
        if (k <= 0) {
            return;
        }
        pose.pushPose();
        RadioPose.placeRadio(pose, model, arm, k);
        itemInHandRenderer.renderItem(entity, stack, context, arm == HumanoidArm.LEFT, pose, buffers, light);
        pose.popPose();
        ci.cancel();
    }
}
