package dev.everest8000.radio.dsp;

import dev.everest8000.radio.RadioConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/**
 * Listening material for the squelch bursts (with -Prender): one Harvard sentence through the radio at degradation
 * 0, 0.4, 0.66 and 0.9, opening burst, call and closing burst, written to test-output/squelch/current_d*.wav.
 * Rename a run to compare it with the next one.
 */
class SquelchCompareTest {
    static final float[] DEGRADATIONS = {0f, 0.4f, 0.66f, 0.9f};

    @Test
    @EnabledIfSystemProperty(named = "radio.render", matches = "true")
    void renderSquelchExamples() throws Exception {
        Path root = Path.of(System.getProperty("radio.testOutput", "test-output"));
        short[] speech;
        try (InputStream in = Files.newInputStream(root.resolve("sources/OSR_us_000_0030_8k/harvard_m1_48k.wav"))) {
            speech = Audio.readWav(in);
        }
        speech = Arrays.copyOf(speech, Math.min(speech.length, (int) (3.2 * Audio.RATE))); // the first sentence
        int lead = RadioRenderer.LEAD_FRAMES;
        int frames = lead + (speech.length + Audio.FRAME - 1) / Audio.FRAME + RadioRenderer.TRAIL_FRAMES;
        short[] dry = new short[frames * Audio.FRAME];
        System.arraycopy(speech, 0, dry, lead * Audio.FRAME, speech.length);
        short[] received = new RadioRenderer.Codec().roundTrip(dry);
        Path out = root.resolve("squelch");
        Files.createDirectories(out);
        for (float d : DEGRADATIONS) {
            short[] radio = RadioRenderer.transmit(RadioConfig.defaults(), received, frames, d, 11).radio();
            Audio.writeWav(out.resolve(String.format(Locale.ROOT, "current_d%.2f.wav", d)), radio);
        }
    }
}
