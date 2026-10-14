package dev.everest8000.cinema.capture;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * Renders at a size the window can't have (3840x2160 on a 2560x1600 screen): the game's framebuffer size is set to the
 * capture size, so the main render target, Iris's and Distant Horizons' buffers and the GUI layout are all built for it,
 * while the window itself keeps whatever size the screen allows and shows the frame scaled down. Window resize events
 * are ignored until {@link #clear}.
 */
public final class RenderSize {
    private static boolean active;
    private static int width, height;
    private static int realWidth, realHeight;

    private RenderSize() {}

    public static boolean active() {
        return active;
    }

    public static int width() {
        return width;
    }

    public static int height() {
        return height;
    }

    /** The window's real framebuffer size (for showing the frame on screen). */
    public static int realWidth() {
        return realWidth;
    }

    public static int realHeight() {
        return realHeight;
    }

    /** Render thread. Sets the framebuffer size and rebuilds everything that depends on it. */
    public static void set(Minecraft mc, int w, int h) {
        Window win = mc.getWindow();
        if (!active) readReal(win);
        active = true;
        width = w;
        height = h;
        if (win.getWidth() != w || win.getHeight() != h) {
            win.setWidth(w);
            win.setHeight(h);
            mc.resizeDisplay();
        }
    }

    /** Render thread. Back to the window's own framebuffer size. */
    public static void clear(Minecraft mc) {
        if (!active) return;
        active = false;
        Window win = mc.getWindow();
        readReal(win);
        win.setWidth(Math.max(1, realWidth));
        win.setHeight(Math.max(1, realHeight));
        mc.resizeDisplay();
    }

    /** Called from the window's framebuffer-resize callback while active: remember the real size, keep ours. */
    public static void onRealResize(int w, int h) {
        realWidth = w;
        realHeight = h;
    }

    private static void readReal(Window win) {
        int[] w = new int[1], h = new int[1];
        GLFW.glfwGetFramebufferSize(win.getWindow(), w, h);
        realWidth = w[0];
        realHeight = h[0];
    }
}
