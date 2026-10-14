package dev.everest8000.expedition.weather;

import dev.everest8000.expedition.time.DayPace;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * The server's weather generator. Three independent systems:
 * <ul>
 *   <li>the jet stream, which wanders between near-calm "summit windows" and hurricane force;</li>
 *   <li>upper-mountain storms, which build for a few minutes, peak, then clear, with a quiet spell after each;</li>
 *   <li>afternoon valley showers, rolled once per Minecraft day.</li>
 * </ul>
 * A shower starts at its time of day but then runs on real ticks, so stopping the daylight cycle or jumping the time
 * (a command, or sleeping through the night) can't leave one stuck on.
 * Kept free of world access so the timing can be simulated in tests.
 */
public final class WeatherModel {
    public enum Phase { CALM, BUILDING, PEAK, CLEARING }

    static final int MEAN_CALM_TICKS = 24000;
    /**
     * Where the jet stream blows toward between storms, in {@link WeatherField#windAngle} terms: the winter jet over
     * Everest comes from the west-north-west, so the summit plume streams east-south-east (bearing 115 degrees). That
     * shows it side-on from the trek's classic viewpoints, Kala Patthar and Tengboche, instead of end-on.
     */
    public static final float PREVAILING_ANGLE = 0.44f;
    /** Storms ride a south-westerly flow: the wind swings round to blow toward the north-east (bearing 60 degrees). */
    public static final float STORM_ANGLE = -0.52f;
    private static final float ANGLE_SPREAD = 0.3f;

    /** Chance of a valley shower per vanilla-length day (20 minutes), as it was before the hour-long day. */
    static final float SHOWER_CHANCE = 0.45f;
    /**
     * Vanilla days of real time in one of ours. The shower roll is made this many times a day (each with the chance
     * scaled to keep the total), so showers come as often per real hour as they did with 20-minute days.
     */
    static final double VANILLA_DAYS_PER_DAY = DayPace.CYCLE_REAL_TICKS / (double) DayPace.DAY_TICKS;
    static final int SHOWER_SLOTS = (int) Math.ceil(VANILLA_DAYS_PER_DAY - 1e-9);
    static final float SLOT_CHANCE = (float) (SHOWER_CHANCE * VANILLA_DAYS_PER_DAY / SHOWER_SLOTS);
    /** Showers start between late morning and late afternoon (day ticks), one roll per slot of that span. */
    private static final int SHOWERS_FROM = 4500;
    private static final int SHOWERS_UNTIL = 11000;
    private static final int SLOT_WIDTH = (SHOWERS_UNTIL - SHOWERS_FROM) / SHOWER_SLOTS;

    float windAngle = PREVAILING_ANGLE;
    float windAngleTarget = PREVAILING_ANGLE;
    float jet = 60f;
    float jetTarget = 60f;
    Phase phase = Phase.CALM;
    int phaseTicks;
    int phaseLength;
    int cooldown = 6000;
    float stormPeak;
    float storm;
    float stormBaseY = 300f;
    int peakTicks;
    int clearTicks;
    long showerDay = Long.MIN_VALUE;
    /** The next of today's shower slots to roll. */
    int showerSlot;
    int showerStart;
    int showerLength;
    /** Real ticks since the current shower began, or -1 while none is falling. */
    int showerTicks = -1;
    float showerPeak;
    float showerTopY = -600f;
    float shower;

    public WeatherField field() {
        return new WeatherField(windAngle, jet, storm, stormBaseY, shower, showerTopY);
    }

    public Phase phase() {
        return phase;
    }

    public void step(long dayTime, int dt, RandomSource random) {
        stepWind(dt, random);
        stepStorm(dt, random);
        stepShowers(dayTime, dt, random);
    }

    private void stepWind(int dt, RandomSource random) {
        if (random.nextFloat() < dt / 6000f) {
            // Pulled toward 80 km/h so it doesn't park at either extreme for long.
            jetTarget = Mth.clamp(jetTarget * 0.6f + 80f * 0.4f + (float) random.nextGaussian() * 45f, 10f, 170f);
        }
        jet = approach(jet, jetTarget, 0.4f * dt / 20f);
        if (random.nextFloat() < dt / 4800f) {
            windAngleTarget = (float) (random.nextGaussian() * ANGLE_SPREAD) + (phase == Phase.CALM ? PREVAILING_ANGLE : STORM_ANGLE);
        }
        windAngle += Mth.clamp(windAngleTarget - windAngle, -0.002f * dt, 0.002f * dt);
    }

