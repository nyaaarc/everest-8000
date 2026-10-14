package dev.everest8000.expedition.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;

/**
 * How far you can see in the storm, shared by the vanilla fog, the Distant Horizons fog and the veil so they all
 * fade together. Visibility runs in log space from the clear-weather view distance (the LOD range when Distant
 * Horizons is on, about 16 km) down to {@link #MIN_VISIBILITY} blocks, so a light storm is haze and a full one is a
 * wall of white. The colour is a pale grey lit by the time of day, so a whiteout is bright at noon and dim at night.
 */
public final class Whiteout {
    public static final float MIN_VISIBILITY = 10f;
    /** Veil opacity at full whiteout: strong under shaders (their fog is mild), light on top of vanilla fog. */
    private static final float VEIL_SHADERS = 0.62f;
    private static final float VEIL_VANILLA = 0.22f;

    /** Far-terrain range in blocks, reported by the Distant Horizons fog hook each frame; 0 without it. */
    static volatile float lodRange;
    private static float red = 0.84f;
    private static float green = 0.87f;
    private static float blue = 0.92f;

    private Whiteout() {}

    public static float strength() {
        return ClientWeather.whiteout();
    }

    public static void setLodRange(float blocks) {
        lodRange = blocks;
    }

    /** Distance at which the fog is total. {@code fallback} is the vanilla view distance when there are no LODs. */
    public static float visibility(float fallback) {
        float clear = Math.max(lodRange, fallback);
        float w = strength();
        return (float) Math.exp(Mth.lerp(w, Math.log(clear), Math.log(MIN_VISIBILITY)));
    }

    /** Vanilla's sky brightness for the time of day, without the rain and thunder dimming. */
    public static float clearSkyBrightness(ClientLevel level, float partialTick) {
        float g = 1f - (Mth.cos(level.getTimeOfDay(partialTick) * Mth.TWO_PI) * 2f + 0.2f);
        return (1f - Mth.clamp(g, 0f, 1f)) * 0.8f + 0.2f;
    }

    /** Blends the frame's fog colour toward the whiteout grey. */
    public static void mixColor(float r, float g, float b, float w) {
        float light = light();
        red = Mth.lerp(w, r, 0.84f * light);
        green = Mth.lerp(w, g, 0.87f * light);
        blue = Mth.lerp(w, b, 0.92f * light);
    }

    /** Daylight on the scene, 0.15 at night to 1 at noon: dims the veil and the frost so neither glows in the dark. */
    static float light() {
        ClientLevel level = Minecraft.getInstance().level;
        return level == null ? 1f : 0.15f + 0.85f * (clearSkyBrightness(level, 1f) - 0.2f) / 0.8f;
    }

    /** A flat pale layer over the whole world image; distance fog alone can't hide things under shaders. */
    public static void drawVeil(GuiGraphics graphics) {
        float w = strength();
        if (w <= 0f) return;
        float alpha = Shaders.active() ? VEIL_SHADERS * (float) Math.pow(w, 1.3) : VEIL_VANILLA * w;
        float light = light();
        int color = FastColor.ARGB32.color(Mth.floor(alpha * 255f), Mth.floor(0.84f * light * 255f),
                Mth.floor(0.87f * light * 255f), Mth.floor(0.92f * light * 255f));
        graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), color);
    }

    public static float red() {
        return red;
    }

    public static float green() {
        return green;
    }

    public static float blue() {
        return blue;
    }
}
