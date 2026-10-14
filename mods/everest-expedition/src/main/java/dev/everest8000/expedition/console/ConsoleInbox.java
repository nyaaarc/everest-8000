package dev.everest8000.expedition.console;

import dev.everest8000.expedition.EverestExpedition;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Lets tools on this machine run console commands without the console window: drop a {@code .cmd} file in the
 * server's {@code console} folder, one command per line, and the output appears next to it as a {@code .out} file.
 * Only someone who can already write to the server folder can use it; there's no network port.
 */
public final class ConsoleInbox {
    private static final Path INBOX = FabricLoader.getInstance().getGameDir().resolve("console");

    private ConsoleInbox() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.isDedicatedServer() && server.getTickCount() % 20 == 0) poll(server);
        });
    }

    private static void poll(MinecraftServer server) {
        if (!Files.isDirectory(INBOX)) return;
        List<Path> files;
        try (Stream<Path> listing = Files.list(INBOX)) {
            files = listing.filter(p -> p.getFileName().toString().endsWith(".cmd")).sorted().toList();
        } catch (IOException e) {
            return;
        }
        for (Path file : files) run(server, file);
    }

    private static void run(MinecraftServer server, Path file) {
        StringBuilder out = new StringBuilder();
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            Files.delete(file);
            CommandSourceStack source = server.createCommandSourceStack().withSource(new Capture(out));
            for (String line : lines) {
                String command = line.strip();
                if (command.isEmpty() || command.startsWith("#")) continue;
                EverestExpedition.LOG.info("Console inbox: {}", command);
                out.append("> ").append(command).append('\n');
                server.getCommands().performPrefixedCommand(source, command);
            }
        } catch (IOException e) {
            out.append("! ").append(e).append('\n');
        }
        String name = file.getFileName().toString();
        Path result = file.resolveSibling(name.substring(0, name.length() - 4) + ".out");
        try {
            Files.writeString(result, out.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            EverestExpedition.LOG.warn("Couldn't write {}", result, e);
        }
    }

    private record Capture(StringBuilder out) implements CommandSource {
        @Override
        public void sendSystemMessage(Component message) {
            out.append(message.getString()).append('\n');
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }
    }
}
