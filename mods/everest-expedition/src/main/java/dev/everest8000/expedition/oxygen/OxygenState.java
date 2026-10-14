package dev.everest8000.expedition.oxygen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Saved with the player, so logging out and back in doesn't reset a hypoxic climber. Not kept through death.
 *
 * @param spo2          blood oxygen, 0..1
 * @param collapseTicks ticks spent collapsed at empty
 */
public record OxygenState(float spo2, int collapseTicks) {
    public static final OxygenState FULL = new OxygenState(1f, 0);
    public static final Codec<OxygenState> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.FLOAT.fieldOf("spo2").forGetter(OxygenState::spo2),
            Codec.INT.fieldOf("collapse_ticks").forGetter(OxygenState::collapseTicks)
    ).apply(i, OxygenState::new));
}
