package dev.everest8000.cinema.actor;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.everest8000.cinema.CinemaClient;
import dev.everest8000.cinema.rpc.RpcException;
import dev.everest8000.cinema.spec.ActorSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.item.ItemParser;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Dresses actors: items from id strings (components allowed), plus the hooks other mods read to draw extras on a player:
 * the Everest Expedition hood style and oxygen mask, and Traveler's Backpack's worn pack. Those are reached by
 * reflection, so a missing mod or a changed internal is reported, never fatal.
 */
public final class Outfits {
    public static final String DOWN_HOOD = "everest_expedition:down_hood";
    public static final String DOWN_JACKET = "everest_expedition:down_jacket";
    public static final String DOWN_PANTS = "everest_expedition:down_pants";
    public static final String DOWN_BOOTS = "everest_expedition:down_boots";
    public static final String PACK = "travelersbackpack:standard";

    private Outfits() {}

    public static ItemStack item(String spec) {
        Minecraft mc = Minecraft.getInstance();
        try {
            ItemParser.ItemResult r = new ItemParser(mc.level.registryAccess()).parse(new StringReader(spec));
            ItemStack s = new ItemStack(r.item(), 1);
            s.applyComponents(r.components());
            return s;
        } catch (CommandSyntaxException e) {
            throw new RpcException(RpcException.INVALID_PARAMS, "bad item \"" + spec + "\": " + e.getMessage());
        }
    }

    public static String hood(boolean down) {
        return hood(down, -1);
    }

    /** The down hood, up or down, dyed rgb (-1 = undyed: the black hood of the gold suit). */
    public static String hood(boolean down, int rgb) {
        java.util.List<String> c = new java.util.ArrayList<>();
        if (down) c.add("everest_expedition:hood_down=true");
        if (rgb >= 0) c.add("minecraft:dyed_color={rgb:" + rgb + "}");
        return DOWN_HOOD + (c.isEmpty() ? "" : "[" + String.join(",", c) + "]");
    }

    private static String dyed(String id, int rgb) {
        return rgb >= 0 ? id + "[minecraft:dyed_color={rgb:" + rgb + "}]" : id;
    }

    /** Puts the outfit on; returns warnings for parts another mod didn't accept. */
    public static java.util.List<String> apply(ActorPlayer p, ActorSpec.Outfit o) {
        java.util.List<String> warnings = new java.util.ArrayList<>();
        if (o.suit()) {
            p.setItemSlot(EquipmentSlot.HEAD, item(hood(o.hoodDown(), o.jacketRgb())));
            p.setItemSlot(EquipmentSlot.CHEST, item(dyed(DOWN_JACKET, o.jacketRgb())));
            p.setItemSlot(EquipmentSlot.LEGS, item(dyed(DOWN_PANTS, o.pantsRgb())));
            p.setItemSlot(EquipmentSlot.FEET, item(DOWN_BOOTS));
        }
        if (o.mainhand() != null) p.setItemSlot(EquipmentSlot.MAINHAND, item(o.mainhand()));
        if (o.offhand() != null) p.setItemSlot(EquipmentSlot.OFFHAND, item(o.offhand()));
        String w = setHoodStyle(p.getUUID(), o.hoodStyle());
        if (w != null) warnings.add(w);
        if (o.mask()) {
            w = setBreathing(p.getId(), true);
            if (w != null) warnings.add(w);
        }
        if (o.packRgb() >= 0) {
            w = wearPack(p, item(PACK + "[minecraft:dyed_color={rgb:" + o.packRgb() + "}]"));
            if (w != null) warnings.add(w);
        }
        return warnings;
    }

    // ---- Everest Expedition hooks

    @SuppressWarnings("unchecked")
    public static String setHoodStyle(UUID uuid, String style) {
        try {
            Class<?> c = Class.forName("dev.everest8000.expedition.client.suit.ClientHoodStyles");
            Field f = c.getDeclaredField("STYLES");
            f.setAccessible(true);
            Map<UUID, Object> map = (Map<UUID, Object>) f.get(null);
            if (style == null) {
                map.remove(uuid);
                return null;
            }
            Class<?> e = Class.forName("dev.everest8000.expedition.gear.HoodStyle");
            Object v = e.getMethod("byId", String.class).invoke(null, style);
            if (v == null) return "unknown hood style " + style;
            map.put(uuid, v);
            return null;
        } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
            CinemaClient.LOG.warn("Cinema: couldn't set the hood style", e);
            return "hood style not applied (Everest Expedition hood styles not found: " + e + ")";
        }
    }

    public static String setBreathing(int entityId, boolean on) {
        try {
            Class<?> c = Class.forName("dev.everest8000.expedition.client.ClientBreathing");
            Field f = c.getDeclaredField("BREATHING");
            f.setAccessible(true);
            Object set = f.get(null);
            set.getClass().getMethod(on ? "add" : "remove", int.class).invoke(set, entityId);
            return null;
        } catch (ReflectiveOperationException | LinkageError e) {
            CinemaClient.LOG.warn("Cinema: couldn't set the oxygen mask", e);
            return "oxygen mask not applied (Everest Expedition breathing set not found: " + e + ")";
        }
    }

    // ---- Traveler's Backpack hook

    public static String wearPack(Player p, ItemStack pack) {
        try {
            Class<?> utils = Class.forName("com.tiviacz.travelersbackpack.component.ComponentUtils");
            Method get = utils.getMethod("getComponent", Player.class);
            Optional<?> comp = (Optional<?>) get.invoke(null, p);
            if (comp.isEmpty()) return "the backpack component isn't attached to actors";
            Object c = comp.get();
            Class<?> iface = Class.forName("com.tiviacz.travelersbackpack.component.ITravelersBackpack");
            if (pack == null || pack.isEmpty()) iface.getMethod("removeWearable").invoke(c);
            else iface.getMethod("equipBackpack", ItemStack.class).invoke(c, pack);
            return null;
        } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
            CinemaClient.LOG.warn("Cinema: couldn't put the backpack on", e);
            return "backpack not applied (Traveler's Backpack API not found: " + e + ")";
        }
    }

    /** Cleans up the per-player state other mods keep. */
    public static void forget(ActorPlayer p) {
        setHoodStyle(p.getUUID(), null);
        setBreathing(p.getId(), false);
    }
}
