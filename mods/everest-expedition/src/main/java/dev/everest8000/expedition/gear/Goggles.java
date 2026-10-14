package dev.everest8000.expedition.gear;

import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * Where a player's goggles go, half of their hood look (the other half is the {@link HoodShape}). The order is the
 * id, saved with the player and sent over the network, and must match GOGGLES in art/suit3d_model.py. Where a
 * placement would clash with a hood shape the model puts the goggles somewhere sensible instead (GOGGLE_OVERRIDES
 * there): forehead goggles under a low hood's brim go up on the hood.
 */
public enum Goggles {
    NONE,
    /** Pushed up on the hood (on a helmet, up on the helmet); round the neck with the hood down. */
    HOOD,
    /** On the forehead, where many skins paint pushed-up goggles. */
    FOREHEAD,
    /** Down over the eyes. */
    EYES,
    /** Hanging round the neck, on the chest. */
    NECK;

    private static final Goggles[] VALUES = values();

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public Component displayName() {
        return Component.translatable("goggles.everest_expedition." + id());
    }

    /**
     * Whether this placement is its own look with that hood. Forehead goggles don't fit under a low hood's brim (they'd
     * go up on the hood, the same as {@link #HOOD}), so the picker doesn't offer them there.
     */
    public boolean fits(HoodShape shape) {
        return this != FOREHEAD || (shape != HoodShape.LOW && shape != HoodShape.LOW_FUR);
    }

    /** The placement with this id number, or null if there's none. */
    public static Goggles byIndex(int index) {
        return index >= 0 && index < VALUES.length ? VALUES[index] : null;
    }

    /** The placement with this name ({@link #id()}, or "up" / "down"), or null if there's none. */
    public static Goggles byId(String id) {
        if (id.equals("up")) return HOOD;
        if (id.equals("down")) return EYES;
        for (Goggles goggles : VALUES) if (goggles.id().equals(id)) return goggles;
        return null;
    }
}
