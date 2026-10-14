package dev.everest8000.radio.dsp;

import dev.everest8000.radio.RadioConfig;

/**
 * What one receiver's radio makes of one sender's voice, modelled as one degraded radio channel rather than a voice
 * with a hiss track laid over it:
 * <ol>
 *   <li>Transmitter: the voice band (300-3000 Hz), a leveler and light saturation, and a pitch wobble (wow) when the
 *       link is poor.</li>
 *   <li>Channel: the signal's strength (the carrier) sinks and swells (slow fading) and drops out (cuts); noise
 *       tears at the voice (the voice modulated by noise) and swells with it; a static floor, crackles (more of them
 *       in cuts) and an occasional heterodyne whistle are mixed in before the receiver, so everything after treats
 *       them and the voice as one signal.</li>
 *   <li>Receiver: a gain control driven by the carrier, so when the signal falls the static rises and every cut is
 *       filled with static rather than silence; a band that narrows as the signal degrades; a notch drifting through
 *       the band (frequency-selective fading); detector clipping; and sample-rate and bit loss.</li>
 * </ol>
 * Nothing here is periodic: fading, the notch, the wow and the whistle's pitch follow smoothed random processes at
 * several unrelated rates, with random jumps; cuts and crackles come in irregular clusters (self-exciting processes,
 * strongest in random "stormy" stretches), and cut lengths vary widely.
 * <p>
 * Everything scales with the degradation (0 perfectly clear, 1 the worst it gets) from its onset in RadioConfig. At 0
 * only the radio voice and a faint open-channel hiss remain. All state is per instance, so every sender/receiver
 * pair needs its own. Works on 20 ms frames of 48 kHz mono 16-bit audio, Simple Voice Chat's format.
 */
public final class RadioVoiceChain {
    public static final int SAMPLE_RATE = 48_000;
    public static final int FRAME_SIZE = 960;
    private static final int CONTROL = 48; // control-rate block, 1 ms: cuts, fading, notch sweep, gain targets
    private static final float CONTROL_SECONDS = CONTROL / (float) SAMPLE_RATE;
    private static final float TWO_PI = (float) (2 * Math.PI);

    // Transmitter
    private static final double BUTTERWORTH_Q_A = 0.5412, BUTTERWORTH_Q_B = 1.3066; // two sections make 4th order
    private static final double PRESENCE_HZ = 1600, PRESENCE_Q = 0.9, PRESENCE_DB = 4; // the walkie-talkie honk
    private static final float LEVEL_TARGET = 0.30f; // envelope the leveler steers toward
    private static final float LEVEL_STRENGTH = 0.7f; // 1 would flatten everything; 0.7 is about a 3:1 compressor
    private static final float LEVEL_MIN_GAIN = 0.5f, LEVEL_MAX_GAIN = 8f;
    private static final float LEVEL_GATE = 0.004f; // quieter than this is a pause: the gain holds instead of rising
    private static final int LEVEL_UPDATE_SAMPLES = 32;
    private static final float ENV_ATTACK = glide(0.010), ENV_RELEASE = glide(0.300), GAIN_GLIDE = glide(0.050);
    /** The transmitted voice's nominal level (RMS); static levels are relative to it. */
    private static final float VOICE_RMS = 0.15f;
    private static final int WOW_BASE = 96, RING = 512; // the wow delay line: a fixed 2 ms delay plus the wobble
    private static final float WOW_SLOW_HZ = 0.7f, WOW_FAST_HZ = 2.3f; // corner rates of the two random drifts

