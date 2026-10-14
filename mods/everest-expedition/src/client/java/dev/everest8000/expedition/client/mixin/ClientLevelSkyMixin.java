package dev.everest8000.expedition.client.mixin;

import dev.everest8000.expedition.client.Whiteout;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla darkens the world heavily in rain plus thunder, which turned blizzards a gloomy grey. A whiteout is
 * diffuse but bright, so most of that darkening is taken back while one is on.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelSkyMixin {
    @Inject(method = "getSkyDarken", at = @At("RETURN"), cancellable = true)
    private void everest$brightWhiteout(float partialTick, CallbackInfoReturnable<Float> cir) {
        float w = Whiteout.strength();
        if (w <= 0f) return;
        float clear = Whiteout.clearSkyBrightness((ClientLevel) (Object) this, partialTick);
        cir.setReturnValue(cir.getReturnValueF() + (clear - cir.getReturnValueF()) * 0.75f * w);
    }
}
