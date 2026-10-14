package dev.everest8000.expedition.gear;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.serialization.Codec;
import dev.everest8000.expedition.EverestExpedition;
import dev.everest8000.expedition.net.HoodStyleChoicePayload;
import dev.everest8000.expedition.net.HoodStylePayload;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * Each player's hood look ({@link HoodStyle}: shape and goggles): saved with the player (kept through death and
 * restarts) and sent to every client. A player who hasn't picked one is asked on joining; they can change it with
 * the picker (/hood), or an admin with {@code /everest hood <shape> [players]} and
 * {@code /everest goggles <placement> [players]} ({@code /everest hood reset} forgets the choice, so they're asked
 * again). The four old style names still work with {@code /everest hood} and set both halves.
 */
public final class HoodStyles {
    /** The look, saved as its two ids. */
    private static final AttachmentType<HoodStyle> LOOK = AttachmentRegistry.create(EverestExpedition.id("hood_look"),
            builder -> builder.persistent(HoodStyle.CODEC).copyOnDeath());
    /** The fixed style a player picked before shape and goggles were separate (mod 0.3.x); moved to LOOK when read. */
    private static final AttachmentType<Integer> LEGACY_STYLE = AttachmentRegistry.create(EverestExpedition.id("hood_style"),
            builder -> builder.persistent(Codec.INT).copyOnDeath());
    private static final DynamicCommandExceptionType UNKNOWN_SHAPE = new DynamicCommandExceptionType(name -> Component.literal(
            "No hood called " + name + " (" + String.join(", ", shapeNames().toList()) + ")"));
    private static final DynamicCommandExceptionType UNKNOWN_GOGGLES = new DynamicCommandExceptionType(name -> Component.literal(
            "No goggle placement called " + name + " (" + String.join(", ", goggleNames().toList()) + ")"));

    private HoodStyles() {}

    public static void register() {
        PayloadTypeRegistry.playS2C().register(HoodStylePayload.TYPE, HoodStylePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(HoodStyleChoicePayload.TYPE, HoodStyleChoicePayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(HoodStyleChoicePayload.TYPE, (payload, context) -> {
            HoodStyle style = HoodStyle.byIndex(payload.style());
            if (style != null) set(context.player(), style);
        });
        // A joining player learns everyone's look (their own included, -1 if they've yet to pick), and everyone
        // else learns theirs.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer joined = handler.player;
            for (ServerPlayer other : server.getPlayerList().getPlayers()) {
                send(joined, payload(other));
                if (other != joined) send(other, payload(joined));
            }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> dispatcher.register(
                // Merges into the admin-only /everest command.
                Commands.literal("everest").requires(source -> source.hasPermission(2))
                        .then(Commands.literal("hood")
                                .then(Commands.argument("hood", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(Stream.of(shapeNames(),
                                                Stream.of("forehead", "goggles_down", "reset")).flatMap(s -> s), builder))
                                        .executes(ctx -> hoodCommand(ctx, List.of(ctx.getSource().getPlayerOrException())))
                                        .then(Commands.argument("players", EntityArgument.players())
                                                .executes(ctx -> hoodCommand(ctx, EntityArgument.getPlayers(ctx, "players"))))))
                        .then(Commands.literal("goggles")
                                .then(Commands.argument("goggles", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(goggleNames(), builder))
                                        .executes(ctx -> gogglesCommand(ctx, List.of(ctx.getSource().getPlayerOrException())))
                                        .then(Commands.argument("players", EntityArgument.players())
                                                .executes(ctx -> gogglesCommand(ctx, EntityArgument.getPlayers(ctx, "players"))))))));
    }

    /** The player's look, or null if they haven't picked one. A look saved by mod 0.3.x is moved over here. */
    public static HoodStyle picked(ServerPlayer player) {
        HoodStyle style = player.getAttached(LOOK);
        if (style != null) return style;
        Integer legacy = player.getAttached(LEGACY_STYLE);
        if (legacy == null) return null;
        style = HoodStyle.fromLegacyIndex(legacy);
        player.removeAttached(LEGACY_STYLE);
        if (style != null) player.setAttached(LOOK, style);
        return style;
    }

    /** The player's look, or the default if they haven't picked one. */
    public static HoodStyle get(ServerPlayer player) {
        HoodStyle style = picked(player);
        return style == null ? HoodStyle.DEFAULT : style;
    }

    public static void set(ServerPlayer player, HoodStyle style) {
        player.removeAttached(LEGACY_STYLE);
        player.setAttached(LOOK, style);
        broadcast(player.server, payload(player));
    }

    private static void update(ServerPlayer player, UnaryOperator<HoodStyle> change) {
        set(player, change.apply(get(player)));
    }

    private static int hoodCommand(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> players) throws CommandSyntaxException {
        String name = StringArgumentType.getString(ctx, "hood");
        if (name.equals("reset")) {
            for (ServerPlayer player : players) {
                player.removeAttached(LOOK);
                player.removeAttached(LEGACY_STYLE);
                broadcast(player.server, payload(player));
            }
            ctx.getSource().sendSuccess(() -> Component.literal("Hood look reset for " + players.size()
                    + " player(s); they'll be asked again when they next join"), true);
            return players.size();
        }
        // The old fixed style names set both halves, as they always did; a hood shape keeps the goggles.
        HoodStyle legacy = HoodStyle.isLegacyName(name) && !name.equals("expedition") && !name.equals("parka")
                ? HoodStyle.byId(name) : null;
        HoodShape shape = HoodShape.byId(name);
        if (legacy == null && shape == null) throw UNKNOWN_SHAPE.create(name);
        for (ServerPlayer player : players) {
            if (legacy != null) {
                set(player, legacy);
            } else {
                update(player, style -> style.withHood(shape));
            }
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Hood " + name + " for " + players.size() + " player(s)"), true);
        return players.size();
    }

    private static int gogglesCommand(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> players) throws CommandSyntaxException {
        String name = StringArgumentType.getString(ctx, "goggles");
        Goggles goggles = Goggles.byId(name);
        if (goggles == null) throw UNKNOWN_GOGGLES.create(name);
        for (ServerPlayer player : players) update(player, style -> style.withGoggles(goggles));
        ctx.getSource().sendSuccess(() -> Component.literal("Goggles " + goggles.id() + " for " + players.size() + " player(s)"), true);
        return players.size();
    }

    private static Stream<String> shapeNames() {
        return Arrays.stream(HoodShape.values()).map(HoodShape::id);
    }

    private static Stream<String> goggleNames() {
        return Stream.concat(Arrays.stream(Goggles.values()).map(Goggles::id), Stream.of("up", "down"));
    }

    private static HoodStylePayload payload(ServerPlayer player) {
        HoodStyle style = picked(player);
        return new HoodStylePayload(player.getUUID(), style == null ? -1 : style.index());
    }

    private static void broadcast(MinecraftServer server, HoodStylePayload payload) {
        for (ServerPlayer player : PlayerLookup.all(server)) send(player, payload);
    }

    private static void send(ServerPlayer to, HoodStylePayload payload) {
        if (ServerPlayNetworking.canSend(to, HoodStylePayload.TYPE)) ServerPlayNetworking.send(to, payload);
    }
}
