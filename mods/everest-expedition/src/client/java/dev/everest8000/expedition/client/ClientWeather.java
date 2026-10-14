package dev.everest8000.expedition.client;

import dev.everest8000.expedition.client.wind.Gusts;
import dev.everest8000.expedition.exposure.Exposure;
import dev.everest8000.expedition.weather.WeatherField;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * The weather as this client sees it. The server's field is eased in over a few seconds, then evaluated at the
 * camera's height once per tick; the rain/thunder mixin, Particle Rain's wind, the push, spindrift, sound and fog
 * all read from here.
 */
public final class ClientWeather {
    private static WeatherField target;
    private static WeatherField shown;
    private static boolean active;

    private static float rain;
    private static float storm;
    private static float shower;
    private static float blizzard;
    private static float gust = 1f;
    private static float windShare = 1f;
    private static boolean underCover;
    private static double cameraX;
    private static double cameraY;
    private static double cameraZ;
    private static long gameTime;

    private ClientWeather() {}

    static void receive(WeatherField field) {
        if (!active) shown = field;
        target = field;
        active = true;
    }

    static void reset() {
        active = false;
        target = shown = null;
        rain = storm = shower = blizzard = 0f;
    }

    /** True once the server has sent weather and the player is in the overworld. */
    public static boolean active() {
        if (!active) return false;
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.level.dimension() == Level.OVERWORLD;
    }

    static void tick(Minecraft mc) {
        if (!active() || shown == null) return;
        shown = shown.lerp(target, 0.03f);
        Entity camera = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;
        if (camera == null) return;
        cameraX = camera.getX();
        cameraY = camera.getY();
        cameraZ = camera.getZ();
        rain = shown.precipitationAt(cameraY);
        storm = shown.stormAt(cameraY);
        shower = shown.showerAt(cameraY);
        blizzard = shown.blizzardAt(cameraY);
        gameTime = mc.level.getGameTime();
        Gusts.tick(shown.windX(), shown.windZ(), shown.windKmhAt(cameraY), storm);
        gust = Gusts.at(cameraX, cameraZ, gameTime, 1f);
        underCover = Exposure.underCover(mc.level, camera.getX(), camera.getEyeY(), camera.getZ());
        windShare = Exposure.windShare(mc.level, shown, camera.getX(), camera.getY(), camera.getZ(), camera.getEyeY());
    }

    public static WeatherField field() {
        return shown;
    }

    public static float rainLevel() {
        return rain;
    }

    public static float thunderLevel() {
        return blizzard;
    }

    /** Wind at the camera, gusts included, before shelter. */
    public static float windKmh() {
        return shown == null ? 0f : shown.windKmhAt(cameraY) * gust;
    }

    public static float windKmhAt(double y) {
        return shown == null ? 0f : shown.windKmhAt(y) * gust;
    }

    /** Wind at a point, with the gust passing over that point (not the one at the camera), before shelter. */
    public static float windKmhAt(double x, double y, double z) {
        return shown == null ? 0f : shown.windKmhAt(y) * Gusts.at(x, z, gameTime, 1f);
    }

    /** The gust multiplier at the camera this tick, about 0.6 to 1.6. */
    public static float gust() {
        return gust;
    }

    /** Storm strength at the camera's height, 0..1. */
    public static float stormLevel() {
        return storm;
    }

    public static float windShare() {
        return windShare;
    }

    public static boolean underCover() {
        return underCover;
    }

    /** How far a whiteout has closed in, 0..1: any real storm starts to haze, a full one is a wall. */
    public static float whiteout() {
        if (!active()) return 0f;
        float t = Mth.clamp((storm - 0.2f) / 0.75f, 0f, 1f);
        float w = Math.max(t * t * (3f - 2f * t), 0.3f * shower);
        return underCover ? w * 0.5f : w;
    }

    /** Horizontal velocity, in blocks per tick, that falling snow should drift at for height y. */
    public static float particleDriftAt(double y) {
        return Math.min(0.3f, 0.1f * windKmhAt(y) / 100f);
    }
}
