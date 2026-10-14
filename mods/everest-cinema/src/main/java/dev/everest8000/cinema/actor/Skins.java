package dev.everest8000.cinema.actor;

import com.mojang.blaze3d.platform.NativeImage;
import dev.everest8000.cinema.rpc.RpcException;
import dev.everest8000.cinema.spec.ActorSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/** Player skins for actors: the local player's own, or a 64x64 PNG from disk (registered once per file content). */
public final class Skins {
    private static final Map<String, ResourceLocation> LOADED = new HashMap<>();

    private Skins() {}

    public static PlayerSkin resolve(ActorSpec.Skin s) {
        Minecraft mc = Minecraft.getInstance();
        if (s == null || s.type().equals("player")) return mc.player.getSkin();
        Path path = Path.of(s.path());
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new RpcException(RpcException.NOT_FOUND, "can't read skin " + path + ": " + e.getMessage());
        }
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes)).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        ResourceLocation id = LOADED.get(hash);
        if (id == null) {
            NativeImage img;
            try (InputStream in = new java.io.ByteArrayInputStream(bytes)) {
                img = NativeImage.read(in);
            } catch (IOException e) {
                throw new RpcException(RpcException.INVALID_PARAMS, "skin " + path + " isn't a readable PNG: " + e.getMessage());
            }
            if (img.getWidth() != 64 || img.getHeight() != 64) {
                int w = img.getWidth(), h = img.getHeight();
                img.close();
                throw new RpcException(RpcException.INVALID_PARAMS, "skin " + path + " is " + w + "x" + h + "; it must be a 64x64 skin");
            }
            id = ResourceLocation.fromNamespaceAndPath("everest_cinema", "actor_skin/" + hash);
            mc.getTextureManager().register(id, new DynamicTexture(img));
            LOADED.put(hash, id);
        }
        PlayerSkin.Model model = "slim".equals(s.model()) ? PlayerSkin.Model.SLIM : PlayerSkin.Model.WIDE;
        return new PlayerSkin(id, null, null, null, model, false);
    }
}
