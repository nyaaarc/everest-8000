package dev.everest8000.expedition.client.compat;

import dev.everest8000.expedition.EverestExpedition;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Traveler's Backpack, read without compiling against it: which backpack a player has on their back, and whether
 * they've hidden it. Used to draw the expedition pack in place of the backpack's own model.
 */
public final class TravelersBackpackCompat {
    private static final boolean LOADED = FabricLoader.getInstance().isModLoaded("travelersbackpack");
    private static final ResourceLocation IS_VISIBLE = ResourceLocation.fromNamespaceAndPath("travelersbackpack", "is_visible");
    private static MethodHandle isWearing;
    private static MethodHandle getWearing;
    private static boolean broken;

    private TravelersBackpackCompat() {}

    /** Installed and its API found, so the expedition pack takes over drawing worn backpacks. */
    public static boolean available() {
        if (!LOADED || broken) return false;
        if (isWearing == null) {
            try {
                Class<?> utils = Class.forName("com.tiviacz.travelersbackpack.component.ComponentUtils");
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                isWearing = lookup.findStatic(utils, "isWearingBackpack", MethodType.methodType(boolean.class, Player.class));
                getWearing = lookup.findStatic(utils, "getWearingBackpack", MethodType.methodType(ItemStack.class, Player.class));
            } catch (ReflectiveOperationException | LinkageError e) {
                fail(e);
                return false;
            }
        }
        return true;
    }

    /** The backpack on this player's back, or an empty stack. */
    public static ItemStack wornBackpack(Player player) {
        if (!available()) return ItemStack.EMPTY;
        try {
            if (!(boolean) isWearing.invoke(player)) return ItemStack.EMPTY;
            ItemStack stack = (ItemStack) getWearing.invoke(player);
            return stack == null ? ItemStack.EMPTY : stack;
        } catch (Throwable e) {
            fail(e);
            return ItemStack.EMPTY;
        }
    }

    /** Traveler's Backpack lets players hide their worn backpack. */
    public static boolean isShown(ItemStack backpack) {
        DataComponentType<?> visible = BuiltInRegistries.DATA_COMPONENT_TYPE.get(IS_VISIBLE);
        return visible == null || !Boolean.FALSE.equals(backpack.get(visible));
    }

    private static void fail(Throwable e) {
        broken = true;
        EverestExpedition.LOG.warn("Couldn't read Traveler's Backpack's worn backpack; its own model will show instead", e);
    }
}
