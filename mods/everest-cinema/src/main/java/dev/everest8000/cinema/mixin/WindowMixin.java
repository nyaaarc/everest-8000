package dev.everest8000.cinema.mixin;

import com.mojang.blaze3d.platform.Window;
import dev.everest8000.cinema.capture.RenderSize;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While an offscreen render size is set, real window resizes don't change the game's framebuffer size. */
@Mixin(Window.class)
public abstract class WindowMixin {
    @Inject(method = "onFramebufferResize", at = @At("HEAD"), cancellable = true)
    private void cinema$keepRenderSize(long window, int width, int height, CallbackInfo ci) {
        if (!RenderSize.active()) return;
        if (width > 0 && height > 0) RenderSize.onRealResize(width, height);
        ci.cancel();
    }
}