    private void stepStorm(int dt, RandomSource random) {
        phaseTicks += dt;
        float progress = phaseLength == 0 ? 1f : Mth.clamp(phaseTicks / (float) phaseLength, 0f, 1f);
        switch (phase) {
            case CALM -> {
                storm = 0f;
                cooldown -= dt;
                if (cooldown <= 0 && random.nextFloat() < dt / (float) MEAN_CALM_TICKS) {
                    begin(0.45f + random.nextFloat() * 0.55f, -250f + random.nextInt(900),
                            2400 + random.nextInt(2400), 6000 + random.nextInt(8400), 2400 + random.nextInt(2400));
                }
            }
            case BUILDING -> {
                storm = stormPeak * smooth(progress);
                if (progress >= 1f) enter(Phase.PEAK, peakTicks);
            }
            case PEAK -> {
                storm = stormPeak * (0.85f + 0.15f * Mth.sin(phaseTicks / 900f));
                if (progress >= 1f) enter(Phase.CLEARING, clearTicks);
            }
            case CLEARING -> {
                storm = stormPeak * (1f - smooth(progress));
                if (progress >= 1f) {
                    enter(Phase.CALM, 0);
                    storm = 0f;
                    cooldown = 12000 + random.nextInt(12000);
                }
            }
        }
    }

    private void stepShowers(long dayTime, int dt, RandomSource random) {
        long day = Math.floorDiv(dayTime, 24000L);
        int timeOfDay = (int) Math.floorMod(dayTime, 24000L);
        if (showerTicks < 0) {
            // A shower that's already falling finishes on its own clock before the next day's are rolled.
            if (day != showerDay) {
                showerDay = day;
                showerSlot = 0;
                showerLength = 0;
            }
            // A planned shower whose time has gone by (the clock jumped past it) is dropped.
            if (showerLength > 0 && timeOfDay >= showerStart + showerLength) showerLength = 0;
            if (showerLength == 0 && showerSlot < SHOWER_SLOTS && timeOfDay >= SHOWERS_FROM + showerSlot * SLOT_WIDTH) {
                int opens = SHOWERS_FROM + showerSlot * SLOT_WIDTH;
                showerSlot++;
                if (random.nextFloat() < SLOT_CHANCE) {
                    showerStart = opens + random.nextInt(SLOT_WIDTH / 2);
                    showerLength = 3000 + random.nextInt(4000);
                    showerPeak = 0.3f + random.nextFloat() * 0.5f;
                    showerTopY = -700f + random.nextInt(500);
                }
            }
            if (showerLength > 0 && timeOfDay >= showerStart && timeOfDay < showerStart + showerLength) {
                showerTicks = timeOfDay - showerStart;
            }
        }
        if (showerTicks < 0) {
            shower = 0f;
            return;
        }
        showerTicks += dt;
        shower = showerPeak * envelope(showerTicks, showerLength, 1200);
        if (showerTicks >= showerLength) {
            // Done, even if the clock is stopped inside the shower's window; the day's next slot can follow.
            showerTicks = -1;
            showerLength = 0;
            shower = 0f;
        }
    }

    /** Starts a storm now: build-up, peak and clearing lengths are in ticks. */
    public void begin(float peak, float baseY, int buildTicks, int peakTicks, int clearTicks) {
        stormPeak = peak;
        stormBaseY = baseY;
        this.peakTicks = peakTicks;
        this.clearTicks = clearTicks;
        float from = storm;
        enter(Phase.BUILDING, buildTicks);
        // A storm forced on top of another keeps building from where the last one was.
        if (from > 0f && peak > 0f) phaseTicks = (int) (buildTicks * Mth.clamp(from / peak, 0f, 1f));
    }

    public void clear(int clearTicks) {
        if (phase == Phase.CALM) return;
        stormPeak = storm;
        enter(Phase.CLEARING, clearTicks);
    }

    public void setJet(float kmh) {
        jet = kmh;
        jetTarget = kmh;
    }

