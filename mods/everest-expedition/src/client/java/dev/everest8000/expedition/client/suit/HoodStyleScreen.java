package dev.everest8000.expedition.client.suit;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.everest8000.expedition.client.compat.TravelersBackpackCompat;
import dev.everest8000.expedition.gear.Colourway;
import dev.everest8000.expedition.gear.EverestGear;
import dev.everest8000.expedition.gear.Goggles;
import dev.everest8000.expedition.gear.HoodShape;
import dev.everest8000.expedition.gear.HoodStyle;
import dev.everest8000.expedition.gear.SuitColours;
import dev.everest8000.expedition.net.SuitChoicePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;

/**
 * The expedition kit picker: hood shape, goggles, and the jacket, pants and pack colours, with the player turning
 * slowly in their choice on the left (drag to turn them; the toggle shows the hood up or down). Shown on first
 * joining and from /hood. Done sends it all to the server, which dyes the suit pieces and packs the player carries and
 * keeps the colours for kit handed out later; Decide later changes nothing.
 *
 * <p>The colours start from what the player is wearing, so a piece they dyed their own colour in a crafting grid
 * keeps it unless they pick another.
 *
 * <p>Still named for its first job, the hood style: the test driver (everest-testdriver) closes it by this name.
 */
public final class HoodStyleScreen extends Screen {
    private static final int PREVIEW_W = 118, PREVIEW_H = 170;
    private static final int LABEL_W = 46;
    private static final int TILE = 31, TILE_H = 36, TILE_GAP = 3;
    private static final int SWATCH = 14, SWATCH_STEP = 17;
    private static final int GAP = 12;
    private static final int SELECTED = 0xFFFFD24A;
    private static final SuitColours.Part[] PARTS = SuitColours.Part.values();
    private static final String[] PART_KEYS = {"jacket", "pants", "pack"};

    private final Component hint;
    private HoodStyle style;
    private SuitColours colours;
    private final int[] wearing = new int[PARTS.length];
    private boolean hoodUp = true;
    private SuitPreview preview;
    private ResourceLocation skin;
    private int left, top, right;
    /** The big preview's turn: 0 faces the viewer. */
    private float yaw = 20f;
    private long lastDrag = -10_000L;
    private long lastFrame;
    private boolean dragging;

    /** keyName: the picker key's name, or null when it's unbound (the hint then mentions only /hood). */
    public HoodStyleScreen(Component keyName) {
        super(Component.translatable("screen.everest_expedition.suit.title"));
        this.hint = keyName == null ? Component.translatable("screen.everest_expedition.suit.hint_command")
                : Component.translatable("screen.everest_expedition.suit.hint", keyName);
    }

