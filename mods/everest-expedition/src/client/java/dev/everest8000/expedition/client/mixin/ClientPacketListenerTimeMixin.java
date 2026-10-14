package dev.everest8000.expedition.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.everest8000.expedition.client.ClientClock;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla's once-a-second time packet would snap the day back to the server's whole tick; while the day is paced the
 * clock keeps its own fractional time (it is steered by the server's exact time instead). A stopped daylight cycle
 * still comes through as vanilla sends it.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerTimeMixin {
    @WrapOperation(method = "handleSetTime", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;setDayTime(J)V"))
    private void everest$keepPacedTime(ClientLevel level, long dayTime, Operation<Void> original) {
        original.call(level, ClientClock.fromServer(dayTime));
    }
}
