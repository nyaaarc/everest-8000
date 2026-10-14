package dev.everest8000.expedition.mixin;

import dev.everest8000.expedition.oxygen.Oxygen;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The oxygen carry limit, applied before pickup so a dropped empty cylinder doesn't bounce back (see Oxygen.refusesPickup). */
@Mixin(ItemEntity.class)
public abstract class ItemEntityPickupMixin {
    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void everest$oxygenCarryLimit(Player player, CallbackInfo ci) {
        ItemEntity self = (ItemEntity) (Object) this;
        if (!self.level().isClientSide && Oxygen.refusesPickup(player, self.getItem())) ci.cancel();
    }
}
