package dev.everest8000.expedition.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import dev.everest8000.expedition.exposure.Exposure;
import dev.everest8000.expedition.exposure.Frostbite;
import dev.everest8000.expedition.gear.EverestGear;
import dev.everest8000.expedition.oxygen.Oxygen;
import dev.everest8000.expedition.time.DayPace;
import dev.everest8000.expedition.time.ServerClock;
import dev.everest8000.expedition.weather.CloudDeck;
import dev.everest8000.expedition.weather.WeatherDirector;
import dev.everest8000.expedition.weather.WeatherField;
import dev.everest8000.expedition.weather.WeatherModel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Admin tools for running and testing the mountain: {@code /everest weather ...}, {@code /everest go <stop>},
 * {@code /everest kit <kit> [players]} (the route datapack's kits, as its advancements hand them out) and
 * {@code /everest oxygen [spo2 <0..1>]}.
 */
public final class EverestCommand {
    private static final Map<String, Vec3> STOPS = new LinkedHashMap<>();
    private static final DynamicCommandExceptionType UNKNOWN_STOP =
            new DynamicCommandExceptionType(name -> Component.literal("No stop called " + name));
    /** Loot tables from the everest_route datapack. */
    private static final Map<String, String> KITS = Map.of("lukla", "everest:kits/lukla", "basecamp", "everest:kits/base_camp");
    /** A forced shower reaches up to here unless told otherwise: just over the Khumbu valley lodge. */
    private static final float SHOWER_TOP_Y = -200f;
    private static final DynamicCommandExceptionType UNKNOWN_KIT =
            new DynamicCommandExceptionType(name -> Component.literal("No kit called " + name + " (lukla, basecamp)"));

    static {
        STOPS.put("lukla", new Vec3(10976.5, -1285, 23352.5));
        STOPS.put("namche", new Vec3(9716.5, -989, 17236.5));
        STOPS.put("tengboche", new Vec3(12627.5, -714, 15660.5));
        STOPS.put("pangboche", new Vec3(14498.5, -597, 14184.5));
        STOPS.put("khumbu", new Vec3(14472.5, -358, 11304.5));
        STOPS.put("basecamp", new Vec3(16747.5, 63, 7309.5));
        STOPS.put("camp1", new Vec3(18324.5, 543, 8006.5));
        STOPS.put("camp2", new Vec3(19350.5, 731, 8150.5));
        STOPS.put("camp3", new Vec3(20430.5, 1193, 8917.5));
        STOPS.put("southcol", new Vec3(21067.5, 1556, 8562.5));
        STOPS.put("summit", new Vec3(20706.5, 2031, 7774.5));
    }

    private EverestCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("everest")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("go")
                        .then(Commands.argument("stop", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(STOPS.keySet(), builder))
                                .executes(EverestCommand::go)))
                .then(Commands.literal("kit")
                        .then(Commands.argument("kit", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(KITS.keySet(), builder))
                                .executes(ctx -> kit(ctx, List.of(ctx.getSource().getPlayerOrException())))
                                .then(Commands.argument("players", EntityArgument.players())
                                        .executes(ctx -> kit(ctx, EntityArgument.getPlayers(ctx, "players"))))))
                .then(Commands.literal("oxygen")
                        .executes(EverestCommand::oxygen)
                        .then(Commands.literal("spo2")
                                .then(Commands.argument("level", FloatArgumentType.floatArg(0f, 1f))
                                        .executes(EverestCommand::setSpo2))))
                .then(Commands.literal("weather")
                        .then(Commands.literal("status").executes(EverestCommand::status))
                        .then(Commands.literal("storm")
                                .then(Commands.argument("strength", FloatArgumentType.floatArg(0f, 1f))
                                        .executes(ctx -> storm(ctx, 10, Float.NaN))
                                        .then(Commands.argument("minutes", IntegerArgumentType.integer(1, 120))
                                                .executes(ctx -> storm(ctx, IntegerArgumentType.getInteger(ctx, "minutes"), Float.NaN))
                                                .then(Commands.argument("baseY", IntegerArgumentType.integer(-2000, 2000))
                                                        .executes(ctx -> storm(ctx, IntegerArgumentType.getInteger(ctx, "minutes"),
                                                                IntegerArgumentType.getInteger(ctx, "baseY")))))))
                        .then(Commands.literal("clear").executes(EverestCommand::clear))
                        .then(Commands.literal("jet")
                                .then(Commands.argument("kmh", FloatArgumentType.floatArg(0f, 250f))
                                        .executes(EverestCommand::jet)))
                        .then(Commands.literal("wind")
                                .then(Commands.argument("fromDegrees", FloatArgumentType.floatArg(0f, 360f))
                                        .executes(EverestCommand::wind)))
                        .then(Commands.literal("shower")
                                .then(Commands.argument("strength", FloatArgumentType.floatArg(0f, 1f))
                                        .executes(ctx -> shower(ctx, 10, SHOWER_TOP_Y))
                                        .then(Commands.argument("minutes", IntegerArgumentType.integer(1, 120))
                                                .executes(ctx -> shower(ctx, IntegerArgumentType.getInteger(ctx, "minutes"), SHOWER_TOP_Y))
                                                .then(Commands.argument("topY", IntegerArgumentType.integer(-2000, 2000))
                                                        .executes(ctx -> shower(ctx, IntegerArgumentType.getInteger(ctx, "minutes"),
                                                                IntegerArgumentType.getInteger(ctx, "topY")))))))));
    }

    private static int go(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String name = StringArgumentType.getString(ctx, "stop");
        Vec3 pos = STOPS.get(name);
        if (pos == null) throw UNKNOWN_STOP.create(name);
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel overworld = ctx.getSource().getServer().overworld();
        player.teleportTo(overworld, pos.x, pos.y, pos.z, Set.of(), player.getYRot(), player.getXRot());
        reply(ctx, "%s, %,.0f m", name, WeatherField.altitudeMeters(pos.y));
        return 1;
    }

    private static int kit(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> players) throws CommandSyntaxException {
        String name = StringArgumentType.getString(ctx, "kit");
        String table = KITS.get(name);
        if (table == null) throw UNKNOWN_KIT.create(name);
        LootTable loot = ctx.getSource().getServer().reloadableRegistries()
                .getLootTable(ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.parse(table)));
        for (ServerPlayer player : players) {
            LootParams params = new LootParams.Builder(player.serverLevel())
                    .withParameter(LootContextParams.THIS_ENTITY, player)
                    .withParameter(LootContextParams.ORIGIN, player.position())
                    .create(LootContextParamSets.ADVANCEMENT_REWARD);
            for (ItemStack stack : loot.getRandomItems(params)) {
                if (!player.addItem(stack)) player.drop(stack, false);
            }
        }
        reply(ctx, "Gave the %s kit (%s) to %d player(s)", name, table, players.size());
        return players.size();
    }

    private static int oxygen(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        reply(ctx, "%s: %s", player.getScoreboardName(), Oxygen.describe(player));
        return 1;
    }

    private static int setSpo2(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Oxygen.setSpo2(player, FloatArgumentType.getFloat(ctx, "level"));
        reply(ctx, "%s: %s", player.getScoreboardName(), Oxygen.describe(player));
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        WeatherModel model = WeatherDirector.state(ctx.getSource().getServer()).model();
        WeatherField f = model.field();
        reply(ctx, "Storm: %s, %.2f strength down to y %.0f (%,.0f m), %ds left in phase, next storm can form in %ds",
                model.phase(), f.storm(), f.stormBaseY(), WeatherField.altitudeMeters(f.stormBaseY()),
                model.phaseTicksLeft() / 20, model.cooldown() / 20);
        reply(ctx, "Jet stream %.0f km/h from the %s, valley showers %.2f up to y %.0f",
                f.jetKmh(), compassFrom(f.windAngle()), f.shower(), f.showerTopY());
        double time = ServerClock.exactDayTime(ctx.getSource().getServer());
        float deck = CloudDeck.baseY(f);
        reply(ctx, "Time of day %.1f (sun %s), the clock runs %.3f day ticks a tick; cloud base y %.0f (%,.0f m)",
                DayPace.wrap(time), DayPace.sunUp(time) ? "up" : "down", DayPace.rate(time), deck, WeatherField.altitudeMeters(deck));
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player != null) {
            ServerLevel level = player.serverLevel();
            boolean covered = Exposure.underCover(level, player.getX(), player.getEyeY(), player.getZ());
            float share = Exposure.windShare(level, f, player.getX(), player.getY(), player.getZ(), player.getEyeY());
            float warmth = EverestGear.warmth(player);
            float night = Frostbite.night(level);
            reply(ctx, "Here (%,.0f m): precipitation %.2f, blizzard %.2f, wind %.0f km/h (%.0f%% reaches you), "
                            + "altitude cold %.2f, night %.2f, suit warmth %.2f, chill %.2f%s",
                    WeatherField.altitudeMeters(player.getY()), f.precipitationAt(player.getY()), f.blizzardAt(player.getY()),
                    f.windKmhAt(player.getY()), share * 100f, Frostbite.altitudeCold(player.getY(), night), night, warmth,
                    Frostbite.chill(f, player.getY(), share, covered, warmth, night), covered ? ", under cover" : "");
        }
        return 1;
    }

    private static int storm(CommandContext<CommandSourceStack> ctx, int minutes, float baseY) {
        float strength = FloatArgumentType.getFloat(ctx, "strength");
        WeatherModel model = WeatherDirector.state(ctx.getSource().getServer()).model();
        float base = Float.isNaN(baseY) ? model.field().stormBaseY() : baseY;
        model.begin(strength, base, 600, minutes * 1200, 1200);
        WeatherDirector.broadcast(ctx.getSource().getServer());
        reply(ctx, "Storm building to %.2f over 30 s, down to y %.0f (%,.0f m), holding %d min",
                strength, base, WeatherField.altitudeMeters(base), minutes);
        return 1;
    }

    private static int clear(CommandContext<CommandSourceStack> ctx) {
        WeatherDirector.state(ctx.getSource().getServer()).model().clear(600);
        WeatherDirector.broadcast(ctx.getSource().getServer());
        reply(ctx, "Storm clearing over 30 s");
        return 1;
    }

    private static int jet(CommandContext<CommandSourceStack> ctx) {
        float kmh = FloatArgumentType.getFloat(ctx, "kmh");
        WeatherDirector.state(ctx.getSource().getServer()).model().setJet(kmh);
        WeatherDirector.broadcast(ctx.getSource().getServer());
        reply(ctx, "Jet stream set to %.0f km/h at the summit", kmh);
        return 1;
    }

    private static int wind(CommandContext<CommandSourceStack> ctx) {
        float from = FloatArgumentType.getFloat(ctx, "fromDegrees");
        WeatherModel model = WeatherDirector.state(ctx.getSource().getServer()).model();
        model.setWindFrom(from);
        WeatherDirector.broadcast(ctx.getSource().getServer());
        reply(ctx, "Wind now from %.0f degrees (the %s); it wanders back toward its usual heading over the next minutes",
                from, compassFrom(model.field().windAngle()));
        return 1;
    }

    private static int shower(CommandContext<CommandSourceStack> ctx, int minutes, float topY) {
        float strength = FloatArgumentType.getFloat(ctx, "strength");
        WeatherDirector.state(ctx.getSource().getServer()).model().forceShower(strength, minutes * 1200, topY);
        WeatherDirector.broadcast(ctx.getSource().getServer());
        reply(ctx, "Valley shower at %.2f for %d min, up to y %.0f (%,.0f m)", strength, minutes, topY, WeatherField.altitudeMeters(topY));
        return 1;
    }

    /** Where the wind comes from, as a compass point. The angle is the direction it blows toward (0 = east, +z = south). */
    private static String compassFrom(float windAngle) {
        String[] points = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        double bearingFrom = Math.toDegrees(windAngle) + 90.0 + 180.0;
        return points[(int) Math.floorMod(Math.round(bearingFrom / 45.0), 8L)];
    }

    private static void reply(CommandContext<CommandSourceStack> ctx, String format, Object... args) {
        Component message = Component.literal(String.format(format, args));
        ctx.getSource().sendSuccess(() -> message, false);
    }
}
