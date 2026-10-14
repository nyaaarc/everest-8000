package dev.everest8000.cinema.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.everest8000.cinema.capture.RenderSize;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** With an offscreen render size, the finished frame is shown scaled to the real window instead of cropped. */
@Mixin(RenderTarget.class)
public abstract class RenderTargetMixin {
    @ModifyVariable(method = "blitToScreen(II)V", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int cinema$screenWidth(int width) {
        return RenderSize.active() && (Object) this == Minecraft.getInstance().getMainRenderTarget() && RenderSize.realWidth() > 0
                ? RenderSize.realWidth() : width;
    }

    @ModifyVariable(method = "blitToScreen(II)V", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private int cinema$screenHeight(int height) {
        return RenderSize.active() && (Object) this == Minecraft.getInstance().getMainRenderTarget() && RenderSize.realHeight() > 0
                ? RenderSize.realHeight() : height;
    }
}
