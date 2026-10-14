package dev.everest8000.cinema.mixin;

import dev.everest8000.cinema.Cinema;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The cinema FOV (vertical degrees), replacing the option and the sprint/zoom effects. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void cinema$fov(Camera camera, float partialTick, boolean useFovSetting, CallbackInfoReturnable<Double> cir) {
        if (!Cinema.enabled || !useFovSetting) return;
        double f = Cinema.director.fovOverride();
        if (!Double.isNaN(f)) cir.setReturnValue(f);
    }
}
