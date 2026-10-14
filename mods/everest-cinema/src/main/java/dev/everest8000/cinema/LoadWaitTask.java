package dev.everest8000.cinema;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

/**
 * Waits until the chunks around the camera are loaded and built: every visible section compiled and the loaded chunk
 * count unchanged for a second. Distant Horizons has no public "done" signal, so with includeLods it then waits
 * lodSettleSec more. Finishes with status "loaded" or "timeout" (never an error), plus the numbers.
 */
public final class LoadWaitTask extends Task {
    private final double timeoutSec;
    private final boolean includeLods;
    private final double lodSettleSec;
    private int lastCount = -1;
    private long stableSince;
    private long loadedAt;
    private boolean timedOut;

    public LoadWaitTask(double timeoutSec, boolean includeLods, double lodSettleSec) {
        super("world.waitLoaded");
        this.timeoutSec = timeoutSec;
        this.includeLods = includeLods;
        this.lodSettleSec = lodSettleSec;
    }

    public static boolean sectionsDone(Minecraft mc) {
        return mc.levelRenderer.hasRenderedAllSections();
    }

    public static int loadedChunks(Minecraft mc) {
        return mc.level == null ? 0 : mc.level.getChunkSource().getLoadedChunksCount();
    }

    @Override
    public boolean update(Minecraft mc) {
        Cinema.requireWorld();
        long now = System.nanoTime();
        if (loadedAt == 0) {
            int count = loadedChunks(mc);
            if (count != lastCount) {
                lastCount = count;
                stableSince = now;
            }
            boolean stable = now - stableSince > 1_000_000_000L;
            if (sectionsDone(mc) && stable) {
                loadedAt = now;
            } else if (elapsedSec() > timeoutSec) {
                timedOut = true;
                loadedAt = now;
            } else {
                return false;
            }
        }
        if (includeLods && !timedOut && (now - loadedAt) / 1e9 < lodSettleSec) return false;
        JsonObject r = new JsonObject();
        r.addProperty("status", timedOut ? "timeout" : "loaded");
        r.addProperty("elapsedSec", Math.round(elapsedSec() * 100) / 100.0);
        r.addProperty("renderedAllSections", sectionsDone(mc));
        r.addProperty("chunksLoaded", loadedChunks(mc));
        r.addProperty("lodSettleSec", includeLods ? lodSettleSec : 0);
        future.complete(r);
        return true;
    }
}
