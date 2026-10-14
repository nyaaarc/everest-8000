package dev.everest8000.expedition.client.suit;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.everest8000.expedition.client.ClientBreathing;
import dev.everest8000.expedition.gear.Hood;
import dev.everest8000.expedition.gear.HoodStyle;
import net.fabricmc.fabric.api.client.rendering.v1.ArmorRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;

/**
 * Draws the down suit as its own 3D models instead of vanilla's armor boxes: the puffy jacket and pants, the boots,
 * and the hood either up or bunched down behind the neck (see {@link Hood}), in the wearer's hood look
 * ({@link HoodStyle}: shape and goggles). Each piece copies the wearer's pose and is drawn in its own dye colour
 * ({@link SuitTextures}).
 */
public final class DownSuitRenderer implements ArmorRenderer {
    private HumanoidModel<LivingEntity> jacket;
    private HumanoidModel<LivingEntity> pants;
    private HumanoidModel<LivingEntity> boots;
    private HumanoidModel<LivingEntity> hoodUp;
    private HumanoidModel<LivingEntity> hoodDown;

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffers, ItemStack stack, LivingEntity entity, EquipmentSlot slot,
                       int light, HumanoidModel<LivingEntity> contextModel) {
        // Fabric calls this for any slot the item sits in; a jacket forced into the head slot draws nothing.
        if (!(stack.getItem() instanceof ArmorItem armor) || armor.getEquipmentSlot() != slot) return;
        if (jacket == null) bake();
        HumanoidModel<LivingEntity> model = switch (slot) {
            case HEAD -> Hood.isDown(stack) ? hoodDown : hoodUp;
            case CHEST -> jacket;
            case LEGS -> pants;
            case FEET -> boots;
            default -> null;
        };
        if (model == null) return;
        contextModel.copyPropertiesTo(model);
        model.setAllVisible(false);
        switch (slot) {
            case HEAD -> {
                // The hood is on the head; hood down lies on the upper back, and goggles round the neck hang on the
                // chest, both of which move with the body rather than the head.
                model.head.visible = model.body.visible = true;
                showStyle(model.head, model.body, model == hoodUp, ClientHoodStyles.of(entity), ClientBreathing.isBreathing(entity));
            }
            case CHEST -> model.body.visible = model.rightArm.visible = model.leftArm.visible = true;
            case LEGS -> model.body.visible = model.rightLeg.visible = model.leftLeg.visible = true;
            case FEET -> model.rightLeg.visible = model.leftLeg.visible = true;
            default -> {
            }
        }
        ArmorRenderer.renderPart(poseStack, buffers, light, stack, model, SuitTextures.of(stack));
    }

    /**
     * Shows the hood parts this look uses (ruff, hat or helmet, goggles) and hides the rest. With the oxygen mask on,
     * some parts give way to a variant that fits round it (goggles round the neck hang lower, over the hose).
     */
    static void showStyle(ModelPart head, ModelPart body, boolean hoodUp, HoodStyle style, boolean masked) {
        String[] optional = hoodUp ? DownSuitModels.HOOD_UP_OPTIONAL : DownSuitModels.HOOD_DOWN_OPTIONAL;
        String[] shown = (hoodUp ? DownSuitModels.HOOD_UP_PARTS : DownSuitModels.HOOD_DOWN_PARTS)
                [style.hood().ordinal()][style.goggles().ordinal()];
        for (String name : optional) {
            ModelPart part = head.hasChild(name) ? head.getChild(name) : body != null && body.hasChild(name) ? body.getChild(name) : null;
            if (part != null) part.visible = isShown(name, shown, masked);
        }
    }

    private static boolean isShown(String name, String[] shown, boolean masked) {
        for (String s : shown) {
            if (masked) {
                for (String[] swap : DownSuitModels.MASK_SWAP) {
                    if (s.equals(swap[0])) s = swap[1];
                }
            }
            if (s.equals(name)) return true;
        }
        return false;
    }

    private void bake() {
        EntityModelSet models = Minecraft.getInstance().getEntityModels();
        jacket = new HumanoidModel<>(models.bakeLayer(DownSuitModels.JACKET));
        pants = new HumanoidModel<>(models.bakeLayer(DownSuitModels.PANTS));
        boots = new HumanoidModel<>(models.bakeLayer(DownSuitModels.BOOTS));
        hoodUp = new HumanoidModel<>(models.bakeLayer(DownSuitModels.HOOD_UP));
        hoodDown = new HumanoidModel<>(models.bakeLayer(DownSuitModels.HOOD_DOWN));
    }
}
