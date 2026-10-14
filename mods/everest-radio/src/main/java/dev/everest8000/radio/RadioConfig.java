package dev.everest8000.radio;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * Everything about the radio that can be tuned, as named settings with their defaults. The server reads overrides from
 * config/everest_radio.properties (written with the defaults on first start); restart the server after editing it.
 * Plain Java with no Minecraft types, so the signal and audio code can be tested offline.
 */
public final class RadioConfig {
    private static final List<Setting> SETTINGS = new ArrayList<>();

    // --- Signal quality (SignalModel). Degradation runs from 0 (perfectly clear) to 1 (the worst the radio gets:
    // the voice faint and broken up in static, words mostly lost, but something always comes through).
    public static final Setting CLEAR_RANGE = setting("clear_range_blocks", 1200, 1, 1_000_000,
            "Perfectly clear inside this distance (blocks), as long as no terrain is in the way.");
    public static final Setting MAX_RANGE = setting("max_range_blocks", 5500, 2, 10_000_000,
            "Distance (blocks) where the distance penalty peaks. Degradation from distance grows with the",
            "logarithm of distance between clear_range_blocks and this, then stays put. At 5500 Base Camp to Camp 2",
            "(2.8 km) gets 0.50 from distance alone and Base Camp to the summit (4.4 km) 0.77; terrain adds the rest.");
    public static final Setting DISTANCE_MAX_DEGRADATION = setting("distance_max_degradation", 0.90, 0, 1,
            "Degradation from distance alone at max_range_blocks (0..1).");
    public static final Setting OCCLUSION_MAX_DEGRADATION = setting("occlusion_max_degradation", 0.20, 0, 1,
            "Extra degradation when terrain blocks the line of sight, added to the distance part (total capped at 1).",
            "Kept small: distance decides how rough a call is, a ridge in the way adds some static.");
    public static final Setting OCCLUSION_FULL_FRACTION = setting("occlusion_full_fraction", 1.0, 0.01, 1,
            "Fraction of the straight path between the radios that lies below ground and gives the full occlusion",
            "penalty. Less than that scales with the square root: at 1, a path a quarter underground gets half.");

    // --- Occlusion sampling (Occlusion, TerrainHeights)
    public static final Setting SAMPLE_SPACING = setting("sample_spacing_blocks", 1.0, 0.25, 1000,
            "Spacing (blocks) of terrain samples along the path; long paths use max_samples spread evenly instead.");
    public static final Setting MAX_SAMPLES = setting("max_samples", 256, 8, 4096,
            "Most terrain samples per pair of radios.");
    public static final Setting ENDPOINT_SKIP = setting("endpoint_skip_blocks", 2.0, 0, 64,
            "Path length (blocks) next to each player that is not sampled, so standing against a wall doesn't count.");
    public static final Setting UPDATE_INTERVAL_TICKS = setting("update_interval_ticks", 4, 1, 200,
            "How often (ticks) who holds or carries a radio, and the signal between them, are re-checked.");
    public static final Setting MAX_DISK_READS = setting("max_disk_reads_in_flight", 4, 1, 64,
            "Terrain between distant players is often not loaded; its heightmap is read from the region files a",
            "few chunks at a time, through the server's own chunk I/O. This caps the reads in flight.");
    public static final Setting MAX_CACHED_CHUNKS = setting("max_cached_chunks", 65_536, 1024, 4_000_000,
            "Chunk heightmaps (16 heights each) kept from those reads; the cache starts over when it is full.");

