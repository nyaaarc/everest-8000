package dev.everest8000.expedition.client.mixin;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.Map;
import java.util.Queue;

/** For diagnostics: which particle layers the engine draws, and what it holds. */
@Mixin(ParticleEngine.class)
public interface ParticleEngineAccessor {
    @Accessor("RENDER_ORDER")
    static List<ParticleRenderType> everest$renderOrder() {
        throw new AssertionError();
    }

    @Accessor("particles")
    Map<ParticleRenderType, Queue<Particle>> everest$particles();
}
