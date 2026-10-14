package dev.everest8000.radio.dsp;

import dev.everest8000.radio.RadioConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The closing squelch burst: short and fading, gentlest over a clear link, a little rougher as the link gets worse,
 * and never gone.
 */
class SquelchTest {
    private static final RadioConfig CONFIG = RadioConfig.defaults();

    /** Loudest 50 ms of the closing burst (dBFS) and its length in frames, after a short silent call. */
    private static double[] closing(float d) {
        RadioVoiceChain chain = new RadioVoiceChain(CONFIG, 11);
        short[] in = new short[RadioVoiceChain.FRAME_SIZE], out = new short[RadioVoiceChain.FRAME_SIZE];
        chain.beginTransmission();
        for (int f = 0; f < 50; f++) {
            chain.processVoice(in, d, out);
        }
        int frames = chain.tailFrames();
        short[] tail = new short[frames * RadioVoiceChain.FRAME_SIZE];
        for (int f = 0; f < frames; f++) {
            chain.renderTail(f, d, out);
            System.arraycopy(out, 0, tail, f * RadioVoiceChain.FRAME_SIZE, out.length);
        }
        int window = Audio.RATE / 20;
        double loudest = -200;
        for (int i = 0; i + window <= tail.length; i += window / 4) {
            loudest = Math.max(loudest, Audio.db(Audio.rms(tail, i, i + window)));
        }
        return new double[]{loudest, frames};
    }

    @Test
    void gentlestOverAClearLink() {
        double[] clear = closing(0f), medium = closing(0.4f);
        assertTrue(clear[0] < medium[0] - 5, "clear " + clear[0] + " dB vs medium " + medium[0] + " dB");
        assertTrue(clear[0] > -45, "still heard over the open-channel hiss: " + clear[0] + " dB");
        assertTrue(clear[1] < medium[1], "shorter over a clear link");
    }

    @Test
    void shortAndRougherOnWorseLinks() {
        double[] medium = closing(0.4f), poor = closing(0.9f);
        assertEquals(medium[1], poor[1], "full length from 0.4 on");
        assertTrue(medium[1] * RadioVoiceChain.FRAME_SIZE * 1000 / Audio.RATE <= 100, "about a tenth of a second");
        assertTrue(poor[0] >= medium[0] - 1, "no gentler on a worse link");
    }
}
