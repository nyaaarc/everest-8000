package dev.everest8000.cinema.mixin;

import dev.everest8000.cinema.Cinema;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Frame hooks (start of frame; finished frame before it is shown) and the tick-rate compensation while recording. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void cinema$frameStart(boolean renderLevel, CallbackInfo ci) {
        Cinema.onFrameStart((Minecraft) (Object) this);
    }

    @Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;blitToScreen(II)V"))
    private void cinema$frameRendered(boolean renderLevel, CallbackInfo ci) {
        Cinema.onFrameRendered((Minecraft) (Object) this);
    }

    /** Keeps client ticks at 20 per recording-second even when the server's tick rate is lowered to match the render. */
    @Inject(method = "getTickTargetMillis", at = @At("HEAD"), cancellable = true)
    private void cinema$tickTarget(float defaultValue, CallbackInfoReturnable<Float> cir) {
        if (Cinema.enabled && Cinema.tickRateCompensation && Cinema.recorder.active()) cir.setReturnValue(defaultValue);
    }
}