    // Channel
    private static final double NOISE_LOW_HZ = 250, NOISE_HIGH_HZ = 3800; // band of the static
    private static final double GRIT_HZ = 400; // bandwidth of the noise that modulates the voice
    private static final double CRACKLE_LOW_HZ = 300, CRACKLE_HIGH_HZ = 4500;
    private static final float CARRIER_GLIDE = glide(0.004); // cuts and fades cross-fade over a few ms, no clicks
    // Fading: three random drifts at unrelated rates, weighted like 1/f noise, plus sudden jumps.
    private static final float[] FADE_HZ = {0.12f, 0.5f, 1.8f}, FADE_WEIGHT = {0.7f, 0.55f, 0.35f};
    private static final float FADE_JUMPS_PER_S = 0.5f, FADE_JUMP_SIZE = 0.9f, JUMP_GLIDE = 0.03f; // glide per ms
    // "Weather": a slowly wandering lognormal intensity (mean 1) that scales cuts and crackles, so they bunch up.
    private static final float STORM_SLOW_HZ = 0.06f, STORM_FAST_HZ = 0.3f, STORM_SLOW = 0.9f, STORM_FAST = 0.5f;
    // Cuts and crackles are self-exciting: each one raises the chance of another for a while (a Hawkes process).
    // Branching is the share of events that are such aftershocks; the base rate is lowered to keep the mean.
    private static final float CUT_BRANCHING = 0.5f, CUT_AFTERSHOCK_S = 0.4f;
    private static final float CUT_LENGTH_SPREAD = 0.6f; // lognormal spread of cut lengths (mean kept)
    private static final float CRACKLE_BRANCHING = 0.6f, CRACKLE_AFTERSHOCK_S = 0.06f;
    private static final float CUT_EXCITE_DECAY = (float) Math.exp(-CONTROL_SECONDS / CUT_AFTERSHOCK_S);
    private static final float CRACKLE_EXCITE_DECAY = (float) Math.exp(-CONTROL_SECONDS / CRACKLE_AFTERSHOCK_S);
    private static final float RIDE_ATTACK = glide(0.003), RIDE_RELEASE = glide(0.040);
    private static final float CUT_CRACKLE_BOOST = 4f;
    private static final float CRACKLE_MIN_S = 0.001f, CRACKLE_MAX_S = 0.004f, CRACKLE_DECAY = 2f;
    private static final float WHISTLE_MIN_HZ = 900, WHISTLE_MAX_HZ = 2600, WHISTLE_MIN_S = 0.4f, WHISTLE_MAX_S = 1.5f;
    private static final float WHISTLE_GLIDE = glide(0.010);

    // Receiver
    private static final float AGC_STRENGTH = 0.8f; // < 1: deep fades still pull the voice down a little
    private static final float AGC_ATTACK = glide(0.015), AGC_RELEASE = glide(0.120); // slow release: breathing
    private static final double NOTCH_LOW_HZ = 400, NOTCH_HIGH_HZ = 2800, NOTCH_Q = 1.4;
    private static final float NOTCH_SLOW_HZ = 0.08f, NOTCH_FAST_HZ = 0.35f, NOTCH_JUMPS_PER_S = 0.25f;
    private static final float NOTCH_GLIDE = 0.0125f; // per ms: a jump moves the notch over about 80 ms
    private static final float CLIP_REF = 0.5f; // a clear signal's peaks; clip levels are fractions of it
    private static final float CLIP_MAKEUP = 0.6f; // makeup gain = (1 / clip level)^this
    private static final float DEGRADATION_STEP = 0.03f; // per frame, so a change takes up to about 0.7 s

    // Squelch
    private static final float OPEN_DUCK = 0.8f; // how far the channel dips under the opening burst
    private static final float TAIL_BOOST = 1.25f;
    private static final float CLICK_S = 0.0025f, CLICK_LEVEL = 0.22f;
    private static final float TAIL_ATTACK_S = 0.004f, TAIL_RELEASE_S = 0.010f;
    private static final float TAIL_FADE = 2.3f; // the closing burst falls by e^-this (about 20 dB) over its length
    private static final double SQUELCH_HZ = 2800; // the bursts' top: rounded off, not raw static
    // Over a clear link the bursts are gentler still: quieter, darker and the closing one shorter, easing to the
    // full burst by SQUELCH_SOFT_UNTIL.
    private static final float SQUELCH_SOFT_DB = -4f, SQUELCH_SOFT_UNTIL = 0.4f, TAIL_SOFT_LENGTH = 0.75f;
    private static final double SQUELCH_SOFT_HZ = 2000;

    private static final float NOISE_NORM = noiseNorm(NOISE_LOW_HZ, NOISE_HIGH_HZ);
    private static final float GRIT_NORM = noiseNorm(0, GRIT_HZ);

    // Settings
    private final float drive, openHiss, noiseAtMax, noiseCurve, signalLoss, agcMax, fadeOnset, fadeDepthAtMax;
    private final float cutOnset, cutRateAtMax, cutMinMs, cutMaxMs, cutDepth, gritOnset, gritAtMax, rideAtMax;
    private final double bandLow, bandHigh, bandLowAtMax, bandHighAtMax;
    private final float notchOnset, notchDepthAtMax, clipOnset, clipAtMax, decimateOnset, decimateAtMax;
    private final float bitsOnset, bitsAtMax, wowOnset, wowDepthAtMax, whistleOnset, whistleRateAtMax, whistleLevel;
    private final float crackleRateAtMax, crackleLevel, squelchLevel;
    private final int openSamples, tailSamples, clickSamples;
    private final float openDecay;

    // Filters
    private final Biquad hp1, hp2, lp1, lp2, presence; // transmitter
    private final Biquad noiseHp, noiseLp, gritLp, crackleHp, crackleLp; // channel
    private final Biquad rxHp, rxLp, notch; // receiver
    private final Biquad squelchLp, squelchSoftLp;

