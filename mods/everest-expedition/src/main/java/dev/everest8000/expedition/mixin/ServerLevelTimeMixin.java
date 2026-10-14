package dev.everest8000.expedition.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.everest8000.expedition.time.ServerClock;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The overworld's day runs at the paced speed: where vanilla's tick adds one to the day time, the clock adds its
 * fraction instead. Only reached while the daylight cycle rule is on. The other dimensions share the overworld's
 * time and don't advance it themselves.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelTimeMixin {
    @WrapOperation(method = "tickTime", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;setDayTime(J)V"))
    private void everest$pacedDay(ServerLevel level, long next, Operation<Void> original) {
        if (level.dimension() != Level.OVERWORLD) {
            original.call(level, next);
            return;
        }
        original.call(level, ServerClock.advance(level.getDayTime()));
    }
}
