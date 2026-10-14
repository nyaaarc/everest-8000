package dev.everest8000.radio.dsp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/** WAV files and measurements for the offline audio tests (48 kHz mono 16-bit throughout). */
final class Audio {
    static final int RATE = RadioVoiceChain.SAMPLE_RATE;
    static final int FRAME = RadioVoiceChain.FRAME_SIZE;
    static final int FFT = 4096;

    private Audio() {
    }

    static short[] readWav(InputStream in) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(in.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN);
        b.position(12);
        int rate = 0, channels = 0, bits = 0;
        while (b.remaining() >= 8) {
            int id = b.getInt(), size = b.getInt(), next = b.position() + size + (size & 1);
            if (id == 0x20746d66) { // "fmt "
                b.getShort();
                channels = b.getShort();
                rate = b.getInt();
                b.getInt();
                b.getShort();
                bits = b.getShort();
            } else if (id == 0x61746164) { // "data"
                if (rate != RATE || channels != 1 || bits != 16) {
                    throw new IOException("need 48 kHz mono 16-bit, got " + rate + "/" + channels + "/" + bits);
                }
                short[] pcm = new short[size / 2];
                b.asShortBuffer().get(pcm);
                return pcm;
            }
            b.position(Math.min(next, b.limit()));
        }
        throw new IOException("no data chunk");
    }

    static void writeWav(Path path, short[] pcm) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(44 + pcm.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x46464952).putInt(36 + pcm.length * 2).putInt(0x45564157); // RIFF, size, WAVE
        b.putInt(0x20746d66).putInt(16).putShort((short) 1).putShort((short) 1).putInt(RATE).putInt(RATE * 2)
                .putShort((short) 2).putShort((short) 16);
        b.putInt(0x61746164).putInt(pcm.length * 2);
        for (short s : pcm) {
            b.putShort(s);
        }
        Files.createDirectories(path.getParent());
        Files.write(path, b.array());
    }

    static double frameRms(short[] a, int frame) {
        return rms(a, frame * FRAME, (frame + 1) * FRAME);
    }

    static double rms(short[] a, int from, int to) {
        double sum = 0;
        to = Math.min(to, a.length);
        for (int i = from; i < to; i++) {
            double v = a[i] / 32768.0;
            sum += v * v;
        }
        return to > from ? Math.sqrt(sum / (to - from)) : 0;
    }

    /** RMS over the 20 ms frames picked by the mask. */
    static double rms(short[] a, boolean[] frames) {
        double sum = 0;
        long n = 0;
        for (int f = 0; f < frames.length && (f + 1) * FRAME <= a.length; f++) {
            if (!frames[f]) continue;
            for (int i = f * FRAME; i < (f + 1) * FRAME; i++) {
                double v = a[i] / 32768.0;
                sum += v * v;
            }
            n += FRAME;
        }
        return n > 0 ? Math.sqrt(sum / n) : 0;
    }

    static double db(double linear) {
        return 20 * Math.log10(Math.max(linear, 1e-7));
    }

    /** Average power spectrum (Hann windows, half overlap) over windows centred in masked frames; null mask = all. */
    static double[] spectrum(short[] a, boolean[] frames) {
        double[] power = new double[FFT / 2];
        double[] re = new double[FFT], im = new double[FFT];
        int windows = 0;
        for (int start = 0; start + FFT <= a.length; start += FFT / 2) {
            int centreFrame = (start + FFT / 2) / FRAME;
            if (frames != null && (centreFrame >= frames.length || !frames[centreFrame])) continue;
            for (int i = 0; i < FFT; i++) {
                double w = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FFT - 1));
                re[i] = a[start + i] / 32768.0 * w;
                im[i] = 0;
            }
            fft(re, im);
            for (int k = 0; k < FFT / 2; k++) {
                power[k] += re[k] * re[k] + im[k] * im[k];
            }
            windows++;
        }
        for (int k = 0; k < power.length; k++) {
            power[k] /= Math.max(1, windows);
        }
        return power;
    }

    static double bandPower(double[] spectrum, double lowHz, double highHz) {
        double sum = 0;
        for (int k = 0; k < spectrum.length; k++) {
            double hz = k * (double) RATE / FFT;
            if (hz >= lowHz && hz < highHz) sum += spectrum[k];
        }
        return sum;
    }

    static double bandDb(double[] spectrum, double lowHz, double highHz) {
        return 10 * Math.log10(Math.max(bandPower(spectrum, lowHz, highHz), 1e-20));
    }

    /** In-place radix-2 FFT. */
    static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len, wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = a + len / 2;
                    double xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi;
                    re[a] += xr; im[a] += xi;
                    double t = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = t;
                }
            }
        }
    }

    /** Pearson correlation of two 10 ms RMS envelopes over the masked 20 ms frames. */
    static double envelopeCorrelation(short[] a, short[] b, boolean[] frames) {
        int block = FRAME / 2;
        double sa = 0, sb = 0, saa = 0, sbb = 0, sab = 0;
        int n = 0;
        for (int f = 0; f < frames.length; f++) {
            if (!frames[f]) continue;
            for (int h = 0; h < 2; h++) {
                int from = f * FRAME + h * block;
                if (from + block > Math.min(a.length, b.length)) continue;
                double x = rms(a, from, from + block), y = rms(b, from, from + block);
                sa += x; sb += y; saa += x * x; sbb += y * y; sab += x * y;
                n++;
            }
        }
        double cov = sab / n - (sa / n) * (sb / n);
        double va = saa / n - (sa / n) * (sa / n), vb = sbb / n - (sb / n) * (sb / n);
        return cov / Math.sqrt(va * vb);
    }

    /**
     * Short-time objective intelligibility, after Taal et al. (2011): the correlation between the clean and the
     * degraded speech's 1/3-octave band envelopes (15 bands from 150 Hz) over 384 ms windows, after dropping frames
     * where the clean speech is silent. About 1 for intact speech, near 0 when nothing of it survives.
     *
     * @param delay samples the degraded signal lags the clean one
     */
    static double stoi(short[] clean, short[] degraded, int delay) {
        return bandEnvelopeCorrelation(clean, degraded, delay, true);
    }

    /**
     * The same as {@link #stoi} without its clipping step, which ties the degraded envelope to the clean one in
     * quiet frames and so gives even pure noise a score of about 0.2-0.4. This one gives noise about 0.
     */
    static double intelligibility(short[] clean, short[] degraded, int delay) {
        return bandEnvelopeCorrelation(clean, degraded, delay, false);
    }

    private static double bandEnvelopeCorrelation(short[] clean, short[] degraded, int delay, boolean clipped) {
        final int n = 1228, hop = 614, nfft = 2048, seg = 30, bands = 15; // 25.6 ms frames at 48 kHz
        final double clip = 1 + Math.pow(10, 15 / 20.0);
        int len = Math.min(clean.length, degraded.length - delay);
        int frames = (len - n) / hop + 1;
        double[] win = new double[n];
        for (int i = 0; i < n; i++) win[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * (i + 1) / (n + 1));
        double[] energy = new double[frames];
        double max = -1e9;
        for (int m = 0; m < frames; m++) {
            double e = 0;
            for (int i = 0; i < n; i++) {
                double v = clean[m * hop + i] / 32768.0 * win[i];
                e += v * v;
            }
            energy[m] = 10 * Math.log10(e + 1e-20);
            max = Math.max(max, energy[m]);
        }
        int[] lo = new int[bands], hi = new int[bands];
        for (int j = 0; j < bands; j++) {
            double fc = 150 * Math.pow(2, j / 3.0);
            lo[j] = (int) Math.round(fc * Math.pow(2, -1 / 6.0) * nfft / RATE);
            hi[j] = (int) Math.round(fc * Math.pow(2, 1 / 6.0) * nfft / RATE);
        }
        java.util.List<double[]> xs = new java.util.ArrayList<>(), ys = new java.util.ArrayList<>();
        double[] re = new double[nfft], im = new double[nfft];
        for (int m = 0; m < frames; m++) {
            if (energy[m] < max - 40) continue;
            xs.add(bandEnvelope(clean, m * hop, win, re, im, lo, hi));
            ys.add(bandEnvelope(degraded, m * hop + delay, win, re, im, lo, hi));
        }
        double sum = 0;
        int count = 0;
        for (int m = seg - 1; m < xs.size(); m++) {
            for (int j = 0; j < bands; j++) {
                double[] x = new double[seg], y = new double[seg];
                double nx = 0, ny = 0;
                for (int k = 0; k < seg; k++) {
                    x[k] = xs.get(m - seg + 1 + k)[j];
                    y[k] = ys.get(m - seg + 1 + k)[j];
                    nx += x[k] * x[k];
                    ny += y[k] * y[k];
                }
                double alpha = ny > 0 ? Math.sqrt(nx / ny) : 0;
                if (clipped) {
                    for (int k = 0; k < seg; k++) y[k] = Math.min(alpha * y[k], clip * x[k]);
                }
                sum += pearson(x, y);
                count++;
            }
        }
        return count > 0 ? sum / count : 0;
    }

    private static double[] bandEnvelope(short[] a, int start, double[] win, double[] re, double[] im, int[] lo, int[] hi) {
        java.util.Arrays.fill(re, 0);
        java.util.Arrays.fill(im, 0);
        for (int i = 0; i < win.length && start + i < a.length; i++) re[i] = a[start + i] / 32768.0 * win[i];
        fft(re, im);
        double[] out = new double[lo.length];
        for (int j = 0; j < lo.length; j++) {
            double e = 0;
            for (int k = lo[j]; k < hi[j]; k++) e += re[k] * re[k] + im[k] * im[k];
            out[j] = Math.sqrt(e);
        }
        return out;
    }

    private static double pearson(double[] x, double[] y) {
        double mx = 0, my = 0;
        for (int k = 0; k < x.length; k++) {
            mx += x[k];
            my += y[k];
        }
        mx /= x.length;
        my /= y.length;
        double sxy = 0, sxx = 0, syy = 0;
        for (int k = 0; k < x.length; k++) {
            sxy += (x[k] - mx) * (y[k] - my);
            sxx += (x[k] - mx) * (x[k] - mx);
            syy += (y[k] - my) * (y[k] - my);
        }
        return sxx > 0 && syy > 0 ? sxy / Math.sqrt(sxx * syy) : 0;
    }

    /** The lag (0..maxLag samples) at which sig best matches ref, by cross-correlation over a few seconds. */
    static int delay(short[] ref, short[] sig, int maxLag) {
        int from = Math.min(RATE / 2, ref.length / 4), to = Math.min(ref.length, from + 4 * RATE);
        double best = -Double.MAX_VALUE;
        int bestLag = 0;
        for (int lag = 0; lag <= maxLag; lag++) {
            double s = 0;
            for (int i = from; i < to && i + lag < sig.length; i++) s += (double) ref[i] * sig[i + lag];
            if (s > best) {
                best = s;
                bestLag = lag;
            }
        }
        return bestLag;
    }

    /** Longest run (ms) of 10 ms blocks quieter than thresholdDb between two sample positions. */
    static int longestQuietMs(short[] a, int from, int to, double thresholdDb) {
        int block = RATE / 100, run = 0, longest = 0;
        for (int i = from; i + block <= Math.min(to, a.length); i += block) {
            if (db(rms(a, i, i + block)) < thresholdDb) {
                run += 10;
                longest = Math.max(longest, run);
            } else {
                run = 0;
            }
        }
        return longest;
    }

    /**
     * How periodic the loudness envelope is, the way an ear would notice a regular swell or wobble:
     * [0] the envelope's autocorrelation peak beyond its first zero crossing (0 for no repeat, 1 for a perfect
     * one), [1] the lag of that peak (s), [2] the modulation spectrum's tallest peak above the median of the third
     * of an octave around it (dB), [3] that peak's frequency (Hz). The envelope is the 10 ms RMS; the spectrum is a
     * Welch average of 10.24 s Hann windows, 0.2-25 Hz. [4] the autocorrelation peak within 6 s windows, averaged:
     * a pattern that drifts over minutes still shows here.
     */
    static double[] periodicity(short[] a, int from, int to) {
        int block = RATE / 100;
        int n = (Math.min(to, a.length) - from) / block;
        double[] e = new double[n];
        double mean = 0;
        for (int k = 0; k < n; k++) {
            e[k] = rms(a, from + k * block, from + (k + 1) * block);
            mean += e[k] / n;
        }
        for (int k = 0; k < n; k++) e[k] -= mean;
        int maxLag = Math.min(500, n / 2);
        double r0 = 0;
        for (int k = 0; k < n; k++) r0 += e[k] * e[k];
        double peak = 0, peakLag = 0;
        boolean crossed = false;
        for (int lag = 1; lag < maxLag; lag++) {
            double r = 0;
            for (int k = 0; k + lag < n; k++) r += e[k] * e[k + lag];
            r /= r0;
            if (!crossed) {
                crossed = r <= 0;
                continue;
            }
            if (lag >= 15 && r > peak) {
                peak = r;
                peakLag = lag / 100.0;
            }
        }
        int seg = 1024;
        double[] power = new double[seg / 2];
        double[] re = new double[seg], im = new double[seg];
        int windows = 0;
        for (int start = 0; start + seg <= n; start += seg / 2) {
            for (int i = 0; i < seg; i++) {
                re[i] = e[start + i] * (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (seg - 1)));
                im[i] = 0;
            }
            fft(re, im);
            for (int k = 0; k < seg / 2; k++) power[k] += re[k] * re[k] + im[k] * im[k];
            windows++;
        }
        double binHz = 100.0 / seg, best = 0, bestHz = 0;
        for (int k = (int) Math.ceil(0.2 / binHz); k <= (int) (25 / binHz) && k < power.length; k++) {
            int lo = Math.max(1, (int) Math.floor(k / 1.26)), hi = Math.min(power.length - 1, (int) Math.ceil(k * 1.26));
            double[] around = java.util.Arrays.copyOfRange(power, lo, hi + 1);
            java.util.Arrays.sort(around);
            double median = around[around.length / 2];
            double ratio = 10 * Math.log10(power[k] / Math.max(median, 1e-30));
            if (ratio > best) {
                best = ratio;
                bestHz = k * binHz;
            }
        }
        // Short-term: the same autocorrelation peak within 6 s windows (what an ear notices over a few swells),
        // averaged over the windows.
        int win = 600;
        double shortSum = 0;
        int shortCount = 0;
        for (int start = 0; start + win <= n; start += win / 2) {
            double w0 = 0, wMean = 0;
            for (int k = start; k < start + win; k++) wMean += e[k] / win;
            for (int k = start; k < start + win; k++) w0 += (e[k] - wMean) * (e[k] - wMean);
            boolean wCrossed = false;
            double wPeak = 0;
            for (int lag = 1; lag < 300; lag++) {
                double r = 0;
                for (int k = start; k + lag < start + win; k++) r += (e[k] - wMean) * (e[k + lag] - wMean);
                r /= w0;
                if (!wCrossed) {
                    wCrossed = r <= 0;
                    continue;
                }
                if (lag >= 15) wPeak = Math.max(wPeak, r);
            }
            shortSum += wPeak;
            shortCount++;
        }
        return new double[]{peak, peakLag, windows > 0 ? best : Double.NaN, bestHz,
                shortCount > 0 ? shortSum / shortCount : Double.NaN};
    }

    static final double[][] BANDS = {{300, 600}, {600, 1200}, {1200, 2400}, {2400, 4000}};

    /**
     * {@link #periodicity} of the broadband envelope and of the envelope in each of four bands (a notch or filter
     * sweeping through the voice shows up in the bands, not in the broadband level). Returns the worst (largest)
     * of each measure: [0] long-term autocorrelation peak, [1] modulation spectrum peak (dB), [2] its frequency,
     * [3] the 6 s window autocorrelation peak.
     */
    static double[] bandPeriodicity(short[] a, int from, int to) {
        double[] worst = new double[4];
        for (int b = -1; b < BANDS.length; b++) {
            short[] x = a;
            if (b >= 0) {
                Biquad h1 = Biquad.highPass(RATE, BANDS[b][0], 0.707), h2 = Biquad.highPass(RATE, BANDS[b][0], 0.707);
                Biquad l1 = Biquad.lowPass(RATE, BANDS[b][1], 0.707), l2 = Biquad.lowPass(RATE, BANDS[b][1], 0.707);
                x = new short[a.length];
                for (int i = 0; i < a.length; i++) {
                    x[i] = (short) Math.max(-32768, Math.min(32767, l2.process(l1.process(h2.process(h1.process(a[i]))))));
                }
            }
            double[] p = periodicity(x, from, to);
            worst[0] = Math.max(worst[0], p[0]);
            if (p[2] > worst[1]) {
                worst[1] = p[2];
                worst[2] = p[3];
            }
            worst[3] = Math.max(worst[3], p[4]);
        }
        return worst;
    }

    /**
     * Periodicity of the pitch of a steady tone after the channel (the wow): the tone's frequency deviation from
     * 10 ms I/Q blocks, then the same autocorrelation and modulation measures as {@link #periodicity}, returned as
     * [0] long-term autocorrelation peak, [1] modulation spectrum peak (dB), [2] its frequency, [3] 6 s windows,
     * [4] the deviation's RMS in cents.
     */
    static double[] pitchPeriodicity(short[] a, int from, int to, double toneHz) {
        int block = RATE / 100;
        int n = (Math.min(to, a.length) - from) / block;
        double[] dev = new double[n];
        double prev = 0, unwrapped = 0;
        for (int k = 0; k < n; k++) {
            double i = 0, q = 0;
            for (int s = from + k * block; s < from + (k + 1) * block; s++) {
                double ph = 2 * Math.PI * toneHz * s / RATE;
                i += a[s] * Math.cos(ph);
                q -= a[s] * Math.sin(ph);
            }
            double phase = Math.atan2(q, i);
            double d = phase - prev;
            d -= 2 * Math.PI * Math.round(d / (2 * Math.PI));
            prev = phase;
            unwrapped += d;
            dev[k] = k == 0 ? 0 : d / (2 * Math.PI) * 100; // Hz off the tone
        }
        // Reuse the envelope measures on the deviation track, scaled into a short array of "samples".
        short[] track = new short[n * block];
        double rmsCents = 0;
        for (int k = 1; k < n; k++) {
            rmsCents += Math.pow(1200 * Math.log((toneHz + dev[k]) / toneHz) / Math.log(2), 2) / n;
        }
        // periodicity() takes an RMS envelope; feed it a track whose 10 ms RMS follows the deviation (offset > 0).
        for (int k = 0; k < n; k++) {
            short v = (short) Math.max(1, Math.min(32767, 8000 + dev[k] * 400));
            java.util.Arrays.fill(track, k * block, (k + 1) * block, v);
        }
        double[] p = periodicity(track, 0, track.length);
        return new double[]{p[0], p[2], p[3], p[4], Math.sqrt(rmsCents)};
    }

    /** 1 ms blocks louder than 3x the RMS of the 40 ms around them: crackles, plus some speech onsets. */
    static int transients(short[] a, int fromFrame, int toFrame) {
        int block = RATE / 1000, count = 0, cooldown = 0;
        for (int i = fromFrame * FRAME + 20 * block; i + 21 * block <= Math.min(toFrame * FRAME, a.length); i += block) {
            if (cooldown > 0) {
                cooldown--;
                continue;
            }
            double local = rms(a, i, i + block);
            double around = rms(a, i - 20 * block, i + 20 * block);
            if (local > 0.02 && local > 3 * around) {
                count++;
                cooldown = 5;
            }
        }
        return count;
    }
}