    // Per-frame parameters, from the degradation
    private float noiseRel, carrierBase, fadeDepth, cutRate, cutMeanMs, grit, ride, notchDb;
    private float clipLevel, clipMakeup, holdStep, quantStep, wowDepth, whistleChance, whistleAmpMax;
    private float crackleChance, crackleScale;
    private float squelchHarsh, squelchGain;

    // State
    private long rng;
    private float degradation = -1;
    private float env, gain = 1, levelWant = 1;
    private int levelCounter;
    private final float[] ring = new float[RING];
    private int ringPos;
    private float wowDelay = WOW_BASE, wowTarget = WOW_BASE;
    private final Wander wowSlow, wowFast;
    private boolean inCut;
    private float cutLeftMs, cutExcite;
    private final Wander[] fadeDrift = new Wander[FADE_HZ.length];
    private float fadeJump, fadeJumpTarget;
    private final Wander stormSlow, stormFast;
    private float storm = 1;
    private float carrier = 1, carrierTarget = 1, rideEnv, agc = 1, agcTarget = 1;
    private final Wander notchSlow, notchFast;
    private float notchJump, notchJumpTarget;
    private boolean notchOn;
    private final Wander whistleDrift, whistleFlicker;
    private float crackleExcite;
    private float holdPhase, held;
    private int whistleLeft;
    private float whistleAmp, whistleHz = 1500, whistlePhase, whistleFlick = 1;
    private int crackleLeft;
    private float crackleAmp, crackleDecay;
    private int openLeft;
    private float openEnv;
    private int tailLength = -1; // samples, fixed when the closing burst starts
    private float lastNoise;
    private int crackleCount, cutCount;
    private long cutSamples;

