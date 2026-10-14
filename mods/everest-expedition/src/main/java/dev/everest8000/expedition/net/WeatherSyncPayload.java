package dev.everest8000.expedition.net;

import dev.everest8000.expedition.EverestExpedition;
import dev.everest8000.expedition.weather.WeatherField;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record WeatherSyncPayload(WeatherField field) implements CustomPacketPayload {
    public static final Type<WeatherSyncPayload> TYPE = new Type<>(EverestExpedition.id("weather"));
    public static final StreamCodec<FriendlyByteBuf, WeatherSyncPayload> CODEC =
            CustomPacketPayload.codec(WeatherSyncPayload::write, WeatherSyncPayload::read);

    private void write(FriendlyByteBuf buf) {
        buf.writeFloat(field.windAngle());
        buf.writeFloat(field.jetKmh());
        buf.writeFloat(field.storm());
        buf.writeFloat(field.stormBaseY());
        buf.writeFloat(field.shower());
        buf.writeFloat(field.showerTopY());
    }

    private static WeatherSyncPayload read(FriendlyByteBuf buf) {
        return new WeatherSyncPayload(new WeatherField(
                buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat()));
    }

    @Override
    public Type<WeatherSyncPayload> type() {
        return TYPE;
    }
}
