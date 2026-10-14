package dev.everest8000.radio.world;

import dev.everest8000.radio.RadioConfig;
import dev.everest8000.radio.signal.Occlusion;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ground heights for occlusion checks, from loaded chunks when possible. Radios work across the whole map, so the
 * terrain between two players is usually not loaded; for those chunks the stored heightmap is read from the region
 * files through the server's own chunk I/O (a streaming scan that only picks out the heightmap, never loading the
 * chunk), a few at a time, and kept at 4x4-block resolution (the highest point of each cell). Until a chunk's read
 * finishes its samples count as unknown. Regions with no file on disk count as empty without a read, because opening
 * a region file creates it. Main thread only, except for the read results.
 */
public final class TerrainHeights {
    private static final Heightmap.Types TYPE = Heightmap.Types.MOTION_BLOCKING_NO_LEAVES;
    private static final short[] ABSENT = new short[0]; // no chunk on disk (outside the map): nothing to block
    private static final short[] UNREADABLE = new short[0]; // a chunk without a usable heightmap

    private final int maxInFlight;
    private final int maxCached;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Map<ResourceKey<Level>, LevelCache> caches = new HashMap<>();

    public TerrainHeights(RadioConfig config) {
        maxInFlight = config.getInt(RadioConfig.MAX_DISK_READS);
        maxCached = config.getInt(RadioConfig.MAX_CACHED_CHUNKS);
    }

    /** The lowest free Y above the terrain at a column, or {@link Occlusion.Ground#UNKNOWN} until it has been read. */
    public int groundY(ServerLevel level, int x, int z) {
        int cx = x >> 4, cz = z >> 4;
        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
        if (chunk != null) {
            return chunk.getHeight(TYPE, x & 15, z & 15) + 1;
        }
        LevelCache cache = caches.computeIfAbsent(level.dimension(), k -> new LevelCache(level));
        long key = ChunkPos.asLong(cx, cz);
        short[] cells = cache.heights.get(key);
        if (cells == null) {
            if (!cache.regionExists(cx >> 5, cz >> 5)) {
                cache.heights.put(key, ABSENT);
                return level.getMinBuildHeight();
            }
            if (cache.requested.add(key)) {
                cache.queue.add(key);
            }
            return Occlusion.Ground.UNKNOWN;
        }
        if (cells == ABSENT) {
            return level.getMinBuildHeight();
        }
        if (cells == UNREADABLE) {
            return Occlusion.Ground.UNKNOWN;
        }
        return cells[((z & 15) >> 2) * 4 + ((x & 15) >> 2)];
    }

    /** Starts queued heightmap reads, up to the in-flight limit. Call every tick. */
    public void startReads(Iterable<ServerLevel> levels) {
        for (ServerLevel level : levels) {
            LevelCache cache = caches.get(level.dimension());
            if (cache == null) {
                continue;
            }
            if (cache.heights.size() > maxCached) {
                cache.heights.clear();
                cache.requested.clear();
                cache.queue.clear();
                cache.regions.clear();
            }
            while (inFlight.get() < maxInFlight && !cache.queue.isEmpty()) {
                read(level, cache, cache.queue.poll());
            }
        }
    }

    public void clear() {
        caches.clear();
    }

    private void read(ServerLevel level, LevelCache cache, long key) {
        inFlight.incrementAndGet();
        CollectFields fields = new CollectFields(new FieldSelector("Heightmaps", LongArrayTag.TYPE, TYPE.getSerializationKey()));
        int minY = level.getMinBuildHeight(), height = level.getHeight();
        try {
            level.getChunkSource().chunkMap.chunkScanner().scanChunk(new ChunkPos(key), fields).whenComplete((v, error) -> {
                try {
                    cache.heights.put(key, error != null ? UNREADABLE : decode(fields.getResult(), minY, height));
                } finally {
                    inFlight.decrementAndGet();
                }
            });
        } catch (RuntimeException e) {
            inFlight.decrementAndGet();
            cache.heights.put(key, UNREADABLE);
        }
    }

    static short[] decode(Tag result, int minY, int height) {
        if (!(result instanceof CompoundTag root)) {
            return ABSENT;
        }
        CompoundTag heightmaps = root.getCompound("Heightmaps");
        if (!heightmaps.contains(TYPE.getSerializationKey(), Tag.TAG_LONG_ARRAY)) {
            return UNREADABLE;
        }
        SimpleBitStorage storage;
        try {
            storage = new SimpleBitStorage(Mth.ceillog2(height + 1), 256, heightmaps.getLongArray(TYPE.getSerializationKey()));
        } catch (SimpleBitStorage.InitializationException e) {
            return UNREADABLE;
        }
        short[] cells = new short[16];
        java.util.Arrays.fill(cells, Short.MIN_VALUE);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int y = storage.get(x + z * 16) + minY;
                int cell = (z >> 2) * 4 + (x >> 2);
                if (y > cells[cell]) {
                    cells[cell] = (short) y;
                }
            }
        }
        return cells;
    }

    private static final class LevelCache {
        final Map<Long, short[]> heights = new ConcurrentHashMap<>();
        final Set<Long> requested = new HashSet<>();
        final ArrayDeque<Long> queue = new ArrayDeque<>();
        final Map<Long, Boolean> regions = new HashMap<>();
        final Path regionFolder;

        LevelCache(ServerLevel level) {
            regionFolder = DimensionType.getStorageFolder(level.dimension(),
                    level.getServer().getWorldPath(LevelResource.ROOT)).resolve("region");
        }

        boolean regionExists(int rx, int rz) {
            return regions.computeIfAbsent(ChunkPos.asLong(rx, rz),
                    k -> Files.isRegularFile(regionFolder.resolve("r." + rx + "." + rz + ".mca")));
        }
    }
}
