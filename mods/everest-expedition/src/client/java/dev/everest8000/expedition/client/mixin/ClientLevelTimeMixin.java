package dev.everest8000.expedition.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.everest8000.expedition.client.ClientClock;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.LevelTimeAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The client's day runs on {@link ClientClock}: each tick it stores the clock's time instead of adding a whole day
 * tick, and the sun's angle (which the sky, the light level, Iris's sun and shadow uniforms and the star brightness
 * all read) comes from the fractional time, so the sun and moon move smoothly at any frame rate.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelTimeMixin implements LevelTimeAccess {
    @WrapOperation(method = "tickTime", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;setDayTime(J)V"))
    private void everest$pacedDay(ClientLevel level, long next, Operation<Void> original) {
        original.call(level, ClientClock.tick(next));
    }

    /** Overrides the interface default, which only sees whole ticks. Dimensions with a fixed time keep it. */
    @Override
    public float getTimeOfDay(float partialTick) {
        if (ClientClock.paced() && !this.dimensionType().hasFixedTime()) return ClientClock.timeOfDay(partialTick);
        return this.dimensionType().timeOfDay(this.dayTime());
    }
}
