package dev.everest8000.expedition.client.suit;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.everest8000.expedition.EverestExpedition;
import dev.everest8000.expedition.client.ClientBreathing;
import dev.everest8000.expedition.gear.EverestGear;
import dev.everest8000.expedition.gear.Goggles;
import dev.everest8000.expedition.gear.HoodShape;
import dev.everest8000.expedition.gear.HoodStyle;
import dev.everest8000.expedition.net.HoodStyleChoicePayload;
import dev.everest8000.expedition.net.HoodTogglePayload;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.ArmorRenderer;
import net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback;
import net.fabricmc.fabric.api.object.builder.v1.client.model.FabricModelPredicateProviderRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FastColor;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.DyedItemColor;
import org.lwjgl.glfw.GLFW;

import java.util.Arrays;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * Client side of the 3D down suit: its models and dye colours, the oxygen mask and expedition pack layers, the hood
 * up/down key, and the suit picker (hood, goggles and colours: on first joining, from its key, or /hood).
 */
public final class DownSuitClient {
    /** J, not H: in this modpack H already belongs to AutoHUD's toggle and Simple Voice Chat's hide-icons key. */
    private static final KeyMapping TOGGLE_HOOD = new KeyMapping("key.everest_expedition.toggle_hood",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, "key.categories.everest_expedition");
    /** Unbound by default: rarely needed (there's /hood and the picker on first joining), and the pack has no free key. */
    private static final KeyMapping HOOD_STYLE = new KeyMapping("key.everest_expedition.hood_style",
            InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), "key.categories.everest_expedition");
    /** Ask a new player for their look this long after joining, once the world is on screen. */
    private static final int ASK_DELAY_TICKS = 60;
    private static final Item[] SUIT = {EverestGear.DOWN_HOOD, EverestGear.DOWN_JACKET, EverestGear.DOWN_PANTS, EverestGear.DOWN_BOOTS};
    private static int askIn = -1;
    private static boolean openPicker;

    private DownSuitClient() {}

    public static void register() {
        DownSuitModels.register();
        SuitTextures.register();
        ArmorRenderer.register(new DownSuitRenderer(), SUIT);
        // Inventory icons: a dyed piece switches to its tintable icon (layer 0 takes the dye, layer 1 the trims).
        ColorProviderRegistry.ITEM.register((stack, tintIndex) -> tintIndex == 0
                ? FastColor.ARGB32.opaque(DyedItemColor.getOrDefault(stack, -1)) : -1, SUIT);
        for (Item piece : SUIT) {
            FabricModelPredicateProviderRegistry.register(piece, EverestExpedition.id("dyed"),
                    (stack, level, entity, seed) -> stack.has(DataComponents.DYED_COLOR) ? 1f : 0f);
        }
        LivingEntityFeatureRendererRegistrationCallback.EVENT.register((type, renderer, helper, context) -> {
            if (renderer instanceof PlayerRenderer player) {
                helper.register(new OxygenMaskLayer(player, context.getModelSet()));
                helper.register(new ExpeditionPackLayer(player, context.getModelSet()));
            }
        });
        KeyBindingHelper.registerKeyBinding(TOGGLE_HOOD);
        KeyBindingHelper.registerKeyBinding(HOOD_STYLE);
        ClientTickEvents.END_CLIENT_TICK.register(DownSuitClient::tick);
        ClientBreathing.register();
        ClientHoodStyles.register();
        // /hood opens the picker (once chat has closed); /hood <shape> and /goggles <placement> change one half of
        // the look straight away. The four old style names still work with /hood.
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registries) -> {
            dispatcher.register(ClientCommandManager.literal("hood")
                    .executes(ctx -> {
                        openPicker = true;
                        return 1;
                    })
                    .then(ClientCommandManager.argument("hood", StringArgumentType.word())
                            .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(Stream.concat(
                                    Arrays.stream(HoodShape.values()).map(HoodShape::id), Stream.of("forehead", "goggles_down")), builder))
                            .executes(ctx -> {
                                String name = StringArgumentType.getString(ctx, "hood");
                                HoodShape shape = HoodShape.byId(name);
                                if (shape != null) return choose(ctx.getSource(), style -> style.withHood(shape));
                                HoodStyle legacy = HoodStyle.byId(name);
                                return legacy == null ? 0 : choose(ctx.getSource(), style -> legacy);
                            })));
            dispatcher.register(ClientCommandManager.literal("goggles")
                    .executes(ctx -> {
                        openPicker = true;
                        return 1;
                    })
                    .then(ClientCommandManager.argument("goggles", StringArgumentType.word())
                            .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                    Arrays.stream(Goggles.values()).map(Goggles::id), builder))
                            .executes(ctx -> {
                                Goggles goggles = Goggles.byId(StringArgumentType.getString(ctx, "goggles"));
                                return goggles == null ? 0 : choose(ctx.getSource(), style -> style.withGoggles(goggles));
                            })));
        });
    }

    private static int choose(FabricClientCommandSource source, UnaryOperator<HoodStyle> change) {
        HoodStyle own = ClientHoodStyles.own();
        HoodStyle style = change.apply(own == null ? HoodStyle.DEFAULT : own);
        if (!ClientPlayNetworking.canSend(HoodStyleChoicePayload.TYPE)) return 0;
        ClientPlayNetworking.send(new HoodStyleChoicePayload(style.index()));
        source.sendFeedback(Component.translatable("message.everest_expedition.hood_style", style.displayName()));
        return 1;
    }

    private static void tick(Minecraft mc) {
        while (HOOD_STYLE.consumeClick()) openPicker = true;
        if (ClientHoodStyles.takeAsk()) askIn = ASK_DELAY_TICKS;
        // Still unset by then (not picked with /hood or set by an admin meanwhile)?
        if (askIn > 0 && --askIn == 0 && ClientHoodStyles.own() == null) openPicker = true;
        if (openPicker && mc.player != null && mc.screen == null) {
            openPicker = false;
            mc.setScreen(new HoodStyleScreen(HOOD_STYLE.isUnbound() ? null : HOOD_STYLE.getTranslatedKeyMessage()));
        }
        while (TOGGLE_HOOD.consumeClick()) {
            if (mc.player != null && mc.player.getItemBySlot(EquipmentSlot.HEAD).is(EverestGear.DOWN_HOOD)
                    && ClientPlayNetworking.canSend(HoodTogglePayload.TYPE)) {
                ClientPlayNetworking.send(HoodTogglePayload.INSTANCE);
            }
        }
    }
}
