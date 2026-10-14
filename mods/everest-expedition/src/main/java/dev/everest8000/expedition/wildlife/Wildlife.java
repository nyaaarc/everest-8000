package dev.everest8000.expedition.wildlife;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.Level;

/**
 * Where the mountain lets animals appear: the lower trek only, and never monsters. The map's biomes below the ceiling
 * are plains and rivers; the Naturalist species that fit them are set in the server's naturalist-server.properties and
 * in this mod's data/naturalist biome tags.
 */
public final class Wildlife {
    /** Nothing spawns above this: just below the Khumbu valley lodge, about 4,510 m. */
    public static final int CEILING_Y = -370;
    /** Spawn attempts are made this far below the surface at most: under trees and overhangs, not in the rock below. */
    public static final int SURFACE_BAND = 24;

    private Wildlife() {}

    public static boolean blocked(MobCategory category, ServerLevel level, BlockPos pos) {
        return level.dimension() == Level.OVERWORLD && (category == MobCategory.MONSTER || pos.getY() > CEILING_Y);
    }
}
