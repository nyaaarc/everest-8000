package dev.everest8000.expedition.client;

import dev.everest8000.expedition.time.DayPace;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

/**
 * This client's copy of the paced day (see {@link DayPace}). Vanilla's client adds a whole day tick every tick and
 * snaps back to the server's time once a second, which on a slowed day would drag the sun forward and yank it back.
 * Instead this clock runs at the server's pace to a fraction of a tick, and the once-a-second syncs only steer it:
 * small differences are worked off over about a second by running a little faster or slower (never backwards), big
 * ones (a {@code /time} command, a night slept through) are taken at once. The sun and moon are drawn from the
 * fractional time, carried forward to the frame, so they glide instead of stepping 20 times a second.
 *
 * <p>Only active once the server has sent its clock, so on any other server the day runs as vanilla.
 */
public final class ClientClock {
    /** Differences beyond this many day ticks are jumps, taken at once. */
    private static final double SNAP = 200;
    /** Share of the remaining difference worked off each tick, at most half the normal pace either way. */
    private static final double STEER = 0.1;

    private static boolean paced;
    private static boolean running;
    private static double exact;
    /** The server's time, carried forward at the same pace since it was last heard. */
    private static double target;
    private static double lastStep;

    private ClientClock() {}

    static void receive(double serverTime, boolean serverRunning) {
        boolean jump = !paced || !serverRunning || Math.abs(serverTime - exact) > SNAP;
        target = serverTime;
        running = serverRunning;
        paced = true;
        if (jump) {
            exact = serverTime;
            lastStep = 0;
            Minecraft mc = Minecraft.getInstance();
            if (running && mc.level != null) mc.level.setDayTime((long) Math.floor(exact));
        }
    }

    static void reset() {
        paced = false;
        running = false;
        lastStep = 0;
    }

    /** True while the day is paced by the server and the daylight cycle runs. */
    public static boolean paced() {
        return paced && running;
    }

    /** One client tick: the day time vanilla should store, in place of its own "day time + 1". */
    public static long tick(long vanillaNext) {
        if (!paced()) return vanillaNext;
        double rate = DayPace.rate(exact);
        target += DayPace.rate(target);
        double steer = Mth.clamp((target - exact) * STEER, -0.5 * rate, 0.5 * rate);
        lastStep = rate + steer;
        exact += lastStep;
        return (long) Math.floor(exact);
    }

    /** The day time a vanilla time packet should leave the world at: this clock's, while it runs. */
    public static long fromServer(long packetDayTime) {
        return paced() && packetDayTime >= 0 ? (long) Math.floor(exact) : packetDayTime;
    }

    /** The sun's angle for this frame, from the fractional time carried forward by the frame's share of a tick. */
    public static float timeOfDay(float partialTick) {
        return DayPace.timeOfDay(exact + partialTick * lastStep);
    }

    /** The fractional day time, for tests and debugging. */
    public static double exactDayTime() {
        return exact;
    }
}
