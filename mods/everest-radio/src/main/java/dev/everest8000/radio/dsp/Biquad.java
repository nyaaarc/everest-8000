package dev.everest8000.radio.dsp;

/** One second-order IIR filter section (RBJ Audio EQ Cookbook), transposed direct form II. */
public final class Biquad {
    private double b0, b1, b2, a1, a2;
    private double z1, z2;

    public static Biquad lowPass(double sampleRate, double freq, double q) {
        Biquad f = new Biquad();
        f.setLowPass(sampleRate, freq, q);
        return f;
    }

    public static Biquad highPass(double sampleRate, double freq, double q) {
        Biquad f = new Biquad();
        double w = 2 * Math.PI * freq / sampleRate, cos = Math.cos(w), alpha = Math.sin(w) / (2 * q);
        f.set((1 + cos) / 2, -(1 + cos), (1 + cos) / 2, 1 + alpha, -2 * cos, 1 - alpha);
        return f;
    }

    public static Biquad peaking(double sampleRate, double freq, double q, double gainDb) {
        Biquad f = new Biquad();
        double a = Math.pow(10, gainDb / 40);
        double w = 2 * Math.PI * freq / sampleRate, cos = Math.cos(w), alpha = Math.sin(w) / (2 * q);
        f.set(1 + alpha * a, -2 * cos, 1 - alpha * a, 1 + alpha / a, -2 * cos, 1 - alpha / a);
        return f;
    }

    /** Retunes as a low-pass, keeping the running state (fine for small per-frame changes). */
    public void setLowPass(double sampleRate, double freq, double q) {
        double w = 2 * Math.PI * Math.min(freq, sampleRate * 0.45) / sampleRate, cos = Math.cos(w);
        double alpha = Math.sin(w) / (2 * q);
        set((1 - cos) / 2, 1 - cos, (1 - cos) / 2, 1 + alpha, -2 * cos, 1 - alpha);
    }

    /** Retunes as a high-pass, keeping the running state. */
    public void setHighPass(double sampleRate, double freq, double q) {
        double w = 2 * Math.PI * Math.min(freq, sampleRate * 0.45) / sampleRate, cos = Math.cos(w);
        double alpha = Math.sin(w) / (2 * q);
        set((1 + cos) / 2, -(1 + cos), (1 + cos) / 2, 1 + alpha, -2 * cos, 1 - alpha);
    }

    /** Retunes as a peaking (or, with negative gain, notching) EQ, keeping the running state. */
    public void setPeaking(double sampleRate, double freq, double q, double gainDb) {
        double a = Math.pow(10, gainDb / 40);
        double w = 2 * Math.PI * Math.min(freq, sampleRate * 0.45) / sampleRate, cos = Math.cos(w);
        double alpha = Math.sin(w) / (2 * q);
        set(1 + alpha * a, -2 * cos, 1 - alpha * a, 1 + alpha / a, -2 * cos, 1 - alpha / a);
    }

    private void set(double nb0, double nb1, double nb2, double na0, double na1, double na2) {
        b0 = nb0 / na0;
        b1 = nb1 / na0;
        b2 = nb2 / na0;
        a1 = na1 / na0;
        a2 = na2 / na0;
    }

    public float process(float x) {
        double y = b0 * x + z1;
        z1 = b1 * x - a1 * y + z2;
        z2 = b2 * x - a2 * y;
        return (float) y;
    }

    public void reset() {
        z1 = 0;
        z2 = 0;
    }
}
