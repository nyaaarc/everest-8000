package dev.everest8000.expedition.time;

/**
 * The overworld's clock to a fraction of a tick. Vanilla adds one whole day tick per tick; this adds
 * {@link DayPace#rate} and hands back the whole part, so the world's own day time (which commands, beds, the sky and
 * other mods read) runs at the paced speed.
 *
 * <p>If the world's day time is no longer what this clock last wrote, something else moved it (a {@code /time}
 * command, everyone sleeping through the night, another mod): the clock picks up from there and reports a jump, so
 * clients can be told straight away instead of at the next regular sync.
 */
public final class DayClock {
    private double exact = Double.NaN;
    private long written = Long.MIN_VALUE;
    private boolean jumped;

    /** The next day time, given the world's current one. Called once per tick while the daylight cycle runs. */
    public long advance(long current) {
        if (current != written || Double.isNaN(exact)) {
            exact = current;
            jumped = true;
        }
        exact += DayPace.rate(exact);
        written = (long) Math.floor(exact);
        return written;
    }

    /** The fractional day time behind {@code current}, or {@code current} itself if this clock didn't write it. */
    public double exact(long current) {
        return current == written ? exact : current;
    }

    /** True once after the clock was moved from outside (or first started). */
    public boolean consumeJump() {
        boolean j = jumped;
        jumped = false;
        return j;
    }

    public void reset() {
        exact = Double.NaN;
        written = Long.MIN_VALUE;
        jumped = false;
    }
}
