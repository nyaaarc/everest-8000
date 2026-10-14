package dev.everest8000.cinema.mixin;

import dev.everest8000.cinema.Cinema;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Chat and crosshair can be hidden on their own while the rest of the HUD shows. */
@Mixin(Gui.class)
public abstract class GuiMixin {
    @Inject(method = "renderChat", at = @At("HEAD"), cancellable = true)
    private void cinema$chat(GuiGraphics graphics, DeltaTracker delta, CallbackInfo ci) {
        if (Cinema.enabled && Cinema.chatHidden) ci.cancel();
    }

    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void cinema$crosshair(GuiGraphics graphics, DeltaTracker delta, CallbackInfo ci) {
        if (Cinema.enabled && Cinema.crosshairHidden) ci.cancel();
    }
}
