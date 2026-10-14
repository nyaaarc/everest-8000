package dev.everest8000.expedition.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** The special spawners (village sieges, phantoms, patrols) don't use the natural spawner; no monsters from them either. */
@Mixin(ServerLevel.class)
public abstract class ServerLevelSpawnersMixin {
    @ModifyVariable(method = "tickCustomSpawners", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private boolean everest$noMonsters(boolean spawnEnemies) {
        return spawnEnemies && ((ServerLevel) (Object) this).dimension() != Level.OVERWORLD;
    }
}