    public RadioVoiceChain(RadioConfig c, long seed) {
        drive = f(c, RadioConfig.SATURATION_DRIVE);
        openHiss = f(c, RadioConfig.OPEN_CHANNEL_HISS);
        noiseAtMax = f(c, RadioConfig.NOISE_AT_MAX);
        noiseCurve = f(c, RadioConfig.NOISE_CURVE);
        signalLoss = f(c, RadioConfig.SIGNAL_LOSS_AT_MAX);
        agcMax = f(c, RadioConfig.AGC_MAX_GAIN);
        fadeOnset = f(c, RadioConfig.FADE_ONSET);
        fadeDepthAtMax = f(c, RadioConfig.FADE_DEPTH_AT_MAX);
        cutOnset = f(c, RadioConfig.CUT_ONSET);
        cutRateAtMax = f(c, RadioConfig.CUT_RATE_AT_MAX);
        cutMinMs = f(c, RadioConfig.CUT_MIN_MS);
        cutMaxMs = f(c, RadioConfig.CUT_MAX_MS);
        cutDepth = f(c, RadioConfig.CUT_DEPTH);
        gritOnset = f(c, RadioConfig.GRIT_ONSET);
        gritAtMax = f(c, RadioConfig.GRIT_AT_MAX);
        rideAtMax = f(c, RadioConfig.RIDE_AT_MAX);
        bandLow = c.get(RadioConfig.BAND_LOW_HZ);
        bandHigh = c.get(RadioConfig.BAND_HIGH_HZ);
        bandLowAtMax = c.get(RadioConfig.BAND_LOW_AT_MAX_HZ);
        bandHighAtMax = c.get(RadioConfig.BAND_HIGH_AT_MAX_HZ);
        notchOnset = f(c, RadioConfig.NOTCH_ONSET);
        notchDepthAtMax = f(c, RadioConfig.NOTCH_DEPTH_AT_MAX_DB);
        clipOnset = f(c, RadioConfig.CLIP_ONSET);
        clipAtMax = f(c, RadioConfig.CLIP_LEVEL_AT_MAX);
        decimateOnset = f(c, RadioConfig.DECIMATE_ONSET);
        decimateAtMax = f(c, RadioConfig.DECIMATE_AT_MAX);
        bitsOnset = f(c, RadioConfig.BITS_ONSET);
        bitsAtMax = f(c, RadioConfig.BITS_AT_MAX);
        wowOnset = f(c, RadioConfig.WOW_ONSET);
        wowDepthAtMax = f(c, RadioConfig.WOW_DEPTH_AT_MAX_MS) * SAMPLE_RATE / 1000f;
        whistleOnset = f(c, RadioConfig.WHISTLE_ONSET);
        whistleRateAtMax = f(c, RadioConfig.WHISTLE_RATE_AT_MAX);
        whistleLevel = f(c, RadioConfig.WHISTLE_LEVEL);
        crackleRateAtMax = f(c, RadioConfig.CRACKLE_RATE_AT_MAX);
        crackleLevel = f(c, RadioConfig.CRACKLE_LEVEL);
        squelchLevel = f(c, RadioConfig.SQUELCH_LEVEL);
        openSamples = (int) (c.get(RadioConfig.SQUELCH_OPEN_MS) * SAMPLE_RATE / 1000);
        tailSamples = (int) (c.get(RadioConfig.SQUELCH_TAIL_MS) * SAMPLE_RATE / 1000);
        clickSamples = (int) (CLICK_S * SAMPLE_RATE);
        openDecay = openSamples > 0 ? (float) Math.exp(-3.0 / openSamples) : 0;

        hp1 = Biquad.highPass(SAMPLE_RATE, bandLow, BUTTERWORTH_Q_A);
        hp2 = Biquad.highPass(SAMPLE_RATE, bandLow, BUTTERWORTH_Q_B);
        lp1 = Biquad.lowPass(SAMPLE_RATE, bandHigh, BUTTERWORTH_Q_A);
        lp2 = Biquad.lowPass(SAMPLE_RATE, bandHigh, BUTTERWORTH_Q_B);
        presence = Biquad.peaking(SAMPLE_RATE, PRESENCE_HZ, PRESENCE_Q, PRESENCE_DB);
        noiseHp = Biquad.highPass(SAMPLE_RATE, NOISE_LOW_HZ, 0.707);
        noiseLp = Biquad.lowPass(SAMPLE_RATE, NOISE_HIGH_HZ, 0.707);
        gritLp = Biquad.lowPass(SAMPLE_RATE, GRIT_HZ, 0.707);
        crackleHp = Biquad.highPass(SAMPLE_RATE, CRACKLE_LOW_HZ, 0.707);
        crackleLp = Biquad.lowPass(SAMPLE_RATE, CRACKLE_HIGH_HZ, 0.707);
        rxHp = Biquad.highPass(SAMPLE_RATE, bandLow / 2, 0.707);
        rxLp = Biquad.lowPass(SAMPLE_RATE, bandHigh * 2, 0.707);
        notch = Biquad.peaking(SAMPLE_RATE, 1000, NOTCH_Q, 0);
        squelchLp = Biquad.lowPass(SAMPLE_RATE, SQUELCH_HZ, 0.707);
        squelchSoftLp = Biquad.lowPass(SAMPLE_RATE, SQUELCH_SOFT_HZ, 0.707);
        rng = (seed * 0x9E3779B97F4A7C15L) | 1;
        for (int i = 0; i < FADE_HZ.length; i++) {
            fadeDrift[i] = new Wander(FADE_HZ[i]);
        }
        stormSlow = new Wander(STORM_SLOW_HZ);
        stormFast = new Wander(STORM_FAST_HZ);
        notchSlow = new Wander(NOTCH_SLOW_HZ);
        notchFast = new Wander(NOTCH_FAST_HZ);
        wowSlow = new Wander(WOW_SLOW_HZ);
        wowFast = new Wander(WOW_FAST_HZ);
        whistleDrift = new Wander(0.4f);
        whistleFlicker = new Wander(3f);
    }

    /** Frames the closing squelch burst takes (see {@link #renderTail}): fewer over a clear link. */
    public int tailFrames() {
        return ((tailLength >= 0 ? tailLength : tailLengthAt(degradation)) + FRAME_SIZE - 1) / FRAME_SIZE;
    }

    private int tailLengthAt(float d) {
        float h = d < 0 ? 1 : harshness(d);
        return Math.round(tailSamples * (TAIL_SOFT_LENGTH + (1 - TAIL_SOFT_LENGTH) * h));
    }

    /** The degradation currently heard (it glides toward the target), or -1 before the first frame. */
    public float degradation() {
        return degradation;
    }

    /** Crackles started so far (for tests and diagnostics). */
    public int crackles() {
        return crackleCount;
    }

    /** Cuts started so far (for tests and diagnostics). */
    public int cuts() {
        return cutCount;
    }

    /** Samples spent in cuts so far (for tests and diagnostics). */
    public long cutSamples() {
        return cutSamples;
    }

    /** A transmission is starting: the next frames get the opening squelch burst. */
    public void beginTransmission() {
        openLeft = openSamples;
        openEnv = 1;
        tailLength = -1;
        squelchLp.reset();
        squelchSoftLp.reset();
    }

    /** Back to a fresh receiver, after a transmission has fully ended. */
    public void reset() {
        for (Biquad f : new Biquad[]{hp1, hp2, lp1, lp2, presence, noiseHp, noiseLp, gritLp, crackleHp, crackleLp,
                rxHp, rxLp, notch, squelchLp, squelchSoftLp}) {
            f.reset();
        }
        java.util.Arrays.fill(ring, 0);
        degradation = -1;
        env = 0;
        inCut = false;
        cutExcite = 0;
        crackleExcite = 0;
        carrier = carrierTarget = 1;
        agc = agcTarget = 1;
        rideEnv = 0;
        holdPhase = 0;
        held = 0;
        whistleLeft = 0;
        whistleAmp = 0;
        crackleLeft = 0;
        openLeft = 0;
        tailLength = -1;
    }