    /** Turns the wind now to blow from a compass bearing (degrees clockwise from north), e.g. 295 for WNW. */
    public void setWindFrom(float bearingDegrees) {
        windAngle = (bearingDegrees + 180f - 90f) * Mth.DEG_TO_RAD;
        windAngle = Mth.wrapDegrees(windAngle * Mth.RAD_TO_DEG) * Mth.DEG_TO_RAD;
        windAngleTarget = windAngle;
    }

    /** Starts a shower at full strength now, lasting {@code lengthTicks} real ticks plus a minute to fade. */
    public void forceShower(float peak, int lengthTicks, float topY) {
        showerTicks = 1200;
        showerLength = lengthTicks + 2400;
        showerPeak = peak;
        showerTopY = topY;
    }

    private void enter(Phase next, int length) {
        phase = next;
        phaseTicks = 0;
        phaseLength = length;
    }

    public int phaseTicksLeft() {
        return Math.max(0, phaseLength - phaseTicks);
    }

    public int cooldown() {
        return Math.max(0, cooldown);
    }

    private static float approach(float value, float target, float maxStep) {
        return value + Mth.clamp(target - value, -maxStep, maxStep);
    }

    private static float smooth(float t) {
        return t * t * (3f - 2f * t);
    }

    /** 0 before start, ramps to 1 over {@code ramp}, holds, ramps back to 0 by start + length. */
    private static float envelope(int sinceStart, int length, int ramp) {
        if (sinceStart <= 0 || sinceStart >= length) return 0f;
        float in = Mth.clamp(sinceStart / (float) ramp, 0f, 1f);
        float out = Mth.clamp((length - sinceStart) / (float) ramp, 0f, 1f);
        return smooth(Math.min(in, out));
    }

    public CompoundTag save(CompoundTag tag) {
        tag.putFloat("windAngle", windAngle);
        tag.putFloat("windAngleTarget", windAngleTarget);
        tag.putFloat("jet", jet);
        tag.putFloat("jetTarget", jetTarget);
        tag.putString("phase", phase.name());
        tag.putInt("phaseTicks", phaseTicks);
        tag.putInt("phaseLength", phaseLength);
        tag.putInt("cooldown", cooldown);
        tag.putFloat("stormPeak", stormPeak);
        tag.putFloat("storm", storm);
        tag.putFloat("stormBaseY", stormBaseY);
        tag.putInt("peakTicks", peakTicks);
        tag.putInt("clearTicks", clearTicks);
        tag.putLong("showerDay", showerDay);
        tag.putInt("showerSlot", showerSlot);
        tag.putInt("showerStart", showerStart);
        tag.putInt("showerLength", showerLength);
        tag.putInt("showerTicks", showerTicks);
        tag.putFloat("showerPeak", showerPeak);
        tag.putFloat("showerTopY", showerTopY);
        tag.putFloat("shower", shower);
        return tag;
    }

    public static WeatherModel load(CompoundTag tag) {
        WeatherModel m = new WeatherModel();
        m.windAngle = tag.getFloat("windAngle");
        m.windAngleTarget = tag.getFloat("windAngleTarget");
        m.jet = tag.getFloat("jet");
        m.jetTarget = tag.getFloat("jetTarget");
        try {
            m.phase = Phase.valueOf(tag.getString("phase"));
        } catch (IllegalArgumentException e) {
            m.phase = Phase.CALM;
        }
        m.phaseTicks = tag.getInt("phaseTicks");
        m.phaseLength = tag.getInt("phaseLength");
        m.cooldown = tag.getInt("cooldown");
        m.stormPeak = tag.getFloat("stormPeak");
        m.storm = tag.getFloat("storm");
        m.stormBaseY = tag.getFloat("stormBaseY");
        m.peakTicks = tag.getInt("peakTicks");
        m.clearTicks = tag.getInt("clearTicks");
        m.showerDay = tag.getLong("showerDay");
        m.showerSlot = tag.getInt("showerSlot");
        m.showerStart = tag.getInt("showerStart");
        m.showerLength = tag.getInt("showerLength");
        m.showerTicks = tag.contains("showerTicks") ? tag.getInt("showerTicks") : -1;
        m.showerPeak = tag.getFloat("showerPeak");
        m.showerTopY = tag.getFloat("showerTopY");
        m.shower = tag.getFloat("shower");
        return m;
    }
}
