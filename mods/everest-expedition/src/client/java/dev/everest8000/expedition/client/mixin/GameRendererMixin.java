package dev.everest8000.expedition.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.everest8000.expedition.client.FrostVignette;
import dev.everest8000.expedition.client.HypoxiaVignette;
import dev.everest8000.expedition.client.Whiteout;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Draws the whiteout veil, the frostbite vignette and the hypoxia tunnel over the finished world image, just before
 * the HUD. Shader packs ignore the vanilla fog, so this is what makes a blizzard close in under shaders; it also runs
 * with the HUD hidden (F1).
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Gui;render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V"))
    private void everest$whiteoutVeil(Gui gui, GuiGraphics graphics, DeltaTracker deltaTracker, Operation<Void> original) {
        Whiteout.drawVeil(graphics);
        FrostVignette.draw(graphics);
        HypoxiaVignette.draw(graphics, deltaTracker);
        original.call(gui, graphics, deltaTracker);
    }
}
