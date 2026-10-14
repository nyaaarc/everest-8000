package dev.everest8000.expedition.client.compat.travelersbackpack;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.everest8000.expedition.client.compat.TravelersBackpackCompat;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips Traveler's Backpack's own on-back model for players, who get the expedition pack instead
 * (ExpeditionPackLayer). The same method draws the backpack for its Trinkets and Accessories slots, so those are
 * covered too. Other entities wearing a backpack keep the original model.
 */
@Mixin(targets = "com.tiviacz.travelersbackpack.client.renderer.BackpackLayer", remap = false)
public abstract class BackpackLayerMixin {
    @Inject(method = "renderBackpackLayer", at = @At("HEAD"), cancellable = true, remap = false)
    private static void everest$drawExpeditionPackInstead(HumanoidModel<?> model, PoseStack poseStack, MultiBufferSource buffers,
                                                         int light, LivingEntity entity, ItemStack stack, CallbackInfo ci) {
        if (entity instanceof Player && TravelersBackpackCompat.available()) ci.cancel();
    }
}
