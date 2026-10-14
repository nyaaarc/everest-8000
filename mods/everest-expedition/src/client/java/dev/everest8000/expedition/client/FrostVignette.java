package dev.everest8000.expedition.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.everest8000.expedition.EverestExpedition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Frostbite closing in on your vision. The frost texture is drawn oversized so only its edges show when the
 * chill starts, then shrinks to screen size as the player freezes, until only a small clear spot is left in the
 * middle. Follows the same frozen meter as vanilla's frost overlay, so it covers powder snow too. Lit by the time of
 * day like the whiteout veil, so at night it's dim blue rather than the brightest thing on screen.
 */
public final class FrostVignette {
    private static final ResourceLocation TEXTURE = EverestExpedition.id("textures/misc/frostbite.png");
    /**
     * Size of the frost layer relative to the screen when the chill first sets in, and when frozen through. Never
     * below 1, or the layer's own edge shows as a frame; how much stays clear is set by the texture.
     */
    private static final float SCALE_START = 2.4f;
    private static final float SCALE_FROZEN = 1.0f;

    private FrostVignette() {}

    public static void draw(GuiGraphics graphics) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        float frozen = player.getPercentFrozen();
        if (frozen <= 0.02f) return;
        float alpha = Mth.clamp(frozen * 1.6f, 0f, 1f);
        float scale = Mth.lerp((float) Math.pow(frozen, 0.8), SCALE_START, SCALE_FROZEN);
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        int quadWidth = Math.round(width * scale);
        int quadHeight = Math.round(height * scale);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        float light = 0.2f + 0.8f * Whiteout.light();
        graphics.setColor(light, light, Math.min(1f, light + 0.1f * (1f - light)), alpha);
        graphics.blit(TEXTURE, (width - quadWidth) / 2, (height - quadHeight) / 2, -90, 0f, 0f,
                quadWidth, quadHeight, quadWidth, quadHeight);
        graphics.setColor(1f, 1f, 1f, 1f);
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
    }
}
