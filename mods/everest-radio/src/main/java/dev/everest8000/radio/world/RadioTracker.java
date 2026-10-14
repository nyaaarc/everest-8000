package dev.everest8000.radio.world;

import dev.everest8000.radio.EverestRadio;
import dev.everest8000.radio.RadioConfig;
import dev.everest8000.radio.signal.Occlusion;
import dev.everest8000.radio.signal.SignalModel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Works out on the server thread, a few times a second, who is holding a radio (transmitters), who carries one
 * (receivers) and the signal between each pair, and publishes it as {@link RadioLinks} for the voice thread.
 * Signal checks need the world (terrain heights), which may only be touched from the server thread.
 */
public final class RadioTracker {
    private static volatile RadioLinks current = RadioLinks.NONE;

    private final RadioConfig config;
    private final TerrainHeights terrain;
    private final int interval;
    private int ticks;

    public RadioTracker(RadioConfig config) {
        this.config = config;
        this.terrain = new TerrainHeights(config);
        this.interval = config.getInt(RadioConfig.UPDATE_INTERVAL_TICKS);
    }

    /** The latest links; safe from any thread. */
    public static RadioLinks current() {
        return current;
    }

    public void tick(MinecraftServer server) {
        terrain.startReads(server.getAllLevels());
        if (++ticks % interval != 0) {
            return;
        }
        List<ServerPlayer> carriers = new ArrayList<>();
        List<ServerPlayer> holders = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.isSpectator() || !p.isAlive()) {
                continue;
            }
            boolean holds = EverestRadio.holdsRadio(p);
            if (holds || p.getInventory().contains(EverestRadio::isRadio) || EverestRadio.isRadio(p.containerMenu.getCarried())) {
                carriers.add(p);
            }
            if (holds) {
                holders.add(p);
            }
        }
        if (holders.isEmpty()) {
            current = RadioLinks.NONE;
            return;
        }
        // The signal is the same both ways, so each pair is measured once.
        Map<Long, Float> pairs = new HashMap<>();
        Map<UUID, Map<UUID, Float>> links = new HashMap<>();
        for (ServerPlayer sender : holders) {
            int si = carriers.indexOf(sender);
            Map<UUID, Float> receivers = new HashMap<>();
            for (int ri = 0; ri < carriers.size(); ri++) {
                if (ri == si) {
                    continue;
                }
                ServerPlayer receiver = carriers.get(ri);
                long key = (long) Math.min(si, ri) << 32 | Math.max(si, ri);
                receivers.put(receiver.getUUID(), pairs.computeIfAbsent(key, k -> degradation(sender, receiver)));
            }
            links.put(sender.getUUID(), Map.copyOf(receivers));
        }
        current = new RadioLinks(Map.copyOf(links));
    }

    public float degradation(ServerPlayer a, ServerPlayer b) {
        if (a.serverLevel() != b.serverLevel()) {
            return SignalModel.CROSS_DIMENSION;
        }
        Vec3 pa = a.getEyePosition(), pb = b.getEyePosition();
        return SignalModel.degradation(pa.distanceTo(pb), probe(a.serverLevel(), pa, pb).fraction(), config);
    }

    /** Terrain between two points (server thread only). */
    public Occlusion.Result probe(ServerLevel level, Vec3 a, Vec3 b) {
        return Occlusion.between(a.x, a.y, a.z, b.x, b.y, b.z, (x, z) -> terrain.groundY(level, x, z), config);
    }

    public RadioConfig config() {
        return config;
    }

    public void close() {
        current = RadioLinks.NONE;
        terrain.clear();
    }
}
