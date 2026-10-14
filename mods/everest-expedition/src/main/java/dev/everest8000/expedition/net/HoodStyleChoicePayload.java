package dev.everest8000.expedition.net;

import dev.everest8000.expedition.EverestExpedition;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** The player picked a hood look (HoodStyle#index) with /hood or /goggles. The server checks it. */
public record HoodStyleChoicePayload(int style) implements CustomPacketPayload {
    public static final Type<HoodStyleChoicePayload> TYPE = new Type<>(EverestExpedition.id("hood_style_choice"));
    public static final StreamCodec<ByteBuf, HoodStyleChoicePayload> CODEC =
            ByteBufCodecs.VAR_INT.map(HoodStyleChoicePayload::new, HoodStyleChoicePayload::style);

    @Override
    public Type<HoodStyleChoicePayload> type() {
        return TYPE;
    }
}
