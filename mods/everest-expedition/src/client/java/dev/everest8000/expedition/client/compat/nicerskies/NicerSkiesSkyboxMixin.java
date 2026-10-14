package dev.everest8000.expedition.client.compat.nicerskies;

import dev.everest8000.expedition.client.Shaders;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Nicer Skies paints its nebula into the vanilla sky, which Iris still draws under a shader pack; with Complementary's
 * own night nebulae on, the two would sit on top of each other. The nebula is still made on joining (so it's there the
 * moment shaders go off) but only drawn without them.
 */
@Mixin(targets = "dev.ztereohype.nicerskies.sky.nebula.Skybox", remap = false)
public abstract class NicerSkiesSkyboxMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void everest$vanillaOnly(CallbackInfo ci) {
        if (Shaders.active()) ci.cancel();
    }
}
