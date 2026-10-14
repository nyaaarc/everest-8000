package dev.everest8000.expedition.gear;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.chat.Component;

import java.util.Map;

/**
 * How a player's down hood looks: its shape and where the goggles go, picked on first join and changeable any time.
 * Purely cosmetic: every look keeps out the same cold.
 *
 * <p>Before shape and goggles were separate choices there were four fixed styles; their names (expedition, parka,
 * forehead, goggles_down) still work in {@link #byId}, and a player's saved style from then is read with
 * {@link #fromLegacyIndex}.
 */
public record HoodStyle(HoodShape hood, Goggles goggles) {
    /** The expedition hood with its goggles pushed up on the hood: what anyone who hasn't picked wears. */
    public static final HoodStyle DEFAULT = new HoodStyle(HoodShape.EXPEDITION, Goggles.HOOD);
    /** Saved with the player as the two ids. */
    public static final Codec<HoodStyle> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.xmap(id -> orDefault(HoodShape.byId(id), DEFAULT.hood), HoodShape::id)
                    .fieldOf("hood").forGetter(HoodStyle::hood),
            Codec.STRING.xmap(id -> orDefault(Goggles.byId(id), DEFAULT.goggles), Goggles::id)
                    .fieldOf("goggles").forGetter(HoodStyle::goggles)
    ).apply(i, HoodStyle::new));
    private static final Map<String, HoodStyle> LEGACY = Map.of(
            "expedition", new HoodStyle(HoodShape.EXPEDITION, Goggles.HOOD),
            "parka", new HoodStyle(HoodShape.PARKA, Goggles.NONE),
            "forehead", new HoodStyle(HoodShape.EXPEDITION, Goggles.FOREHEAD),
            "goggles_down", new HoodStyle(HoodShape.EXPEDITION, Goggles.EYES));
    private static final String[] LEGACY_ORDER = {"expedition", "parka", "forehead", "goggles_down"};
    /** Room for this many goggle placements in {@link #index()}. */
    private static final int GOGGLE_SLOTS = 16;

    public HoodStyle withHood(HoodShape hood) {
        return new HoodStyle(hood, goggles);
    }

    public HoodStyle withGoggles(Goggles goggles) {
        return new HoodStyle(hood, goggles);
    }

    /** One number for the look, for the network. */
    public int index() {
        return hood.ordinal() * GOGGLE_SLOTS + goggles.ordinal();
    }

    /** "shape/goggles", which {@link #byId} reads back. */
    public String id() {
        return hood.id() + "/" + goggles.id();
    }

    public Component displayName() {
        return Component.translatable("hood_style.everest_expedition.look", hood.displayName(), goggles.displayName());
    }

    /** The look with this {@link #index()}, or null if there's none. */
    public static HoodStyle byIndex(int index) {
        if (index < 0) return null;
        HoodShape hood = HoodShape.byIndex(index / GOGGLE_SLOTS);
        Goggles goggles = Goggles.byIndex(index % GOGGLE_SLOTS);
        return hood == null || goggles == null ? null : new HoodStyle(hood, goggles);
    }

    /**
     * The look named by "shape/goggles" or one of the four old fixed style names, or null if it names none. (Everest
     * Cinema reaches this by reflection to dress its actors.)
     */
    public static HoodStyle byId(String id) {
        HoodStyle legacy = LEGACY.get(id);
        if (legacy != null) return legacy;
        int slash = id.indexOf('/');
        if (slash < 0) return null;
        HoodShape hood = HoodShape.byId(id.substring(0, slash));
        Goggles goggles = Goggles.byId(id.substring(slash + 1));
        return hood == null || goggles == null ? null : new HoodStyle(hood, goggles);
    }

    /** One of the four old fixed styles, by the id it was saved under, or null. */
    public static HoodStyle fromLegacyIndex(int index) {
        return index >= 0 && index < LEGACY_ORDER.length ? LEGACY.get(LEGACY_ORDER[index]) : null;
    }

    /** True for the four old fixed style names. */
    public static boolean isLegacyName(String id) {
        return LEGACY.containsKey(id);
    }

    private static <T> T orDefault(T value, T fallback) {
        return value == null ? fallback : value;
    }
}
