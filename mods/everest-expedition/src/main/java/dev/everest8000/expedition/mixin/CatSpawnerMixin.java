package dev.everest8000.expedition.mixin;

import dev.everest8000.expedition.wildlife.Wildlife;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.npc.CatSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Village cats skip the natural spawner, and the lodges up the valley have beds; keep the ceiling for them too. */
@Mixin(CatSpawner.class)
public abstract class CatSpawnerMixin {
    @Inject(method = "spawnCat", at = @At("HEAD"), cancellable = true)
    private void everest$lowerTrekOnly(BlockPos pos, ServerLevel level, CallbackInfoReturnable<Integer> cir) {
        if (Wildlife.blocked(MobCategory.CREATURE, level, pos)) cir.setReturnValue(0);
    }
}