    /**
     * One frame of the sender's voice as this receiver hears it.
     *
     * @param in                {@link #FRAME_SIZE} samples of decoded voice
     * @param targetDegradation current signal degradation for this pair (0..1)
     * @param out               {@link #FRAME_SIZE} samples for the receiver
     */
    public void processVoice(short[] in, float targetDegradation, short[] out) {
        setup(step(targetDegradation));
        for (int i = 0; i < FRAME_SIZE; i++) {
            if (i % CONTROL == 0) {
                control(true);
            }
            float y = receive(channel(wow(transmit(in[i] * (1f / 32768f)))));
            if (openLeft > 0) {
                int t = openSamples - openLeft;
                float burst = lastNoise * (squelchLevel / VOICE_RMS) * openEnv;
                if (t < clickSamples) {
                    burst += CLICK_LEVEL * (1 - t / (float) clickSamples) * white();
                }
                // A softer burst ducks the voice less.
                y = y * (1 - OPEN_DUCK * openEnv * (0.5f + 0.5f * squelchHarsh)) + squelch(burst);
                openEnv *= openDecay;
                openLeft--;
            }
            out[i] = toShort(y);
        }
    }

    /**
     * One frame of the closing squelch burst, played after the voice stops: the carrier drops away, the static
     * rushes up and it ends in a click. Over a clear link it is quieter, darker and shorter.
     *
     * @param frame 0 for the first frame after the transmission ended, up to {@link #tailFrames()} - 1
     */
    public void renderTail(int frame, float targetDegradation, short[] out) {
        setup(step(targetDegradation));
        if (frame == 0 || tailLength < 0) {
            tailLength = tailLengthAt(degradation);
            squelchLp.reset();
            squelchSoftLp.reset();
        }
        int attack = (int) (TAIL_ATTACK_S * SAMPLE_RATE), release = (int) (TAIL_RELEASE_S * SAMPLE_RATE);
        for (int i = 0; i < FRAME_SIZE; i++) {
            if (i % CONTROL == 0) {
                control(false);
            }
            int t = frame * FRAME_SIZE + i;
            float y = receive(channel(wow(transmit(0))));
            if (t < tailLength) {
                float shape = Math.min(1f, Math.min(t / (float) Math.max(1, attack),
                        (tailLength - t) / (float) Math.max(1, release)))
                        * (float) Math.exp(-TAIL_FADE * t / (float) tailLength); // dies away, not a slab of static
                float burst = lastNoise * (squelchLevel / VOICE_RMS) * TAIL_BOOST * shape;
                int fromEnd = tailLength - t;
                if (fromEnd <= clickSamples) {
                    burst += CLICK_LEVEL * (fromEnd / (float) clickSamples) * white();
                }
                y = y * shape + squelch(burst);
            } else {
                y = 0; // the squelch has closed
            }
            out[i] = toShort(y);
        }
    }

    // --- per frame -------------------------------------------------------------------------------------------

    private void setup(float d) {
        squelchHarsh = harshness(d);
        squelchGain = (float) Math.pow(10, SQUELCH_SOFT_DB * (1 - squelchHarsh) / 20);
        noiseRel = Math.max(openHiss / VOICE_RMS, noiseAtMax * (float) Math.pow(d, noiseCurve));
        carrierBase = 1 - signalLoss * d;
        fadeDepth = fadeDepthAtMax * ramp(d, fadeOnset);
        float cutRamp = ramp(d, cutOnset);
        cutRate = cutRateAtMax * cutRamp; // per second, on average
        cutMeanMs = cutMinMs + (cutMaxMs - cutMinMs) * cutRamp;
        grit = gritAtMax * ramp(d, gritOnset);
        ride = rideAtMax * ramp(d, gritOnset);
        notchDb = notchDepthAtMax * ramp(d, notchOnset);
        clipLevel = (float) Math.exp(Math.log(clipAtMax) * ramp(d, clipOnset));
        clipMakeup = (float) Math.pow(1 / clipLevel, CLIP_MAKEUP);
        holdStep = 1f / (1 + (decimateAtMax - 1) * ramp(d, decimateOnset));
        float bits = 16 - (16 - bitsAtMax) * ramp(d, bitsOnset);
        quantStep = bits >= 15.9f ? 0 : (float) Math.pow(2, 1 - bits);
        wowDepth = wowDepthAtMax * ramp(d, wowOnset);
        float w = ramp(d, whistleOnset);
        whistleChance = whistleRateAtMax * w * CONTROL_SECONDS;
        whistleAmpMax = whistleLevel * w * VOICE_RMS;
        crackleChance = crackleRateAtMax * d * d / SAMPLE_RATE;
        crackleScale = (float) Math.sqrt(d);
        // The band narrows on a log scale, most of the way by the middle.
        double k = Math.pow(d, 0.7);
        rxHp.setHighPass(SAMPLE_RATE, logLerp(bandLow / 2, bandLowAtMax, k), 0.707);
        rxLp.setLowPass(SAMPLE_RATE, logLerp(bandHigh * 2, bandHighAtMax, k), 0.707);
    }

