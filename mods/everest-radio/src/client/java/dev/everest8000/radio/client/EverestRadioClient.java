package dev.everest8000.radio.client;

import net.fabricmc.api.ClientModInitializer;

/** Client side: raising a talker's radio to their face (see {@link RadioPose}). */
public final class EverestRadioClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        RadioPose.register();
    }
}
