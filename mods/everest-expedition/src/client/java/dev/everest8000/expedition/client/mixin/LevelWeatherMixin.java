package dev.everest8000.expedition.client.mixin;

import dev.everest8000.expedition.client.ClientWeather;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Local weather: the client's rain and thunder levels come from the mountain field at the camera's height instead
 * of the server's global weather. Vanilla sky and rain, Particle Rain and shader packs all read these two values.
 */
@Mixin(Level.class)
public abstract class LevelWeatherMixin {
    @Inject(method = "getRainLevel", at = @At("HEAD"), cancellable = true)
    private void everest$rainLevel(float partialTick, CallbackInfoReturnable<Float> cir) {
        if ((Object) this instanceof ClientLevel && ClientWeather.active()) cir.setReturnValue(ClientWeather.rainLevel());
    }

    @Inject(method = "getThunderLevel", at = @At("HEAD"), cancellable = true)
    private void everest$thunderLevel(float partialTick, CallbackInfoReturnable<Float> cir) {
        if ((Object) this instanceof ClientLevel && ClientWeather.active()) cir.setReturnValue(ClientWeather.thunderLevel());
    }
}
