package dev.everest8000.radio.dsp;

import de.maxhenkel.opus4j.OpusDecoder;
import de.maxhenkel.opus4j.OpusEncoder;
import dev.everest8000.radio.RadioConfig;
import dev.everest8000.radio.signal.SignalModel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Renders speech through the radio the way a receiver hears it, at a range of distances and amounts of terrain:
 * the sender's Opus (as the voice arrives at the server), the radio chain (opening squelch, voice, closing squelch),
 * then the receiver's Opus. Measures each result and writes WAVs, a report and a README to the output folder.
 * Real recordings come from output/sources (see the README); the Windows TTS sample is always included.
 */
final class RadioRenderer {
    static final int LEAD_FRAMES = 10, TRAIL_FRAMES = 10; // 200 ms of open channel around the speech
    static final long[] SEEDS = {11, 22, 33, 44, 55};
    static final Source TTS = new Source("tts", "Windows text-to-speech (Microsoft David), the earlier test input",
            null, false);

    record Source(String name, String what, Path file, boolean wavs) {
    }

    record Scenario(String name, String what, double distance, double occlusion) {
    }

    record Result(Source source, Scenario scenario, float degradation, double intel, double stoi, double envCorr, double speechDb,
                  double pauseDb, double cutPct, double cutsPerS, double cracklesPerS, int quietMs) {
    }

    // Distances and blocked fractions of the route's own links come from tools/radio_balance.py.
    static final List<Scenario> SCENARIOS = List.of(
            new Scenario("good_100m_open", "100 m, line of sight", 100, 0),
            new Scenario("good_1km_open", "1 km, line of sight (inside the 1.2 km clear range)", 1000, 0),
            new Scenario("good_500m_small_ridge", "500 m, small ridge between (5% of the path below ground)", 500, 0.05),
            new Scenario("good_1km_ridge", "1 km, behind a ridge (20% below ground)", 1000, 0.20),
            new Scenario("fair_2km_open", "2 km, line of sight", 2000, 0),
            new Scenario("fair_icefall_1800m", "1.8 km over the Icefall, Base Camp to Camp 1 (59% below ground)", 1790, 0.59),
            new Scenario("mid_3km_open", "3 km, line of sight", 3000, 0),
            new Scenario("mid_cwm_2800m", "2.8 km into the Western Cwm, Base Camp to Camp 2 (73% below ground)", 2816, 0.73),
            new Scenario("poor_summit_4400m", "4.4 km, Base Camp to the summit (line of sight)", 4446, 0),
            new Scenario("poor_8km_open", "8 km, line of sight (the distance penalty peaks at 5.5 km)", 8000, 0),
            new Scenario("worst_6km_through_mountain", "6 km, through a mountain (60% below ground)", 6000, 0.60),
            new Scenario("worst_17km_lukla", "17 km, Lukla to Base Camp through the massif (82% below ground)", 17_000, 0.82));

    static List<Source> sources(Path out) {
        Path s = out.resolve("sources");
        List<Source> list = new ArrayList<>();
        list.add(new Source("harvard_f1", "Harvard sentences, female (Open Speech Repository OSR_us_000_0010, 0.2-16.6 s)",
                s.resolve("OSR_us_000_0010_8k/harvard_f1_48k.wav"), true));
        list.add(new Source("harvard_m1", "Harvard sentences, male (Open Speech Repository OSR_us_000_0030, 1.7-20.0 s)",
                s.resolve("OSR_us_000_0030_8k/harvard_m1_48k.wav"), true));
        list.add(new Source("librivox", "LibriVox, The Gift of the Magi (O. Henry), read by Betsie Bush, 5:02.2-5:19.6",
                s.resolve("librivox_gift_of_the_magi/librivox_magi_48k.wav"), true));
        list.add(new Source("harvard_f2", "Harvard sentences, female (Open Speech Repository OSR_us_000_0011, 0.9-18.9 s)",
                s.resolve("OSR_us_000_0011_8k/harvard_f2_48k.wav"), false));
        list.add(new Source("harvard_m2", "Harvard sentences, male (Open Speech Repository OSR_us_000_0057, 0.7-16.9 s)",
                s.resolve("OSR_us_000_0057_8k/harvard_m2_48k.wav"), false));
        list.add(TTS);
        list.removeIf(src -> src.file() != null && !Files.exists(src.file()));
        return list;
    }

