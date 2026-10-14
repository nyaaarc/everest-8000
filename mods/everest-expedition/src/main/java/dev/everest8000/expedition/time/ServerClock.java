package dev.everest8000.expedition.time;

import dev.everest8000.expedition.net.DayClockPayload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;

/**
 * Paces the overworld's day (see {@link DayPace}) and keeps clients in step: the exact time goes out once a second,
 * alongside vanilla's own time sync, and right away whenever the time jumps. The daylight cycle game rule keeps its
 * meaning: when it's off the clock stands still as in vanilla.
 */
public final class ServerClock {
    private static final int SYNC_TICKS = 20;
    private static final DayClock CLOCK = new DayClock();

    private ServerClock() {}

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> CLOCK.reset());
        ServerTickEvents.END_SERVER_TICK.register(ServerClock::tick);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> send(handler.player, payload(server)));
    }

    /** The overworld's next day time; replaces vanilla's "day time + 1" (see ServerLevelTimeMixin). */
    public static long advance(long current) {
        return CLOCK.advance(current);
    }

    /** The overworld's day time to a fraction of a tick. */
    public static double exactDayTime(MinecraftServer server) {
        return CLOCK.exact(server.overworld().getDayTime());
    }

    private static void tick(MinecraftServer server) {
        if (!CLOCK.consumeJump() && server.getTickCount() % SYNC_TICKS != 0) return;
        DayClockPayload payload = payload(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) send(player, payload);
    }

    private static DayClockPayload payload(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        boolean running = overworld.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT);
        return new DayClockPayload(running ? exactDayTime(server) : overworld.getDayTime(), running);
    }

    private static void send(ServerPlayer player, DayClockPayload payload) {
        if (ServerPlayNetworking.canSend(player, DayClockPayload.TYPE)) ServerPlayNetworking.send(player, payload);
    }
}
