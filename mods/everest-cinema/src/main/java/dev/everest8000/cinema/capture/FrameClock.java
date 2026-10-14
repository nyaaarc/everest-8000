package dev.everest8000.cinema.capture;

import net.minecraft.Util;
import net.minecraft.util.TimeSource;

/**
 * The game's clock ({@link Util#getNanos()}), wrapped so that a recording can step it by exactly one frame per rendered
 * frame. Everything that animates from Util time (the tick timer, Iris's frameTimeCounter, HUD animations) then runs
 * in recording time, however slowly frames actually render. Outside a recording it is the game's own clock minus an
 * offset, so it never jumps when a recording ends.
 *
 * <p>Minecraft installs its own (GLFW) time source in its constructor, after mod initialisers have run, so
 * {@link #ensureInstalled()} is called every frame and wraps whatever source is current.
 */
public final class FrameClock {
    private static final TimeSource.NanoTimeSource SELF = FrameClock::now;
    private static volatile TimeSource.NanoTimeSource real = System::nanoTime;
    private static volatile boolean virtual;
    private static volatile long virtualNanos;
    private static volatile long offset;

    private FrameClock() {}

    /** Puts this clock in front of the game's time source if something replaced it. Render thread. */
    public static void ensureInstalled() {
        TimeSource.NanoTimeSource current = Util.timeSource;
        if (current == SELF) return;
        // Keep reading the game's own source so the time stays continuous.
        real = current;
        offset = 0;
        Util.timeSource = SELF;
    }

    public static boolean installed() {
        return Util.timeSource == SELF;
    }

    public static long now() {
        return virtual ? virtualNanos : real.getAsLong() - offset;
    }

    public static boolean isVirtual() {
        return virtual;
    }

    /** Freezes the clock at the current time; it then only moves through {@link #setVirtual}. */
    public static synchronized long enterVirtual() {
        ensureInstalled();
        virtualNanos = now();
        virtual = true;
        return virtualNanos;
    }

    public static void setVirtual(long nanos) {
        if (nanos > virtualNanos) virtualNanos = nanos;
    }

    /** Back to real time, continuing from the virtual time reached. */
    public static synchronized void exitVirtual() {
        if (!virtual) return;
        offset = real.getAsLong() - virtualNanos;
        virtual = false;
    }
}