    /**
     * @param full every voice, three seeds, WAVs (into out, replacing the old ones); otherwise only the TTS sample
     *             with one seed and no files, which is quick enough for every build
     */
    static List<Result> render(Path out, RadioConfig config, boolean full) throws IOException {
        return render(out, config, full, full);
    }

    static List<Result> render(Path out, RadioConfig config, boolean full, boolean writeWavs) throws IOException {
        long[] seeds = full ? SEEDS : new long[]{SEEDS[0]};
        Files.createDirectories(out);
        if (writeWavs) {
            try (Stream<Path> old = Files.list(out)) {
                for (Path p : old.filter(p -> p.toString().endsWith(".wav")).toList()) {
                    Files.delete(p);
                }
            }
        }
        List<Result> results = new ArrayList<>();
        for (Source src : full ? sources(out) : List.of(TTS)) {
            short[] speech = load(src);
            int frames = LEAD_FRAMES + (speech.length + Audio.FRAME - 1) / Audio.FRAME + TRAIL_FRAMES;
            short[] dry = new short[frames * Audio.FRAME];
            System.arraycopy(speech, 0, dry, LEAD_FRAMES * Audio.FRAME, speech.length);
            short[] received = new Codec().roundTrip(dry);
            boolean wavs = writeWavs && src.wavs();
            if (wavs) {
                Audio.writeWav(out.resolve(src.name() + "_00_dry_input.wav"), dry);
            }
            // Frames by the dry speech: talking (within 25 dB of the loudest frame) and true pauses (40 dB down
            // with their neighbours, away from the opening squelch).
            double peak = 0;
            for (int f = 0; f < frames; f++) peak = Math.max(peak, Audio.frameRms(dry, f));
            boolean[] talk = new boolean[frames], pause = new boolean[frames];
            for (int f = 0; f < frames; f++) {
                talk[f] = Audio.frameRms(dry, f) > peak * 0.056;
                boolean quiet = f >= 4 && f < frames - 2;
                for (int g = f - 2; g <= f + 2 && quiet; g++) quiet = Audio.frameRms(dry, g) < peak * 0.01;
                pause[f] = quiet;
            }
            int delay = -1;
            double seconds = frames * (double) Audio.FRAME / Audio.RATE;
            for (Scenario sc : SCENARIOS) {
                float d = SignalModel.degradation(sc.distance(), sc.occlusion(), config);
                // The cuts and fades are random, and where they land against the words moves the scores, so each
                // measure is the mean over a few seeds. The WAV is the first.
                double[] m = new double[10];
                for (int k = 0; k < seeds.length; k++) {
                    Call call = transmit(config, received, frames, d, seeds[k]);
                    short[] radio = call.radio();
                    RadioVoiceChain stats = call.chain();
                    if (delay < 0) {
                        delay = Audio.delay(dry, radio, 3000);
                    }
                    double[] v = {Audio.intelligibility(dry, radio, delay), Audio.stoi(dry, radio, delay),
                            envCorr(dry, radio, delay, talk), Audio.db(Audio.rms(radio, talk)),
                            Audio.db(Audio.rms(radio, pause)), 100.0 * stats.cutSamples() / (frames * Audio.FRAME),
                            stats.cuts() / seconds, stats.crackles() / seconds, 0, 0};
                    for (int i = 0; i < 8; i++) m[i] += v[i] / seeds.length;
                    m[8] = Math.max(m[8], Audio.longestQuietMs(radio, 0, frames * Audio.FRAME, -60));
                    if (k == 0 && wavs) {
                        Audio.writeWav(out.resolve(src.name() + "_" + sc.name() + ".wav"), radio);
                    }
                }
                results.add(new Result(src, sc, d, m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7], (int) m[8]));
            }
        }
        return results;
    }

    record Call(short[] radio, RadioVoiceChain chain) {
    }

    static Call transmit(RadioConfig config, short[] received, int frames, float d, long seed) {
        RadioVoiceChain chain = new RadioVoiceChain(config, seed);
        Codec codec = new Codec();
        chain.beginTransmission();
        int total = frames + chain.tailFrames();
        short[] radio = new short[total * Audio.FRAME];
        short[] in = new short[Audio.FRAME], out = new short[Audio.FRAME];
        for (int f = 0; f < total; f++) {
            if (f < frames) {
                System.arraycopy(received, f * Audio.FRAME, in, 0, Audio.FRAME);
                chain.processVoice(in, d, out);
            } else {
                chain.renderTail(f - frames, d, out);
            }
            System.arraycopy(codec.receiver(out), 0, radio, f * Audio.FRAME, Audio.FRAME);
        }
        return new Call(radio, chain);
    }

    /** Correlation of the 10 ms loudness envelopes of the dry and the radio speech over the talking frames. */
    private static double envCorr(short[] dry, short[] radio, int delay, boolean[] talk) {
        short[] shifted = new short[dry.length];
        System.arraycopy(radio, Math.min(delay, radio.length), shifted, 0, Math.min(dry.length, radio.length - delay));
        return Audio.envelopeCorrelation(dry, shifted, talk);
    }

    private static short[] load(Source src) throws IOException {
        if (src.file() == null) {
            try (InputStream in = RadioRenderer.class.getResourceAsStream("/radio-test-speech.wav")) {
                if (in == null) throw new IOException("missing test speech resource");
                return Audio.readWav(in);
            }
        }
        try (InputStream in = Files.newInputStream(src.file())) {
            return Audio.readWav(in);
        }
    }

    /** Mean of a measure per scenario over the real recordings (everything but the TTS). */
    static Map<Scenario, double[]> realSpeechMeans(List<Result> results) {
        Map<Scenario, double[]> means = new LinkedHashMap<>();
        for (Scenario sc : SCENARIOS) {
            List<Result> rs = results.stream().filter(r -> r.scenario() == sc && r.source().file() != null).toList();
            if (rs.isEmpty()) rs = results.stream().filter(r -> r.scenario() == sc).toList();
            means.put(sc, new double[]{
                    rs.stream().mapToDouble(Result::intel).average().orElse(Double.NaN),
                    rs.stream().mapToDouble(Result::intel).min().orElse(Double.NaN),
                    rs.stream().mapToDouble(Result::intel).max().orElse(Double.NaN),
                    rs.stream().mapToDouble(Result::envCorr).average().orElse(Double.NaN),
                    rs.stream().mapToDouble(Result::speechDb).average().orElse(Double.NaN),
                    rs.stream().mapToDouble(Result::pauseDb).average().orElse(Double.NaN),
                    rs.stream().mapToDouble(Result::cutPct).average().orElse(Double.NaN),
                    rs.stream().mapToInt(Result::quietMs).max().orElse(0),
                    rs.stream().mapToDouble(Result::stoi).average().orElse(Double.NaN)});
        }
        return means;
    }

    static String summary(List<Result> results) {
        StringBuilder r = new StringBuilder();
        r.append(String.format(Locale.ROOT, "%-27s %5s  %-17s %5s %5s %7s %7s %6s %7s%n", "scenario", "degr",
                "intel mean (range)", "STOI", "env", "speech", "pause", "cut%", "silent"));
        for (Map.Entry<Scenario, double[]> e : realSpeechMeans(results).entrySet()) {
            double[] m = e.getValue();
            float d = results.stream().filter(x -> x.scenario() == e.getKey()).findFirst().orElseThrow().degradation();
            r.append(String.format(Locale.ROOT, "%-27s %5.2f  %5.2f (%5.2f-%4.2f) %5.2f %5.2f %7.1f %7.1f %6.1f %5.0fms%n",
                    e.getKey().name(), d, m[0], m[1], m[2], m[8], m[3], m[4], m[5], m[6], m[7]));
        }
        return r.toString();
    }

    static String table(List<Result> results) {
        StringBuilder r = new StringBuilder();
        r.append(String.format(Locale.ROOT, "%-11s %-27s %5s %5s %5s %5s %7s %7s %5s %6s %6s %6s%n", "source", "scenario",
                "degr", "intel", "STOI", "env", "speech", "pause", "cut%", "cuts/s", "crk/s", "silent"));
        for (Result x : results) {
            r.append(String.format(Locale.ROOT, "%-11s %-27s %5.2f %5.2f %5.2f %5.2f %7.1f %7.1f %5.1f %6.2f %6.1f %4dms%n",
                    x.source().name(), x.scenario().name(), x.degradation(), x.intel(), x.stoi(), x.envCorr(), x.speechDb(),
                    x.pauseDb(), x.cutPct(), x.cutsPerS(), x.cracklesPerS(), x.quietMs()));
        }
        return r.toString();
    }

    static String readme(List<Result> results, RadioConfig config, double[] cpu, double[] envelope, double[] pitch) {
        StringBuilder r = new StringBuilder();
        r.append("Everest Radio: offline listening test\n");
        r.append("=====================================\n\n");
        r.append("Each file is one radio call as a receiver hears it: the sender's voice after their Opus encoder,\n");
        r.append("the radio chain (opening squelch, the call, closing squelch) at the given distance and terrain,\n");
        r.append("then the receiver's Opus. Push-to-talk is held for the whole call, with 200 ms of open channel\n");
        r.append("before and after the speech.\n\n");
        r.append("Files: <voice>_<scenario>.wav, plus <voice>_00_dry_input.wav (the input, for comparison).\n");
        r.append("Scenario names start with the condition: good, fair, mid, poor, worst.\n\n");
        r.append("Voices:\n");
        for (Source s : results.stream().map(Result::source).distinct().toList()) {
            r.append(String.format(Locale.ROOT, "  %-11s %s%s%n", s.name(), s.what(), s.wavs() ? "" : "  [measured only]"));
        }
        r.append("\nScenarios (degradation 0 = perfectly clear, 1 = the worst the radio gets):\n");
        for (Scenario sc : SCENARIOS) {
            float d = SignalModel.degradation(sc.distance(), sc.occlusion(), config);
            r.append(String.format(Locale.ROOT, "  %-27s degradation %.2f  %s%n", sc.name(), d, sc.what()));
        }
        r.append("\nWhat you'll hear:\n");
        r.append("  good   a clean radio voice (300-3000 Hz, a little honk and saturation), a faint open-channel hiss\n");
        r.append("         30-40 dB down between words, a squelch click and burst at both ends.\n");
        r.append("  fair   a static floor about 15 dB under the voice, short cuts filled with static (about 6% of\n");
        r.append("         the call), crackles, slow fading, the band starting to close.\n");
        r.append("  mid    static about 5 dB under the voice and riding inside the words, cuts in irregular clusters\n");
        r.append("         (about 15% of the call), a narrower, muffled band, random fading with the static breathing\n");
        r.append("         up, a notch wandering through the voice, clipping, some pitch wobble. Words come and go;\n");
        r.append("         hard to follow, but you get it (Base Camp to Camp 2).\n");
        r.append("  poor   static as loud as the voice in the gaps, 15-25% cut, harsh clipping, grainy, an occasional\n");
        r.append("         whistle; a word here and there (Base Camp to the summit).\n");
        r.append("  worst  static as loud as everything, 20-35% cut, the voice faint inside it and broken into\n");
        r.append("         fragments, grainy (down to about 8 kHz and 5 bits), pitch wobble, an occasional\n");
        r.append("         heterodyne whistle. Something always comes through; the words mostly don't.\n");
        r.append("\nMeasures:\n");
        r.append("  intel   intelligibility index: correlation of the call's 1/3-octave band envelopes (15 bands from\n");
        r.append("          150 Hz) with the dry input's over 384 ms windows. It is STOI (Taal et al. 2011) without\n");
        r.append("          STOI's clipping step, which ties pure noise to the speech envelope and gives it 0.2-0.4;\n");
        r.append("          here pure noise scores about 0 and intact speech 1. The targets are on this measure:\n");
        r.append("          good above 0.85, fair 0.5-0.75, mid 0.3-0.5, poor 0.12-0.33, worst 0.05-0.2.\n");
        r.append("  STOI    the same with STOI's clipping, for reference.\n");
        r.append("  env     correlation of the 10 ms loudness envelope with the dry input while talking.\n");
        r.append("  speech  RMS level while talking (dBFS); pause = RMS in pauses (the static floor).\n");
        r.append("  cut%    share of the call spent in cuts (signal dropouts, filled with static).\n");
        r.append("  silent  longest stretch of 10 ms blocks below -60 dBFS while transmitting (0 = never silent).\n\n");
        r.append("Mean over the real recordings (harvard_f1, harvard_f2, harvard_m1, harvard_m2, librivox):\n");
        r.append(summary(results));
        r.append(String.format(Locale.ROOT, "%nRandomness check (worst case, 120 s of a steady voice-band signal through "
                + "the channel):%n"
                + "  loudness envelopes (broadband and four bands, the worst of them): autocorrelation peak %.2f, in 6 s%n"
                + "  windows %.2f, tallest modulation-spectrum peak %.1f dB above its surroundings (at %.2f Hz)%n"
                + "  pitch (a 1 kHz tone through the wow): autocorrelation peak %.2f, in 6 s windows %.2f, tallest%n"
                + "  modulation peak %.1f dB (at %.2f Hz), wobble %.0f cents RMS%n"
                + "  The previous, sinusoidal version measured 0.18-0.23 / 0.21-0.38 for the envelopes and 0.24-0.41 /%n"
                + "  0.64-0.70 for the pitch. Peaks of about 3 dB at scattered frequencies are estimation noise.%n",
                envelope[0], envelope[3], envelope[1], envelope[2], pitch[0], pitch[3], pitch[1], pitch[2], pitch[4]));
        r.append(String.format(Locale.ROOT, "%nCPU on this PC: the radio chain takes %.0f us per 20 ms frame per listener when "
                + "clear and %.0f us at the worst; the Opus encode %.0f us. One talker heard by 9 listeners at the worst "
                + "costs about %.1f ms per 20 ms (%.0f%% of one core).%n", cpu[0], cpu[1], cpu[2],
                9 * (cpu[1] + cpu[2]) / 1000, 9 * (cpu[1] + cpu[2]) / 1000 / 20 * 100));
        r.append("\nEvery voice and scenario (each measure the mean of 5 runs with different random cuts and fades):\n");
        r.append(table(results));
        r.append("\nSources and terms:\n");
        r.append("  Harvard sentences: Open Speech Repository, https://www.voiptroubleshooter.com/open_speech/\n");
        r.append("    American English, 8 kHz 16-bit. Terms: freely available for VoIP testing, research and\n");
        r.append("    development, may be copied and modified; the source must be identified as\n");
        r.append("    \"Open Speech Repository\".\n");
        r.append("  LibriVox: The Gift of the Magi by O. Henry, read by Betsie Bush,\n");
        r.append("    https://archive.org/details/giftofmagi (gift_of_the_magi_henry_blb.mp3). LibriVox recordings are\n");
        r.append("    public domain (in the USA; https://librivox.org/pages/public-domain/), the archive.org item is\n");
        r.append("    marked public domain, and the 1905 text is public domain.\n");
        r.append("  Originals are in sources/<name>/; the *_48k.wav files are the excerpts used, resampled to 48 kHz\n");
        r.append("    mono (ffmpeg, soxr) and peak-normalized to -3 dBFS.\n");
        return r.toString();
    }

    /**
     * The channel on its own at a degradation: a steady voice-band signal sent for the given time, so the only
     * movement in the output is the channel's (fades, cuts, static, crackle, the notch), through the receiver's
     * Opus. Returns the periodicity of its loudness envelope (see {@link Audio#periodicity}).
     */
    static double[] channelPeriodicity(RadioConfig config, float d, int seconds, long seed, Path wav) throws IOException {
        int frames = seconds * 50;
        // A steady voice-band signal at speaking level, so what moves in the output is the channel, not speech.
        short[] probe = new short[frames * Audio.FRAME];
        java.util.Random r = new java.util.Random(seed);
        Biquad hp = Biquad.highPass(Audio.RATE, 300, 0.707), lp = Biquad.lowPass(Audio.RATE, 3000, 0.707);
        for (int i = 0; i < probe.length; i++) {
            probe[i] = (short) Math.max(-32768, Math.min(32767, lp.process(hp.process((float) r.nextGaussian())) * 9000));
        }
        Call call = transmit(config, probe, frames, d, seed);
        if (wav != null) {
            Audio.writeWav(wav, java.util.Arrays.copyOf(call.radio(), Math.min(call.radio().length, 20 * Audio.RATE)));
        }
        return Audio.bandPeriodicity(call.radio(), 4 * Audio.FRAME, frames * Audio.FRAME);
    }

    /** The wow alone at a degradation: a steady 1 kHz tone through a channel with every other effect off. */
    static double[] wowPeriodicity(RadioConfig config, float d, int seconds, long seed) {
        RadioConfig wowOnly = config;
        for (RadioConfig.Setting s : List.of(RadioConfig.OPEN_CHANNEL_HISS, RadioConfig.NOISE_AT_MAX,
                RadioConfig.SIGNAL_LOSS_AT_MAX, RadioConfig.FADE_DEPTH_AT_MAX, RadioConfig.CUT_RATE_AT_MAX,
                RadioConfig.GRIT_AT_MAX, RadioConfig.RIDE_AT_MAX, RadioConfig.NOTCH_DEPTH_AT_MAX_DB,
                RadioConfig.WHISTLE_LEVEL, RadioConfig.CRACKLE_RATE_AT_MAX)) {
            wowOnly = wowOnly.with(s, s.min());
        }
        wowOnly = wowOnly.with(RadioConfig.CLIP_LEVEL_AT_MAX, 1).with(RadioConfig.DECIMATE_AT_MAX, 1)
                .with(RadioConfig.BITS_AT_MAX, 16).with(RadioConfig.SQUELCH_LEVEL, 0)
                .with(RadioConfig.BAND_LOW_AT_MAX_HZ, 150).with(RadioConfig.BAND_HIGH_AT_MAX_HZ, 6000);
        int frames = seconds * 50;
        short[] tone = new short[frames * Audio.FRAME];
        for (int i = 0; i < tone.length; i++) tone[i] = (short) (3000 * Math.sin(2 * Math.PI * 1000 * i / Audio.RATE));
        RadioVoiceChain chain = new RadioVoiceChain(wowOnly, seed);
        short[] out = new short[tone.length], in = new short[Audio.FRAME], buf = new short[Audio.FRAME];
        for (int f = 0; f < frames; f++) {
            System.arraycopy(tone, f * Audio.FRAME, in, 0, Audio.FRAME);
            chain.processVoice(in, d, buf);
            System.arraycopy(buf, 0, out, f * Audio.FRAME, Audio.FRAME);
        }
        return Audio.pitchPeriodicity(out, Audio.RATE, out.length, 1000);
    }

    /** Microseconds per 20 ms frame: the radio chain when clear and at the worst, and one Opus encode. */
    static double[] benchmark(RadioConfig config) {
        java.util.Random r = new java.util.Random(5);
        short[][] inputs = new short[50][Audio.FRAME];
        for (short[] frame : inputs) {
            for (int i = 0; i < frame.length; i++) frame[i] = (short) (r.nextGaussian() * 3000);
        }
        short[] out = new short[Audio.FRAME];
        double[] us = new double[3];
        Codec codec = new Codec();
        for (int which = 0; which < 2; which++) {
            RadioVoiceChain chain = new RadioVoiceChain(config, which + 1);
            float d = which == 0 ? 0 : 1;
            for (int round = 0; round < 2; round++) { // the first round warms up the JIT
                long t0 = System.nanoTime();
                for (int f = 0; f < 3000; f++) {
                    chain.processVoice(inputs[f % inputs.length], d, out);
                    if (which == 1) codec.receiver(out);
                }
                us[which] = (System.nanoTime() - t0) / 3000.0 / 1000;
            }
        }
        us[2] = codec.encodeNanos / (double) codec.encodes / 1000;
        us[1] -= us[2]; // that loop also encoded
        return us;
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "test-output");
        boolean wavs = args.length > 1 && args[1].equals("wavs");
        RadioConfig config = RadioConfig.defaults();
        for (int i = 2; i < args.length; i++) { // key=value overrides, for tuning
            String[] kv = args[i].split("=");
            RadioConfig.Setting s = RadioConfig.settings().stream().filter(x -> x.key().equals(kv[0])).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("no setting " + kv[0]));
            config = config.with(s, Double.parseDouble(kv[1]));
        }
        if (args.length > 1 && args[1].equals("periodicity")) {
            for (long seed : SEEDS) {
                double[] p = channelPeriodicity(config, 1f, 120, seed, null);
                double[] w = wowPeriodicity(config, 1f, 120, seed);
                System.out.printf(Locale.ROOT, "seed %d: envelopes (broadband and 4 bands, worst of): autocorr %.2f, "
                        + "6 s windows %.2f, modulation peak %.1f dB at %.2f Hz | pitch: autocorr %.2f, 6 s %.2f, "
                        + "modulation peak %.1f dB at %.2f Hz, wobble %.0f cents RMS%n",
                        seed, p[0], p[3], p[1], p[2], w[0], w[3], w[1], w[2], w[4]);
            }
            return;
        }
        long t0 = System.nanoTime();
        List<Result> results = render(out, config, wavs || (args.length > 1 && args[1].equals("measure")), wavs);
        if (wavs) {
            System.out.println(table(results));
        }
        System.out.println(summary(results));
        System.out.printf(Locale.ROOT, "%.1f s%n", (System.nanoTime() - t0) / 1e9);
    }

    /** opus4j, the native Opus Simple Voice Chat uses, set up like its server encoder (VOIP, 48 kHz mono). */
    static final class Codec {
        private final OpusEncoder encoder;
        private final OpusDecoder decoder;
        long encodeNanos;
        int encodes;

        Codec() {
            try {
                encoder = new OpusEncoder(Audio.RATE, 1, OpusEncoder.Application.VOIP);
                encoder.setMaxPayloadSize(1024);
                decoder = new OpusDecoder(Audio.RATE, 1);
                decoder.setFrameSize(Audio.FRAME);
            } catch (Exception e) {
                throw new IllegalStateException("Opus natives unavailable", e);
            }
        }

        String version() {
            return encoder.getOpusVersion();
        }

        short[] roundTrip(short[] pcm) {
            short[] out = new short[pcm.length], frame = new short[Audio.FRAME];
            for (int f = 0; f < pcm.length / Audio.FRAME; f++) {
                System.arraycopy(pcm, f * Audio.FRAME, frame, 0, Audio.FRAME);
                System.arraycopy(receiver(frame), 0, out, f * Audio.FRAME, Audio.FRAME);
            }
            return out;
        }

        short[] receiver(short[] frame) {
            long t0 = System.nanoTime();
            byte[] packet = encoder.encode(frame);
            encodeNanos += System.nanoTime() - t0;
            encodes++;
            return decoder.decode(packet);
        }
    }
}
