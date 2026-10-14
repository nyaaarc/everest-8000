package dev.everest8000.expedition.gear;

import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * The down hood's shape, half of a player's hood look (the other half is where their {@link Goggles} go). Purely
 * cosmetic: every hood keeps out the same cold. The order is the shape's id, saved with the player and sent over the
 * network, and must match HOOD_SHAPES in art/suit3d_model.py, which decides the parts each shape shows.
 */
public enum HoodShape {
    /** Puffy open hood with a thick down roll round the face. */
    EXPEDITION,
    /** Fur-ruffed parka hood. */
    PARKA,
    /** The down hood with its front edge cinched down to the brows, covering the forehead. */
    LOW,
    /** The fur parka hood, its ruff brought down to the brows. */
    LOW_FUR,
    /** A red Himalayan knit hat under the hood (its band shows); hood down, the whole hat with ear flaps. */
    SHERPA,
    /** A white climbing helmet with a headlamp, over the hood or, hood down, on the head. */
    HELMET;

    private static final HoodShape[] VALUES = values();

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public Component displayName() {
        return Component.translatable("hood_shape.everest_expedition." + id());
    }

    /** The shape with this id number, or null if there's none. */
    public static HoodShape byIndex(int index) {
        return index >= 0 && index < VALUES.length ? VALUES[index] : null;
    }

    /** The shape with this name ({@link #id()}), or null if there's none. */
    public static HoodShape byId(String id) {
        for (HoodShape shape : VALUES) if (shape.id().equals(id)) return shape;
        return null;
    }
}
