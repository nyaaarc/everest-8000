package dev.everest8000.expedition.client.suit;

import dev.everest8000.expedition.gear.EverestGear;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * A check for the in-game tests (everest-testdriver's {@code invoke} step): each suit piece with a red dye in a
 * crafting grid, through the game's own recipe lookup, as the crafting table would. Changes nothing.
 */
public final class SuitSelfTest {
    private SuitSelfTest() {}

    /** "piece -> #rrggbb" for each suit piece dyed red, or "no recipe". */
    public static String dyeRecipe() {
        Level level = Minecraft.getInstance().level;
        if (level == null) return "not in a world";
        StringBuilder out = new StringBuilder();
        for (Item piece : List.of(EverestGear.DOWN_HOOD, EverestGear.DOWN_JACKET, EverestGear.DOWN_PANTS, EverestGear.DOWN_BOOTS)) {
            CraftingInput grid = CraftingInput.of(2, 1, List.of(new ItemStack(piece), new ItemStack(Items.RED_DYE)));
            String result = level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, grid, level)
                    .map(recipe -> {
                        ItemStack made = recipe.value().assemble(grid, level.registryAccess());
                        DyedItemColor dyed = made.get(DataComponents.DYED_COLOR);
                        return made.is(piece) && dyed != null ? String.format("#%06X", dyed.rgb()) : "made " + made;
                    })
                    .orElse("no recipe");
            out.append(BuiltInRegistries.ITEM.getKey(piece).getPath()).append(" -> ").append(result).append("; ");
        }
        return out.toString();
    }
}
