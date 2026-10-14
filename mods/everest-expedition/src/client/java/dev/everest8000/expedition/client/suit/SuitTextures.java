package dev.everest8000.expedition.client.suit;

import com.mojang.blaze3d.platform.NativeImage;
import dev.everest8000.expedition.EverestExpedition;
import dev.everest8000.expedition.gear.SuitRamps;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The suit texture in a dye colour. A dyed suit piece is drawn from a copy of the painted suit texture in which every
 * tintable texel (marked in down_suit_3d_dye.png: the down fabric, not the trims, zips, harness, goggles or fur) is
 * repainted from the colour's shading ramps ({@link SuitRamps}), so it shades like real down rather than a flat
 * tint. Built on first use per colour and kept while it's used; an undyed piece uses the painted texture as is.
 */
public final class SuitTextures {
    private static final ResourceLocation DYE_MAP = EverestExpedition.id("textures/models/armor/down_suit_3d_dye.png");
    /** At most this many colours are kept; a server rarely shows more than a few. */
    private static final int MAX_COLOURS = 64;
    private static final Map<Integer, ResourceLocation> TEXTURES = new LinkedHashMap<>(16, 0.75f, true);
    private static NativeImage base;
    private static NativeImage dyeMap;
    private static boolean unavailable;

    private SuitTextures() {}

    static void register() {
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override
            public ResourceLocation getFabricId() {
                return EverestExpedition.id("dyed_suit_textures");
            }

            @Override
            public void onResourceManagerReload(ResourceManager manager) {
                clear();
            }
        });
    }

    /** The texture to draw this suit piece with: the painted suit, or its dyed copy. */
    public static ResourceLocation of(ItemStack stack) {
        DyedItemColor dyed = stack.get(DataComponents.DYED_COLOR);
        return dyed == null ? DownSuitModels.SUIT_TEXTURE : forColour(dyed.rgb());
    }

    /** The suit texture dyed this colour (0xRRGGBB); the painted suit for a negative colour. Render thread only. */
    public static ResourceLocation forColour(int rgb) {
        if (rgb < 0) return DownSuitModels.SUIT_TEXTURE;
        rgb &= 0xFFFFFF;
        ResourceLocation texture = TEXTURES.get(rgb);
        if (texture != null) return texture;
        texture = build(rgb);
        if (texture == DownSuitModels.SUIT_TEXTURE) return texture;
        TEXTURES.put(rgb, texture);
        if (TEXTURES.size() > MAX_COLOURS) {
            Iterator<ResourceLocation> oldest = TEXTURES.values().iterator();
            Minecraft.getInstance().getTextureManager().release(oldest.next());
            oldest.remove();
        }
        return texture;
    }

    private static ResourceLocation build(int rgb) {
        if (!loadSources()) return DownSuitModels.SUIT_TEXTURE;
        int[][] ramps = SuitRamps.ramps(rgb);
        // NativeImage pixels are ABGR.
        int[][] abgr = new int[ramps.length][SuitRamps.STEPS];
        for (int c = 0; c < ramps.length; c++) {
            for (int i = 0; i < SuitRamps.STEPS; i++) {
                int v = ramps[c][i];
                abgr[c][i] = 0xFF000000 | (v & 0xFF) << 16 | (v & 0xFF00) | (v >> 16 & 0xFF);
            }
        }
        NativeImage image = new NativeImage(base.getWidth(), base.getHeight(), false);
        image.copyFrom(base);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int mark = dyeMap.getPixelRGBA(x, y);
                if (mark >>> 24 != 0xFF) continue;
                int level = mark & 0xFF, ramp = mark >> 8 & 0xFF;
                if (ramp < abgr.length && level < SuitRamps.STEPS) image.setPixelRGBA(x, y, abgr[ramp][level]);
            }
        }
        ResourceLocation id = EverestExpedition.id(String.format("dyed_suit/%06x", rgb));
        Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(image));
        return id;
    }

    private static boolean loadSources() {
        if (base != null) return true;
        if (unavailable) return false;
        ResourceManager resources = Minecraft.getInstance().getResourceManager();
        try {
            base = read(resources, DownSuitModels.SUIT_TEXTURE);
            dyeMap = read(resources, DYE_MAP);
            if (base.getWidth() != dyeMap.getWidth() || base.getHeight() != dyeMap.getHeight()) {
                throw new IOException("the suit texture and its dye map differ in size");
            }
            return true;
        } catch (IOException e) {
            EverestExpedition.LOG.warn("Couldn't load the suit texture for dyeing; dyed suits will show undyed", e);
            closeSources();
            unavailable = true;
            return false;
        }
    }

    private static NativeImage read(ResourceManager resources, ResourceLocation location) throws IOException {
        Optional<Resource> resource = resources.getResource(location);
        if (resource.isEmpty()) throw new IOException("missing " + location);
        try (InputStream in = resource.get().open()) {
            return NativeImage.read(in);
        }
    }

    /** Resource packs changed: build the dyed textures again from the (possibly new) suit texture. */
    private static void clear() {
        for (ResourceLocation texture : TEXTURES.values()) Minecraft.getInstance().getTextureManager().release(texture);
        TEXTURES.clear();
        closeSources();
        unavailable = false;
    }

    private static void closeSources() {
        if (base != null) base.close();
        if (dyeMap != null) dyeMap.close();
        base = dyeMap = null;
    }
}