    // --- every millisecond -----------------------------------------------------------------------------------

    private void control(boolean carrierOn) {
        // The weather: a wandering lognormal intensity (mean 1) that bunches cuts and crackles together.
        float logStorm = STORM_SLOW * stormSlow.next() + STORM_FAST * stormFast.next();
        storm = (float) Math.exp(logStorm - 0.5f * (STORM_SLOW * STORM_SLOW + STORM_FAST * STORM_FAST));
        // Cuts: self-exciting (each cut makes another more likely for a while), with lengths that vary widely
        // around the mean.
        cutExcite *= CUT_EXCITE_DECAY;
        if (inCut) {
            cutLeftMs -= 1000 * CONTROL_SECONDS;
            if (cutLeftMs <= 0) {
                inCut = false;
            }
        } else if (cutRate > 0) {
            float perSecond = cutRate * (1 - CUT_BRANCHING) * storm + cutExcite;
            if (uniform() < perSecond * CONTROL_SECONDS) {
                inCut = true;
                cutCount++;
                cutExcite += CUT_BRANCHING / CUT_AFTERSHOCK_S;
                cutLeftMs = cutMeanMs * (float) Math.exp(CUT_LENGTH_SPREAD * gaussian()
                        - 0.5f * CUT_LENGTH_SPREAD * CUT_LENGTH_SPREAD);
            }
        }
        if (inCut && carrierOn) {
            cutSamples += CONTROL;
        }
        crackleExcite *= CRACKLE_EXCITE_DECAY;
        // Slow fading: random drifts at three unrelated rates plus sudden jumps, squashed into 0..1.
        float drift = 0;
        for (int i = 0; i < FADE_HZ.length; i++) {
            drift += FADE_WEIGHT[i] * fadeDrift[i].next();
        }
        if (uniform() < FADE_JUMPS_PER_S * CONTROL_SECONDS) {
            fadeJumpTarget = FADE_JUMP_SIZE * gaussian();
        }
        fadeJump += JUMP_GLIDE * (fadeJumpTarget - fadeJump);
        float fade = 0.5f + 0.5f * tanh(0.9f * (drift + fadeJump));
        carrierTarget = carrierOn ? carrierBase * (1 - fadeDepth * fade) * (inCut ? cutDepth : 1) : 0;
        // The receiver's gain follows the carrier (signal plus static), not the audio, so pauses keep their floor.
        float c = Math.max(carrier, carrierTarget);
        agcTarget = Math.min(agcMax, (float) Math.pow(c * c + noiseRel * noiseRel, -0.5 * AGC_STRENGTH));
        // Frequency-selective fading: a notch drifting through the band.
        // Frequency-selective fading: a notch wandering through the band at random, now and then jumping.
        notchOn = notchDb > 0.5f;
        float notchDrift = notchSlow.next() + 0.6f * notchFast.next();
        if (uniform() < NOTCH_JUMPS_PER_S * CONTROL_SECONDS) {
            notchJumpTarget = 1.2f * gaussian();
        }
        notchJump += NOTCH_GLIDE * (notchJumpTarget - notchJump);
        if (notchOn) {
            double where = 0.5 + 0.5 * tanh(0.8f * (notchDrift + notchJump));
            notch.setPeaking(SAMPLE_RATE, logLerp(NOTCH_LOW_HZ, NOTCH_HIGH_HZ, where), NOTCH_Q, -notchDb);
        }
        // Wow: the transmitter's pitch drifting at random, never further than wowDepth.
        wowTarget = WOW_BASE + wowDepth * tanh(0.8f * wowSlow.next() + 0.5f * wowFast.next());
        // Heterodyne whistle episodes (more likely in stormy stretches), wandering in pitch, flickering in level.
        if (whistleLeft <= 0 && whistleChance > 0 && uniform() < whistleChance * storm) {
            whistleLeft = (int) (SAMPLE_RATE * (WHISTLE_MIN_S + (WHISTLE_MAX_S - WHISTLE_MIN_S) * uniform()));
            whistleHz = WHISTLE_MIN_HZ + (WHISTLE_MAX_HZ - WHISTLE_MIN_HZ) * uniform();
        }
        whistleHz = clamp(whistleHz + (uniform() - 0.5f) * 6f + 0.4f * whistleDrift.next(), WHISTLE_MIN_HZ,
                WHISTLE_MAX_HZ);
        whistleFlick = 0.75f + 0.25f * tanh(whistleFlicker.next());
    }

