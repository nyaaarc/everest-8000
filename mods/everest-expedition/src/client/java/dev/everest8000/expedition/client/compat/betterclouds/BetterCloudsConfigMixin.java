package dev.everest8000.expedition.client.compat.betterclouds;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Better Clouds spreads its clouds over a multiple of the vanilla render distance, which with Distant Horizons doing
 * the far terrain is often 2 to 8 chunks: a few hundred blocks of cloud in a view that reaches 16 km. Its cloud field
 * is kept at least {@link #MIN_BLOCKS} wide (each way) whatever the render distance, so the deck reaches out over
 * the valleys; a larger setting still wins.
 */
@Mixin(targets = "com.qendolin.betterclouds.config.Config", remap = false)
public abstract class BetterCloudsConfigMixin {
    private static final int MIN_BLOCKS = 1024;

    @Inject(method = "blockDistance", at = @At("RETURN"), cancellable = true)
    private void everest$wideDeck(CallbackInfoReturnable<Integer> cir) {
        if (cir.getReturnValueI() < MIN_BLOCKS) cir.setReturnValue(MIN_BLOCKS);
    }
}
