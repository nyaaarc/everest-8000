package dev.everest8000.expedition.gear;

import dev.everest8000.expedition.EverestExpedition;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.cauldron.CauldronInteraction;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Expedition gear handed out at Base Camp: the down suit and oxygen cylinders. None of it can be crafted, but the
 * suit pieces dye like leather armour (the minecraft:dyeable tag) and wash clean in a water cauldron.
 */
public final class EverestGear {
    /** Oxygen left in a cylinder, in points; one point lasts a second at a walk. */
    public static final DataComponentType<Integer> OXYGEN = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE,
            EverestExpedition.id("oxygen"), DataComponentType.<Integer>builder()
                    .persistent(ExtraCodecs.NON_NEGATIVE_INT).networkSynchronized(ByteBufCodecs.VAR_INT).build());

    /** Leather-like protection; the point of the suit is warmth (see {@link #warmth}). Repaired with feathers, being down. */
    public static final Holder<ArmorMaterial> DOWN = Registry.registerForHolder(BuiltInRegistries.ARMOR_MATERIAL,
            EverestExpedition.id("down"), new ArmorMaterial(
                    new EnumMap<>(Map.of(ArmorItem.Type.HELMET, 1, ArmorItem.Type.CHESTPLATE, 3,
                            ArmorItem.Type.LEGGINGS, 2, ArmorItem.Type.BOOTS, 1, ArmorItem.Type.BODY, 3)),
                    15, SoundEvents.ARMOR_EQUIP_LEATHER, () -> Ingredient.of(Items.FEATHER),
                    List.of(new ArmorMaterial.Layer(EverestExpedition.id("down_suit"))), 0f, 0f));

    public static final Item DOWN_HOOD = suitPiece("down_hood", ArmorItem.Type.HELMET);
    public static final Item DOWN_JACKET = suitPiece("down_jacket", ArmorItem.Type.CHESTPLATE);
    public static final Item DOWN_PANTS = suitPiece("down_pants", ArmorItem.Type.LEGGINGS);
    public static final Item DOWN_BOOTS = suitPiece("down_boots", ArmorItem.Type.BOOTS);
    public static final Item OXYGEN_CYLINDER = Registry.register(BuiltInRegistries.ITEM, EverestExpedition.id("oxygen_cylinder"),
            new OxygenCylinderItem(new Item.Properties().stacksTo(1).component(OXYGEN, OxygenCylinderItem.CAPACITY)));

    /** How much of the cold each piece keeps out; the full suit adds up to 1. */
    private static final Map<Item, Float> WARMTH = Map.of(DOWN_HOOD, 0.2f, DOWN_JACKET, 0.35f, DOWN_PANTS, 0.25f, DOWN_BOOTS, 0.2f);
    private static final EquipmentSlot[] ARMOR_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private EverestGear() {}

    public static void register() {
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, EverestExpedition.id("gear"), FabricItemGroup.builder()
                .title(Component.translatable("itemGroup.everest_expedition.gear"))
                .icon(() -> new ItemStack(OXYGEN_CYLINDER))
                .displayItems((params, output) -> {
                    output.accept(DOWN_HOOD);
                    output.accept(DOWN_JACKET);
                    output.accept(DOWN_PANTS);
                    output.accept(DOWN_BOOTS);
                    output.accept(OXYGEN_CYLINDER);
                })
                .build());
        // Washing a dyed piece in a water cauldron takes the dye out, as with leather armour.
        for (Item piece : List.of(DOWN_HOOD, DOWN_JACKET, DOWN_PANTS, DOWN_BOOTS)) {
            CauldronInteraction.WATER.map().put(piece, CauldronInteraction.DYED_ITEM);
        }
    }

    /** 0 = no suit, 1 = the full down suit. */
    public static float warmth(LivingEntity entity) {
        float warmth = 0f;
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack stack = entity.getItemBySlot(slot);
            warmth += Hood.isDown(stack) ? Hood.WARMTH_DOWN : WARMTH.getOrDefault(stack.getItem(), 0f);
        }
        return Math.min(warmth, 1f);
    }

    private static Item suitPiece(String name, ArmorItem.Type type) {
        return Registry.register(BuiltInRegistries.ITEM, EverestExpedition.id(name),
                new ArmorItem(DOWN, type, new Item.Properties().durability(type.getDurability(15))));
    }
}
