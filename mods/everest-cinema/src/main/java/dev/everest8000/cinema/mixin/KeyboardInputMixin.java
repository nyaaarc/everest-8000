package dev.everest8000.cinema.mixin;

import dev.everest8000.cinema.Cinema;
import net.minecraft.client.player.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The puppet's movement replaces the keyboard's while it is engaged. */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void cinema$puppet(boolean slow, float slowFactor, CallbackInfo ci) {
        if (Cinema.enabled) Cinema.puppet.applyInput((KeyboardInput) (Object) this, slow, slowFactor);
    }
}
