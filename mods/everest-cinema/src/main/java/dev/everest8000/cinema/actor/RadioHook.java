package dev.everest8000.cinema.actor;

import dev.everest8000.cinema.CinemaClient;

import java.lang.reflect.Method;

/**
 * The Everest Radio raise-to-face animation (dev.everest8000.radio.client.RadioPose), reached by reflection so the cinema
 * mod still runs without the radio mod. Keyed, the arm holding the radio (main hand first) eases up to the face; the
 * easing runs in client ticks, so frame-stepped takes repeat exactly. The radio mod clears its state when the entity
 * unloads.
 */
final class RadioHook {
    private static final String CLASS = "dev.everest8000.radio.client.RadioPose";
    private static Method setKeyed, setAmount, amount;
    private static boolean looked;
    private static String missing;

    private RadioHook() {}

    private static synchronized boolean resolve() {
        if (!looked) {
            looked = true;
            try {
                Class<?> c = Class.forName(CLASS);
                setKeyed = c.getMethod("setKeyed", int.class, boolean.class);
                setAmount = c.getMethod("setAmount", int.class, float.class);
                amount = c.getMethod("amount", int.class, float.class);
            } catch (ReflectiveOperationException | LinkageError e) {
                setKeyed = setAmount = amount = null;
                missing = e.toString();
                CinemaClient.LOG.warn("Cinema: Everest Radio's RadioPose hook not found ({}); pose \"raise\" falls back to the horn pose", e.toString());
            }
        }
        return setKeyed != null;
    }

    /** Whether the radio mod's hook is there. */
    static boolean available() {
        return resolve();
    }

    /** Why the hook isn't available, or null. */
    static String missing() {
        resolve();
        return missing;
    }

    /** Raise (true) or lower (false) the radio. Returns an error message, or null. */
    static String setKeyed(int entityId, boolean keyed) {
        if (!resolve()) return "Everest Radio's RadioPose hook not found: " + missing;
        try {
            setKeyed.invoke(null, entityId, keyed);
            return null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            CinemaClient.LOG.warn("Cinema: RadioPose.setKeyed failed", e);
            return "RadioPose.setKeyed failed: " + e;
        }
    }

    /** Pins the raise amount (0..1), or releases the pin with NaN. Returns an error message, or null. */
    static String setAmount(int entityId, float value) {
        if (!resolve()) return "Everest Radio's RadioPose hook not found: " + missing;
        try {
            setAmount.invoke(null, entityId, value);
            return null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            CinemaClient.LOG.warn("Cinema: RadioPose.setAmount failed", e);
            return "RadioPose.setAmount failed: " + e;
        }
    }

    /** The current raise amount (0..1) at this partial tick, or NaN without the hook. */
    static double amount(int entityId, float partialTick) {
        if (!resolve()) return Double.NaN;
        try {
            return ((Number) amount.invoke(null, entityId, partialTick)).doubleValue();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Double.NaN;
        }
    }
}