    // --- every sample ----------------------------------------------------------------------------------------

    /** Transmitter: voice band, leveler, light saturation. */
    private float transmit(float x) {
        x = presence.process(lp2.process(lp1.process(hp2.process(hp1.process(x)))));
        float a = Math.abs(x);
        env += (a > env ? ENV_ATTACK : ENV_RELEASE) * (a - env);
        if (++levelCounter >= LEVEL_UPDATE_SAMPLES) {
            levelCounter = 0;
            if (env > LEVEL_GATE) {
                levelWant = Math.max(LEVEL_MIN_GAIN, Math.min(LEVEL_MAX_GAIN,
                        (float) Math.pow(LEVEL_TARGET / env, LEVEL_STRENGTH)));
            }
        }
        gain += GAIN_GLIDE * (levelWant - gain);
        return tanh(x * gain * drive) / drive;
    }

    /** A short modulated delay: the pitch wobbles while wowDepth is above 0, otherwise a fixed 2 ms delay. */
    private float wow(float x) {
        ring[ringPos & (RING - 1)] = x;
        wowDelay += 0.01f * (wowTarget - wowDelay);
        float read = ringPos - wowDelay;
        int i0 = (int) Math.floor(read);
        float frac = read - i0;
        ringPos++;
        return ring[i0 & (RING - 1)] * (1 - frac) + ring[(i0 + 1) & (RING - 1)] * frac;
    }

    /** The radio channel: carrier strength, noise in and around the voice, crackle and whistle. */
    private float channel(float voice) {
        carrier += CARRIER_GLIDE * (carrierTarget - carrier);
        float n = noiseLp.process(noiseHp.process(white())) * NOISE_NORM;
        lastNoise = n * VOICE_RMS;
        float v = voice;
        if (grit > 0) {
            v *= 1 + grit * gritLp.process(white()) * GRIT_NORM;
        }
        float a = Math.abs(voice);
        rideEnv += (a > rideEnv ? RIDE_ATTACK : RIDE_RELEASE) * (a - rideEnv);
        float ch = carrier * v + (VOICE_RMS * noiseRel + ride * rideEnv * carrier) * n;
        float base = crackleChance * (1 - CRACKLE_BRANCHING) * storm * (inCut ? CUT_CRACKLE_BOOST : 1);
        ch += crackle(base + crackleExcite / SAMPLE_RATE);
        if (whistleLeft > 0) {
            whistleLeft--;
        }
        whistleAmp += WHISTLE_GLIDE * ((whistleLeft > 0 ? whistleAmpMax * whistleFlick : 0) - whistleAmp);
        if (whistleAmp > 1e-5f) {
            whistlePhase = (whistlePhase + TWO_PI * whistleHz / SAMPLE_RATE) % TWO_PI;
            ch += whistleAmp * (float) Math.sin(whistlePhase);
        }
        return ch;
    }

    /** The receiver: gain control, narrowing band, drifting notch, clipping, sample-rate and bit loss. */
    private float receive(float ch) {
        agc += (agcTarget > agc ? AGC_RELEASE : AGC_ATTACK) * (agcTarget - agc);
        float y = rxLp.process(rxHp.process(ch * agc));
        if (notchOn) {
            y = notch.process(y);
        }
        if (clipLevel < 0.999f) {
            float t = CLIP_REF * clipLevel;
            y = Math.max(-t, Math.min(t, y)) * clipMakeup;
        }
        if (holdStep < 0.999f) {
            holdPhase += holdStep;
            if (holdPhase >= 1) {
                holdPhase -= 1;
                held = y;
            }
            y = held;
        }
        if (quantStep > 0) {
            y = Math.round(y / quantStep) * quantStep;
        }
        return y;
    }

    private float crackle(float chance) {
        if (crackleLeft <= 0) {
            if (chance <= 0) {
                return 0;
            }
            if (uniform() < chance) {
                crackleLeft = (int) (SAMPLE_RATE * (CRACKLE_MIN_S + (CRACKLE_MAX_S - CRACKLE_MIN_S) * uniform()));
                crackleAmp = crackleLevel * (0.3f + 0.7f * uniform()) * crackleScale;
                crackleDecay = (float) Math.exp(-CRACKLE_DECAY / Math.max(1, crackleLeft));
                crackleCount++;
                crackleExcite += CRACKLE_BRANCHING / CRACKLE_AFTERSHOCK_S;
            }
        }
        float x = 0;
        if (crackleLeft > 0) {
            x = white() * crackleAmp;
            crackleAmp *= crackleDecay;
            crackleLeft--;
        }
        return crackleLp.process(crackleHp.process(x));
    }

