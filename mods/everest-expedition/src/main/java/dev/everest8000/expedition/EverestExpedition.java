package dev.everest8000.expedition;

import dev.everest8000.expedition.command.EverestCommand;
import dev.everest8000.expedition.console.ConsoleInbox;
import dev.everest8000.expedition.exposure.Frostbite;
import dev.everest8000.expedition.gear.EverestGear;
import dev.everest8000.expedition.gear.Hood;
import dev.everest8000.expedition.gear.HoodStyles;
import dev.everest8000.expedition.gear.SuitColouring;
import dev.everest8000.expedition.net.DayClockPayload;
import dev.everest8000.expedition.net.OxygenSyncPayload;
import dev.everest8000.expedition.net.WeatherSyncPayload;
import dev.everest8000.expedition.oxygen.Breathing;
import dev.everest8000.expedition.oxygen.Oxygen;
import dev.everest8000.expedition.time.ServerClock;
import dev.everest8000.expedition.weather.WeatherDirector;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EverestExpedition implements ModInitializer {
    public static final String MOD_ID = "everest_expedition";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.playS2C().register(WeatherSyncPayload.TYPE, WeatherSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(OxygenSyncPayload.TYPE, OxygenSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(DayClockPayload.TYPE, DayClockPayload.CODEC);
        EverestGear.register();
        Hood.register();
        HoodStyles.register();
        SuitColouring.register();
        WeatherDirector.register();
        ServerClock.register();
        Frostbite.register();
        Oxygen.register();
        Breathing.register();
        ConsoleInbox.register();
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> EverestCommand.register(dispatcher));
    }
}
