package dev.everest8000.expedition.client.wind;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.InflaterInputStream;

/**
 * The map's terrain surface, baked from the world's region files (art/wind_terrain.py). The client only holds the few
 * chunks around the player, but the summit plume is seen from kilometres away, past Distant Horizons' far terrain:
 * this is what tells it which ridges stand in front of it.
 *
 * <p>Grids from finest to coarsest: a 4-block core over Everest, Lhotse and Nuptse (whose ridges the plume meets), an
 * 8-block grid over the upper Khumbu, and a 32-block one over the whole trek. Each cell holds the highest surface in
 * it (the y of the first air block). Immutable once loaded, so it is safe to read from the horizon worker thread.
 */
public final class TerrainHeights {
    public static final short VOID = Short.MIN_VALUE;
    private static final int MAGIC = 0x4556484D; // "EVHM"

    /** One grid. {@code (originX, originZ)} is the corner of cell (0, 0); values are row-major, x fastest. */
    public record Grid(int originX, int originZ, int cell, int width, int depth, short[] values) {
        public boolean contains(double x, double z) {
            double gx = (x - originX) / cell - 0.5;
            double gz = (z - originZ) / cell - 0.5;
            return gx >= 0 && gz >= 0 && gx < width - 1 && gz < depth - 1;
        }

        /** Bilinear between cell centres; NaN outside the grid or next to a void cell. */
        public float at(double x, double z) {
            double gx = (x - originX) / cell - 0.5;
            double gz = (z - originZ) / cell - 0.5;
            int ix = (int) Math.floor(gx);
            int iz = (int) Math.floor(gz);
            if (ix < 0 || iz < 0 || ix >= width - 1 || iz >= depth - 1) return Float.NaN;
            float fx = (float) (gx - ix);
            float fz = (float) (gz - iz);
            int i = iz * width + ix;
            short a = values[i], b = values[i + 1], c = values[i + width], d = values[i + width + 1];
            if (a == VOID || b == VOID || c == VOID || d == VOID) return Float.NaN;
            float top = a + (b - a) * fx;
            float bottom = c + (d - c) * fx;
            return top + (bottom - top) * fz;
        }
    }

    private final Grid[] grids;

    private TerrainHeights(Grid[] grids) {
        this.grids = grids;
    }

    /** The 8-block grid over the upper Khumbu, which covers everywhere a plume can reach: the one the GPU gets. */
    public Grid fine() {
        for (Grid g : grids) if (g.cell() == 8) return g;
        return grids[0];
    }

    /** Surface height at a block position, from the finest grid that covers it; NaN off the map. */
    public float at(double x, double z) {
        for (Grid g : grids) {
            float h = g.at(x, z);
            if (!Float.isNaN(h)) return h;
        }
        return Float.NaN;
    }

    public static TerrainHeights read(InputStream raw) throws IOException {
        try (DataInputStream in = new DataInputStream(new InflaterInputStream(raw))) {
            if (in.readInt() != MAGIC) throw new IOException("not an Everest height map");
            int version = in.readInt();
            if (version != 1 && version != 2) throw new IOException("unknown height map version " + version);
            int count = in.readInt();
            if (count < 1 || count > 8) throw new IOException("bad grid count " + count);
            Grid[] grids = new Grid[count];
            for (int i = 0; i < count; i++) grids[i] = readGrid(in);
            // Finest first, so a lookup takes the most detailed grid that covers the point.
            java.util.Arrays.sort(grids, java.util.Comparator.comparingInt(Grid::cell));
            return new TerrainHeights(grids);
        }
    }

    private static Grid readGrid(DataInputStream in) throws IOException {
        int originX = in.readInt();
        int originZ = in.readInt();
        int cell = in.readInt();
        int width = in.readInt();
        int depth = in.readInt();
        short[] values = new short[width * depth];
        // Rows are delta-coded along x, which deflates far better for terrain.
        for (int z = 0; z < depth; z++) {
            short prev = 0;
            for (int x = 0; x < width; x++) {
                prev = (short) (prev + in.readShort());
                values[z * width + x] = prev;
            }
        }
        return new Grid(originX, originZ, cell, width, depth, values);
    }
}
