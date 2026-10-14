package dev.everest8000.radio.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.everest8000.radio.EverestRadio;
import dev.everest8000.radio.client.RadioPose;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** First person: your radio comes up toward the bottom middle of the view while you talk into it. */
@Mixin(ItemInHandRenderer.class)
abstract class ItemInHandRendererMixin {
    @Inject(method = "renderArmWithItem", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"))
    private void everestRadio$raiseRadio(AbstractClientPlayer player, float partialTick, float pitch, InteractionHand hand,
                                         float swing, ItemStack stack, float equip, PoseStack pose, MultiBufferSource buffers,
                                         int light, CallbackInfo ci) {
        if (!EverestRadio.isRadio(stack)) {
            return;
        }
        HumanoidArm arm = hand == InteractionHand.MAIN_HAND ? player.getMainArm() : player.getMainArm().getOpposite();
        float k = arm == RadioPose.arm(player) ? RadioPose.amount(player.getId(), partialTick) : 0;
        if (k > 0) {
            RadioPose.raiseFirstPerson(pose, arm == HumanoidArm.RIGHT, k);
        }
    }
}
