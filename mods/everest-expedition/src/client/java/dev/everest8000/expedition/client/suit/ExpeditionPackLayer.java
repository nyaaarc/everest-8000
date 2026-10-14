package dev.everest8000.expedition.client.suit;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.everest8000.expedition.client.compat.TravelersBackpackCompat;
import dev.everest8000.expedition.gear.EverestGear;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.FastColor;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;

/**
 * The expedition pack, drawn on any player wearing a Traveler's Backpack in place of that mod's own model (see
 * BackpackLayerMixin). The shell takes the backpack's dye colour, red when undyed; the gear and straps keep theirs.
 * Shoulder straps and the hip belt fit over the down jacket when it's worn, or the bare body when not.
 */
public final class ExpeditionPackLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
    private final HumanoidModel<AbstractClientPlayer> model;
    private final ModelPart shell;
    private final ModelPart gear;
    private final ModelPart strapsSuited;
    private final ModelPart strapsBare;

    public ExpeditionPackLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent, EntityModelSet models) {
        super(parent);
        model = new HumanoidModel<>(models.bakeLayer(DownSuitModels.EXPEDITION_PACK));
        shell = model.body.getChild("pack_shell");
        gear = model.body.getChild("pack_gear");
        strapsSuited = model.body.getChild("straps_suited");
        strapsBare = model.body.getChild("straps_bare");
        model.setAllVisible(false);
        model.body.visible = true;
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffers, int light, AbstractClientPlayer player, float limbSwing,
                       float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        ItemStack backpack = TravelersBackpackCompat.wornBackpack(player);
        if (backpack.isEmpty() || !TravelersBackpackCompat.isShown(backpack)) return;
        getParentModel().copyPropertiesTo(model);
        boolean suited = player.getItemBySlot(EquipmentSlot.CHEST).is(EverestGear.DOWN_JACKET);
        VertexConsumer buffer = buffers.getBuffer(RenderType.armorCutoutNoCull(DownSuitModels.PACK_TEXTURE));
        // The shell is painted in greys and tinted, like dyed leather armour.
        show(true, false, false, false);
        int tint = FastColor.ARGB32.opaque(DyedItemColor.getOrDefault(backpack, DownSuitModels.PACK_DEFAULT_RGB));
        model.renderToBuffer(poseStack, buffer, light, OverlayTexture.NO_OVERLAY, tint);
        show(false, true, suited, !suited);
        model.renderToBuffer(poseStack, buffer, light, OverlayTexture.NO_OVERLAY);
    }

    private void show(boolean shellPart, boolean gearPart, boolean suitedStraps, boolean bareStraps) {
        shell.visible = shellPart;
        gear.visible = gearPart;
        strapsSuited.visible = suitedStraps;
        strapsBare.visible = bareStraps;
    }
}
