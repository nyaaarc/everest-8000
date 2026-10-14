package dev.everest8000.expedition.client.suit;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.everest8000.expedition.gear.HoodStyle;
import dev.everest8000.expedition.gear.SuitColours;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.world.entity.LivingEntity;

/**
 * Draws a player in the down suit for the picker, without an entity: their skin at rest, the suit pieces in the chosen
 * colours, the hood in the chosen look (up or down) and, optionally, the expedition pack. Model space, one unit a
 * block, y down from the neck (the feet are at y 1.5).
 */
final class SuitPreview {
    private static final String[] LIMBS = {"head", "hat", "body", "right_arm", "left_arm", "right_leg", "left_leg"};
    private final ModelPart player;
    private final HumanoidModel<LivingEntity> jacket;
    private final HumanoidModel<LivingEntity> pants;
    private final HumanoidModel<LivingEntity> boots;
    private final HumanoidModel<LivingEntity> hoodUp;
    private final HumanoidModel<LivingEntity> hoodDown;
    private final HumanoidModel<LivingEntity> pack;
    private final ModelPart packShell, packGear, packStraps, packStrapsBare;

    SuitPreview(EntityModelSet models, boolean slim) {
        player = models.bakeLayer(slim ? ModelLayers.PLAYER_SLIM : ModelLayers.PLAYER);
        // The deadmau5 ears and the cape are parts of the player model too; the picker shows neither.
        player.getChild("ear").visible = false;
        player.getChild("cloak").visible = false;
        jacket = new HumanoidModel<>(models.bakeLayer(DownSuitModels.JACKET));
        pants = new HumanoidModel<>(models.bakeLayer(DownSuitModels.PANTS));
        boots = new HumanoidModel<>(models.bakeLayer(DownSuitModels.BOOTS));
        hoodUp = new HumanoidModel<>(models.bakeLayer(DownSuitModels.HOOD_UP));
        hoodDown = new HumanoidModel<>(models.bakeLayer(DownSuitModels.HOOD_DOWN));
        pack = new HumanoidModel<>(models.bakeLayer(DownSuitModels.EXPEDITION_PACK));
        packShell = pack.body.getChild("pack_shell");
        packGear = pack.body.getChild("pack_gear");
        packStraps = pack.body.getChild("straps_suited");
        packStrapsBare = pack.body.getChild("straps_bare");
        for (HumanoidModel<LivingEntity> model : java.util.List.of(jacket, pants, boots, hoodUp, hoodDown, pack)) {
            // A model starts out young (EntityModel.young is true until a renderer copies the entity's), which would
            // draw it at baby size and offsets.
            model.young = false;
            pose(model);
        }
    }

    /** Puts a suit model's parts where the player's are (slim arms sit half a pixel lower). */
    private void pose(HumanoidModel<LivingEntity> model) {
        ModelPart[] parts = {model.head, model.hat, model.body, model.rightArm, model.leftArm, model.rightLeg, model.leftLeg};
        for (int i = 0; i < LIMBS.length; i++) parts[i].copyFrom(player.getChild(LIMBS[i]));
    }

    /**
     * @param skin    the player's skin texture
     * @param hoodUp  the hood up, or down on the back
     * @param withPack draw the expedition pack (in the pack colour)
     */
    void render(PoseStack pose, MultiBufferSource buffers, ResourceLocation skin, HoodStyle style, boolean hoodUp,
                SuitColours colours, boolean withPack) {
        int light = LightTexture.FULL_BRIGHT, overlay = OverlayTexture.NO_OVERLAY;
        player.render(pose, buffers.getBuffer(RenderType.entityTranslucent(skin)), light, overlay);

        ResourceLocation jacketTexture = SuitTextures.forColour(colours.jacket());
        draw(jacket, pose, buffers, jacketTexture, m -> m.body.visible = m.rightArm.visible = m.leftArm.visible = true);
        draw(pants, pose, buffers, SuitTextures.forColour(colours.pants()),
                m -> m.body.visible = m.rightLeg.visible = m.leftLeg.visible = true);
        draw(boots, pose, buffers, DownSuitModels.SUIT_TEXTURE, m -> m.rightLeg.visible = m.leftLeg.visible = true);
        HumanoidModel<LivingEntity> hood = hoodUp ? this.hoodUp : hoodDown;
        draw(hood, pose, buffers, jacketTexture, m -> {
            m.head.visible = m.body.visible = true;
            DownSuitRenderer.showStyle(m.head, m.body, hoodUp, style, false);
        });

        if (withPack) {
            pack.setAllVisible(false);
            pack.body.visible = true;
            VertexConsumer buffer = buffers.getBuffer(RenderType.armorCutoutNoCull(DownSuitModels.PACK_TEXTURE));
            showPack(true, false);
            int rgb = colours.pack() < 0 ? DownSuitModels.PACK_DEFAULT_RGB : colours.pack();
            pack.renderToBuffer(pose, buffer, light, overlay, FastColor.ARGB32.opaque(rgb));
            showPack(false, true);
            pack.renderToBuffer(pose, buffer, light, overlay);
        }
    }

    private void showPack(boolean shell, boolean rest) {
        packShell.visible = shell;
        packGear.visible = packStraps.visible = rest;
        packStrapsBare.visible = false;
    }

    private static void draw(HumanoidModel<LivingEntity> model, PoseStack pose, MultiBufferSource buffers, ResourceLocation texture,
                             java.util.function.Consumer<HumanoidModel<LivingEntity>> show) {
        model.setAllVisible(false);
        show.accept(model);
        model.renderToBuffer(pose, buffers.getBuffer(RenderType.armorCutoutNoCull(texture)), LightTexture.FULL_BRIGHT,
                OverlayTexture.NO_OVERLAY);
    }
}
