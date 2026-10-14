package dev.everest8000.expedition.gear;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * A bottle of oxygen. The bar under it shows the oxygen left, but it's our own component rather than durability, so
 * anvils, grindstones and the crafting grid can't merge two half-empty bottles into one.
 */
public final class OxygenCylinderItem extends Item {
    /** Eight minutes at a walk. */
    public static final int CAPACITY = 480;

    public OxygenCylinderItem(Properties properties) {
        super(properties);
    }

    public static boolean is(ItemStack stack) {
        return stack.getItem() instanceof OxygenCylinderItem;
    }

    public static int oxygen(ItemStack stack) {
        return stack.getOrDefault(EverestGear.OXYGEN, 0);
    }

    public static void setOxygen(ItemStack stack, int points) {
        stack.set(EverestGear.OXYGEN, Mth.clamp(points, 0, CAPACITY));
    }

    public static float fill(ItemStack stack) {
        return oxygen(stack) / (float) CAPACITY;
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return oxygen(stack) < CAPACITY;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        return Math.round(13f * fill(stack));
    }

    /** Cyan when full, through green and yellow to red when empty. */
    @Override
    public int getBarColor(ItemStack stack) {
        return Mth.hsvToRgb(0.5f * fill(stack), 0.9f, 1f);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        int seconds = oxygen(stack);
        lines.add(Component.translatable("item.everest_expedition.oxygen_cylinder.left",
                String.format("%d:%02d", seconds / 60, seconds % 60)).withStyle(ChatFormatting.GRAY));
    }
}
