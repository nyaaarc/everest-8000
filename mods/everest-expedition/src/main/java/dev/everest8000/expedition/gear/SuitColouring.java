package dev.everest8000.expedition.gear;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.serialization.MapCodec;
import dev.everest8000.expedition.EverestExpedition;
import dev.everest8000.expedition.compat.TravelersBackpackServer;
import dev.everest8000.expedition.net.SuitChoicePayload;
import dev.everest8000.expedition.net.SuitColoursPayload;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.NonNullList;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.level.storage.loot.functions.LootItemFunctionType;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Players' suit colours. Colours live on the items (minecraft:dyed_color on each suit piece and the backpack), so
 * they travel with them and the pieces dye and wash like leather armour; on top of that each player's chosen colours
 * are saved with them ({@link SuitColours}), so that:
 * <ul>
 *     <li>choosing on the picker dyes the suit pieces and backpacks they carry or wear (jacket and hood in the jacket
 *     colour, pants in the pants colour, packs in the pack colour; the boots keep their own colour), and</li>
 *     <li>kit pieces handed out later (the route's kits, by advancement or {@code /everest kit}) come in those
 *     colours, through the {@link SuitColourFunction} added to the kit loot tables.</li>
 * </ul>
 * Admins: {@code /everest suit <jacket|pants|pack> <colourway|#rrggbb|default>} (for themselves),
 * {@code /everest suit <players> <jacket|pants|pack> <colour>}, and {@code /everest suit reset [players]} (back to
 * the undyed suit and the red pack).
 */
public final class SuitColouring {
    private static final AttachmentType<SuitColours> COLOURS = AttachmentRegistry.create(EverestExpedition.id("suit_colours"),
            builder -> builder.persistent(SuitColours.CODEC).copyOnDeath());
    public static final LootItemFunctionType<SuitColourFunction> FUNCTION_TYPE = Registry.register(
            BuiltInRegistries.LOOT_FUNCTION_TYPE, EverestExpedition.id("suit_colours"),
            new LootItemFunctionType<>(MapCodec.unit(SuitColourFunction.INSTANCE)));
    /** The route datapack's kits: the Lukla backpack and the Base Camp suit. */
    private static final Set<ResourceLocation> KITS = Set.of(
            ResourceLocation.fromNamespaceAndPath("everest", "kits/lukla"),
            ResourceLocation.fromNamespaceAndPath("everest", "kits/base_camp"));
    private static final DynamicCommandExceptionType BAD_COLOUR = new DynamicCommandExceptionType(name -> Component.literal(
            "No colour " + name + ": a colourway (" + String.join(", ", colourwayNames().toList()) + "), #rrggbb or default"));

    private SuitColouring() {}

    public static void register() {
        PayloadTypeRegistry.playC2S().register(SuitChoicePayload.TYPE, SuitChoicePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SuitColoursPayload.TYPE, SuitColoursPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(SuitChoicePayload.TYPE, (payload, context) -> {
            HoodStyle style = HoodStyle.byIndex(payload.style());
            if (style != null) HoodStyles.set(context.player(), style);
            set(context.player(), payload.colours());
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> send(handler.player));
        LootTableEvents.MODIFY.register((key, tableBuilder, source, registries) -> {
            if (KITS.contains(key.location())) tableBuilder.apply(() -> SuitColourFunction.INSTANCE);
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> {
            var suit = Commands.literal("suit")
                    .then(Commands.literal("reset")
                            .executes(ctx -> reset(ctx, List.of(ctx.getSource().getPlayerOrException())))
                            .then(Commands.argument("players", EntityArgument.players())
                                    .executes(ctx -> reset(ctx, EntityArgument.getPlayers(ctx, "players")))));
            // The colour comes last (it may be #rrggbb, which a plain word can't hold), so other players go first:
            // /everest suit <part> <colour> for yourself, /everest suit <players> <part> <colour> for them.
            var forPlayers = Commands.argument("players", EntityArgument.players());
            for (SuitColours.Part part : SuitColours.Part.values()) {
                suit.then(Commands.literal(part.id()).then(colourArgument()
                        .executes(ctx -> colour(ctx, part, List.of(ctx.getSource().getPlayerOrException())))));
                forPlayers.then(Commands.literal(part.id()).then(colourArgument()
                        .executes(ctx -> colour(ctx, part, EntityArgument.getPlayers(ctx, "players")))));
            }
            suit.then(forPlayers);
            // Merges into the admin-only /everest command.
            dispatcher.register(Commands.literal("everest").requires(source -> source.hasPermission(2)).then(suit));
        });
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> colourArgument() {
        return Commands.argument("colour", StringArgumentType.greedyString())
                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                        Stream.concat(colourwayNames(), Stream.of("default")), builder));
    }

    /** The colours the player chose, or null if they never have. */
    public static SuitColours chosen(ServerPlayer player) {
        return player.getAttached(COLOURS);
    }

    /** Saves the player's colours and dyes what they carry and wear to match. */
    public static void set(ServerPlayer player, SuitColours colours) {
        player.setAttached(COLOURS, colours);
        dyeCarried(player, colours);
        send(player);
    }

    /** Dyes a suit piece or backpack for these colours; anything else is left alone. */
    public static void colour(ItemStack stack, SuitColours colours) {
        if (stack.is(EverestGear.DOWN_JACKET) || stack.is(EverestGear.DOWN_HOOD)) {
            dye(stack, colours.jacket());
        } else if (stack.is(EverestGear.DOWN_PANTS)) {
            dye(stack, colours.pants());
        } else if (TravelersBackpackServer.isBackpack(stack)) {
            dye(stack, colours.pack());
        }
    }

    /** The suit pieces and backpacks in the player's inventory (hands and armour included) and on their back. */
    private static void dyeCarried(ServerPlayer player, SuitColours colours) {
        for (NonNullList<ItemStack> list : List.of(player.getInventory().items, player.getInventory().armor,
                player.getInventory().offhand)) {
            for (ItemStack stack : list) colour(stack, colours);
        }
        // Changed in place: the inventory and equipment syncs pick these up. The worn pack needs a push.
        ItemStack worn = TravelersBackpackServer.wornBackpack(player);
        if (!worn.isEmpty()) {
            colour(worn, colours);
            TravelersBackpackServer.sync(player);
        }
    }

    private static void dye(ItemStack stack, int rgb) {
        if (rgb == SuitColours.DEFAULT) {
            stack.remove(DataComponents.DYED_COLOR);
        } else {
            stack.set(DataComponents.DYED_COLOR, new DyedItemColor(rgb, true));
        }
    }

    private static void send(ServerPlayer player) {
        if (!ServerPlayNetworking.canSend(player, SuitColoursPayload.TYPE)) return;
        SuitColours colours = chosen(player);
        ServerPlayNetworking.send(player, new SuitColoursPayload(colours == null ? SuitColours.DEFAULTS : colours));
    }

    private static int colour(CommandContext<CommandSourceStack> ctx, SuitColours.Part part, Collection<ServerPlayer> players)
            throws CommandSyntaxException {
        String name = StringArgumentType.getString(ctx, "colour");
        int rgb = parse(name, part);
        for (ServerPlayer player : players) {
            SuitColours current = chosen(player);
            set(player, (current == null ? SuitColours.DEFAULTS : current).with(part, rgb));
        }
        String shown = rgb == SuitColours.DEFAULT ? "default" : String.format("#%06X", rgb);
        ctx.getSource().sendSuccess(() -> Component.literal("Suit " + part.id() + " " + shown + " for " + players.size()
                + " player(s)"), true);
        return players.size();
    }

    private static int reset(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> players) {
        for (ServerPlayer player : players) set(player, SuitColours.DEFAULTS);
        ctx.getSource().sendSuccess(() -> Component.literal("Suit colours reset for " + players.size() + " player(s)"), true);
        return players.size();
    }

    /** A colourway id (its suit or pack colour, for the part), "default", or #rrggbb. */
    private static int parse(String name, SuitColours.Part part) throws CommandSyntaxException {
        String s = name.trim().toLowerCase(Locale.ROOT);
        if (s.equals("default")) return SuitColours.DEFAULT;
        Colourway colourway = Colourway.byId(s);
        if (colourway != null) return SuitColours.of(colourway, part);
        String hex = s.startsWith("#") ? s.substring(1) : s;
        if (hex.matches("[0-9a-f]{6}")) return Integer.parseInt(hex, 16);
        throw BAD_COLOUR.create(name);
    }

    private static Stream<String> colourwayNames() {
        return Arrays.stream(Colourway.values()).map(Colourway::id);
    }
}
