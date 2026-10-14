package dev.everest8000.expedition.client;

import dev.everest8000.expedition.client.wind.WindParticles;
import dev.everest8000.expedition.weather.WeatherField;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * What the wind does to the local player: it shoves them, harder in gusts, and it rips snow off the ground around
 * them ({@link WindParticles}).
 */
final class WindEffects {
    /** Push in blocks/tick² at 100 km/h; it grows with the square of the speed, like drag. */
    private static final float PUSH_AT_100 = 0.011f;

    private WindEffects() {}

    static void tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || !ClientWeather.active() || mc.isPaused()) return;
        WeatherField field = ClientWeather.field();
        if (field == null) return;
        push(player, field);
        WindParticles.tick(mc);
    }

    private static void push(LocalPlayer player, WeatherField field) {
        if (player.isSpectator() || player.isCreative() || player.getAbilities().flying || player.isPassenger()
                || player.isInWater() || player.onClimbable()) return;
        float kmh = ClientWeather.windKmhAt(player.getY()) * ClientWeather.windShare();
        if (kmh < 30f) return;
        float push = PUSH_AT_100 * Mth.square(kmh / 100f);
        // Crouching braces against the wind; in the air there's no ground friction, so keep jumps from launching people.
        if (player.isShiftKeyDown()) push *= 0.3f;
        if (!player.onGround()) push *= 0.5f;
        player.setDeltaMovement(player.getDeltaMovement().add(field.windX() * push, 0, field.windZ() * push));
    }
}
