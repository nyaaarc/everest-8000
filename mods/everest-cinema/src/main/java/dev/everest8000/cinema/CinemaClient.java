package dev.everest8000.cinema;

import dev.everest8000.cinema.api.Api;
import dev.everest8000.cinema.rpc.RpcServer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Entry point: reads config/everest-cinema.json and, if enabled, starts the localhost API and the frame hooks. */
public final class CinemaClient implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("everest_cinema");
    public static final int API_VERSION = 1;
    private static Path apiLog;

    @Override
    public void onInitializeClient() {
        Path dir = FabricLoader.getInstance().getConfigDir();
        CinemaConfig config = CinemaConfig.load(dir.resolve("everest-cinema.json"));
        if (!config.enabled()) {
            LOG.info("Cinema is installed but disabled (config/everest-cinema.json)");
            return;
        }
        Cinema.config = config;
        apiLog = FabricLoader.getInstance().getGameDir().resolve("cinema").resolve("api.log");
        try {
            Files.createDirectories(apiLog.getParent());
        } catch (IOException e) {
            apiLog = null;
        }
        ClientTickEvents.START_CLIENT_TICK.register(Cinema::onTickStart);
        ClientTickEvents.END_CLIENT_TICK.register(Cinema::onTickEnd);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) Cinema.chat.onMessage(message);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(Cinema::onDisconnect));
        Cinema.enabled = true;
        try {
            new RpcServer(config.port(), config.token(), Api.methods()).start();
        } catch (IOException e) {
            LOG.error("Cinema: couldn't open the API port {}", config.port(), e);
        }
    }

    public static String modVersion() {
        return FabricLoader.getInstance().getModContainer("everest_cinema")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
    }

    public static synchronized void apiLog(String line) {
        if (apiLog == null) return;
        try {
            Files.writeString(apiLog, LocalTime.now().format(DateTimeFormatter.ISO_LOCAL_TIME) + " " + line + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Logging must never break a call.
        }
    }
}
