package dev.everest8000.expedition.weather;

import net.minecraft.util.Mth;

/**
 * The mountain's weather at one moment, as the server sends it to clients. Everything a player feels is derived
 * from these numbers plus their own height, so the valley can be clear while the summit is in a blizzard.
 *
 * @param windAngle   direction the wind blows toward, radians in the x/z plane (0 = east, -pi/2 = north)
 * @param jetKmh      jet-stream wind speed at the summit
 * @param storm       upper-mountain storm strength, 0..1
 * @param stormBaseY  height the storm reaches down to
 * @param shower      valley shower strength, 0..1
 * @param showerTopY  height the valley showers reach up to
 */
public record WeatherField(float windAngle, float jetKmh, float storm, float stormBaseY, float shower, float showerTopY) {
    public static final WeatherField CALM = new WeatherField(WeatherModel.PREVAILING_ANGLE, 40f, 0f, 400f, 0f, -600f);

    public static final float SUMMIT_Y = 2031f;
    /** Below this the jet stream no longer reaches the ground (about 4,240 m). */
    public static final float JET_FLOOR_Y = -400f;
    /** Above this precipitation always falls as snow, whatever the map's biome says (about 4,700 m). */
    public static final float SNOWLINE_Y = -250f;
    private static final float EDGE = 80f;

    public float stormAt(double y) {
        return storm * smoothstep(stormBaseY - EDGE, stormBaseY + EDGE, (float) y);
    }

    public float showerAt(double y) {
        return shower * (1f - smoothstep(showerTopY - EDGE, showerTopY + EDGE, (float) y));
    }

    public float precipitationAt(double y) {
        return Math.max(stormAt(y), showerAt(y));
    }

    /** Thunder level for the client: from a moderate storm up, so particle mods switch to their blizzard mode. */
    public float blizzardAt(double y) {
        return Mth.clamp((stormAt(y) - 0.3f) / 0.55f, 0f, 1f);
    }

    public float windKmhAt(double y) {
        float lift = Mth.clamp(((float) y - JET_FLOOR_Y) / (SUMMIT_Y - JET_FLOOR_Y), 0f, 1f);
        return 8f + jetKmh * (float) Math.pow(lift, 1.6) + 70f * stormAt(y) + 15f * showerAt(y);
    }

    public float windX() {
        return Mth.cos(windAngle);
    }

    public float windZ() {
        return Mth.sin(windAngle);
    }

    public WeatherField lerp(WeatherField to, float t) {
        return new WeatherField(
                windAngle + Mth.wrapDegrees((to.windAngle - windAngle) * Mth.RAD_TO_DEG) * Mth.DEG_TO_RAD * t,
                Mth.lerp(t, jetKmh, to.jetKmh),
                Mth.lerp(t, storm, to.storm),
                Mth.lerp(t, stormBaseY, to.stormBaseY),
                Mth.lerp(t, shower, to.shower),
                Mth.lerp(t, showerTopY, to.showerTopY));
    }

    /**
     * Real-world altitude for a map height, from the map's own georeferencing: the Lukla runway (y -1285) is 2,860 m
     * and the summit (y 2031) 8,849 m, so 1.806 m a block. The stops in between sit within about 100 m of their real
     * heights (Base Camp, y 63, reads 5,294 m against 5,364 m).
     */
    public static float altitudeMeters(double y) {
        return 2860f + ((float) y + 1285f) * 1.806f;
    }

    /** The map height for a real-world altitude; the inverse of {@link #altitudeMeters}. */
    public static float yForAltitude(float meters) {
        return (meters - 2860f) / 1.806f - 1285f;
    }

    static float smoothstep(float edge0, float edge1, float x) {
        float t = Mth.clamp((x - edge0) / (edge1 - edge0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }
}