    @Override
    protected void init() {
        boolean slim = minecraft.player != null && minecraft.player.getSkin().model() == PlayerSkin.Model.SLIM;
        preview = new SuitPreview(minecraft.getEntityModels(), slim);
        skin = minecraft.player != null ? minecraft.player.getSkin().texture() : null;
        if (style == null) {
            HoodStyle own = ClientHoodStyles.own();
            style = own == null ? HoodStyle.DEFAULT : own;
            colours = startingColours();
            for (int i = 0; i < PARTS.length; i++) wearing[i] = colours.get(PARTS[i]);
        }

        int rightW = LABEL_W + HoodShape.values().length * (TILE + TILE_GAP) - TILE_GAP;
        int totalW = PREVIEW_W + GAP + rightW;
        left = (width - totalW) / 2;
        right = left + PREVIEW_W + GAP;
        top = Math.max(18, (height - 198) / 2 + 12);

        int buttonY = top + PREVIEW_H + 6;
        addRenderableWidget(Button.builder(hoodButtonText(), b -> {
            hoodUp = !hoodUp;
            b.setMessage(hoodButtonText());
        }).bounds(left + 9, buttonY, PREVIEW_W - 18, 20).build());
        int buttonW = (rightW - 6) / 2;
        addRenderableWidget(Button.builder(Component.translatable("screen.everest_expedition.suit.done"), b -> done())
                .bounds(right, buttonY, buttonW, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.everest_expedition.suit.later"), b -> onClose())
                .bounds(right + buttonW + 6, buttonY, buttonW, 20).build());
    }

    private Component hoodButtonText() {
        return Component.translatable(hoodUp ? "screen.everest_expedition.suit.hood_up" : "screen.everest_expedition.suit.hood_down");
    }

    /** The player's chosen colours, with the colour of each piece they're wearing in its place. */
    private SuitColours startingColours() {
        SuitColours chosen = ClientHoodStyles.ownColours();
        if (minecraft.player == null) return chosen;
        int jacket = wornColour(minecraft.player.getItemBySlot(EquipmentSlot.CHEST), EverestGear.DOWN_JACKET.getDefaultInstance(), chosen.jacket());
        int pants = wornColour(minecraft.player.getItemBySlot(EquipmentSlot.LEGS), EverestGear.DOWN_PANTS.getDefaultInstance(), chosen.pants());
        ItemStack backpack = TravelersBackpackCompat.wornBackpack(minecraft.player);
        int pack = backpack.isEmpty() ? chosen.pack() : DyedItemColor.getOrDefault(backpack, SuitColours.DEFAULT);
        return new SuitColours(jacket, pants, pack);
    }

    private static int wornColour(ItemStack worn, ItemStack kind, int fallback) {
        if (!worn.is(kind.getItem())) return fallback;
        DyedItemColor dyed = worn.get(DataComponents.DYED_COLOR);
        return dyed == null ? SuitColours.DEFAULT : dyed.rgb();
    }

    private void done() {
        if (ClientPlayNetworking.canSend(SuitChoicePayload.TYPE)) {
            ClientPlayNetworking.send(new SuitChoicePayload(style.index(), colours));
        }
        onClose();
    }

    // ---- Layout

    private int rowY(int row) {
        // Hood and goggles tiles, then the three colour rows.
        return row < 2 ? top + row * (TILE_H + 6) : top + 2 * (TILE_H + 6) + (row - 2) * (SWATCH_STEP + 2);
    }

    private int tileX(int i) {
        return right + LABEL_W + i * (TILE + TILE_GAP);
    }

    private int swatchX(int i) {
        return right + LABEL_W + i * SWATCH_STEP;
    }

    /** The colour on a part's swatch i; the last swatch is the player's own colour when it isn't a colourway. */
    private static int swatchColour(Colourway way, SuitColours.Part part) {
        return part == SuitColours.Part.PACK ? way.packRgb : way.suitSwatch();
    }

    private static boolean matches(int current, Colourway way, SuitColours.Part part) {
        int value = SuitColours.of(way, part);
        if (part == SuitColours.Part.PACK) {
            // The undyed pack is the red one.
            int a = current < 0 ? Colourway.DEFAULT_PACK_RGB : current, b = value < 0 ? Colourway.DEFAULT_PACK_RGB : value;
            return a == b;
        }
        return current == value;
    }

    private static boolean isColourway(int current, SuitColours.Part part) {
        for (Colourway way : Colourway.values()) if (matches(current, way, part)) return true;
        return false;
    }

    // ---- Drawing

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, top - 14, 0xFFFFFF);

        // The player, turning slowly unless they've just dragged them round.
        long now = Util.getMillis();
        if (now - lastDrag > 2500 && !dragging && lastFrame != 0) yaw += Math.min(now - lastFrame, 100) * 0.03f;
        lastFrame = now;
        graphics.fill(left, top, left + PREVIEW_W, top + PREVIEW_H, 0x40000000);
        graphics.enableScissor(left, top, left + PREVIEW_W, top + PREVIEW_H);
        drawFigure(graphics, left + PREVIEW_W / 2, top + 54, 70f, 12f, yaw, hoodUp, true);
        graphics.disableScissor();

