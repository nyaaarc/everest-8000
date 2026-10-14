package dev.everest8000.expedition.client.mixin;

import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Particle.class)
public interface ParticleAccessor {
    @Accessor("y")
    double everest$y();

    @Accessor("xd")
    double everest$xd();

    @Accessor("xd")
    void everest$setXd(double xd);

    @Accessor("zd")
    double everest$zd();

    @Accessor("zd")
    void everest$setZd(double zd);
}