    // --- Radio sound (RadioVoiceChain). Each effect grows linearly from its onset degradation to its value at
    // degradation 1. Levels marked "relative" are fractions of the transmitted voice level (about 0.15 RMS).
    public static final Setting BAND_LOW_HZ = setting("band_low_hz", 300, 50, 2000,
            "Lower edge of the radio's voice band (Hz) when the signal is clear.");
    public static final Setting BAND_HIGH_HZ = setting("band_high_hz", 3000, 1000, 12000,
            "Upper edge of the radio's voice band (Hz) when the signal is clear.");
    public static final Setting SATURATION_DRIVE = setting("saturation_drive", 1.6, 1, 10,
            "Soft clipping of the voice after the transmitter's leveler; 1 is almost clean, higher is grittier.");
    public static final Setting OPEN_CHANNEL_HISS = setting("open_channel_hiss", 0.002, 0, 0.1,
            "Faint static (RMS) under every transmission even when perfectly clear, so an open channel is never",
            "dead silent (about 37 dB below the voice).");
    public static final Setting NOISE_AT_MAX = setting("noise_at_max", 0.8, 0, 4,
            "Static floor at degradation 1 (relative). It grows with degradation^noise_curve.");
    public static final Setting NOISE_CURVE = setting("noise_curve", 1.5, 0.25, 4,
            "Shape of the static floor's growth.");
    public static final Setting SIGNAL_LOSS_AT_MAX = setting("signal_loss_at_max", 0.5, 0, 0.95,
            "How much weaker the received voice is at degradation 1. The receiver's gain control brings the total",
            "back up, so a weak signal is about as loud, but the voice is fainter inside the static.");
    public static final Setting AGC_MAX_GAIN = setting("agc_max_gain", 3.0, 1, 20,
            "Most the receiver's gain control can boost; it decides how loud the static gets when the signal drops.");
    public static final Setting FADE_ONSET = setting("fade_onset", 0.1, 0, 1,
            "Degradation where slow fading starts (the signal swelling and sinking, static breathing with it).");
    public static final Setting FADE_DEPTH_AT_MAX = setting("fade_depth_at_max", 0.7, 0, 0.99,
            "Depth of that fading at degradation 1 (fraction of the signal).");
    public static final Setting CUT_ONSET = setting("cut_onset", 0.12, 0, 1,
            "Degradation where cuts start: the signal drops out for parts of words or whole syllables, in bursts,",
            "and the receiver fills the gap with static.");
    public static final Setting CUT_RATE_AT_MAX = setting("cut_rate_at_max", 4, 0, 50,
            "Cuts per second at degradation 1 (on average; they come in clusters).");
    public static final Setting CUT_MIN_MS = setting("cut_min_ms", 40, 5, 2000,
            "Average cut length (ms) just past the onset.");
    public static final Setting CUT_MAX_MS = setting("cut_max_ms", 220, 5, 5000,
            "Average cut length (ms) at degradation 1.");
    public static final Setting CUT_DEPTH = setting("cut_depth", 0.02, 0, 1,
            "Signal left during a cut (fraction). A little gets through; the static covers the rest.");
    public static final Setting GRIT_ONSET = setting("grit_onset", 0.1, 0, 1,
            "Degradation where noise starts tearing at the voice itself (the voice modulated by noise).");
    public static final Setting GRIT_AT_MAX = setting("grit_at_max", 1.0, 0, 4,
            "Depth of that modulation at degradation 1; 1 means the voice's level swings by its full size.");
    public static final Setting RIDE_AT_MAX = setting("ride_at_max", 0.8, 0, 4,
            "Static that swells with the voice, so static bursts happen inside words (relative, at degradation 1).");
    public static final Setting BAND_LOW_AT_MAX_HZ = setting("band_low_at_max_hz", 500, 50, 4000,
            "The band's lower edge at degradation 1 (Hz): weak signals lose their body...");
    public static final Setting BAND_HIGH_AT_MAX_HZ = setting("band_high_at_max_hz", 1700, 500, 12000,
            "...and their top (Hz), so they sound muffled and thin.");
    public static final Setting NOTCH_ONSET = setting("notch_onset", 0.2, 0, 1,
            "Degradation where frequency-selective fading starts: a notch slowly sweeping through the voice band.");
    public static final Setting NOTCH_DEPTH_AT_MAX_DB = setting("notch_depth_at_max_db", 24, 0, 60,
            "Depth of that notch (dB) at degradation 1.");
    public static final Setting CLIP_ONSET = setting("clip_onset", 0.15, 0, 1,
            "Degradation where the receiver starts clipping (hard, harsh distortion).");
    public static final Setting CLIP_LEVEL_AT_MAX = setting("clip_level_at_max", 0.1, 0.01, 1,
            "Clipping threshold at degradation 1, as a fraction of a clear signal's peaks.");
    public static final Setting DECIMATE_ONSET = setting("decimate_onset", 0.6, 0, 1,
            "Degradation where the sample rate starts dropping (aliasing, a grainy metallic edge).");
    public static final Setting DECIMATE_AT_MAX = setting("decimate_at_max", 6, 1, 48,
            "Sample-rate reduction factor at degradation 1 (6 means 8 kHz).");
    public static final Setting BITS_ONSET = setting("bits_onset", 0.65, 0, 1,
            "Degradation where the resolution starts dropping.");
    public static final Setting BITS_AT_MAX = setting("bits_at_max", 5, 2, 16,
            "Bits of resolution left at degradation 1.");
    public static final Setting WOW_ONSET = setting("wow_onset", 0.5, 0, 1,
            "Degradation where the pitch starts to wobble (wow).");
    public static final Setting WOW_DEPTH_AT_MAX_MS = setting("wow_depth_at_max_ms", 0.8, 0, 10,
            "Depth of that wobble at degradation 1, as a swing in delay (ms).");
    public static final Setting WHISTLE_ONSET = setting("whistle_onset", 0.7, 0, 1,
            "Degradation where an occasional heterodyne whistle starts.");
    public static final Setting WHISTLE_RATE_AT_MAX = setting("whistle_rate_at_max", 0.2, 0, 10,
            "Whistles per second at degradation 1 (each 0.4 to 1.5 s, gliding in pitch).");
    public static final Setting WHISTLE_LEVEL = setting("whistle_level", 0.35, 0, 4,
            "Whistle level (relative) at degradation 1.");
    public static final Setting CRACKLE_RATE_AT_MAX = setting("crackle_rate_at_max", 20, 0, 500,
            "Crackles per second at degradation 1; scales with degradation squared, and is four times that in cuts.");
    public static final Setting CRACKLE_LEVEL = setting("crackle_level", 2.0, 0, 4,
            "Level of a crackle burst before the radio band filter, which takes off most of it.");
    public static final Setting SQUELCH_LEVEL = setting("squelch_level", 0.075, 0, 1,
            "Level (RMS) of the squelch bursts when a transmission starts and ends; 0 turns them off.");
    public static final Setting SQUELCH_OPEN_MS = setting("squelch_open_ms", 40, 0, 1000,
            "Length (ms) of the burst laid over the start of a transmission.");
    public static final Setting SQUELCH_TAIL_MS = setting("squelch_tail_ms", 100, 0, 2000,
            "Length (ms) of the burst played after a transmission ends; it fades away over that time.");
    public static final Setting END_TIMEOUT_MS = setting("end_timeout_ms", 250, 60, 5000,
            "A transmission also ends after this long (ms) without voice packets, in case the stop packet is lost.");

