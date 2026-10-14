package dev.everest8000.expedition.net;

import dev.everest8000.expedition.EverestExpedition;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * The server's day time to a fraction of a tick, and whether the daylight cycle runs. Receiving it tells a client the
 * day is paced (see {@code DayPace}), so it runs its own clock at the same speed between syncs.
 */
public record DayClockPayload(double dayTime, boolean running) implements CustomPacketPayload {
    public static final Type<DayClockPayload> TYPE = new Type<>(EverestExpedition.id("clock"));
    public static final StreamCodec<FriendlyByteBuf, DayClockPayload> CODEC =
            CustomPacketPayload.codec(DayClockPayload::write, DayClockPayload::read);

    private void write(FriendlyByteBuf buf) {
        buf.writeDouble(dayTime);
        buf.writeBoolean(running);
    }

    private static DayClockPayload read(FriendlyByteBuf buf) {
        return new DayClockPayload(buf.readDouble(), buf.readBoolean());
    }

    @Override
    public Type<DayClockPayload> type() {
        return TYPE;
    }
}
