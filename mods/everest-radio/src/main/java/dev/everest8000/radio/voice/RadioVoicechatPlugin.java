package dev.everest8000.radio.voice;

import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;
import dev.everest8000.radio.EverestRadio;
import dev.everest8000.radio.world.RadioKeying;
import dev.everest8000.radio.world.RadioTracker;

import java.util.UUID;

/**
 * The Simple Voice Chat side: every microphone packet goes to the {@link RadioRelay}, which drops it at once unless
 * the speaker is holding a radio, and voice from someone holding one tells {@link RadioKeying} they're talking.
 * Normal proximity voice is left alone.
 */
public final class RadioVoicechatPlugin implements VoicechatPlugin {
    private volatile RadioRelay relay;

    @Override
    public String getPluginId() {
        return EverestRadio.MOD_ID;
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, event -> {
            if (relay != null) {
                relay.shutdown();
            }
            relay = new RadioRelay(event.getVoicechat(), EverestRadio.config());
            EverestRadio.LOG.info("Everest Radio relay running");
        });
        registration.registerEvent(VoicechatServerStoppedEvent.class, event -> {
            if (relay != null) {
                relay.shutdown();
                relay = null;
            }
        });
        registration.registerEvent(MicrophonePacketEvent.class, event -> {
            RadioRelay r = relay;
            VoicechatConnection sender = event.getSenderConnection();
            if (sender == null) {
                return;
            }
            UUID id = sender.getPlayer().getUuid();
            byte[] opus = event.getPacket().getOpusEncodedData();
            if (opus.length > 0 && RadioTracker.current().receiversOf(id) != null) {
                RadioKeying.onVoice(id); // talking into the radio in their hand: up to their face it goes
            }
            if (r != null) {
                r.onMicrophonePacket(id, opus);
            }
        });
        registration.registerEvent(PlayerDisconnectedEvent.class, event -> {
            RadioRelay r = relay;
            if (r != null) {
                r.onDisconnect(event.getPlayerUuid());
            }
        });
    }
}
