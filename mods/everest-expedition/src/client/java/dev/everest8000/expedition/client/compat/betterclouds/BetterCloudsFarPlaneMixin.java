package dev.everest8000.expedition.client.compat.betterclouds;

import dev.everest8000.expedition.client.Shaders;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Better Clouds draws with the game's own projection, whose far plane is four times the render distance: 128 blocks at
 * the 2 chunks a Distant Horizons setup often runs at, so a cloud deck a few hundred blocks up, or below a high camp,
 * was cut away entirely. Without shaders the far plane is pushed out to {@link #MIN_FAR} blocks. Depth precision near
 * the camera barely changes (it depends on the near plane), and nothing past the render distance is loaded to draw.
 * Shader packs read the far plane for their own fog, so it is left alone under them.
 */
@Mixin(GameRenderer.class)
public abstract class BetterCloudsFarPlaneMixin {
    private static final float MIN_FAR = 2048f;

    @Inject(method = "getDepthFar", at = @At("RETURN"), cancellable = true)
    private void everest$farClouds(CallbackInfoReturnable<Float> cir) {
        if (cir.getReturnValueF() < MIN_FAR && !Shaders.active()) cir.setReturnValue(MIN_FAR);
    }
}
