package dev.everest8000.expedition.client;

import dev.everest8000.expedition.weather.CloudDeck;
import dev.everest8000.expedition.weather.WeatherField;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

/**
 * The cloud deck's height on this client: eased toward {@link CloudDeck#baseY} for the weather the client sees, at
 * most 8 blocks a second, so a change of weather moves the deck over half a minute to a minute and a half instead of in
 * one jump. Every cloud renderer reads it through the overworld's cloud height (vanilla and Sodium, Better Clouds,
 * Iris's {@code cloudHeight} uniform that Complementary builds its clouds on, and Distant Horizons' far clouds through
 * {@code compat.distanthorizons}).
 */
public final class ClientSky {
    /** Share of the remaining distance covered each tick, and the most in blocks. */
    private static final float EASE = 0.02f;
    private static final float MAX_STEP = 0.4f;

    private static float deck = Float.NaN;
    private static float previous = Float.NaN;

    private ClientSky() {}

    static void tick() {
        WeatherField field = ClientWeather.field();
        if (!ClientWeather.active() || field == null) {
            deck = previous = Float.NaN;
            return;
        }
        float target = CloudDeck.baseY(field);
        if (Float.isNaN(deck)) {
            deck = previous = target;
            return;
        }
        previous = deck;
        deck += Mth.clamp((target - deck) * EASE, -MAX_STEP, MAX_STEP);
    }

    static void reset() {
        deck = previous = Float.NaN;
    }

    public static boolean active() {
        return !Float.isNaN(deck) && ClientWeather.active();
    }

    /** The cloud base for the frame being drawn. */
    public static float cloudHeight() {
        float partialTick = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true);
        return Mth.lerp(partialTick, previous, deck);
    }
}
