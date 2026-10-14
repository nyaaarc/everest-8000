package dev.everest8000.expedition.weather;

import dev.everest8000.expedition.net.WeatherSyncPayload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;

/** Steps the weather once a second and sends the result to every player. */
public final class WeatherDirector {
    private static final int STEP_TICKS = 20;
    private static volatile WeatherField current = WeatherField.CALM;

    private WeatherDirector() {}

    public static WeatherField current() {
        return current;
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(WeatherDirector::onStarted);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> current = WeatherField.CALM);
        ServerTickEvents.END_SERVER_TICK.register(WeatherDirector::tick);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> send(handler.player));
    }

    public static WeatherState state(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(WeatherState.FACTORY, WeatherState.NAME);
    }

    private static void onStarted(MinecraftServer server) {
        // Weather is drawn on each client from the field below. Real vanilla rain would pile snow layers and
        // freeze water on the map, so the server's own weather stays clear for good.
        server.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        server.overworld().setWeatherParameters(6000, 0, false, false);
        current = state(server).model().field();
    }

    private static void tick(MinecraftServer server) {
        if (server.getTickCount() % STEP_TICKS != 0) return;
        WeatherState state = state(server);
        state.model().step(server.overworld().getDayTime(), STEP_TICKS, server.overworld().getRandom());
        state.setDirty();
        current = state.model().field();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) send(player);
    }

    /** Pushes the current field right away, e.g. after a command changed it. */
    public static void broadcast(MinecraftServer server) {
        current = state(server).model().field();
        state(server).setDirty();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) send(player);
    }

    private static void send(ServerPlayer player) {
        if (ServerPlayNetworking.canSend(player, WeatherSyncPayload.TYPE)) {
            ServerPlayNetworking.send(player, new WeatherSyncPayload(current));
        }
    }
}
