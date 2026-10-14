package dev.everest8000.radio.dsp;

import dev.everest8000.radio.RadioConfig;
import dev.everest8000.radio.dsp.RadioRenderer.Result;
import dev.everest8000.radio.dsp.RadioRenderer.Scenario;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Radio calls rendered offline (see {@link RadioRenderer}). Every build runs a quick pass on the TTS sample; with
 * -Prender (Gradle) it renders every voice, writes the WAVs and README.txt to test-output/, and checks the
 * intelligibility targets the chain was calibrated to on the real recordings, with the route's links placed by the
 * October 2026 balance (Base Camp to Camp 2 is "mid": hard to follow, but you get it):
 * good above 0.85, fair 0.5-0.75, mid 0.3-0.5, poor 0.12-0.33, worst 0.05-0.2.
 */
class RadioAudioRenderTest {
    private static final Path OUT = Path.of(System.getProperty("radio.testOutput", "test-output"));

    @Test
    void radioCalls() throws Exception {
        boolean full = Boolean.getBoolean("radio.render");
        RadioConfig config = RadioConfig.defaults();
        List<Result> results = RadioRenderer.render(OUT, config, full);
        System.out.println(RadioRenderer.table(results));

        for (Result r : results) {
            String what = r.source().name() + " " + r.scenario().name();
            assertTrue(r.quietMs() <= 40, what + ": silent for " + r.quietMs() + " ms while transmitting");
            Result clear = results.stream().filter(x -> x.source() == r.source()
                    && x.scenario().name().equals("good_100m_open")).findFirst().orElseThrow();
            assertTrue(Math.abs(r.speechDb() - clear.speechDb()) < 4, what + ": loudness should stay similar");
            if (r.scenario().name().startsWith("good")) {
                assertTrue(r.cutPct() == 0, what + ": no cuts when the signal is good");
            }
            if (r.scenario().name().startsWith("worst")) {
                assertTrue(r.cutPct() > 20, what + ": real cuts at the worst");
                assertTrue(r.intel() > 0.02, what + ": something always comes through");
            }
        }
        if (!full) {
            return;
        }
        Map<Scenario, double[]> means = RadioRenderer.realSpeechMeans(results);
        means.forEach((sc, m) -> {
            double intel = m[0];
            if (sc.name().startsWith("good")) {
                assertTrue(intel > 0.85, sc.name() + " intelligibility " + intel);
            } else if (sc.name().startsWith("fair")) {
                assertTrue(intel > 0.5 && intel < 0.75, sc.name() + " intelligibility " + intel);
            } else if (sc.name().startsWith("mid")) {
                assertTrue(intel > 0.3 && intel < 0.5, sc.name() + " intelligibility " + intel);
            } else if (sc.name().startsWith("poor")) {
                assertTrue(intel > 0.12 && intel < 0.33, sc.name() + " intelligibility " + intel);
            } else if (sc.name().startsWith("worst")) {
                assertTrue(intel > 0.05 && intel < 0.2, sc.name() + " intelligibility " + intel);
            }
        });
        // Nothing periodic at the worst: no regular swell in the level (in any band) or wobble in the pitch.
        double[] envelope = RadioRenderer.channelPeriodicity(config, 1f, 120, 11, null);
        double[] pitch = RadioRenderer.wowPeriodicity(config, 1f, 120, 11);
        assertTrue(envelope[0] < 0.1 && envelope[3] < 0.2, "envelope periodicity " + envelope[0] + " / " + envelope[3]);
        assertTrue(pitch[0] < 0.15 && pitch[3] < 0.3, "pitch periodicity " + pitch[0] + " / " + pitch[3]);
        String readme = RadioRenderer.readme(results, config, RadioRenderer.benchmark(config), envelope, pitch);
        Files.writeString(OUT.resolve("README.txt"), readme);
        Files.deleteIfExists(OUT.resolve("report.txt"));
        System.out.println(readme);
    }
}
