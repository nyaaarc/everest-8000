package dev.everest8000.expedition.net;

import dev.everest8000.expedition.EverestExpedition;
import dev.everest8000.expedition.gear.SuitColours;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** A player's own chosen suit colours, sent to them so the picker opens on their current choice. */
public record SuitColoursPayload(SuitColours colours) implements CustomPacketPayload {
    public static final Type<SuitColoursPayload> TYPE = new Type<>(EverestExpedition.id("suit_colours"));
    public static final StreamCodec<ByteBuf, SuitColoursPayload> CODEC =
            SuitColours.STREAM_CODEC.map(SuitColoursPayload::new, SuitColoursPayload::colours);

    @Override
    public Type<SuitColoursPayload> type() {
        return TYPE;
    }
}
