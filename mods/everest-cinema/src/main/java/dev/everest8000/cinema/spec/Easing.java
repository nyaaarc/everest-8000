package dev.everest8000.cinema.spec;

/** Time easing curves; wire names are the JSON strings. */
public enum Easing {
    LINEAR("linear"), EASE_IN("easeIn"), EASE_OUT("easeOut"), EASE_IN_OUT("easeInOut"), SINE_IN_OUT("sineInOut"),
    EASE_IN_OUT_QUINT("easeInOutQuint");

    public static final String[] WIRE = {"linear", "easeIn", "easeOut", "easeInOut", "sineInOut", "easeInOutQuint"};
    public final String wire;

    Easing(String wire) {
        this.wire = wire;
    }

    public static Easing of(String wire) {
        for (Easing e : values()) if (e.wire.equals(wire)) return e;
        throw new IllegalArgumentException(wire);
    }

    public static Easing read(JObj o, String key, Easing def) {
        return of(o.choiceOr(key, def.wire, WIRE));
    }

    /** Maps 0..1 to 0..1. */
    public double apply(double t) {
        t = Math.max(0, Math.min(1, t));
        return switch (this) {
            case LINEAR -> t;
            case EASE_IN -> t * t * t;
            case EASE_OUT -> 1 - Math.pow(1 - t, 3);
            case EASE_IN_OUT -> t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
            case SINE_IN_OUT -> -(Math.cos(Math.PI * t) - 1) / 2;
            case EASE_IN_OUT_QUINT -> t < 0.5 ? 16 * Math.pow(t, 5) : 1 - Math.pow(-2 * t + 2, 5) / 2;
        };
    }
}
