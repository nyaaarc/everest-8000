package dev.everest8000.radio.signal;

import dev.everest8000.radio.RadioConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OcclusionTest {
    private final RadioConfig config = RadioConfig.defaults();

    @Test
    void openGroundIsClear() {
        Occlusion.Result r = Occlusion.between(0, 70, 0, 100, 70, 0, (x, z) -> 64, config);
        assertEquals(0, r.fraction());
        assertEquals(r.samples(), r.known());
    }

    @Test
    void ridgeBlocksItsShareOfThePath() {
        // a 10-block-wide ridge rising to y=100 halfway between two players at y=70
        Occlusion.Ground ridge = (x, z) -> x >= 45 && x < 55 ? 100 : 64;
        Occlusion.Result r = Occlusion.between(0, 70, 0, 100, 70, 0, ridge, config);
        assertEquals(10.0 / 96, r.fraction(), 0.02); // 2 blocks next to each player are not sampled
        assertTrue(SignalModel.degradation(100, r.fraction(), config) > 0.05, "the ridge adds some static");
    }

    @Test
    void lineOfSightOverAValleyIsClear() {
        // from one peak to another across a deep valley: the line passes over the valley, nothing blocks it
        Occlusion.Ground valley = (x, z) -> 100 - (int) (80 * Math.sin(Math.PI * x / 1000.0));
        Occlusion.Result r = Occlusion.between(0, 102, 0, 1000, 102, 0, valley, config);
        assertEquals(0, r.fraction());
    }

    @Test
    void throughAMountain() {
        // a mountain peaking at y=2000 between two valleys at y=0; players at y=10 on either side, 6000 blocks apart
        Occlusion.Ground mountain = (x, z) -> (int) Math.max(0, 2000 - Math.abs(x - 3000) * 0.8);
        Occlusion.Result r = Occlusion.between(0, 10, 0, 6000, 10, 0, mountain, config);
        assertEquals(config.getInt(RadioConfig.MAX_SAMPLES), r.samples());
        assertEquals(5000.0 / 6000, r.fraction(), 0.02); // ground is above y=10 for 2500 blocks each side of the peak
        assertEquals(1f, SignalModel.degradation(6000, r.fraction(), config), 0.15f);
    }

    @Test
    void wallNextToAPlayerIsIgnored() {
        Occlusion.Ground cliff = (x, z) -> x <= 1 ? 90 : 64; // player stands against a cliff
        assertEquals(0, Occlusion.between(1.5, 70, 0, 100, 70, 0, cliff, config).fraction());
    }

    @Test
    void unknownTerrainIsLeftOut() {
        Occlusion.Ground half = (x, z) -> x < 50 ? Occlusion.Ground.UNKNOWN : (x < 60 ? 100 : 64);
        Occlusion.Result r = Occlusion.between(0, 70, 0, 100, 70, 0, half, config);
        assertTrue(r.known() < r.samples() && r.known() > 0);
        assertEquals(10.0 / r.known(), r.fraction(), 0.03);
        assertEquals(0, Occlusion.between(0, 70, 0, 100, 70, 0, (x, z) -> Occlusion.Ground.UNKNOWN, config).fraction());
    }

    @Test
    void playersSideBySide() {
        assertEquals(Occlusion.Result.CLEAR, Occlusion.between(0, 70, 0, 3, 70, 0, (x, z) -> 200, config));
    }
}
