package dev.everest8000.expedition.mixin;

import com.llamalad7.mixinextras.injector.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * No movement anti-cheat on this server (the owner's call: it's friends only). Vanilla snaps a player or their vehicle
 * back when a move is too fast ("moved too quickly") or ends inside a block ("moved wrongly"); here every client move is
 * accepted and nothing is logged. Same-tick teleport confirmations on localhost tripped the speed check on every /tp.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerMovementCheckMixin {
    /** Vanilla skips the speed check for the singleplayer owner, so treat everyone as the owner there. */
    @WrapOperation(method = {"handleMovePlayer", "handleMoveVehicle"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;isSingleplayerOwner()Z"))
    private boolean everest$noSpeedCheck(ServerGamePacketListenerImpl self, Operation<Boolean> original) {
        return true;
    }

    /** "Was the old spot clear?" decides whether a move into a block is rolled back; never roll back. */
    @WrapOperation(method = {"handleMovePlayer", "handleMoveVehicle"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;noCollision(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Z"))
    private boolean everest$noCollisionRollback(ServerLevel level, Entity entity, AABB box, Operation<Boolean> original) {
        return false;
    }

    @WrapOperation(method = "handleMovePlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;isPlayerCollidingWithAnythingNew(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/world/phys/AABB;DDD)Z"))
    private boolean everest$noNewCollisionRollback(ServerGamePacketListenerImpl self, LevelReader level, AABB box,
                                                   double x, double y, double z, Operation<Boolean> original) {
        return false;
    }

    /** The "moved wrongly!" warnings: the player's takes one argument, the vehicle's a varargs array. */
    @WrapWithCondition(method = "handleMovePlayer", at = @At(value = "INVOKE",
            target = "Lorg/slf4j/Logger;warn(Ljava/lang/String;Ljava/lang/Object;)V"))
    private boolean everest$quietPlayerWarning(Logger logger, String message, Object arg) {
        return false;
    }

    @WrapWithCondition(method = "handleMoveVehicle", at = @At(value = "INVOKE",
            target = "Lorg/slf4j/Logger;warn(Ljava/lang/String;[Ljava/lang/Object;)V"))
    private boolean everest$quietVehicleWarning(Logger logger, String message, Object[] args) {
        return false;
    }
}
