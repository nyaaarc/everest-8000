package dev.everest8000.expedition.client.wind;

/**
 * Light on the plume for the time of day: a white sun high up, an orange one low down (the plume glows at sunrise
 * and sunset while the valleys are already dark), a dim blue moon at night, and a sky fill tinted by the sky's own
 * colour. Values are linear; the shader converts to display colour at the end.
 */
public final class PlumeLight {
    public final float[] sunDir = new float[3];
    public final float[] sunColor = new float[3];
    public final float[] skyColor = new float[3];
    public final float[] hazeColor = new float[3];

    /**
     * @param sunAngle Minecraft's sun angle (radians, 0 at noon)
     * @param sky      vanilla sky colour at the camera, display values 0..1
     * @param dim      0..1, how much cloud at the camera dulls the light (a storm overhead)
     */
    public void update(float sunAngle, float skyR, float skyG, float skyB, float dim) {
        float sx = (float) -Math.sin(sunAngle), sy = (float) Math.cos(sunAngle);
        float elev = sy;
        float day = smooth(-0.10f, 0.10f, elev);
        // Low sun: warm, and stronger reddening right at the horizon.
        float low = 1f - smooth(0.02f, 0.40f, elev);
        float r = 1.00f, g = lerp(low, 0.96f, 0.50f), b = lerp(low, 0.90f, 0.24f);
        float sunPower = 1.05f * smooth(-0.06f, 0.06f, elev) * (1f - 0.75f * dim);
        float moonPower = 0.09f * smooth(-0.02f, 0.15f, -elev) * (1f - 0.6f * dim);
        if (sunPower >= moonPower) {
            set(sunDir, sx, sy, 0f);
            set(sunColor, r * sunPower, g * sunPower, b * sunPower);
        } else {
            set(sunDir, -sx, -sy, 0f);
            set(sunColor, 0.70f * moonPower, 0.80f * moonPower, 1.00f * moonPower);
        }
        float lr = linear(skyR), lg = linear(skyG), lb = linear(skyB);
        float fill = 0.42f * day + 0.02f;
        // Twilight fill keeps a little of the sunset's warmth.
        float warm = day * low * 0.35f;
        set(skyColor, (lr * 0.45f + 0.20f + warm * 0.3f) * fill, (lg * 0.45f + 0.21f + warm * 0.12f) * fill,
                (lb * 0.45f + 0.24f) * fill);
        set(hazeColor, (lr * 0.5f + 0.4f) * (0.08f + 0.6f * day), (lg * 0.5f + 0.42f) * (0.08f + 0.6f * day),
                (lb * 0.5f + 0.46f) * (0.1f + 0.6f * day));
    }

    private static void set(float[] v, float x, float y, float z) {
        v[0] = x;
        v[1] = y;
        v[2] = z;
    }

    private static float linear(float c) {
        return (float) Math.pow(Math.max(0f, c), 2.2);
    }

    private static float lerp(float t, float a, float b) {
        return a + (b - a) * t;
    }

    private static float smooth(float e0, float e1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - e0) / (e1 - e0)));
        return t * t * (3f - 2f * t);
    }
}
