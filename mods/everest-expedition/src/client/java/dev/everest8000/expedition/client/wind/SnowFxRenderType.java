package dev.everest8000.expedition.client.wind;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.everest8000.expedition.EverestExpedition;
import dev.everest8000.expedition.client.Shaders;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureManager;

import java.io.IOException;

/**
 * Blown snow's own particle layer: translucent and without depth writes, so overlapping veils blend instead of
 * cutting holes in each other. Without a shader pack it also swaps in a copy of the particle shader that keeps faint
 * alpha (vanilla's drops everything under 0.1); with one, the pack's particle program lights it like everything else.
 */
public final class SnowFxRenderType {
    private static ShaderInstance shader;

    public static final ParticleRenderType INSTANCE = new ParticleRenderType() {
        @Override
        public BufferBuilder begin(Tesselator tesselator, TextureManager textureManager) {
            RenderSystem.depthMask(false);
            RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_PARTICLES);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            if (shader != null && !Shaders.active()) RenderSystem.setShader(() -> shader);
            return tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
        }

        @Override
        public String toString() {
            return "EVEREST_BLOWN_SNOW";
        }
    };

    private SnowFxRenderType() {}

    public static void registerShader(CoreShaderRegistrationCallback.RegistrationContext context) throws IOException {
        context.register(EverestExpedition.id("snow_particle"), DefaultVertexFormat.PARTICLE, s -> shader = s);
    }
}
