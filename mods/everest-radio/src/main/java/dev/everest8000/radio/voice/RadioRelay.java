package dev.everest8000.radio.voice;

import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.VolumeCategory;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoderMode;
import dev.everest8000.radio.EverestRadio;
import dev.everest8000.radio.RadioConfig;
import dev.everest8000.radio.dsp.RadioVoiceChain;
import dev.everest8000.radio.world.RadioTracker;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Carries radio transmissions, server-side. While a player holds a radio, each of their microphone frames is decoded
 * once and then, for every other player carrying a radio, run through that pair's {@link RadioVoiceChain}, re-encoded
 * with that pair's encoder and sent as a static (non-positional) sound, so it is heard wherever the sender is.
 * <p>
 * Everything below runs on one "Everest Radio" thread, off Simple Voice Chat's packet thread: per-sender decoders and
 * per-pair encoders, filters and channels are confined to it. A 20 ms timer on the same thread notices transmissions
 * that ended without a stop packet and plays the closing squelch, one frame per tick.
 */
final class RadioRelay {
    static final String CATEGORY = "everest_radio";
    private static final long FRAME_MS = 20;
    private static final long STALE_FRAME_NANOS = TimeUnit.MILLISECONDS.toNanos(200);
    private static final long IDLE_CLOSE_NANOS = TimeUnit.SECONDS.toNanos(60);

    private final VoicechatServerApi api;
    private final RadioConfig config;
    private final long endTimeoutNanos;
    private final ScheduledExecutorService thread;
    private final Map<UUID, Transmission> transmissions = new HashMap<>(); // radio thread only
    private final Set<UUID> open = ConcurrentHashMap.newKeySet(); // senders mid-transmission, read by the voice thread