        Component tooltip = null;
        // Hood shapes, each with the current goggles where they fit.
        graphics.drawString(font, Component.translatable("screen.everest_expedition.suit.hood"), right, rowY(0) + 14, 0xE0E0E0);
        float tileYaw = 20f + Mth.sin(Util.getMillis() / 1400f) * 20f;
        HoodShape[] shapes = HoodShape.values();
        for (int i = 0; i < shapes.length; i++) {
            HoodStyle look = new HoodStyle(shapes[i], style.goggles().fits(shapes[i]) ? style.goggles() : Goggles.HOOD);
            if (tile(graphics, tileX(i), rowY(0), look, shapes[i] == style.hood(), true, tileYaw, mouseX, mouseY)) {
                tooltip = shapes[i].displayName();
            }
        }
        // Goggles, on the current hood.
        graphics.drawString(font, Component.translatable("screen.everest_expedition.suit.goggles"), right, rowY(1) + 14, 0xE0E0E0);
        Goggles[] goggles = Goggles.values();
        for (int i = 0; i < goggles.length; i++) {
            boolean fits = goggles[i].fits(style.hood());
            if (tile(graphics, tileX(i), rowY(1), style.withGoggles(goggles[i]), goggles[i] == style.goggles(), fits, tileYaw,
                    mouseX, mouseY)) {
                tooltip = fits ? goggles[i].displayName()
                        : Component.translatable("screen.everest_expedition.suit.goggles_dont_fit", goggles[i].displayName());
            }
        }
        // Colours: the curated colourways, then the player's own colour if they've dyed one.
        for (int p = 0; p < PARTS.length; p++) {
            SuitColours.Part part = PARTS[p];
            int y = rowY(2 + p);
            graphics.drawString(font, Component.translatable("screen.everest_expedition.suit." + PART_KEYS[p]), right, y + 3, 0xE0E0E0);
            int current = colours.get(part);
            Colourway[] ways = Colourway.values();
            for (int i = 0; i < ways.length; i++) {
                boolean hover = swatch(graphics, swatchX(i), y, swatchColour(ways[i], part), matches(current, ways[i], part), mouseX, mouseY);
                if (hover) tooltip = ways[i].displayName();
            }
            int own = wearing[p];
            if (own >= 0 && !isColourway(own, part)) {
                if (swatch(graphics, swatchX(ways.length) + 4, y, own, current == own, mouseX, mouseY)) {
                    tooltip = Component.translatable("screen.everest_expedition.suit.own_colour", String.format("#%06X", own));
                }
            }
        }
        graphics.drawWordWrap(font, hint, right, rowY(5) + 2, LABEL_W + HoodShape.values().length * (TILE + TILE_GAP), 0xA0A0A0);
        if (tooltip != null) graphics.renderTooltip(font, tooltip, mouseX, mouseY);
    }

    /** A head-and-shoulders tile of the player in this look; true while the mouse is over it. */
    private boolean tile(GuiGraphics graphics, int x, int y, HoodStyle look, boolean selected, boolean enabled, float tileYaw,
                         int mouseX, int mouseY) {
        boolean hover = mouseX >= x && mouseX < x + TILE && mouseY >= y && mouseY < y + TILE_H;
        graphics.fill(x, y, x + TILE, y + TILE_H, selected ? 0x70FFD24A : hover && enabled ? 0x50FFFFFF : 0x40000000);
        if (selected) outline(graphics, x, y, TILE, TILE_H, SELECTED);
        graphics.enableScissor(x + 1, y + 1, x + TILE - 1, y + TILE_H - 1);
        drawFigure(graphics, x + TILE / 2, y + 21, 26f, 8f, tileYaw, true, false, look);
        graphics.disableScissor();
        if (!enabled) graphics.fill(x, y, x + TILE, y + TILE_H, 0xA0202020);
        return hover;
    }

    /** A colour swatch; true while the mouse is over it. */
    private boolean swatch(GuiGraphics graphics, int x, int y, int rgb, boolean selected, int mouseX, int mouseY) {
        boolean hover = mouseX >= x - 1 && mouseX < x + SWATCH + 1 && mouseY >= y - 1 && mouseY < y + SWATCH + 1;
        if (selected) outline(graphics, x - 2, y - 2, SWATCH + 4, SWATCH + 4, SELECTED);
        outline(graphics, x - 1, y - 1, SWATCH + 2, SWATCH + 2, hover ? 0xFFFFFFFF : 0xFF101010);
        graphics.fill(x, y, x + SWATCH, y + SWATCH, 0xFF000000 | rgb);
        // A little light along the top, so the swatches read as fabric rather than flat chips.
        graphics.fill(x, y, x + SWATCH, y + 2, 0x30FFFFFF);
        return hover;
    }

    private static void outline(GuiGraphics graphics, int x, int y, int w, int h, int argb) {
        graphics.fill(x, y, x + w, y + 1, argb);
        graphics.fill(x, y + h - 1, x + w, y + h, argb);
        graphics.fill(x, y, x + 1, y + h, argb);
        graphics.fill(x + w - 1, y, x + w, y + h, argb);
    }

    private void drawFigure(GuiGraphics graphics, int cx, int neckY, float scale, float pitch, float figureYaw, boolean up,
                            boolean withPack) {
        drawFigure(graphics, cx, neckY, scale, pitch, figureYaw, up, withPack, style);
    }

    /** The player in a look and the chosen colours, the neck at (cx, neckY), scale pixels a block. */
    private void drawFigure(GuiGraphics graphics, int cx, int neckY, float scale, float pitch, float figureYaw, boolean up,
                            boolean withPack, HoodStyle look) {
        if (skin == null) return;
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(cx, neckY, 150);
        pose.scale(scale, scale, -scale);
        pose.mulPose(Axis.XP.rotationDegrees(pitch)); // looking down on them a little
        pose.mulPose(Axis.YP.rotationDegrees(figureYaw));
        Lighting.setupForEntityInInventory();
        preview.render(pose, graphics.bufferSource(), skin, look, up, colours, withPack);
        graphics.flush();
        Lighting.setupFor3DItems();
        pose.popPose();
    }

    // ---- Input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            HoodShape[] shapes = HoodShape.values();
            for (int i = 0; i < shapes.length; i++) {
                if (inside(mouseX, mouseY, tileX(i), rowY(0), TILE, TILE_H)) {
                    style = style.withHood(shapes[i]);
                    if (!style.goggles().fits(shapes[i])) style = style.withGoggles(Goggles.HOOD);
                    return click();
                }
            }
            Goggles[] goggles = Goggles.values();
            for (int i = 0; i < goggles.length; i++) {
                if (inside(mouseX, mouseY, tileX(i), rowY(1), TILE, TILE_H) && goggles[i].fits(style.hood())) {
                    style = style.withGoggles(goggles[i]);
                    return click();
                }
            }
            for (int p = 0; p < PARTS.length; p++) {
                int y = rowY(2 + p);
                Colourway[] ways = Colourway.values();
                for (int i = 0; i < ways.length; i++) {
                    if (inside(mouseX, mouseY, swatchX(i) - 1, y - 1, SWATCH + 2, SWATCH + 2)) {
                        colours = colours.with(PARTS[p], SuitColours.of(ways[i], PARTS[p]));
                        return click();
                    }
                }
                if (wearing[p] >= 0 && inside(mouseX, mouseY, swatchX(ways.length) + 3, y - 1, SWATCH + 2, SWATCH + 2)) {
                    colours = colours.with(PARTS[p], wearing[p]);
                    return click();
                }
            }
            if (inside(mouseX, mouseY, left, top, PREVIEW_W, PREVIEW_H)) {
                dragging = true;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging) {
            yaw += (float) dragX * 2.5f;
            lastDrag = Util.getMillis();
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragging) {
            dragging = false;
            lastDrag = Util.getMillis();
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private boolean click() {
        minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0f));
        return true;
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
