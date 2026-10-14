package dev.everest8000.expedition.net;

import dev.everest8000.expedition.EverestExpedition;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.Arrays;

/**
 * One player's breathing, for their oxygen gauge and hypoxia vignette.
 *
 * @param flags     {@link #ACTIVE}, {@link #THIN_AIR}, {@link #REFILLING}
 * @param spo2      blood oxygen in thousandths
 * @param collapse  ticks spent collapsed at empty, 0 when breathing
 * @param cylinders oxygen points in each carried cylinder, in inventory order, -1 for an empty slot
 * @param inUse     index of the cylinder being breathed from, or -1
 */
public record OxygenSyncPayload(int flags, int spo2, int collapse, int[] cylinders, int inUse) implements CustomPacketPayload {
    /** Survival or adventure in the overworld: the gauge can show. */
    public static final int ACTIVE = 1;
    /** Above the oxygen line. */
    public static final int THIN_AIR = 2;
    /** At a camp with a cylinder that isn't full yet. */
    public static final int REFILLING = 4;

    public static final Type<OxygenSyncPayload> TYPE = new Type<>(EverestExpedition.id("oxygen"));
    public static final StreamCodec<FriendlyByteBuf, OxygenSyncPayload> CODEC =
            CustomPacketPayload.codec(OxygenSyncPayload::write, OxygenSyncPayload::read);

    public boolean has(int flag) {
        return (flags & flag) != 0;
    }

    /** Same numbers, so there's nothing new to send. */
    public boolean sameAs(OxygenSyncPayload other) {
        return other != null && flags == other.flags && spo2 == other.spo2 && collapse == other.collapse
                && inUse == other.inUse && Arrays.equals(cylinders, other.cylinders);
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeByte(flags);
        buf.writeVarInt(spo2);
        buf.writeVarInt(collapse);
        buf.writeByte(cylinders.length);
        for (int c : cylinders) buf.writeVarInt(c + 1);
        buf.writeByte(inUse + 1);
    }

    private static OxygenSyncPayload read(FriendlyByteBuf buf) {
        int flags = buf.readByte();
        int spo2 = buf.readVarInt();
        int collapse = buf.readVarInt();
        int[] cylinders = new int[buf.readByte()];
        for (int i = 0; i < cylinders.length; i++) cylinders[i] = buf.readVarInt() - 1;
        return new OxygenSyncPayload(flags, spo2, collapse, cylinders, buf.readByte() - 1);
    }

    @Override
    public Type<OxygenSyncPayload> type() {
        return TYPE;
    }
}
