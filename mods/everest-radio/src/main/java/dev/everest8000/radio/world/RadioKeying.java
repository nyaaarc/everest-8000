package dev.everest8000.radio.world;

import dev.everest8000.radio.EverestRadio;
import dev.everest8000.radio.RadioConfig;
import dev.everest8000.radio.net.RadioKeyedPayload;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Who is talking into a radio right now, told to everyone who can see them (and to the talker) so their clients raise
 * the radio to the talker's face. The voice thread reports every microphone frame from a player holding a radio; once
 * a tick the server thread counts a player as keyed while they still hold a radio and their last frame is less than
 * {@link RadioConfig#KEY_HANG_MS} old, so pauses between words don't make the arm bob. /radio key forces the pose for
 * testing and filming, radio or not.
 */
public final class RadioKeying {
    private static final Map<UUID, Long> VOICE = new ConcurrentHashMap<>(); // last voice frame (nanoTime) per holder

    private final long hangNanos;
    private final Map<UUID, Long> forcedUntil = new HashMap<>(); // tick a forced key ends; Long.MAX_VALUE: until "off"
    private final Set<UUID> keyed = new HashSet<>();
    private long ticks;

    public RadioKeying(RadioConfig config) {
        this.hangNanos = TimeUnit.MILLISECONDS.toNanos(config.getInt(RadioConfig.KEY_HANG_MS));
    }

    /** Simple Voice Chat's packet thread: a voice frame from a player holding a radio. */
    public static void onVoice(UUID player) {
        VOICE.put(player, System.nanoTime());
    }

    public void tick(MinecraftServer server) {
        ticks++;
        long now = System.nanoTime();
        boolean refresh = ticks % RadioKeyedPayload.REFRESH_TICKS == 0;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            boolean on = isForced(p.getUUID()) || talking(p, now);
            if (on ? keyed.add(p.getUUID()) : keyed.remove(p.getUUID())) {
                broadcast(p, on);
            } else if (on && refresh) {
                broadcast(p, true); // clients drop a pose that isn't refreshed
            }
        }
        forcedUntil.values().removeIf(until -> until <= ticks);
        VOICE.values().removeIf(t -> now - t > hangNanos);
    }

    /** Someone came into view of {@code viewer} (sent before the spawn packet, so keyed by entity id). */
    public void onStartTracking(Entity entity, ServerPlayer viewer) {
        if (entity instanceof ServerPlayer player && keyed.contains(player.getUUID())) {
            send(viewer, new RadioKeyedPayload(player.getId(), true));
        }
    }

    public void forget(UUID player) {
        keyed.remove(player);
        forcedUntil.remove(player);
        VOICE.remove(player);
    }

    /** Holds the pose for {@code seconds}, or until {@link #release} when seconds is infinite. */
    public void force(ServerPlayer player, double seconds) {
        forcedUntil.put(player.getUUID(), Double.isInfinite(seconds) ? Long.MAX_VALUE : ticks + Math.max(1, Math.round(seconds * 20)));
    }

    public void release(ServerPlayer player) {
        forcedUntil.remove(player.getUUID());
    }

    public Set<UUID> keyed() {
        return Set.copyOf(keyed);
    }

    public static void shutdown() {
        VOICE.clear();
    }

    public boolean isForced(UUID player) {
        Long until = forcedUntil.get(player);
        return until != null && until > ticks;
    }

    private boolean talking(ServerPlayer p, long now) {
        Long last = VOICE.get(p.getUUID());
        return last != null && now - last <= hangNanos && p.isAlive() && !p.isSpectator() && EverestRadio.holdsRadio(p);
    }

    private static void broadcast(ServerPlayer player, boolean on) {
        RadioKeyedPayload payload = new RadioKeyedPayload(player.getId(), on);
        send(player, payload);
        for (ServerPlayer viewer : PlayerLookup.tracking(player)) {
            send(viewer, payload);
        }
    }

    private static void send(ServerPlayer to, RadioKeyedPayload payload) {
        if (ServerPlayNetworking.canSend(to, RadioKeyedPayload.TYPE)) {
            ServerPlayNetworking.send(to, payload);
        }
    }
}
