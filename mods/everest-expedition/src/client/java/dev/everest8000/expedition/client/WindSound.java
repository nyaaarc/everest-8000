package dev.everest8000.expedition.client;

import dev.everest8000.expedition.EverestExpedition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;

/**
 * A wind loop whose volume and pitch follow the wind at the player, muffled indoors. It plays on the Weather
 * volume slider. The sound event is only looked up client-side, so it needs no registry entry on the server.
 */
final class WindSound extends AbstractTickableSoundInstance {
    private static final SoundEvent WIND = SoundEvent.createVariableRangeEvent(EverestExpedition.id("wind"));
    private static WindSound playing;

    private WindSound() {
        super(WIND, SoundSource.WEATHER, SoundInstance.createUnseededRandom());
        looping = true;
        delay = 0;
        volume = 0.01f;
        relative = true;
        attenuation = Attenuation.NONE;
    }

    static void update(Minecraft mc) {
        if (!ClientWeather.active() || mc.player == null) {
            if (playing != null) playing.fadeOut();
            return;
        }
        if ((playing == null || playing.isStopped()) && target() > 0.02f) {
            playing = new WindSound();
            mc.getSoundManager().play(playing);
        }
    }

    private static float target() {
        float loudness = Mth.clamp((ClientWeather.windKmh() - 20f) / 140f, 0f, 1f);
        return loudness * (ClientWeather.underCover() ? 0.35f : 0.4f + 0.6f * ClientWeather.windShare());
    }

    private void fadeOut() {
        volume = Math.max(0f, volume - 0.05f);
        if (volume <= 0f) stop();
    }

    @Override
    public void tick() {
        if (!ClientWeather.active()) {
            fadeOut();
            return;
        }
        float target = target();
        // Quick enough to follow the gusts: a burst swells the roar over about half a second.
        volume = Mth.lerp(target > volume ? 0.14f : 0.08f, volume, target);
        // Kept near 1: pitching the loop down muffles it, which is only wanted indoors. Gusts lift it a little.
        float gust = Mth.clamp(ClientWeather.gust() - 1f, -0.4f, 0.6f);
        pitch = (ClientWeather.underCover() ? 0.8f : 0.95f) + 0.12f * Mth.clamp(ClientWeather.windKmh() / 200f, 0f, 1f)
                + 0.06f * gust * ClientWeather.windShare();
        if (target <= 0f && volume < 0.005f) stop();
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }
}
