package dev.everest8000.expedition.weather;

import dev.everest8000.expedition.time.DayPace;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeatherModelTest {
    private static final int STEP = 20;
    private static final int TICKS_PER_HOUR = 72000;

    /** Runs 50 hours of play and checks storms come often enough to matter but leave room to climb. */
    @Test
    void stormTimingOverFiftyHours() {
        WeatherModel model = new WeatherModel();
        RandomSource random = RandomSource.create(8849);
        int hours = 50;
        List<int[]> storms = new ArrayList<>();
        int stormStart = -1;
        float peakSeen = 0f;
        long showerTicks = 0, summitWindowTicks = 0, stormyTicks = 0;
        float jetMin = Float.MAX_VALUE, jetMax = 0f;
        for (long t = 0; t < (long) hours * TICKS_PER_HOUR; t += STEP) {
            model.step(t, STEP, random);
            WeatherField f = model.field();
            boolean stormy = model.phase() != WeatherModel.Phase.CALM;
            if (stormy && stormStart < 0) { stormStart = (int) t; peakSeen = 0f; }
            if (stormy) { peakSeen = Math.max(peakSeen, f.storm()); stormyTicks += STEP; }
            if (!stormy && stormStart >= 0) { storms.add(new int[]{stormStart, (int) t - stormStart, (int) (peakSeen * 100)}); stormStart = -1; }
            if (f.shower() > 0.05f) showerTicks += STEP;
            if (f.windKmhAt(WeatherField.SUMMIT_Y) < 45f && !stormy) summitWindowTicks += STEP;
            jetMin = Math.min(jetMin, f.jetKmh());
            jetMax = Math.max(jetMax, f.jetKmh());
        }
        double perHour = storms.size() / (double) hours;
        double meanMinutes = storms.stream().mapToInt(s -> s[1]).average().orElse(0) / 1200.0;
        System.out.printf("%d storms in %d h (%.2f/h), mean length %.1f min, stormy %.0f%% of the time%n",
                storms.size(), hours, perHour, meanMinutes, 100.0 * stormyTicks / (hours * (double) TICKS_PER_HOUR));
        System.out.printf("valley showers %.0f%% of the time, summit windows (< 45 km/h, no storm) %.0f%%, jet %.0f..%.0f km/h%n",
                100.0 * showerTicks / (hours * (double) TICKS_PER_HOUR), 100.0 * summitWindowTicks / (hours * (double) TICKS_PER_HOUR), jetMin, jetMax);
        for (int[] s : storms.subList(0, Math.min(8, storms.size()))) {
            System.out.printf("  storm at %5.1f min, lasting %4.1f min, peak %.2f%n", s[0] / 1200.0, s[1] / 1200.0, s[2] / 100.0);
        }
        assertTrue(perHour > 0.6 && perHour < 2.0, "storms per hour " + perHour);
        assertTrue(meanMinutes > 8 && meanMinutes < 25, "mean storm minutes " + meanMinutes);
        assertTrue(summitWindowTicks > 0, "there should be summit windows");
    }

    @Test
    void saveRoundTrip() {
        WeatherModel model = new WeatherModel();
        RandomSource random = RandomSource.create(1);
        model.begin(0.8f, 100f, 600, 6000, 1200);
        for (int t = 0; t < 2000; t += STEP) model.step(t, STEP, random);
        WeatherModel copy = WeatherModel.load(model.save(new CompoundTag()));
        assertEquals(model.field(), copy.field());
        assertEquals(model.phase(), copy.phase());
    }

    /** With the daylight cycle stopped (or the time jumping), showers still end. */
    @Test
    void showersEndWhenTimeStands() {
        RandomSource random = RandomSource.create(3);
        WeatherModel forced = new WeatherModel();
        forced.forceShower(0.7f, 2400, -200f);
        forced.step(1000, STEP, random);
        assertEquals(0.7f, forced.field().shower(), 1e-3, "a forced shower starts at full strength");
        assertEquals(-200f, forced.field().showerTopY(), 1e-3);
        for (int t = 0; t < 2400 + 1200; t += STEP) forced.step(1000, STEP, random);
        assertEquals(0f, forced.field().shower(), 1e-3, "and ends on real ticks with the clock stopped");

        WeatherModel daily = new WeatherModel();
        long frozenAt = -1;
        for (long t = 0; t < 30L * 24000 && frozenAt < 0; t += STEP) {
            daily.step(t, STEP, random);
            if (daily.field().shower() > 0.2f) frozenAt = t;
        }
        assertTrue(frozenAt >= 0, "some day has a shower");
        for (int t = 0; t < 8000; t += STEP) daily.step(frozenAt, STEP, random);
        assertEquals(0f, daily.field().shower(), 1e-3, "a daily shower also ends if the clock stops inside it");
    }

    /**
     * The jet blows from the west-north-west between storms, so the summit plume streams out to the right of the
     * summit as seen from Kala Patthar and Tengboche; storms swing it round to the south-west.
     */
    @Test
    void prevailingJetShowsThePlumeSideOnFromTheTrek() {
        WeatherModel model = new WeatherModel();
        RandomSource random = RandomSource.create(29);
        double calmX = 0, calmZ = 0, stormX = 0, stormZ = 0;
        long calm = 0, inProfile = 0;
        // Bearings (degrees clockwise from north) from each viewpoint to the summit in this map.
        double kalaPatthar = bearing(15760, 7430, 20716, 7776);
        double tengboche = bearing(12627, 15660, 20716, 7776);
        for (long t = 0; t < 50L * 72000; t += STEP) {
            model.step(t, STEP, random);
            WeatherField f = model.field();
            if (model.phase() == WeatherModel.Phase.CALM) {
                calm++;
                calmX += f.windX();
                calmZ += f.windZ();
                double plume = Math.toDegrees(f.windAngle()) + 90.0;
                double offKp = wrap(plume - kalaPatthar), offTeng = wrap(plume - tengboche);
                if (offKp > 5 && offKp < 60 && offTeng > 30 && offTeng < 110) inProfile++;
            } else if (model.phase() == WeatherModel.Phase.PEAK) {
                stormX += f.windX();
                stormZ += f.windZ();
            }
        }
        double calmBearing = Math.toDegrees(Math.atan2(calmZ, calmX)) + 90.0;
        double stormBearing = Math.toDegrees(Math.atan2(stormZ, stormX)) + 90.0;
        System.out.printf("plume streams toward %.0f deg between storms (%.0f%% of that time side-on from both "
                + "Kala Patthar at %.0f and Tengboche at %.0f), toward %.0f deg in storms%n",
                calmBearing, 100.0 * inProfile / calm, kalaPatthar, tengboche, stormBearing);
        assertEquals(115.0, calmBearing, 6.0, "between storms the wind blows toward the ESE");
        assertEquals(60.0, stormBearing, 12.0, "storm winds blow toward the NE");
        assertTrue(inProfile > calm * 0.5, "the plume is side-on from the trek most of the time");
    }

    /**
     * With the hour-long day, valley showers still come as often per real hour as they did with vanilla's 20-minute
     * day (0.45 a day then: three days, so 1.35 an hour).
     */
    @Test
    void showersPerRealHourMatchTheVanillaDay() {
        double vanillaPerHour = WeatherModel.SHOWER_CHANCE * (72000.0 / 24000.0);
        int hours = 300;
        WeatherModel model = new WeatherModel();
        RandomSource random = RandomSource.create(41);
        double dayTime = 0;
        int showers = 0;
        boolean falling = false;
        for (long t = 0; t < hours * 72000L; t += STEP) {
            for (int i = 0; i < STEP; i++) dayTime += DayPace.rate(dayTime);
            model.step((long) dayTime, STEP, random);
            boolean now = model.field().shower() > 0.01f;
            if (now && !falling) showers++;
            falling = now;
        }
        double perHour = showers / (double) hours;
        System.out.printf("%d showers in %d real hours: %.2f an hour (vanilla day: %.2f), %d rolls a day at %.2f%n",
                showers, hours, perHour, vanillaPerHour, WeatherModel.SHOWER_SLOTS, WeatherModel.SLOT_CHANCE);
        assertEquals(vanillaPerHour, perHour, 0.15, "showers per real hour");
    }

    @Test
    void windFromBearing() {
        WeatherModel model = new WeatherModel();
        model.setWindFrom(295f);
        // From the WNW: blowing toward the ESE, so east (+x) and a little south (+z).
        assertEquals(Math.cos(Math.toRadians(25)), model.field().windX(), 1e-3);
        assertEquals(Math.sin(Math.toRadians(25)), model.field().windZ(), 1e-3);
        model.setWindFrom(270f);
        assertEquals(1f, model.field().windX(), 1e-3, "from the west blows toward +x");
    }

    private static double bearing(double fromX, double fromZ, double toX, double toZ) {
        return Math.toDegrees(Math.atan2(toX - fromX, -(toZ - fromZ)));
    }

    private static double wrap(double degrees) {
        return ((degrees % 360) + 540) % 360 - 180;
    }

    @Test
    void altitudeShape() {
        WeatherField f = new WeatherField(0f, 100f, 1f, 300f, 0.6f, -500f);
        assertEquals(0f, f.stormAt(62), 1e-3, "base camp below the storm");
        assertEquals(1f, f.stormAt(1193), 1e-3, "camp 3 in it");
        assertEquals(0.6f, f.showerAt(-1285), 1e-3, "lukla in the shower");
        assertEquals(0f, f.showerAt(62), 1e-3, "base camp above the shower");
        assertTrue(f.windKmhAt(2031) > 170f, "summit gets jet plus storm");
        assertTrue(f.windKmhAt(-1285) < 20f, "lukla is sheltered");
    }
}
