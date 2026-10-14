package dev.everest8000.expedition.client.mixin;

import dev.everest8000.expedition.client.ClientSky;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The overworld's clouds sit at the weather's cloud base (see {@link ClientSky}) instead of vanilla's fixed y 192.
 * Vanilla and Sodium's cloud renderers, Better Clouds and Iris's {@code cloudHeight} uniform all ask this.
 */
@Mixin(DimensionSpecialEffects.class)
public abstract class DimensionSpecialEffectsMixin {
    @Inject(method = "getCloudHeight", at = @At("HEAD"), cancellable = true)
    private void everest$cloudDeck(CallbackInfoReturnable<Float> cir) {
        if ((Object) this instanceof DimensionSpecialEffects.OverworldEffects && ClientSky.active()) {
            cir.setReturnValue(ClientSky.cloudHeight());
        }
    }
}
