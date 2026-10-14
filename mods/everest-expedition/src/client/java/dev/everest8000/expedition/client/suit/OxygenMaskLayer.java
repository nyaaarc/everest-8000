package dev.everest8000.expedition.client.suit;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.everest8000.expedition.client.ClientBreathing;
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
import net.minecraft.world.entity.EquipmentSlot;

/**
 * The oxygen mask, regulator and hose, drawn on any player breathing from a cylinder, with or without the suit.
 */
public final class OxygenMaskLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
    private final HumanoidModel<AbstractClientPlayer> model;
    private final ModelPart hose;

    public OxygenMaskLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent, EntityModelSet models) {
        super(parent);
        model = new HumanoidModel<>(models.bakeLayer(DownSuitModels.OXYGEN_MASK));
        hose = model.body.getChild("hose");
        model.setAllVisible(false);
        model.head.visible = true;
        model.body.visible = true;
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffers, int light, AbstractClientPlayer player, float limbSwing,
                       float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        if (!ClientBreathing.isBreathing(player)) return;
        getParentModel().copyPropertiesTo(model);
        // The hose lies on the puffy jacket; without it, pull the hose back onto the chest.
        hose.z = player.getItemBySlot(EquipmentSlot.CHEST).is(EverestGear.DOWN_JACKET) ? 0f : DownSuitModels.HOSE_BARE_CHEST_Z;
        model.renderToBuffer(poseStack, buffers.getBuffer(RenderType.armorCutoutNoCull(DownSuitModels.MASK_TEXTURE)), light,
                OverlayTexture.NO_OVERLAY);
    }
}
