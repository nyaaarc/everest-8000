package dev.everest8000.radio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RadioConfigTest {
    @Test
    void writesDefaultsThenReadsOverrides(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("everest_radio.properties");
        List<String> warnings = new ArrayList<>();
        RadioConfig first = RadioConfig.load(file, warnings::add);
        assertTrue(Files.exists(file));
        assertTrue(warnings.isEmpty(), warnings.toString());
        assertEquals(1200, first.get(RadioConfig.CLEAR_RANGE));
        String text = Files.readString(file);
        for (RadioConfig.Setting s : RadioConfig.settings()) {
            assertTrue(text.contains(s.key() + "="), s.key());
        }

        Files.writeString(file, text.replace("clear_range_blocks=1200", "clear_range_blocks=1300")
                .replace("noise_at_max=0.8", "noise_at_max=banana")
                .replace("max_samples=256", "max_samples=1"));
        RadioConfig second = RadioConfig.load(file, warnings::add);
        assertEquals(1300, second.get(RadioConfig.CLEAR_RANGE));
        assertEquals(0.8, second.get(RadioConfig.NOISE_AT_MAX));
        assertEquals(8, second.getInt(RadioConfig.MAX_SAMPLES));
        assertEquals(2, warnings.size(), warnings.toString());
    }
}
