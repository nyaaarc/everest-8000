package dev.everest8000.expedition.gear;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.Locale;

/**
 * The colours a player chose for their suit: jacket (the hood follows it), pants and pack, each an RGB dye colour or
 * {@link #DEFAULT} for the undyed look (the gold suit with its black hood, the red pack). The colours themselves live
 * on the items as minecraft:dyed_color; this is the player's choice, kept so kit pieces handed out later match.
 */
public record SuitColours(int jacket, int pants, int pack) {
    public static final int DEFAULT = -1;
    public static final SuitColours DEFAULTS = new SuitColours(DEFAULT, DEFAULT, DEFAULT);
    public static final Codec<SuitColours> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.optionalFieldOf("jacket", DEFAULT).forGetter(SuitColours::jacket),
            Codec.INT.optionalFieldOf("pants", DEFAULT).forGetter(SuitColours::pants),
            Codec.INT.optionalFieldOf("pack", DEFAULT).forGetter(SuitColours::pack)
    ).apply(i, SuitColours::new));
    /** Colours go +1 so DEFAULT is a single byte. */
    private static final StreamCodec<ByteBuf, Integer> COLOUR = ByteBufCodecs.VAR_INT.map(v -> v - 1, v -> v + 1);
    public static final StreamCodec<ByteBuf, SuitColours> STREAM_CODEC = StreamCodec.composite(
            COLOUR, SuitColours::jacket, COLOUR, SuitColours::pants, COLOUR, SuitColours::pack, SuitColours::new);

    public SuitColours {
        jacket = clean(jacket);
        pants = clean(pants);
        pack = clean(pack);
    }

    /** What each colour is for. */
    public enum Part {
        JACKET, PANTS, PACK;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public int get(Part part) {
        return switch (part) {
            case JACKET -> jacket;
            case PANTS -> pants;
            case PACK -> pack;
        };
    }

    public SuitColours with(Part part, int rgb) {
        return switch (part) {
            case JACKET -> new SuitColours(rgb, pants, pack);
            case PANTS -> new SuitColours(jacket, rgb, pack);
            case PACK -> new SuitColours(jacket, pants, rgb);
        };
    }

    /** The colourway's colour for this part: its suit colour, or its pack colour for the pack. */
    public static int of(Colourway colourway, Part part) {
        return part == Part.PACK ? colourway.packRgb : colourway.suitRgb;
    }

    private static int clean(int rgb) {
        return rgb < 0 ? DEFAULT : rgb & 0xFFFFFF;
    }
}
