package dev.everest8000.expedition.client.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.everest8000.expedition.client.Shaders;
import dev.everest8000.expedition.client.Whiteout;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Whiteout for the vanilla renderer. Distant Horizons pushes vanilla fog out to infinity every frame, so this runs
 * after it (higher priority) and sets absolute distances instead of scaling what's there. Shader packs draw their
 * own fog from the rain level, so this stands down while one is active.
 */
@Mixin(value = FogRenderer.class, priority = 1500)
public abstract class FogRendererMixin {
    @Shadow private static float fogRed;
    @Shadow private static float fogGreen;
    @Shadow private static float fogBlue;

    @Inject(method = "setupColor", at = @At("TAIL"))
    private static void everest$whiteoutColor(Camera camera, float partialTick, ClientLevel level, int renderDistance,
                                              float bossColorModifier, CallbackInfo ci) {
        float w = everest$strength(camera);
        if (w <= 0f) return;
        Whiteout.mixColor(fogRed, fogGreen, fogBlue, w);
        fogRed = Whiteout.red();
        fogGreen = Whiteout.green();
        fogBlue = Whiteout.blue();
        RenderSystem.clearColor(fogRed, fogGreen, fogBlue, 0f);
    }

    @Inject(method = "setupFog", at = @At("TAIL"))
    private static void everest$whiteoutDistance(Camera camera, FogRenderer.FogMode fogMode, float farPlaneDistance,
                                                 boolean thickFog, float partialTick, CallbackInfo ci) {
        float w = everest$strength(camera);
        if (w <= 0f) return;
        float visibility = Whiteout.visibility(farPlaneDistance);
        // The sky only whitens once the storm is thick enough to hide the edge of the loaded world.
        if (fogMode == FogRenderer.FogMode.FOG_SKY && visibility >= farPlaneDistance) return;
        RenderSystem.setShaderFogStart(0f);
        RenderSystem.setShaderFogEnd(visibility);
    }

    private static float everest$strength(Camera camera) {
        if (camera.getFluidInCamera() != FogType.NONE || Shaders.active()) return 0f;
        return Whiteout.strength();
    }
}
