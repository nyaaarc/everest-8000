package dev.everest8000.expedition.client;

import dev.everest8000.expedition.client.compat.DistantHorizonsFog;
import dev.everest8000.expedition.client.suit.DownSuitClient;
import dev.everest8000.expedition.client.wind.PlumeRenderer;
import dev.everest8000.expedition.client.wind.SnowFxRenderType;
import dev.everest8000.expedition.client.wind.WindFxConfig;
import dev.everest8000.expedition.net.DayClockPayload;
import dev.everest8000.expedition.net.OxygenSyncPayload;
import dev.everest8000.expedition.net.WeatherSyncPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

public final class EverestExpeditionClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(WeatherSyncPayload.TYPE, (payload, context) -> ClientWeather.receive(payload.field()));
        ClientPlayNetworking.registerGlobalReceiver(OxygenSyncPayload.TYPE, (payload, context) -> ClientOxygen.receive(payload));
        ClientPlayNetworking.registerGlobalReceiver(DayClockPayload.TYPE,
                (payload, context) -> ClientClock.receive(payload.dayTime(), payload.running()));
        WindFxConfig.load();
        CoreShaderRegistrationCallback.EVENT.register(context -> {
            PlumeRenderer.registerShaders(context);
            SnowFxRenderType.registerShader(context);
        });
        WorldRenderEvents.END.register(PlumeRenderer::capture);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ClientWeather.reset();
            PlumeRenderer.reset();
            ClientOxygen.reset();
            ClientClock.reset();
            ClientSky.reset();
        });
        HudRenderCallback.EVENT.register(OxygenGauge::draw);
        DownSuitClient.register();
        if (FabricLoader.getInstance().isModLoaded("distanthorizons")) DistantHorizonsFog.register();
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            ClientWeather.tick(mc);
            ClientSky.tick();
            WindEffects.tick(mc);
            PlumeRenderer.tick(mc);
            WindSound.update(mc);
            ClientOxygen.tick(mc);
        });
    }
}
