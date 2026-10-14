package dev.everest8000.expedition.gear;

import com.mojang.serialization.Codec;
import dev.everest8000.expedition.EverestExpedition;
import dev.everest8000.expedition.net.HoodTogglePayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * The down hood is worn up or pushed down (the hood key, J by default). Down, it shows the face and keeps out less of
 * the cold. The state lives on the hood item, so it stays with the hood and reaches every client through the normal
 * equipment sync.
 */
public final class Hood {
    /** True while the hood is pushed down; absent means up. */
    public static final DataComponentType<Boolean> HOOD_DOWN = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE,
            EverestExpedition.id("hood_down"), DataComponentType.<Boolean>builder()
                    .persistent(Codec.BOOL).networkSynchronized(ByteBufCodecs.BOOL).build());
    /** The hood's warmth while down; up, it counts its full share (see EverestGear#warmth). */
    public static final float WARMTH_DOWN = 0.05f;
    /** Ignore toggles closer together than this, so a flood of packets can't spam the sound. */
    private static final int TOGGLE_COOLDOWN_TICKS = 5;
    private static final Map<ServerPlayer, Integer> LAST_TOGGLE = new WeakHashMap<>();

    private Hood() {}

    public static void register() {
        PayloadTypeRegistry.playC2S().register(HoodTogglePayload.TYPE, HoodTogglePayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(HoodTogglePayload.TYPE, (payload, context) -> toggle(context.player()));
    }

    public static boolean isDown(ItemStack stack) {
        return stack.is(EverestGear.DOWN_HOOD) && Boolean.TRUE.equals(stack.get(HOOD_DOWN));
    }

    /** Puts the worn hood up or down, with a soft rustle; does nothing unless the player is wearing it. */
    public static void toggle(ServerPlayer player) {
        ItemStack hood = player.getItemBySlot(EquipmentSlot.HEAD);
        if (!hood.is(EverestGear.DOWN_HOOD) || !player.isAlive() || player.isSpectator()) return;
        Integer last = LAST_TOGGLE.get(player);
        if (last != null && player.tickCount - last < TOGGLE_COOLDOWN_TICKS) return;
        LAST_TOGGLE.put(player, player.tickCount);

        boolean down = !isDown(hood);
        // Changing the worn stack in place is picked up by the equipment sync, which sends it to everyone tracking.
        if (down) {
            hood.set(HOOD_DOWN, true);
        } else {
            hood.remove(HOOD_DOWN);
        }
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ARMOR_EQUIP_LEATHER,
                SoundSource.PLAYERS, 0.6f, down ? 1.25f : 1.05f);
        player.displayClientMessage(Component.translatable(down ? "message.everest_expedition.hood_down"
                : "message.everest_expedition.hood_up"), true);
    }
}
