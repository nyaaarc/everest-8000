package dev.everest8000.expedition.net;

import dev.everest8000.expedition.EverestExpedition;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/**
 * A player's hood look (shape and goggles), for every client to draw their hood with.
 *
 * @param player the player
 * @param style  HoodStyle#index, or -1 when they haven't picked one yet (their own client then asks them)
 */
public record HoodStylePayload(UUID player, int style) implements CustomPacketPayload {
    public static final Type<HoodStylePayload> TYPE = new Type<>(EverestExpedition.id("hood_style"));
    public static final StreamCodec<ByteBuf, HoodStylePayload> CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, HoodStylePayload::player,
            ByteBufCodecs.VAR_INT.map(v -> v - 1, v -> v + 1), HoodStylePayload::style,
            HoodStylePayload::new);

    @Override
    public Type<HoodStylePayload> type() {
        return TYPE;
    }
}
