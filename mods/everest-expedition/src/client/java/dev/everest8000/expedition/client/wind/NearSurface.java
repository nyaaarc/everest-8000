package dev.everest8000.expedition.client.wind;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The real surface right around the camera, copied from the loaded chunks on the client thread so the horizon worker
 * can read it. The baked heights are only good to a few blocks; up close the ridge you stand behind must be exact.
 */
final class NearSurface implements HorizonMap.Surface {
    private static final int RADIUS = 64;
    private static final int STEP = 2;
    private static final int SIZE = RADIUS * 2 / STEP + 1;

    private final int originX;
    private final int originZ;
    private final float[] heights = new float[SIZE * SIZE];

    private NearSurface(int originX, int originZ) {
        this.originX = originX;
        this.originZ = originZ;
    }

    static NearSurface capture(ClientLevel level, double camX, double camZ) {
        int ox = (int) Math.floor(camX) - RADIUS;
        int oz = (int) Math.floor(camZ) - RADIUS;
        NearSurface s = new NearSurface(ox, oz);
        for (int j = 0; j < SIZE; j++) {
            for (int i = 0; i < SIZE; i++) {
                int x = ox + i * STEP, z = oz + j * STEP;
                boolean loaded = level.getChunkSource().hasChunk(x >> 4, z >> 4);
                s.heights[j * SIZE + i] = loaded ? level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) : Float.NaN;
            }
        }
        return s;
    }

    @Override
    public float at(double x, double z) {
        int i = (int) Math.round((x - originX) / STEP);
        int j = (int) Math.round((z - originZ) / STEP);
        if (i < 0 || j < 0 || i >= SIZE || j >= SIZE) return Float.NaN;
        return heights[j * SIZE + i];
    }
}
