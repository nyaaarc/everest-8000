package dev.everest8000.expedition.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.everest8000.expedition.wildlife.Wildlife;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Natural spawning, vanilla's and every mod's that adds to biome spawn lists (Naturalist included), goes through here.
 * A spawn group shares the height of its starting position, so checking that is enough for the ceiling.
 */
@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerMixin {
    @Inject(method = "spawnCategoryForPosition(Lnet/minecraft/world/entity/MobCategory;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/NaturalSpawner$SpawnPredicate;Lnet/minecraft/world/level/NaturalSpawner$AfterSpawnCallback;)V",
            at = @At("HEAD"), cancellable = true)
    private static void everest$lowerTrekOnly(MobCategory category, ServerLevel level, ChunkAccess chunk, BlockPos pos,
                                             NaturalSpawner.SpawnPredicate filter, NaturalSpawner.AfterSpawnCallback callback,
                                             CallbackInfo ci) {
        if (Wildlife.blocked(category, level, pos)) ci.cancel();
    }

    /**
     * Leashed animals don't count toward the spawn cap, like name-tagged ones. The map ties a string of pack donkeys up
     * at Lukla, and with the server's simulation distance they alone would fill the cap and keep anything else from
     * spawning around the start of the trek.
     */
    @WrapOperation(method = "createState", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Mob;requiresCustomPersistence()Z"))
    private static boolean everest$leashedDontCount(Mob mob, Operation<Boolean> original) {
        return original.call(mob) || mob.isLeashed();
    }

    /**
     * Vanilla tries a random height anywhere from the bottom of the world to the surface, which in this 4,000-block-tall
     * world almost always lands in solid rock, so animals would hardly ever appear. With no monsters in the overworld
     * nothing needs the caves, so try near the surface instead.
     */
    @Inject(method = "getRandomPosWithin", at = @At("HEAD"), cancellable = true)
    private static void everest$nearSurface(Level level, LevelChunk chunk, CallbackInfoReturnable<BlockPos> cir) {
        if (level.dimension() != Level.OVERWORLD) return;
        ChunkPos chunkPos = chunk.getPos();
        int x = chunkPos.getMinBlockX() + level.random.nextInt(16);
        int z = chunkPos.getMinBlockZ() + level.random.nextInt(16);
        int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 1;
        int y = Mth.randomBetweenInclusive(level.random, Math.max(level.getMinBuildHeight(), top - Wildlife.SURFACE_BAND), top);
        cir.setReturnValue(new BlockPos(x, y, z));
    }
}
