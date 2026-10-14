package dev.everest8000.cinema.actor;

import dev.everest8000.cinema.nav.BlockQuery;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.HashMap;
import java.util.Map;

/** {@link BlockQuery} over the client world, cached per instance (make a new one per search or tick). */
public final class McBlockQuery implements BlockQuery {
    private final Level level;
    private final BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
    private final Map<Long, BlockState> cache = new HashMap<>();

    public McBlockQuery(Level level) {
        this.level = level;
    }

    private BlockState state(int x, int y, int z) {
        long k = BlockPos.asLong(x, y, z);
        BlockState s = cache.get(k);
        if (s == null) {
            s = level.getBlockState(m.set(x, y, z));
            cache.put(k, s);
        }
        return s;
    }

    @Override
    public double collisionTop(int x, int y, int z) {
        BlockState s = state(x, y, z);
        if (s.isAir()) return 0;
        // Ladders and vines have a thin collision box but are climbed through, not stood on or blocked by.
        if (s.is(BlockTags.CLIMBABLE) && !s.is(Blocks.SCAFFOLDING)) return 0;
        VoxelShape shape = s.getCollisionShape(level, m.set(x, y, z));
        return shape.isEmpty() ? 0 : Math.max(0, Math.min(1.5, shape.max(net.minecraft.core.Direction.Axis.Y)));
    }

    @Override
    public boolean climbable(int x, int y, int z) {
        return state(x, y, z).is(BlockTags.CLIMBABLE);
    }

    @Override
    public boolean hazard(int x, int y, int z) {
        BlockState s = state(x, y, z);
        return !s.getFluidState().isEmpty() || s.is(BlockTags.FIRE) || s.is(Blocks.POWDER_SNOW) || s.is(Blocks.CACTUS)
                || s.is(Blocks.MAGMA_BLOCK) || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.CAMPFIRE) || s.is(Blocks.SOUL_CAMPFIRE);
    }

    @Override
    public double snowDepth(int x, int y, int z) {
        BlockState s = state(x, y, z);
        if (s.is(Blocks.SNOW)) return s.getValue(SnowLayerBlock.LAYERS) / 8.0;
        if (s.is(Blocks.POWDER_SNOW)) return 1;
        BlockState below = state(x, y - 1, z);
        return below.is(Blocks.SNOW) ? below.getValue(SnowLayerBlock.LAYERS) / 8.0 : 0;
    }

    @Override
    public boolean loaded(int x, int z) {
        return level.hasChunk(x >> 4, z >> 4);
    }
}
