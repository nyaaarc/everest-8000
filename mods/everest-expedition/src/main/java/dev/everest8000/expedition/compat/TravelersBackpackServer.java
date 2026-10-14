package dev.everest8000.expedition.compat;

import dev.everest8000.expedition.EverestExpedition;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Traveler's Backpack on the server, read without compiling against it: whether a stack is a backpack, the backpack a
 * player wears, and pushing a changed worn backpack out to clients. Used to colour players' packs.
 */
public final class TravelersBackpackServer {
    private static final boolean LOADED = FabricLoader.getInstance().isModLoaded("travelersbackpack");
    private static Class<?> backpackItem;
    private static MethodHandle isWearing;
    private static MethodHandle getWearing;
    private static MethodHandle synchronise;
    private static boolean broken;

    private TravelersBackpackServer() {}

    private static boolean available() {
        if (!LOADED || broken) return false;
        if (backpackItem == null) {
            try {
                backpackItem = Class.forName("com.tiviacz.travelersbackpack.item.TravelersBackpackItem");
                Class<?> utils = Class.forName("com.tiviacz.travelersbackpack.component.ComponentUtils");
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                isWearing = lookup.findStatic(utils, "isWearingBackpack", MethodType.methodType(boolean.class, Player.class));
                getWearing = lookup.findStatic(utils, "getWearingBackpack", MethodType.methodType(ItemStack.class, Player.class));
                synchronise = lookup.findStatic(utils, "synchronise", MethodType.methodType(void.class, Player.class));
            } catch (ReflectiveOperationException | LinkageError e) {
                fail(e);
                return false;
            }
        }
        return true;
    }

    public static boolean isBackpack(ItemStack stack) {
        return !stack.isEmpty() && available() && backpackItem.isInstance(stack.getItem());
    }

    /** The backpack on this player's back (the live stack, so changes to it stick), or an empty stack. */
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

    /** Sends the player's worn backpack to them and everyone tracking them, after changing it. */
    public static void sync(Player player) {
        if (!available()) return;
        try {
            synchronise.invoke(player);
        } catch (Throwable e) {
            fail(e);
        }
    }

    private static void fail(Throwable e) {
        broken = true;
        EverestExpedition.LOG.warn("Couldn't reach Traveler's Backpack; worn packs won't take players' chosen colours", e);
    }
}
