package dev.everest8000.expedition.gear;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Java ramps match the previews' (art/suit3d_colours.py), to within a unit per channel of rounding. */
class SuitRampsTest {
    @Test
    void matchesThePreviewRamps() throws IOException {
        int checked = 0;
        try (InputStream in = SuitRampsTest.class.getResourceAsStream("/suit_ramps_expected.txt")) {
            assertNotNull(in, "suit_ramps_expected.txt (python art/suit3d_colours.py --java)");
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = reader.readLine()) != null; ) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] colourAndRamps = line.split(":");
                int colour = Integer.parseInt(colourAndRamps[0].trim(), 16);
                String[] classes = colourAndRamps[1].split("\\|");
                int[][] ramps = SuitRamps.ramps(colour);
                for (int c = 0; c < 2; c++) {
                    String[] expected = classes[c].trim().split(" ");
                    assertEquals(SuitRamps.STEPS, expected.length);
                    for (int i = 0; i < SuitRamps.STEPS; i++) {
                        int want = Integer.parseInt(expected[i], 16), got = ramps[c][i];
                        for (int shift = 0; shift <= 16; shift += 8) {
                            int diff = Math.abs(((want >> shift) & 255) - ((got >> shift) & 255));
                            assertTrue(diff <= 1, String.format("%06x class %d step %d: want %06x got %06x", colour, c, i, want, got));
                        }
                    }
                }
                checked++;
            }
        }
        assertTrue(checked > 20, "only " + checked + " colours checked");
    }

    @Test
    void theDyeColourIsTheBase() {
        assertEquals(0xC42A24, SuitRamps.ramp(0xC42A24)[3]);
    }

    @Test
    void theDefaultGoldReproducesThePaintedRamp() {
        // art/suit3d_textures.py GOLD, outline .. sheen; the generator was fitted to it.
        int[] painted = {0x613805, 0x9C630B, 0xD28C15, 0xF5B526, 0xFFCB47, 0xFFE596};
        int[] ramp = SuitRamps.ramp(0xF5B526);
        for (int i = 0; i < painted.length; i++) {
            for (int shift = 0; shift <= 16; shift += 8) {
                assertTrue(Math.abs(((painted[i] >> shift) & 255) - ((ramp[i] >> shift) & 255)) <= 4,
                        String.format("step %d: painted %06x, ramp %06x", i, painted[i], ramp[i]));
            }
        }
        assertArrayEquals(new int[]{0xF5B526}, new int[]{ramp[3]});
    }
}
