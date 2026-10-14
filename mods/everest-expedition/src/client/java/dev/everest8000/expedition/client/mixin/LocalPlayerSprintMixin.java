package dev.everest8000.expedition.client.mixin;

import dev.everest8000.expedition.client.ClientOxygen;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Collapsed from hypoxia: no sprinting. Sprint is decided here on the client, so the server can't stop it alone. */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerSprintMixin {
    // Checked both to start a sprint and to keep one going, like running out of food.
    @Inject(method = "hasEnoughFoodToStartSprinting", at = @At("HEAD"), cancellable = true)
    private void everest$noSprintWhileCollapsed(CallbackInfoReturnable<Boolean> cir) {
        if (ClientOxygen.collapsed()) cir.setReturnValue(false);
    }
}
