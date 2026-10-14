package dev.everest8000.expedition.client.wind;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.everest8000.expedition.EverestExpedition;
import dev.everest8000.expedition.client.ClientSky;
import dev.everest8000.expedition.client.ClientWeather;
import dev.everest8000.expedition.weather.WeatherField;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Draws the summit plume. After the world is finished (and, under a shader pack, after Iris has composited it) the
 * plume is ray-marched into a reduced-resolution target and laid over the frame, before the hand and the HUD.
 * Iris refuses to run mod shaders while it is drawing the world, which is why this happens afterwards; nearby blocks
 * still hide the plume through the world's depth buffer, and Distant Horizons' far terrain through the
 * {@link HorizonMap}.
 */
public final class PlumeRenderer {
    private static final ResourceLocation TERRAIN = EverestExpedition.id("terrain/khumbu_heights.bin");
    private static final ResourceLocation NOISE = EverestExpedition.id("textures/effect/plume_noise.png");
    private static final int NOISE_SIZE = 64;
    private static final int NOISE_UNIT = 5;

    private static final PlumeField FIELD = new PlumeField();
    private static final PlumeUniforms UNIFORMS = new PlumeUniforms();
    private static final Matrix4f PROJECTION = new Matrix4f();
    private static final Matrix4f VIEW = new Matrix4f();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Everest plume horizon");
        t.setDaemon(true);
        return t;
    });

    private static ShaderInstance plumeShader;
    private static ShaderInstance compositeShader;
    private static TextureTarget target;
    /** A copy of the frame, for telling cloud from sky behind the plume. */
    private static TextureTarget background;
    private static int noiseTexture = -1;
    private static int heightsTexture = -1;
    private static int horizonTexture = -1;
    private static CompletableFuture<TerrainHeights> terrain;
    private static boolean failed;

    private static Future<HorizonMap.Result> pendingHorizon;
    private static HorizonMap.Result horizon;
    private static HorizonMap.Result uploadedHorizon;
    private static int ticksSinceHorizon;
    private static float visibleFade;
    private static boolean capturedThisFrame;
    /** GPU time of the last measured plume pass, from a timer query read a frame or more later (no stall). */
    private static int timerQuery = -1;
    private static boolean timerPending;
    private static Boolean timers;
    private static float gpuMillis;
    private static int drawnFrames;

    private PlumeRenderer() {}

    public static void registerShaders(CoreShaderRegistrationCallback.RegistrationContext context) throws IOException {
        context.register(EverestExpedition.id("plume"), DefaultVertexFormat.POSITION, s -> plumeShader = s);
        context.register(EverestExpedition.id("plume_composite"), DefaultVertexFormat.POSITION, s -> compositeShader = s);
    }

    public static void reset() {
        FIELD.reset();
        horizon = null;
        visibleFade = 0f;
    }

    /** Client tick: follow the weather, and keep the horizon table current for where the camera is. */
    public static void tick(Minecraft mc) {
        if (failed || !WindFxConfig.summitPlume || !ClientWeather.active() || mc.level == null || mc.isPaused()) return;
        WeatherField weather = ClientWeather.field();
        if (weather == null) return;
        FIELD.tick(weather);
        if (!FIELD.visible()) return;
        if (terrain == null) terrain = loadTerrain(mc);
        TerrainHeights heights = terrain.getNow(null);
        if (heights == null) return;

        if (pendingHorizon != null && pendingHorizon.isDone()) {
            try {
                horizon = pendingHorizon.get();
            } catch (Exception e) {
                EverestExpedition.LOG.warn("Summit plume horizon failed", e);
            }
            pendingHorizon = null;
        }
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        ticksSinceHorizon++;
        boolean moved = horizon == null || cam.distanceToSqr(horizon.camX(), horizon.camY(), horizon.camZ()) > 4.0;
        if (pendingHorizon == null && (moved || ticksSinceHorizon > 40)) {
            ticksSinceHorizon = 0;
            NearSurface near = NearSurface.capture(mc.level, cam.x, cam.z);
            double[] corners = FIELD.corners();
            double reach = FIELD.reach(cam.x, cam.y, cam.z) + 300;
            double x = cam.x, y = cam.y, z = cam.z;
            pendingHorizon = WORKER.submit(() -> HorizonMap.compute(x, y, z, corners, reach, near, heights::at));
        }
    }

    /** For tests and debugging: what the plume is doing right now. */
    public static String status() {
        return String.format(java.util.Locale.ROOT,
                "visible=%b strength=%.2f/%.2f/%.2f length=%.0f cap=%.2f horizon=%s frames=%d gpuMs=%.2f failed=%b",
                FIELD.visible(), FIELD.strength(0), FIELD.strength(1), FIELD.strength(2), FIELD.length(0),
                FIELD.capStrength(), horizon != null, drawnFrames, gpuMillis, failed);
    }

    public static float gpuMillis() {
        return gpuMillis;
    }

    /** WorldRenderEvents.END: the matrices this frame's depth buffer was drawn with. */
    public static void capture(WorldRenderContext context) {
        PROJECTION.set(context.projectionMatrix());
        VIEW.set(context.positionMatrix());
        capturedThisFrame = true;
    }

    /** Right after the world is drawn, before the hand. */
    public static void render(float partialTick) {
        boolean captured = capturedThisFrame;
        capturedThisFrame = false;
        Minecraft mc = Minecraft.getInstance();
        if (failed || !captured || !WindFxConfig.summitPlume || !ClientWeather.active() || mc.level == null) return;
        if (plumeShader == null || compositeShader == null || !FIELD.visible() || horizon == null) return;
        TerrainHeights heights = terrain == null ? null : terrain.getNow(null);
        if (heights == null) return;
        Camera camera = mc.gameRenderer.getMainCamera();
        if (camera.getFluidInCamera() != FogType.NONE) return;
        Vec3 cam = camera.getPosition();
        // A stale table after a teleport would hide the plume behind the wrong ridges: wait for the new one.
        if (cam.distanceToSqr(horizon.camX(), horizon.camY(), horizon.camZ()) > 64.0) return;
        float whiteout = ClientWeather.whiteout();
        visibleFade = Math.min(1f, visibleFade + 0.02f);
        float fade = visibleFade * (1f - 0.9f * whiteout);
        if (fade <= 0.01f) return;
        try {
            draw(mc, heights, cam, fade, partialTick);
        } catch (RuntimeException e) {
            failed = true;
            EverestExpedition.LOG.error("Summit plume rendering failed; turning it off for this session", e);
        }
    }

    private static void draw(Minecraft mc, TerrainHeights heights, Vec3 cam, float fade, float partialTick) {
        RenderSystem.assertOnRenderThread();
        ensureTextures(mc, heights);
        if (horizon != uploadedHorizon) uploadHorizon(horizon);

        RenderTarget main = mc.getMainRenderTarget();
        int quality = WindFxConfig.plumeQuality;
        float scale = quality == 1 ? 1f / 3f : 0.5f;
        int w = Math.max(1, (int) (main.width * scale));
        int h = Math.max(1, (int) (main.height * scale));
        if (target == null) {
            target = new TextureTarget(w, h, false, Minecraft.ON_OSX);
            target.setClearColor(0f, 0f, 0f, 0f);
            target.setFilterMode(GL11.GL_LINEAR);
        } else if (target.width != w || target.height != h) {
            target.resize(w, h, Minecraft.ON_OSX);
            target.setFilterMode(GL11.GL_LINEAR);
        }

        Matrix4f projView = new Matrix4f(PROJECTION).mul(VIEW);
        new Matrix4f(projView).invert().get(UNIFORMS.invProjView);
        UNIFORMS.packField(FIELD, cam.x, cam.y, cam.z);
        UNIFORMS.setHorizon(horizon);
        TerrainHeights.Grid fine = heights.fine();
        UNIFORMS.heightsInfo[0] = (float) (fine.originX() - cam.x);
        UNIFORMS.heightsInfo[1] = (float) (fine.originZ() - cam.z);
        UNIFORMS.heightsInfo[2] = fine.cell();
        UNIFORMS.heightsInfo[3] = (float) cam.y;
        UNIFORMS.params[0] = quality == 1 ? 28 : quality == 2 ? 40 : 64;
        UNIFORMS.params[1] = (mc.level.getGameTime() + partialTick) / 20f % 100000f;
        UNIFORMS.params[2] = fade;
        UNIFORMS.params[3] = 1f;
        // Radians per pixel of the plume target: m11 of a perspective matrix is 1 / tan(fov / 2).
        UNIFORMS.params2[0] = 2f / (PROJECTION.m11() * h);
        // Standing in the plume's root (on or near the summit) it would be a uniform white veil: thin it out much
        // further from the eye there and let the blown snow carry the near field.
        PlumeField.Peak top = PlumeField.PEAKS[0];
        double toSummit = Math.sqrt(Mth.square(cam.x - top.x()) + Mth.square(cam.y - top.y()) + Mth.square(cam.z - top.z()));
        UNIFORMS.params2[1] = 40f + 260f * (1f - Mth.clamp((float) (toSummit - 150) / 350f, 0f, 1f));
        Vec3 sky = mc.level.getSkyColor(cam, partialTick);
        UNIFORMS.light.update(mc.level.getSunAngle(partialTick), (float) sky.x, (float) sky.y, (float) sky.z,
                ClientWeather.rainLevel() * 0.6f);
        UNIFORMS.noiseUnit = NOISE_UNIT;

        if (timers == null) timers = GL.getCapabilities().OpenGL33 || GL.getCapabilities().GL_ARB_timer_query;
        if (timers) readTimer();
        boolean timing = timers && !timerPending;
        if (timing) {
            if (timerQuery < 0) timerQuery = GL15.glGenQueries();
            GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, timerQuery);
        }

        // Ray-march into the offscreen target.
        target.clear(Minecraft.ON_OSX);
        target.bindWrite(true);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableBlend();
        RenderSystem.disableCull();
        plumeShader.setSampler("SceneDepth", main.getDepthTextureId());
        plumeShader.setSampler("Heights", heightsTexture);
        plumeShader.setSampler("Horizon", horizonTexture);
        plumeShader.apply();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + NOISE_UNIT);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, noiseTexture);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        UNIFORMS.upload(plumeShader.getId());
        drawFullscreen();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + NOISE_UNIT);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        plumeShader.clear();

        // Under the cloud deck, the composite needs the frame as it is to see where the clouds are.
        float deckY = ClientSky.active() ? ClientSky.cloudHeight() : Float.NaN;
        boolean underDeck = !Float.isNaN(deckY) && cam.y < deckY;
        if (underDeck) copyFrame(main);

        // Over the frame: premultiplied colour, and the frame's alpha left alone.
        main.bindWrite(true);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
        compositeShader.setSampler("PlumeSampler", target.getColorTextureId());
        compositeShader.setSampler("BackgroundSampler", underDeck ? background.getColorTextureId() : target.getColorTextureId());
        compositeShader.apply();
        int program = compositeShader.getId();
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program, "InvProjView"), false,
                    stack.floats(UNIFORMS.invProjView));
        }
        GL20.glUniform2f(GL20.glGetUniformLocation(program, "Deck"), underDeck ? (float) (deckY - cam.y) : 0f, underDeck ? 1f : 0f);
        drawFullscreen();
        compositeShader.clear();
        if (timing) {
            GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
            timerPending = true;
        }
        drawnFrames++;

        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
    }

    private static void copyFrame(RenderTarget main) {
        if (background == null) {
            background = new TextureTarget(main.width, main.height, false, Minecraft.ON_OSX);
        } else if (background.width != main.width || background.height != main.height) {
            background.resize(main.width, main.height, Minecraft.ON_OSX);
        }
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, background.frameBufferId);
        GlStateManager._glBlitFrameBuffer(0, 0, main.width, main.height, 0, 0, main.width, main.height,
                GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    private static void readTimer() {
        if (!timerPending || GL15.glGetQueryObjecti(timerQuery, GL15.GL_QUERY_RESULT_AVAILABLE) == 0) return;
        float ms = GL33.glGetQueryObjecti64(timerQuery, GL15.GL_QUERY_RESULT) / 1.0e6f;
        gpuMillis = gpuMillis == 0f ? ms : gpuMillis * 0.9f + ms * 0.1f;
        timerPending = false;
    }

    private static void drawFullscreen() {
        BufferBuilder buffer = RenderSystem.renderThreadTesselator().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        buffer.addVertex(0f, 0f, 0f);
        buffer.addVertex(1f, 0f, 0f);
        buffer.addVertex(1f, 1f, 0f);
        buffer.addVertex(0f, 1f, 0f);
        BufferUploader.draw(buffer.buildOrThrow());
    }

    private static CompletableFuture<TerrainHeights> loadTerrain(Minecraft mc) {
        Optional<Resource> resource = mc.getResourceManager().getResource(TERRAIN);
        if (resource.isEmpty()) {
            failed = true;
            EverestExpedition.LOG.error("Missing {}; no summit plume", TERRAIN);
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.supplyAsync(() -> {
            try (InputStream in = resource.get().open()) {
                return TerrainHeights.read(in);
            } catch (IOException e) {
                failed = true;
                EverestExpedition.LOG.error("Couldn't read {}; no summit plume", TERRAIN, e);
                return null;
            }
        }, WORKER);
    }

    private static void ensureTextures(Minecraft mc, TerrainHeights heights) {
        if (noiseTexture < 0) noiseTexture = loadNoise(mc);
        if (heightsTexture < 0) {
            TerrainHeights.Grid g = heights.fine();
            FloatBuffer buf = MemoryUtil.memAllocFloat(g.width() * g.depth());
            try {
                for (short v : g.values()) buf.put(v == TerrainHeights.VOID ? -30000f : v);
                buf.flip();
                heightsTexture = floatTexture(g.width(), g.depth(), buf);
            } finally {
                MemoryUtil.memFree(buf);
            }
        }
        if (horizonTexture < 0) horizonTexture = floatTexture(HorizonMap.DISTANCES, HorizonMap.AZIMUTHS, null);
    }

    private static int floatTexture(int w, int h, FloatBuffer data) {
        int id = GlStateManager._genTexture();
        GlStateManager._bindTexture(id);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        resetUnpack();
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R32F, w, h, 0, GL11.GL_RED, GL11.GL_FLOAT, data);
        GlStateManager._bindTexture(0);
        return id;
    }

    private static void uploadHorizon(HorizonMap.Result result) {
        FloatBuffer buf = MemoryUtil.memAllocFloat(result.angles().length);
        try {
            buf.put(result.angles()).flip();
            GlStateManager._bindTexture(horizonTexture);
            resetUnpack();
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, HorizonMap.DISTANCES, HorizonMap.AZIMUTHS, GL11.GL_RED,
                    GL11.GL_FLOAT, buf);
            GlStateManager._bindTexture(0);
        } finally {
            MemoryUtil.memFree(buf);
        }
        uploadedHorizon = result;
    }

    private static void resetUnpack() {
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
    }

    private static int loadNoise(Minecraft mc) {
        Optional<Resource> resource = mc.getResourceManager().getResource(NOISE);
        if (resource.isEmpty()) throw new IllegalStateException("missing " + NOISE);
        ByteBuffer buf = MemoryUtil.memAlloc(NOISE_SIZE * NOISE_SIZE * NOISE_SIZE * 4);
        try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
            // The PNG holds the 64 z-slices as an 8 x 8 grid.
            for (int z = 0; z < NOISE_SIZE; z++) {
                int ox = (z % 8) * NOISE_SIZE, oy = (z / 8) * NOISE_SIZE;
                for (int y = 0; y < NOISE_SIZE; y++) {
                    for (int x = 0; x < NOISE_SIZE; x++) {
                        int abgr = image.getPixelRGBA(ox + x, oy + y);
                        buf.put((byte) abgr).put((byte) (abgr >> 8)).put((byte) (abgr >> 16)).put((byte) (abgr >>> 24));
                    }
                }
            }
            buf.flip();
            int id = GlStateManager._genTexture();
            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + NOISE_UNIT);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, id);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL11.GL_REPEAT);
            resetUnpack();
            GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL11.GL_RGBA8, NOISE_SIZE, NOISE_SIZE, NOISE_SIZE, 0, GL11.GL_RGBA,
                    GL11.GL_UNSIGNED_BYTE, buf);
            GL30.glGenerateMipmap(GL12.GL_TEXTURE_3D);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
            GlStateManager._activeTexture(GL13.GL_TEXTURE0);
            return id;
        } catch (IOException e) {
            throw new IllegalStateException("couldn't read " + NOISE, e);
        } finally {
            MemoryUtil.memFree(buf);
        }
    }
}
