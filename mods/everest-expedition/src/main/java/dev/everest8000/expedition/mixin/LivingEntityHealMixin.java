package dev.everest8000.expedition.mixin;

import dev.everest8000.expedition.exposure.Frostbite;
import dev.everest8000.expedition.oxygen.Oxygen;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No healing while frozen through or collapsed from hypoxia; otherwise regeneration would cancel the damage out. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityHealMixin {
    @Inject(method = "heal", at = @At("HEAD"), cancellable = true)
    private void everest$noHealWhileExposed(float amount, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayer player && (Frostbite.isFreezing(player) || Oxygen.isHypoxic(player))) ci.cancel();
    }
}
