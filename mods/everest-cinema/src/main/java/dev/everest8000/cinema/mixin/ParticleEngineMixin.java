package dev.everest8000.cinema.mixin;

import dev.everest8000.cinema.Cinema;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Drops new particles whose class is on the shot's block list (Cinema.particleBlock). */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineMixin {
    @Inject(method = "add(Lnet/minecraft/client/particle/Particle;)V", at = @At("HEAD"), cancellable = true)
    private void cinema$blockParticle(Particle particle, CallbackInfo ci) {
        if (Cinema.enabled && Cinema.particleBlocked(particle)) ci.cancel();
    }
}
