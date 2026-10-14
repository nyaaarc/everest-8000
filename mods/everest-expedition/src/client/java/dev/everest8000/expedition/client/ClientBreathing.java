package dev.everest8000.expedition.client;

import dev.everest8000.expedition.net.BreathingPayload;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/** Which players are breathing from a cylinder, for drawing their oxygen mask. */
public final class ClientBreathing {
    private static final IntSet BREATHING = new IntOpenHashSet();

    private ClientBreathing() {}

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(BreathingPayload.TYPE, (payload, context) -> {
            if (payload.breathing()) {
                BREATHING.add(payload.entityId());
            } else {
                BREATHING.remove(payload.entityId());
            }
        });
        ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> BREATHING.remove(entity.getId()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> BREATHING.clear());
    }

    /** This player from their own oxygen sync; anyone else from the server's broadcast. */
    public static boolean isBreathing(Entity entity) {
        if (entity == Minecraft.getInstance().player) return ClientOxygen.inUse() >= 0;
        return BREATHING.contains(entity.getId());
    }
}