    RadioRelay(VoicechatServerApi api, RadioConfig config) {
        this.api = api;
        this.config = config;
        this.endTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(config.getInt(RadioConfig.END_TIMEOUT_MS));
        this.thread = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Everest Radio");
            t.setDaemon(true);
            return t;
        });
        registerCategory();
        thread.scheduleAtFixedRate(guarded(this::tick), FRAME_MS, FRAME_MS, TimeUnit.MILLISECONDS);
    }

    /** Simple Voice Chat's packet thread: every microphone packet from every player, so this must stay cheap. */
    void onMicrophonePacket(UUID sender, byte[] opus) {
        if (RadioTracker.current().receiversOf(sender) == null && !open.contains(sender)) {
            return;
        }
        long received = System.nanoTime();
        byte[] data = opus.clone();
        submit(() -> onFrame(sender, data, received));
    }

    void onDisconnect(UUID player) {
        submit(() -> forget(player));
    }

    void shutdown() {
        submit(() -> {
            transmissions.values().forEach(Transmission::close);
            transmissions.clear();
            open.clear();
        });
        thread.shutdown();
    }

    private void onFrame(UUID sender, byte[] opus, long received) {
        Map<UUID, Float> receivers = RadioTracker.current().receiversOf(sender);
        Transmission tx = transmissions.get(sender);
        if (opus.length == 0 || receivers == null) {
            // An empty packet means the push-to-talk key (or voice activation) let go; no receivers means the
            // radio left their hands.
            if (tx != null && tx.on) {
                tx.end();
            }
            return;
        }
        if (tx == null) {
            if (receivers.isEmpty()) {
                return;
            }
            tx = new Transmission(sender, api.createDecoder());
            transmissions.put(sender, tx);
        }
        short[] pcm = tx.decoder.decode(opus); // even when late, to keep the decoder in step with the stream
        if (pcm == null || pcm.length != RadioVoiceChain.FRAME_SIZE || System.nanoTime() - received > STALE_FRAME_NANOS) {
            return; // fell behind: drop rather than let the delay build up
        }
        tx.voice(pcm, receivers);
    }

    private void tick() {
        long now = System.nanoTime();
        Iterator<Transmission> it = transmissions.values().iterator();
        while (it.hasNext()) {
            Transmission tx = it.next();
            if (!tx.tick(now)) {
                tx.close();
                it.remove();
            }
        }
    }

    private void forget(UUID player) {
        Transmission own = transmissions.remove(player);
        if (own != null) {
            own.close();
        }
        for (Transmission tx : transmissions.values()) {
            Link link = tx.links.remove(player);
            if (link != null) {
                link.close();
            }
        }
    }

    private Link openLink(UUID sender, UUID receiver) {
        VoicechatConnection connection = api.getConnectionOf(receiver);
        if (connection == null || !connection.isInstalled()) {
            return null;
        }
        UUID id = UUID.nameUUIDFromBytes((EverestRadio.MOD_ID + ":" + sender + ">" + receiver).getBytes(StandardCharsets.UTF_8));
        StaticAudioChannel channel = api.createStaticAudioChannel(id);
        if (channel == null) {
            return null;
        }
        channel.addTarget(connection);
        channel.setCategory(CATEGORY);
        return new Link(channel, api.createEncoder(OpusEncoderMode.VOIP),
                new RadioVoiceChain(config, id.getLeastSignificantBits() ^ System.nanoTime()));
    }

    private void registerCategory() {
        try {
            VolumeCategory category = api.volumeCategoryBuilder()
                    .setId(CATEGORY)
                    .setName("Expedition Radio") // the protocol allows 16 characters
                    .setDescription("Voices coming in over your Expedition Radio")
                    .setIcon(icon())
                    .build();
            api.registerVolumeCategory(category);
        } catch (RuntimeException e) {
            EverestRadio.LOG.warn("Couldn't register the radio volume category", e);
        }
    }

    /** The item texture as the category icon: 16x16, indexed [x][y], in the ABGR order NativeImage takes. */
    private static int[][] icon() {
        try (InputStream in = RadioRelay.class.getResourceAsStream("/assets/everest_radio/textures/item/radio.png")) {
            BufferedImage img = in == null ? null : ImageIO.read(in);
            if (img == null) {
                return null;
            }
            int[][] icon = new int[16][16];
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    int argb = img.getRGB(x * img.getWidth() / 16, y * img.getHeight() / 16);
                    icon[x][y] = (argb & 0xFF00FF00) | (argb >> 16 & 0xFF) | (argb & 0xFF) << 16;
                }
            }
            return icon;
        } catch (Exception e) {
            return null;
        }
    }

    private void submit(Runnable task) {
        try {
            thread.execute(guarded(task));
        } catch (RejectedExecutionException ignored) {
            // shutting down
        }
    }

    private static Runnable guarded(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable t) {
                EverestRadio.LOG.error("Radio relay error", t);
            }
        };
    }

    /** One player's transmissions: their decoder and a link to each receiver. */
    private final class Transmission {
        final UUID sender;
        final OpusDecoder decoder;
        final Map<UUID, Link> links = new HashMap<>();
        boolean on;
        int tailFrame = -1;
        long lastFrame;
        long quietSince = System.nanoTime();

        Transmission(UUID sender, OpusDecoder decoder) {
            this.sender = sender;
            this.decoder = decoder;
        }

        void voice(short[] pcm, Map<UUID, Float> receivers) {
            if (!on) {
                on = true;
                tailFrame = -1;
                open.add(sender);
                links.values().forEach(l -> l.chain.beginTransmission());
            }
            lastFrame = System.nanoTime();
            links.entrySet().removeIf(e -> {
                if (receivers.containsKey(e.getKey())) {
                    return false;
                }
                e.getValue().close(); // put their radio away
                return true;
            });
            for (Map.Entry<UUID, Float> e : receivers.entrySet()) {
                Link link = links.get(e.getKey());
                if (link == null) {
                    link = openLink(sender, e.getKey());
                    if (link == null) {
                        continue; // not connected to voice chat
                    }
                    links.put(e.getKey(), link);
                    link.chain.beginTransmission();
                }
                link.degradation = e.getValue();
                link.chain.processVoice(pcm, link.degradation, link.pcm);
                link.send();
            }
        }

        void end() {
            on = false;
            tailFrame = 0;
        }

        /** Every 20 ms. Returns false once this sender has been quiet long enough to free its codecs. */
        boolean tick(long now) {
            if (on && now - lastFrame > endTimeoutNanos) {
                end();
            }
            if (!on && tailFrame >= 0) {
                boolean done = true;
                for (Link link : links.values()) {
                    if (tailFrame < link.chain.tailFrames()) {
                        link.chain.renderTail(tailFrame, link.degradation, link.pcm);
                        link.send();
                        done = false;
                    }
                }
                tailFrame++;
                if (done) {
                    links.values().forEach(Link::finish);
                    decoder.resetState();
                    tailFrame = -1;
                    open.remove(sender);
                    quietSince = now;
                }
            }
            return on || tailFrame >= 0 || now - quietSince < IDLE_CLOSE_NANOS;
        }

        void close() {
            links.values().forEach(Link::close);
            links.clear();
            decoder.close();
            open.remove(sender);
        }
    }

    /** One sender heard by one receiver: its own channel, encoder and radio sound state. */
    private static final class Link {
        final StaticAudioChannel channel;
        final OpusEncoder encoder;
        final RadioVoiceChain chain;
        final short[] pcm = new short[RadioVoiceChain.FRAME_SIZE];
        float degradation;

        Link(StaticAudioChannel channel, OpusEncoder encoder, RadioVoiceChain chain) {
            this.channel = channel;
            this.encoder = encoder;
            this.chain = chain;
        }

        void send() {
            channel.send(encoder.encode(pcm));
        }

        /** The transmission is over: tell the client the stream ended and start fresh next time. */
        void finish() {
            channel.flush();
            encoder.resetState();
            chain.reset();
        }

        void close() {
            channel.flush();
            encoder.close();
        }
    }
}