    private float step(float target) {
        target = clamp(target, 0f, 1f);
        if (degradation < 0) {
            degradation = target;
        } else {
            degradation += clamp(target - degradation, -DEGRADATION_STEP, DEGRADATION_STEP);
        }
        return degradation;
    }

    /** Rational tanh approximation, exact at the clamp points. */
    private static float tanh(float x) {
        if (x > 3f) return 1f;
        if (x < -3f) return -1f;
        float x2 = x * x;
        return x * (27f + x2) / (27f + 9f * x2);
    }

    /** 0 for a clear link .. 1 from SQUELCH_SOFT_UNTIL on, eased in and out. */
    private static float harshness(float d) {
        float x = Math.min(1f, Math.max(0f, d / SQUELCH_SOFT_UNTIL));
        return x * x * (3 - 2 * x);
    }

    /** A squelch burst at the current harshness: rounded off, and gentler and darker still over a clear link. */
    private float squelch(float burst) {
        float soft = squelchSoftLp.process(burst);
        float full = squelchLp.process(burst);
        return squelchGain * (soft + (full - soft) * squelchHarsh);
    }

    private static float ramp(float d, float onset) {
        return onset >= 1 ? 0 : Math.max(0f, (d - onset) / (1 - onset));
    }

    private static double logLerp(double a, double b, double t) {
        return Math.exp(Math.log(a) + (Math.log(b) - Math.log(a)) * t);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static float f(RadioConfig c, RadioConfig.Setting s) {
        return (float) c.get(s);
    }

    private static short toShort(float y) {
        return (short) Math.round(clamp(y, -1f, 1f) * 32767f);
    }

    /** Uniform in [-1, 1). xorshift64*, per instance so links never share a sequence. */
    private float white() {
        rng ^= rng >>> 12;
        rng ^= rng << 25;
        rng ^= rng >>> 27;
        return ((rng * 0x2545F4914F6CDD1DL) >>> 40) * (1f / (1 << 23)) - 1f;
    }

    /** Uniform in [0, 1). */
    private float uniform() {
        return (white() + 1f) * 0.5f;
    }

    /** Roughly standard normal (a sum of four uniforms). */
    private float gaussian() {
        return (white() + white() + white() + white()) * 0.8660254f;
    }

    /**
     * A smoothly wandering random value with unit spread: white noise through two one-pole low-passes at a corner
     * rate, stepped once per control tick (1 ms). Never periodic; several at unrelated rates make 1/f-like motion.
     */
    private final class Wander {
        private final float a, norm;
        private float s1, s2;

        Wander(float hz) {
            a = (float) (1 - Math.exp(-2 * Math.PI * hz * CONTROL_SECONDS));
            double b = 1 - a;
            double variance = (1 / 3.0) * Math.pow(a, 4) * (1 + b * b) / Math.pow(1 - b * b, 3);
            norm = (float) (1 / Math.sqrt(variance));
            s1 = s2 = gaussian() / norm; // start anywhere in its range, not at 0
        }

        float next() {
            s1 += a * (white() - s1);
            s2 += a * (s1 - s2);
            return s2 * norm;
        }
    }

    private static float glide(double seconds) {
        return (float) (1 - Math.exp(-1.0 / (seconds * SAMPLE_RATE)));
    }

    /** 1 / RMS of white noise through a band (lowHz 0: low-pass only), so noise levels can be given as RMS. */
    private static float noiseNorm(double lowHz, double highHz) {
        Biquad hp = lowHz > 0 ? Biquad.highPass(SAMPLE_RATE, lowHz, 0.707) : null;
        Biquad lp = Biquad.lowPass(SAMPLE_RATE, highHz, 0.707);
        long r = 0x1234567L;
        double sum = 0;
        int warmup = SAMPLE_RATE / 10, n = SAMPLE_RATE;
        for (int i = 0; i < warmup + n; i++) {
            r ^= r >>> 12;
            r ^= r << 25;
            r ^= r >>> 27;
            float w = ((r * 0x2545F4914F6CDD1DL) >>> 40) * (1f / (1 << 23)) - 1f;
            float y = lp.process(hp != null ? hp.process(w) : w);
            if (i >= warmup) {
                sum += y * (double) y;
            }
        }
        return (float) (1 / Math.sqrt(sum / n));
    }
}
