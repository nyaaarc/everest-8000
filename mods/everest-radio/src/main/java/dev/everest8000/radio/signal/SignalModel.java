package dev.everest8000.radio.signal;

import dev.everest8000.radio.RadioConfig;

/**
 * How badly a radio signal is degraded between two players: 0 is perfectly clear, 1 is the worst it gets (the voice
 * faint and broken up in static, words mostly lost). Something always comes through.
 * <ul>
 *   <li>Clear only within {@link RadioConfig#CLEAR_RANGE} blocks with no terrain in the way.</li>
 *   <li>Beyond that, distance adds degradation on a log curve, reaching {@link RadioConfig#DISTANCE_MAX_DEGRADATION}
 *       at {@link RadioConfig#MAX_RANGE} (about Base Camp to the top of the mountain) and staying there.</li>
 *   <li>Terrain below the line of sight adds up to {@link RadioConfig#OCCLUSION_MAX_DEGRADATION} more, rising with the
 *       square root of the blocked fraction. It is kept small, so distance decides how rough a call is.</li>
 * </ul>
 */
public final class SignalModel {
    /** Players in different dimensions still hear each other, at the worst quality. */
    public static final float CROSS_DIMENSION = 1f;

    private SignalModel() {
    }

    /**
     * @param distance  blocks between the two radios
     * @param occlusion fraction (0..1) of the straight path between them that lies below ground
     */
    public static float degradation(double distance, double occlusion, RadioConfig config) {
        return (float) Math.min(1.0, distancePart(distance, config) + occlusionPart(occlusion, config));
    }

    public static double distancePart(double distance, RadioConfig config) {
        double clear = config.get(RadioConfig.CLEAR_RANGE);
        double max = Math.max(config.get(RadioConfig.MAX_RANGE), clear * 1.0001);
        if (!(distance > clear)) {
            return 0;
        }
        double t = Math.min(1.0, Math.log(distance / clear) / Math.log(max / clear));
        return config.get(RadioConfig.DISTANCE_MAX_DEGRADATION) * t;
    }

    public static double occlusionPart(double occlusion, RadioConfig config) {
        if (!(occlusion > 0)) {
            return 0;
        }
        double t = Math.min(1.0, occlusion / config.get(RadioConfig.OCCLUSION_FULL_FRACTION));
        return config.get(RadioConfig.OCCLUSION_MAX_DEGRADATION) * Math.sqrt(t);
    }
}
