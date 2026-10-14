package dev.everest8000.cinema.actor;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;

/**
 * A player clone that exists only on this client: rendered by the normal player renderer (so armour renderers, the
 * oxygen mask and pack layers apply), moved by vanilla player physics from input-style controls each tick (collision,
 * step-ups, gravity, limb swing), never sent to the server.
 */
public final class ActorPlayer extends AbstractClientPlayer {
    private PlayerSkin skin;
    // Controls for the next movement tick, set by the actor's brain at the end of the previous tick.
    float forward;
    float strafe;
    boolean jump;
    boolean sneak;
    boolean sprint;

    public ActorPlayer(ClientLevel level, GameProfile profile) {
        super(level, profile);
        // Show every skin layer (hat, jacket, sleeves, trousers).
        this.entityData.set(DATA_PLAYER_MODE_CUSTOMISATION, (byte) 0x7F);
        this.noPhysics = false;
    }

    void setSkin(PlayerSkin skin) {
        this.skin = skin;
    }

    @Override
    public PlayerSkin getSkin() {
        return skin != null ? skin : super.getSkin();
    }

    /** Physics run here, like the local player's. */
    @Override
    public boolean isControlledByLocalInstance() {
        return true;
    }

    @Override
    public boolean isSpectator() {
        return false;
    }

    @Override
    public boolean isCreative() {
        return false;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 512 * 512;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    @Override
    public void aiStep() {
        this.xxa = strafe;
        this.zza = forward;
        this.setJumping(jump);
        this.setShiftKeyDown(sneak);
        this.setSprinting(sprint && forward > 0.8f && !sneak);
        super.aiStep();
    }

    /** Starts the "use" animation (bow draw...) on this client-only body. */
    void beginUse(InteractionHand hand) {
        if (getItemInHand(hand).isEmpty()) return;
        startUsingItem(hand);
        setLivingEntityFlag(1, true);
        setLivingEntityFlag(2, hand == InteractionHand.OFF_HAND);
    }

    void endUse() {
        stopUsingItem();
        setLivingEntityFlag(1, false);
        setLivingEntityFlag(2, false);
        this.useItemRemaining = 0;
    }
}
