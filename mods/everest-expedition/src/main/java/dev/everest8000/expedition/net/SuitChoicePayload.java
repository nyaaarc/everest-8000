package dev.everest8000.expedition.net;

import dev.everest8000.expedition.EverestExpedition;
import dev.everest8000.expedition.gear.SuitColours;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** The player chose their hood style and suit colours on the picker. The server checks both. */
public record SuitChoicePayload(int style, SuitColours colours) implements CustomPacketPayload {
    public static final Type<SuitChoicePayload> TYPE = new Type<>(EverestExpedition.id("suit_choice"));
    public static final StreamCodec<ByteBuf, SuitChoicePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SuitChoicePayload::style,
            SuitColours.STREAM_CODEC, SuitChoicePayload::colours,
            SuitChoicePayload::new);

    @Override
    public Type<SuitChoicePayload> type() {
        return TYPE;
    }
}
