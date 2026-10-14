package dev.everest8000.expedition.net;

import dev.everest8000.expedition.EverestExpedition;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** The hood key was pressed: put the worn down hood up or down. The server checks the player is wearing it. */
public record HoodTogglePayload() implements CustomPacketPayload {
    public static final HoodTogglePayload INSTANCE = new HoodTogglePayload();
    public static final Type<HoodTogglePayload> TYPE = new Type<>(EverestExpedition.id("hood_toggle"));
    public static final StreamCodec<ByteBuf, HoodTogglePayload> CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public Type<HoodTogglePayload> type() {
        return TYPE;
    }
}