    // --- Raising the radio to the face (RadioKeying)
    public static final Setting KEY_HANG_MS = setting("key_hang_ms", 600, 0, 5000,
            "Everyone sees a talker raise their radio to their face. It stays up this long (ms) after their voice",
            "stops, so pauses between words don't make the arm bob, then comes down.");

    private final double[] values;

    private RadioConfig(double[] values) {
        this.values = values;
    }

    public static RadioConfig defaults() {
        return new RadioConfig(SETTINGS.stream().mapToDouble(Setting::defaultValue).toArray());
    }

    public double get(Setting setting) {
        return values[setting.index];
    }

    public int getInt(Setting setting) {
        return (int) Math.round(values[setting.index]);
    }

    /** A copy with one setting changed (for tests). */
    public RadioConfig with(Setting setting, double value) {
        double[] copy = values.clone();
        copy[setting.index] = setting.clamp(value);
        return new RadioConfig(copy);
    }

    public static List<Setting> settings() {
        return Collections.unmodifiableList(SETTINGS);
    }

    /**
     * Reads the file, falling back to defaults for missing or bad values (reported to warn), and rewrites it with
     * every setting and its description if anything was missing.
     */
    public static RadioConfig load(Path file, Consumer<String> warn) {
        Properties props = new Properties();
        boolean exists = Files.exists(file);
        if (exists) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(r);
            } catch (IOException e) {
                warn.accept("Couldn't read " + file + ", using defaults: " + e);
            }
        }
        double[] values = new double[SETTINGS.size()];
        boolean missing = false;
        for (Setting s : SETTINGS) {
            String raw = props.getProperty(s.key);
            double v = s.defaultValue;
            if (raw == null) {
                missing = true;
            } else {
                try {
                    v = Double.parseDouble(raw.trim());
                    if (v != s.clamp(v)) {
                        warn.accept(s.key + "=" + raw.trim() + " is outside " + s.min + ".." + s.max + "; clamped");
                        v = s.clamp(v);
                    }
                } catch (NumberFormatException e) {
                    warn.accept(s.key + "=" + raw + " is not a number; using " + s.format(s.defaultValue));
                    v = s.defaultValue;
                }
            }
            values[s.index] = v;
        }
        RadioConfig config = new RadioConfig(values);
        if (missing) {
            try {
                Files.createDirectories(file.getParent());
                try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    config.write(w);
                }
            } catch (IOException e) {
                warn.accept("Couldn't write " + file + ": " + e);
            }
        }
        return config;
    }

    void write(Writer w) throws IOException {
        w.write("# Everest Radio server settings. Restart the server after editing.\n");
        w.write("# Degradation runs from 0 (perfectly clear) to 1 (the voice broken up in static, words mostly lost);\n");
        w.write("# the radio never goes silent while someone transmits.\n");
        for (Setting s : SETTINGS) {
            w.write("\n");
            for (String line : s.comment) {
                w.write("# " + line + "\n");
            }
            w.write("# Default " + s.format(s.defaultValue) + "\n");
            w.write(s.key + "=" + s.format(values[s.index]) + "\n");
        }
    }

    private static Setting setting(String key, double defaultValue, double min, double max, String... comment) {
        Setting s = new Setting(SETTINGS.size(), key, defaultValue, min, max, Arrays.asList(comment));
        SETTINGS.add(s);
        return s;
    }

    public record Setting(int index, String key, double defaultValue, double min, double max, List<String> comment) {
        double clamp(double v) {
            return Math.max(min, Math.min(max, v));
        }

        String format(double v) {
            return v == Math.rint(v) && Math.abs(v) < 1e15 ? Long.toString((long) v) : String.format(Locale.ROOT, "%s", v);
        }
    }
}
