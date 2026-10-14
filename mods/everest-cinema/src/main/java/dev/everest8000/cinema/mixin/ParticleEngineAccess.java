package dev.everest8000.cinema.mixin;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Map;
import java.util.Queue;

/** Read access to the live particles, for world.particles. */
@Mixin(ParticleEngine.class)
public interface ParticleEngineAccess {
    @Accessor("particles")
    Map<ParticleRenderType, Queue<Particle>> cinema$particles();

    @Invoker("clearParticles")
    void cinema$clearParticles();
}
