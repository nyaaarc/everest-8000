package dev.everest8000.expedition.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.everest8000.expedition.EverestExpedition;
import net.minecraft.Util;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;

/**
 * Hypoxia closing in on your vision: as blood oxygen falls, a dark tunnel shrinks in from the edges (drawn oversized
 * at first, like the frost vignette) and the colour washes out. Near empty a slow heartbeat throbs through it, and
 * once collapsed everything goes nearly black. Drawn after the frost, since this is your brain rather than your
 * goggles.
 */
public final class HypoxiaVignette {
    private static final ResourceLocation TEXTURE = EverestExpedition.id("textures/misc/hypoxia.png");
    /** Blood oxygen where the tunnel first shows. */
    private static final float ONSET_SPO2 = 0.9f;
    private static final float SCALE_START = 1.6f;
    private static final float SCALE_EMPTY = 1.0f;
    /** A dull grey wash over everything, standing in for desaturation. */
    private static final float WASH_ALPHA = 0.28f;
    /** How dark the whole screen gets once collapsed, at the start and by the end of the collapse. */
    private static final float BLACKOUT_START = 0.55f;
    private static final float BLACKOUT_FULL = 0.93f;
    /** The heartbeat: below this blood oxygen, one lub-dub this often. */
    private static final float HEARTBEAT_SPO2 = 0.25f;
    private static final float HEARTBEAT_SECONDS = 1.5f;

    private HypoxiaVignette() {}

    public static void draw(GuiGraphics graphics, DeltaTracker deltaTracker) {
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
        float spo2 = ClientOxygen.spo2(partialTick);
        float collapse = ClientOxygen.collapse(partialTick);
        float t = Mth.clamp((ONSET_SPO2 - spo2) / ONSET_SPO2, 0f, 1f);
        if (t <= 0.005f && collapse <= 0.005f) return;
        t = t * t * (3f - 2f * t);

        float beat = 0f;
        if (spo2 < HEARTBEAT_SPO2 || collapse > 0f) {
            float phase = (Util.getMillis() / 1000f) % HEARTBEAT_SECONDS;
            beat = (float) (Math.exp(-phase / 0.07) + (phase > 0.3f ? 0.6 * Math.exp(-(phase - 0.3f) / 0.07) : 0.0));
            beat *= Math.max(Mth.clamp((HEARTBEAT_SPO2 - spo2) / HEARTBEAT_SPO2, 0f, 1f), collapse > 0f ? 1f : 0f);
        }

        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        float wash = WASH_ALPHA * t;
        if (wash > 0f) graphics.fill(0, 0, width, height, FastColor.ARGB32.color(Mth.floor(wash * 255f), 70, 72, 78));

        // After the fill, which switches blending off again once drawn; without it the soft edge turns hard.
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);

        float scale = Mth.lerp((float) Math.pow(t, 0.8), SCALE_START, SCALE_EMPTY) * (1f - 0.05f * beat);
        int quadWidth = Math.round(width * scale);
        int quadHeight = Math.round(height * scale);
        graphics.setColor(1f, 1f, 1f, Mth.clamp(t * 1.4f + 0.2f * beat, 0f, 1f));
        graphics.blit(TEXTURE, (width - quadWidth) / 2, (height - quadHeight) / 2, -90, 0f, 0f,
                quadWidth, quadHeight, quadWidth, quadHeight);
        graphics.setColor(1f, 1f, 1f, 1f);

        if (collapse > 0f) {
            float blackout = Mth.lerp(Math.min(1f, collapse * 1.5f), BLACKOUT_START, BLACKOUT_FULL);
            blackout = Mth.clamp(blackout * Math.min(1f, collapse * 8f) + 0.05f * beat, 0f, 0.97f);
            graphics.fill(0, 0, width, height, FastColor.ARGB32.color(Mth.floor(blackout * 255f), 0, 0, 0));
        }

        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
    }
}
