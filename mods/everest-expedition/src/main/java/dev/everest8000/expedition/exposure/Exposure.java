package dev.everest8000.expedition.exposure;

import dev.everest8000.expedition.weather.WeatherField;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

/** How exposed a spot is. Shared by the server (frostbite) and the client (wind push and sound) so they agree. */
public final class Exposure {
    private static final int[] UPWIND_SAMPLES = {2, 4, 7, 11};

    private Exposure() {}

    /** Under a roof or tent: no direct sky above the eyes. */
    public static boolean underCover(Level level, double x, double eyeY, double z) {
        return !level.canSeeSky(BlockPos.containing(x, eyeY, z));
    }

    /** 0 in the open, up to 0.8 with terrain standing above head height on the upwind side (behind a ridge or rock). */
    public static float lee(Level level, WeatherField field, double x, double y, double z) {
        int blocked = 0;
        for (int dist : UPWIND_SAMPLES) {
            int sx = Mth.floor(x - field.windX() * dist);
            int sz = Mth.floor(z - field.windZ() * dist);
            if (level.getHeight(Heightmap.Types.MOTION_BLOCKING, sx, sz) > y + 1.6) blocked++;
        }
        return blocked / (float) UPWIND_SAMPLES.length * 0.8f;
    }

    /** Share of the wind that reaches a player: 1 in the open, about 0.15 indoors. */
    public static float windShare(Level level, WeatherField field, double x, double y, double z, double eyeY) {
        if (underCover(level, x, eyeY, z)) return 0.15f;
        return 1f - lee(level, field, x, y, z);
    }
}
