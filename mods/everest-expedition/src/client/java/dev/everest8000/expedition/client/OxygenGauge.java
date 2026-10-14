package dev.everest8000.expedition.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.everest8000.expedition.EverestExpedition;
import net.minecraft.Util;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;

/**
 * The oxygen gauge, in the spot above the hunger bar where vanilla's air bubbles go: the blood-oxygen bar, a mask, and
 * the two cylinder slots. The cylinder being breathed from is drawn on the left, next to the mask; when it runs dry the
 * other one moves over. No text. It fades in above the oxygen line, while cylinders refill at a camp, or while blood
 * oxygen is still recovering.
 */
public final class OxygenGauge {
    private static final ResourceLocation CYLINDER_FRAME = sprite("cylinder_frame");
    private static final ResourceLocation CYLINDER_FILL = sprite("cylinder_fill");
    private static final ResourceLocation CYLINDER_MISSING = sprite("cylinder_missing");
    private static final ResourceLocation MASK = sprite("mask");
    private static final ResourceLocation SPO2_BACKGROUND = sprite("spo2_background");
    private static final ResourceLocation SPO2_FILL = sprite("spo2_fill");
    private static final ResourceLocation SPO2_FILL_LOW = sprite("spo2_fill_low");
    private static final int CYLINDER_W = 7;
    private static final int CYLINDER_H = 14;
    private static final int FILL_W = 5;
    private static final int FILL_H = 11;
    private static final int BAR_W = 56;
    private static final int BAR_H = 5;
    private static final int BAR_FILL_W = 54;
    private static final int MASK_SIZE = 7;
    /** Left edges of the two cylinder slots are this far apart. */
    private static final int CYLINDER_STEP = 9;
    /** Below this the blood-oxygen bar turns red. */
    private static final float LOW_SPO2 = 0.3f;

    private OxygenGauge() {}

    public static void draw(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.options.hideGui || mc.gameMode == null || !mc.gameMode.canHurtPlayer()) return;
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
        float alpha = ClientOxygen.gaugeAlpha(partialTick);
        if (alpha <= 0.01f) return;

        // Right-aligned to the hotbar, over the hunger bar: 81 px wide, like the row of air bubbles.
        int right = graphics.guiWidth() / 2 + 91;
        int cylinderTop = graphics.guiHeight() - 39 - 16 - rowsTakenAbove(player) * 10;
        int leftCylinderX = right - CYLINDER_STEP - CYLINDER_W;
        int maskX = leftCylinderX - MASK_SIZE - 1;
        int barX = maskX - 1 - BAR_W;
        int barY = cylinderTop + 9;
        float time = Util.getMillis() / 1000f;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        float spo2 = ClientOxygen.spo2(partialTick);
        // At empty the bar's frame pulses, to say why everything is going dark.
        float barPulse = spo2 <= 0.005f ? 0.65f + 0.35f * Mth.sin(time * 6f) : 1f;
        graphics.setColor(1f, 1f, 1f, alpha * barPulse);
        graphics.blitSprite(SPO2_BACKGROUND, barX, barY, BAR_W, BAR_H);
        graphics.setColor(1f, 1f, 1f, alpha);
        int barFill = Math.round(BAR_FILL_W * Mth.clamp(spo2, 0f, 1f));
        if (barFill > 0) {
            graphics.blitSprite(spo2 < LOW_SPO2 ? SPO2_FILL_LOW : SPO2_FILL, BAR_FILL_W, 3, 0, 0, barX + 1, barY + 1, barFill, 3);
        }

        int inUse = ClientOxygen.inUse();
        boolean refilling = ClientOxygen.refilling();
        for (int slot = 0; slot < 2; slot++) {
            // The one in use goes in the left slot, beside the mask.
            int i = inUse == 1 ? 1 - slot : slot;
            int x = leftCylinderX + slot * CYLINDER_STEP;
            float fill = ClientOxygen.fill(i, partialTick);
            if (fill < 0f) {
                graphics.blitSprite(CYLINDER_MISSING, x, cylinderTop, CYLINDER_W, CYLINDER_H);
                continue;
            }
            graphics.blitSprite(CYLINDER_FRAME, x, cylinderTop, CYLINDER_W, CYLINDER_H);
            int height = Math.round(FILL_H * Mth.clamp(fill, 0f, 1f));
            if (height > 0) {
                // A gentle shimmer while gas is going in.
                float shimmer = refilling && fill < 1f ? 0.8f + 0.2f * Mth.sin(time * 5f + i) : 1f;
                graphics.setColor(shimmer, shimmer, shimmer, alpha);
                graphics.blitSprite(CYLINDER_FILL, FILL_W, FILL_H, 0, FILL_H - height,
                        x + 1, cylinderTop + 2 + FILL_H - height, FILL_W, height);
                graphics.setColor(1f, 1f, 1f, alpha);
            }
        }
        if (inUse >= 0) {
            // Fading softly in time with breathing.
            float breath = 0.75f + 0.25f * Mth.sin(time * 1.6f);
            graphics.setColor(1f, 1f, 1f, alpha * breath);
            graphics.blitSprite(MASK, maskX, cylinderTop + 7, MASK_SIZE, MASK_SIZE);
        }
        graphics.setColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
    }

    /** Rows of vanilla's own right-hand status above the hunger bar right now: air bubbles, extra mount-heart rows. */
    private static int rowsTakenAbove(LocalPlayer player) {
        int rows = 0;
        if (player.getVehicle() instanceof LivingEntity mount && mount.showVehicleHealth()) {
            int hearts = Math.min(30, (int) (mount.getMaxHealth() + 0.5f) / 2);
            rows += Math.max(0, (int) Math.ceil(hearts / 10.0) - 1);
        }
        if (player.isEyeInFluid(FluidTags.WATER) || player.getAirSupply() < player.getMaxAirSupply()) rows++;
        return rows;
    }

    private static ResourceLocation sprite(String name) {
        return EverestExpedition.id("hud/oxygen/" + name);
    }
}
