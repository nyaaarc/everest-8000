package dev.everest8000.expedition.oxygen;

import dev.everest8000.expedition.net.BreathingPayload;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Who is breathing from a cylinder, told to the players who can see them so their oxygen mask shows. Each player's own
 * client already knows from {@link dev.everest8000.expedition.net.OxygenSyncPayload}; this is for everyone else.
 */
public final class Breathing {
    private static final Set<UUID> BREATHING = new HashSet<>();

    private Breathing() {}

    public static void register() {
        PayloadTypeRegistry.playS2C().register(BreathingPayload.TYPE, BreathingPayload.CODEC);
        // Someone coming into view: tell the newcomer if they're on oxygen (sent before the spawn packet, keyed by id).
        EntityTrackingEvents.START_TRACKING.register((entity, viewer) -> {
            if (entity instanceof ServerPlayer player && BREATHING.contains(player.getUUID())) {
                send(viewer, new BreathingPayload(player.getId(), true));
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> BREATHING.remove(handler.player.getUUID()));
    }

    /** Called by {@link Oxygen} every tick with whether the player is breathing from a cylinder right now. */
    public static void update(ServerPlayer player, boolean breathing) {
        boolean changed = breathing ? BREATHING.add(player.getUUID()) : BREATHING.remove(player.getUUID());
        if (!changed) return;
        BreathingPayload payload = new BreathingPayload(player.getId(), breathing);
        for (ServerPlayer viewer : PlayerLookup.tracking(player)) send(viewer, payload);
    }

    private static void send(ServerPlayer to, BreathingPayload payload) {
        if (ServerPlayNetworking.canSend(to, BreathingPayload.TYPE)) ServerPlayNetworking.send(to, payload);
    }
}
