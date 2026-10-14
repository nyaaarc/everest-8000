package dev.everest8000.radio.world;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.everest8000.radio.signal.Occlusion;
import dev.everest8000.radio.signal.SignalModel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Operator diagnostics: /radio status lists the live radio links and their degradation, and who is talking; /radio
 * probe from to checks the signal between two points in the source's dimension (run it again after a moment if part
 * of the terrain was still being read from disk). /radio key players seconds|on|off holds players' radios up to their
 * faces as if they were talking, for testing and filming (on lasts until off or they leave).
 */
public final class RadioCommand {
    private RadioCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, Supplier<RadioTracker> tracker,
                                Supplier<RadioKeying> keying) {
        dispatcher.register(Commands.literal("radio")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("status").executes(ctx -> status(ctx, keying.get())))
                .then(Commands.literal("probe")
                        .then(Commands.argument("from", Vec3Argument.vec3())
                                .then(Commands.argument("to", Vec3Argument.vec3())
                                        .executes(ctx -> probe(ctx, tracker.get())))))
                .then(Commands.literal("key")
                        .then(Commands.argument("players", EntityArgument.players())
                                .then(Commands.literal("off").executes(ctx -> key(ctx, keying.get(), 0)))
                                .then(Commands.literal("on").executes(ctx -> key(ctx, keying.get(), Double.POSITIVE_INFINITY)))
                                .then(Commands.argument("seconds", DoubleArgumentType.doubleArg(0.05, 86_400))
                                        .executes(ctx -> key(ctx, keying.get(), DoubleArgumentType.getDouble(ctx, "seconds")))))));
    }

    private static int status(CommandContext<CommandSourceStack> ctx, RadioKeying keying) {
        Map<UUID, Map<UUID, Float>> links = RadioTracker.current().bySender();
        var players = ctx.getSource().getServer().getPlayerList();
        Set<UUID> keyed = keying == null ? Set.of() : keying.keyed();
        if (!keyed.isEmpty()) {
            StringBuilder line = new StringBuilder("Radio up to their face:");
            keyed.forEach(id -> line.append(' ').append(name(players.getPlayer(id), id))
                    .append(keying.isForced(id) ? " (forced)" : ""));
            ctx.getSource().sendSuccess(() -> Component.literal(line.toString()), false);
        }
        if (links.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("Nobody is holding a radio."), false);
            return 0;
        }
        links.forEach((sender, receivers) -> {
            StringBuilder line = new StringBuilder(name(players.getPlayer(sender), sender)).append(" transmits to:");
            if (receivers.isEmpty()) {
                line.append(" nobody (no one else carries a radio)");
            }
            receivers.forEach((receiver, d) -> line.append(' ').append(name(players.getPlayer(receiver), receiver))
                    .append(String.format(Locale.ROOT, " (%.2f)", d)));
            ctx.getSource().sendSuccess(() -> Component.literal(line.toString()), false);
        });
        return links.size();
    }

    private static int probe(CommandContext<CommandSourceStack> ctx, RadioTracker tracker) {
        if (tracker == null) {
            return 0;
        }
        Vec3 from = Vec3Argument.getVec3(ctx, "from"), to = Vec3Argument.getVec3(ctx, "to");
        Occlusion.Result r = tracker.probe(ctx.getSource().getLevel(), from, to);
        double distance = from.distanceTo(to);
        float d = SignalModel.degradation(distance, r.fraction(), tracker.config());
        String msg = String.format(Locale.ROOT,
                "distance %.0f blocks, below ground %.1f%% (%d of %d samples known), degradation %.2f "
                        + "(distance %.2f + terrain %.2f)",
                distance, r.fraction() * 100, r.known(), r.samples(), d,
                SignalModel.distancePart(distance, tracker.config()), SignalModel.occlusionPart(r.fraction(), tracker.config()));
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return Math.round(d * 100);
    }

    private static int key(CommandContext<CommandSourceStack> ctx, RadioKeying keying, double seconds)
            throws CommandSyntaxException {
        if (keying == null) {
            return 0;
        }
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "players");
        for (ServerPlayer p : targets) {
            if (seconds > 0) {
                keying.force(p, seconds);
            } else {
                keying.release(p);
            }
        }
        String who = targets.size() == 1 ? targets.iterator().next().getGameProfile().getName() : targets.size() + " players";
        String msg = seconds <= 0 ? who + ": radio pose released (back to their voice)"
                : Double.isInfinite(seconds) ? who + ": radio up until /radio key ... off"
                : String.format(Locale.ROOT, "%s: radio up for %.1f s", who, seconds);
        ctx.getSource().sendSuccess(() -> Component.literal(msg), true);
        return targets.size();
    }

    private static String name(ServerPlayer player, UUID id) {
        return player != null ? player.getGameProfile().getName() : id.toString();
    }
}
