package dev.everest8000.expedition.net;

import dev.everest8000.expedition.EverestExpedition;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Another player started or stopped breathing from a cylinder, so clients can show or hide their oxygen mask.
 *
 * @param entityId  the player's entity id
 * @param breathing true while they breathe bottled oxygen
 */
public record BreathingPayload(int entityId, boolean breathing) implements CustomPacketPayload {
    public static final Type<BreathingPayload> TYPE = new Type<>(EverestExpedition.id("breathing"));
    public static final StreamCodec<ByteBuf, BreathingPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, BreathingPayload::entityId,
            ByteBufCodecs.BOOL, BreathingPayload::breathing,
            BreathingPayload::new);

    @Override
    public Type<BreathingPayload> type() {
        return TYPE;
    }
}
