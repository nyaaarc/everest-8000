package dev.everest8000.cinema.lod;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Picture stability, a cross-check for the LOD gate: every interval it reads the finished frame and reduces it to a grid
 * of block-average brightness (so TAA noise and a few snow particles average out), then reports the mean change since
 * the previous sample in 0..255 levels, and the 95th-percentile cell change (a patch of distant terrain popping in shows
 * there even when the mean barely moves). A settled picture stays below ~0.5 mean.
 */
public final class FrameProbe {
    private static final int GW = 64, GH = 36;
    private static boolean active;
    private static long intervalNanos = 250_000_000L;
    private static long last;
    private static float[] prev;
    private static double lastDiff = Double.NaN, lastP95 = Double.NaN;
    private static int samples;
    private static ByteBuffer buf;

    private FrameProbe() {}

    public static void start(double intervalSec) {
        active = true;
        intervalNanos = (long) (intervalSec * 1e9);
        prev = null;
        lastDiff = Double.NaN;
        lastP95 = Double.NaN;
        samples = 0;
        last = 0;
    }

    public static void stop() {
        active = false;
        prev = null;
        buf = null;
    }

    public static double lastDiff() {
        return lastDiff;
    }

    public static double lastP95() {
        return lastP95;
    }

    public static int samples() {
        return samples;
    }

    /** Render thread, once per finished frame. */
    public static void onFrame() {
        if (!active) return;
        long now = System.nanoTime();
        if (now - last < intervalNanos) return;
        last = now;
        RenderTarget rt = Minecraft.getInstance().getMainRenderTarget();
        int w = rt.width, h = rt.height;
        if (buf == null || buf.capacity() < w * h * 4) buf = BufferUtils.createByteBuffer(w * h * 4);
        buf.clear();
        int prevFb = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, rt.frameBufferId);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
        GL11.glReadPixels(0, 0, w, h, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, buf);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevFb);
        float[] grid = new float[GW * GH];
        int[] counts = new int[GW * GH];
        int step = Math.max(2, Math.min(w, h) / 270); // about 270 samples down the short side at any resolution
        for (int y = 0; y < h; y += step) {
            int gy = Math.min(GH - 1, y * GH / h);
            for (int x = 0; x < w; x += step) {
                int gx = Math.min(GW - 1, x * GW / w);
                int i = (y * w + x) * 4;
                float l = 0.0722f * (buf.get(i) & 0xFF) + 0.7152f * (buf.get(i + 1) & 0xFF) + 0.2126f * (buf.get(i + 2) & 0xFF);
                grid[gy * GW + gx] += l;
                counts[gy * GW + gx]++;
            }
        }
        for (int i = 0; i < grid.length; i++) grid[i] /= Math.max(1, counts[i]);
        if (prev != null) {
            double s = 0;
            float[] d = new float[grid.length];
            for (int i = 0; i < grid.length; i++) {
                d[i] = Math.abs(grid[i] - prev[i]);
                s += d[i];
            }
            lastDiff = s / grid.length;
            Arrays.sort(d);
            lastP95 = d[(int) (d.length * 0.95)];
        }
        prev = grid;
        samples++;
    }
}
