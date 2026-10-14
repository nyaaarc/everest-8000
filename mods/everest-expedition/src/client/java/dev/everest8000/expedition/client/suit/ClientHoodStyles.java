package dev.everest8000.expedition.client.suit;

import dev.everest8000.expedition.gear.HoodStyle;
import dev.everest8000.expedition.gear.SuitColours;
import dev.everest8000.expedition.net.HoodStylePayload;
import dev.everest8000.expedition.net.SuitColoursPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Everyone's hood look as the server last told us; this player's own suit colours (for the picker to start from);
 * whether we still need to ask this player to pick. (Everest Cinema puts its actors' looks straight into STYLES.)
 */
public final class ClientHoodStyles {
    private static final Map<UUID, HoodStyle> STYLES = new HashMap<>();
    private static SuitColours ownColours = SuitColours.DEFAULTS;
    private static boolean askPending;

    private ClientHoodStyles() {}

    static void register() {
        ClientPlayNetworking.registerGlobalReceiver(HoodStylePayload.TYPE, (payload, context) -> {
            HoodStyle style = HoodStyle.byIndex(payload.style());
            if (style == null) {
                STYLES.remove(payload.player());
            } else {
                STYLES.put(payload.player(), style);
            }
            if (payload.player().equals(context.player().getUUID())) askPending = style == null;
        });
        ClientPlayNetworking.registerGlobalReceiver(SuitColoursPayload.TYPE, (payload, context) -> ownColours = payload.colours());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            STYLES.clear();
            ownColours = SuitColours.DEFAULTS;
            askPending = false;
        });
    }

    /** The look to draw this entity's hood in; the default for anyone without one (or not a player). */
    public static HoodStyle of(Entity entity) {
        return entity instanceof Player player ? STYLES.getOrDefault(player.getUUID(), HoodStyle.DEFAULT) : HoodStyle.DEFAULT;
    }

    /** This player's own look, if they've picked one. */
    static HoodStyle own() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? null : STYLES.get(mc.player.getUUID());
    }

    /** This player's chosen suit colours (defaults until they choose). */
    static SuitColours ownColours() {
        return ownColours;
    }

    /** True once, after joining, if this player has yet to pick a look. */
    static boolean takeAsk() {
        boolean ask = askPending;
        askPending = false;
        return ask;
    }
}
