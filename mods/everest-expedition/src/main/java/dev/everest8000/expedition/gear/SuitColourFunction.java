package dev.everest8000.expedition.gear;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import net.minecraft.world.level.storage.loot.functions.LootItemFunctionType;
import net.minecraft.world.level.storage.loot.parameters.LootContextParam;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

import java.util.Set;

/**
 * Loot function {@code everest_expedition:suit_colours}: dyes suit pieces and backpacks in the colours the player the
 * loot is for ({@code this}) chose on the picker. Added to the route's kit tables (see {@link SuitColouring}), so a
 * Base Camp suit comes in the player's colours; items for someone who hasn't chosen are left as they are.
 */
public final class SuitColourFunction implements LootItemFunction {
    public static final SuitColourFunction INSTANCE = new SuitColourFunction();

    private SuitColourFunction() {}

    @Override
    public LootItemFunctionType<SuitColourFunction> getType() {
        return SuitColouring.FUNCTION_TYPE;
    }

    @Override
    public Set<LootContextParam<?>> getReferencedContextParams() {
        return Set.of(LootContextParams.THIS_ENTITY);
    }

    @Override
    public ItemStack apply(ItemStack stack, LootContext context) {
        if (context.getParamOrNull(LootContextParams.THIS_ENTITY) instanceof ServerPlayer player) {
            SuitColours colours = SuitColouring.chosen(player);
            if (colours != null) SuitColouring.colour(stack, colours);
        }
        return stack;
    }
}
