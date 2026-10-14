package dev.everest8000.expedition.client.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Drops vanilla's blue pixel-frost border; the frostbite vignette replaces it, powder snow included. */
@Mixin(Gui.class)
public abstract class GuiFrostMixin {
    @WrapWithCondition(method = "renderCameraOverlays", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Gui;renderTextureOverlay(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/resources/ResourceLocation;F)V"))
    private boolean everest$skipVanillaFrost(Gui gui, GuiGraphics graphics, ResourceLocation texture, float alpha) {
        return !texture.getPath().equals("textures/misc/powder_snow_outline.png");
    }
}
