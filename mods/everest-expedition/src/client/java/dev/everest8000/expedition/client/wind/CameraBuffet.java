package dev.everest8000.expedition.client.wind;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.everest8000.expedition.client.ClientWeather;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * A slight unsteadiness of the view when strong gusts hit an exposed player: a fraction of a degree, a few times a
 * second, only in hurricane-force wind and mostly while a gust is passing. Follows the game's Distortion Effects
 * slider and the camera_buffet setting; bracing (sneaking) halves it.
 */
public final class CameraBuffet {
    /** Degrees of roll at full strength. */
    private static final float MAX_DEGREES = 0.45f;

    private CameraBuffet() {}

    public static void apply(PoseStack pose, float partialTick) {
        if (!WindFxConfig.cameraBuffet || !ClientWeather.active()) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.isPaused() || player.isSpectator()) return;
        float distortion = mc.options.screenEffectScale().get().floatValue();
        if (distortion <= 0f) return;
        float exposure = ClientWeather.underCover() ? 0f : ClientWeather.windShare();
        float kmh = ClientWeather.windKmh();
        float strong = smooth(75f, 170f, kmh) * exposure;
        if (strong <= 0.01f) return;
        float gusting = smooth(0.95f, 1.45f, ClientWeather.gust());
        float amp = MAX_DEGREES * strong * (0.3f + 0.7f * gusting) * distortion;
        if (player.isShiftKeyDown()) amp *= 0.5f;
        double t = mc.level.getGameTime() + partialTick;
        float roll = amp * (float) (Gusts.Noise.at(t * 0.17, 3.1, 0.7) + 0.4 * Gusts.Noise.at(t * 0.41, 8.3, 1.9));
        float pitch = amp * 0.6f * (float) Gusts.Noise.at(t * 0.21, 13.7, 4.2);
        float yaw = amp * 0.5f * (float) Gusts.Noise.at(t * 0.15, 21.1, 6.6);
        pose.mulPose(Axis.ZP.rotationDegrees(roll));
        pose.mulPose(Axis.XP.rotationDegrees(pitch));
        pose.mulPose(Axis.YP.rotationDegrees(yaw));
    }

    private static float smooth(float e0, float e1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - e0) / (e1 - e0)));
        return t * t * (3f - 2f * t);
    }
}
