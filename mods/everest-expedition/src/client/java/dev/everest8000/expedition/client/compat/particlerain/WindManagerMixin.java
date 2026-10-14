package dev.everest8000.expedition.client.compat.particlerain;

import dev.everest8000.expedition.client.ClientWeather;
import dev.everest8000.expedition.client.mixin.ParticleAccessor;
import dev.everest8000.expedition.weather.WeatherField;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pigcart.particlerain.WindManager;
import pigcart.particlerain.config.ParticleData;

/**
 * Particle Rain's snow and rain follow the mountain's wind instead of its own fixed south-easterly breeze: each
 * particle eases toward the wind velocity for its height, so snow streams sideways near the summit.
 */
@Mixin(value = WindManager.class, remap = false)
public abstract class WindManagerMixin {
    @Inject(method = "applyWind", at = @At("HEAD"), cancellable = true)
    private static void everest$applyWind(Particle particle, ParticleData data, ClientLevel level, CallbackInfo ci) {
        if (!ClientWeather.active()) return;
        ci.cancel();
        // Heavy particles (rain) lean into the wind more slowly than snow.
        everest$drift(particle, data, data.gravity != null && data.gravity > 0.5f ? 0.04f : 0.1f);
    }

    @Inject(method = "applySpawnWind", at = @At("HEAD"), cancellable = true)
    private static void everest$applySpawnWind(Particle particle, ParticleData data, ClientLevel level, CallbackInfo ci) {
        if (!ClientWeather.active()) return;
        ci.cancel();
        everest$drift(particle, data, 1f);
    }

    private static void everest$drift(Particle particle, ParticleData data, float couple) {
        // Fog, ripples and other still effects have no wind strength and stay put.
        if (data.windStrength == null || data.windStrength <= 0f) return;
        WeatherField field = ClientWeather.field();
        if (field == null) return;
        ParticleAccessor p = (ParticleAccessor) particle;
        float speed = ClientWeather.particleDriftAt(p.everest$y());
        p.everest$setXd(p.everest$xd() + (field.windX() * speed - p.everest$xd()) * couple);
        p.everest$setZd(p.everest$zd() + (field.windZ() * speed - p.everest$zd()) * couple);
    }
}
