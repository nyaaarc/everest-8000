package dev.everest8000.radio;

import dev.everest8000.radio.net.RadioKeyedPayload;
import dev.everest8000.radio.world.RadioCommand;
import dev.everest8000.radio.world.RadioKeying;
import dev.everest8000.radio.world.RadioTracker;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Expedition radios: hold one in either hand and talk to transmit; carry one anywhere in your inventory to listen.
 * The item and the tracking of who holds what live here; the audio is a Simple Voice Chat plugin (voice package).
 * Clients with the mod raise a talker's radio to their face (client source set), told who is talking by RadioKeying.
 */
public final class EverestRadio implements ModInitializer {
    public static final String MOD_ID = "everest_radio";
    public static final Logger LOG = LoggerFactory.getLogger("Everest Radio");
    public static final Item RADIO = new Item(new Item.Properties().stacksTo(1));

    private static volatile RadioConfig config = RadioConfig.defaults();
    private static RadioTracker tracker;
    private static RadioKeying keying;

    @Override
    public void onInitialize() {
        Registry.register(BuiltInRegistries.ITEM, ResourceLocation.fromNamespaceAndPath(MOD_ID, "radio"), RADIO);
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(entries -> entries.accept(RADIO));
        PayloadTypeRegistry.playS2C().register(RadioKeyedPayload.TYPE, RadioKeyedPayload.CODEC);

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            config = RadioConfig.load(FabricLoader.getInstance().getConfigDir().resolve("everest_radio.properties"),
                    LOG::warn);
            tracker = new RadioTracker(config);
            keying = new RadioKeying(config);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (tracker != null) {
                tracker.tick(server);
            }
            if (keying != null) {
                keying.tick(server);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            if (tracker != null) {
                tracker.close();
                tracker = null;
            }
            keying = null;
            RadioKeying.shutdown();
        });
        EntityTrackingEvents.START_TRACKING.register((entity, viewer) -> {
            if (keying != null) {
                keying.onStartTracking(entity, viewer);
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (keying != null) {
                keying.forget(handler.player.getUUID());
            }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) ->
                RadioCommand.register(dispatcher, () -> tracker, () -> keying));
    }

    public static RadioConfig config() {
        return config;
    }

    public static boolean isRadio(ItemStack stack) {
        return stack.is(RADIO);
    }

    /** A radio in either hand: talking transmits. */
    public static boolean holdsRadio(Player player) {
        return isRadio(player.getMainHandItem()) || isRadio(player.getOffhandItem());
    }
}
