package dev.everest8000.radio.signal;

import dev.everest8000.radio.RadioConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalModelTest {
    private final RadioConfig config = RadioConfig.defaults();

    @Test
    void perfectlyClearWithinTheClearRange() {
        for (double d : new double[]{0, 1, 50, 250, 800, 1199.9, 1200}) {
            assertEquals(0f, SignalModel.degradation(d, 0, config), "distance " + d);
        }
        assertTrue(SignalModel.degradation(1201, 0, config) > 0);
        assertTrue(SignalModel.degradation(1250, 0, config) < 0.03, "no jump just past the clear range");
    }

    @Test
    void distanceFollowsLogCurveAndNeverCutsOut() {
        float previous = 0;
        for (double d = 1250; d <= 60_000; d *= 1.25) {
            float now = SignalModel.degradation(d, 0, config);
            assertTrue(now > previous || now == 0.9f, "increases with distance at " + d);
            assertTrue(now <= 1f);
            previous = now;
        }
        // log curve: every doubling of distance adds the same amount
        double perDoubling = SignalModel.distancePart(2600, config) - SignalModel.distancePart(1300, config);
        assertEquals(perDoubling, SignalModel.distancePart(5400, config) - SignalModel.distancePart(2700, config), 1e-9);
        assertEquals(0.90, SignalModel.degradation(5500, 0, config), 1e-6, "where the distance penalty peaks");
        assertEquals(0.90, SignalModel.degradation(1_000_000, 0, config), 1e-6, "stays put beyond");
    }

    @Test
    void routeLinksLandWhereTheyWereTuned() {
        // Distances and blocked fractions measured on the map (tools/radio_balance.py).
        assertEquals(0.39, SignalModel.degradation(1790, 0.586, config), 0.01, "Base Camp to Camp 1, over the Icefall");
        assertEquals(0.67, SignalModel.degradation(2816, 0.727, config), 0.01, "Base Camp to Camp 2: hard but possible");
        assertEquals(0.79, SignalModel.degradation(4446, 0.004, config), 0.01, "Base Camp to the summit, line of sight");
        assertEquals(0.88, SignalModel.degradation(4739, 0.109, config), 0.01, "Base Camp to Camp 4");
        assertEquals(1f, SignalModel.degradation(18_664, 0.39, config), "Lukla to the summit");
    }

    @Test
    void terrainAddsSomeStatic() {
        assertTrue(SignalModel.degradation(100, 0.05, config) > 0.03, "a small ridge at short range is heard");
        assertEquals(0.10, SignalModel.occlusionPart(0.25, config), 1e-9, "a quarter underground: half the penalty");
        assertEquals(0.20, SignalModel.occlusionPart(1.0, config), 1e-9);
        assertEquals(1f, SignalModel.degradation(25_000, 1.0, config), "worst case is capped at 1");
        for (double o = 0.01; o <= 1; o += 0.01) {
            assertTrue(SignalModel.occlusionPart(o, config) >= SignalModel.occlusionPart(o - 0.01, config));
        }
    }

    @Test
    void namedThresholdsComeFromConfig() {
        RadioConfig wide = config.with(RadioConfig.CLEAR_RANGE, 1400);
        assertEquals(0f, SignalModel.degradation(1399, 0, wide));
        assertTrue(SignalModel.degradation(1399, 0, config) > 0);
    }
}
