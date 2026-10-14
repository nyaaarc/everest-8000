package dev.everest8000.radio.net;

import dev.everest8000.radio.EverestRadio;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * A player started or stopped talking into the radio in their hand, so clients raise it to their face (or lower it).
 * While they keep talking the server repeats {@code active = true} every second; a client that hears nothing for a
 * few seconds lowers the radio on its own, so a lost "stop" can't leave an arm stuck up.
 *
 * @param entityId the talker's entity id
 * @param active   true while they talk
 */
public record RadioKeyedPayload(int entityId, boolean active) implements CustomPacketPayload {
    public static final Type<RadioKeyedPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(EverestRadio.MOD_ID, "radio_keyed"));
    public static final StreamCodec<ByteBuf, RadioKeyedPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, RadioKeyedPayload::entityId,
            ByteBufCodecs.BOOL, RadioKeyedPayload::active,
            RadioKeyedPayload::new);

    /** How often (ticks) the server repeats {@code active = true} while someone talks. */
    public static final int REFRESH_TICKS = 20;

    @Override
    public Type<RadioKeyedPayload> type() {
        return TYPE;
    }
}
