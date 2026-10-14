package dev.everest8000.expedition.weather;

import net.minecraft.util.Mth;

/**
 * Where the cloud base sits, from the weather: about 5,500 m in fair weather (y 177, some 115 blocks over Base Camp,
 * with every big peak standing out of it), down at the top of a valley shower while one falls (3,600 to 4,500 m, so
 * from Base Camp you look down on it), and at the storm's base once a storm sets in (Base Camp sits under it, the
 * climbers above it are inside it). The client eases toward this height, so the deck drifts rather than jumps.
 */
public final class CloudDeck {
    public static final float FAIR_ALTITUDE_M = 5500f;
    public static final float FAIR_Y = WeatherField.yForAltitude(FAIR_ALTITUDE_M);
    /** Shower and storm strengths at which the deck has fully moved to them. */
    private static final float SHOWER_FULL = 0.2f;
    private static final float STORM_FULL = 0.25f;

    private CloudDeck() {}

    public static float baseY(WeatherField field) {
        float shower = WeatherField.smoothstep(0f, SHOWER_FULL, field.shower());
        float storm = WeatherField.smoothstep(0f, STORM_FULL, field.storm());
        float y = Mth.lerp(shower, FAIR_Y, field.showerTopY());
        return Mth.lerp(storm, y, field.stormBaseY());
    }
}
