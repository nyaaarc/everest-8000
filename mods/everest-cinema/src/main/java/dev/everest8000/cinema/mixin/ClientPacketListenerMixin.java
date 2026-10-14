package dev.everest8000.cinema.mixin;

import dev.everest8000.cinema.Cinema;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Counts server position corrections (a rejected "moved too quickly" move, or a teleport). While the hidden player
 * rides along with the camera, each one snaps the camera entity elsewhere for a frame, which flickers everything
 * derived from it (local weather, eye brightness...), so renders report them.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @Inject(method = "handleMovePlayer", at = @At("HEAD"))
    private void cinema$positionCorrection(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        if (Cinema.enabled) Cinema.onServerPosition();
    }
}
