package dev.everest8000.cinema.capture;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.everest8000.cinema.CinemaClient;
import dev.everest8000.cinema.rpc.RpcException;
import dev.everest8000.cinema.spec.RenderSettings;
import net.minecraft.client.Minecraft;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Frame-stepped recording: each rendered frame is read back from the main render target (after the GUI is drawn,
 * before it is shown), streamed as raw BGRA into ffmpeg's stdin, and the {@link FrameClock} is advanced by exactly one
 * frame. Also saves selected frames as PNG stills and builds a contact sheet. Render-thread calls only, except where
 * noted; encoding and PNG writing happen on a writer thread.
 */
public final class Recorder {
    private static final byte[] POISON = new byte[0];

    /** Where and what to write. video null = stills/sheet only. */
    public record Plan(Path video, RenderSettings settings, int fps, int totalFrames, Map<Integer, Path> stills,
                       List<Integer> sheetFrames, Path sheet, String label, String ffmpeg) {}

    private Plan plan;
    private boolean active;
    private int width, height;
    private int frame;
    private long base;
    private long frameNanos;
    private ByteBuffer direct;
    private BlockingQueue<byte[]> queue;
    private BlockingQueue<byte[]> pool;
    private Thread writer;
    private Process ffmpeg;
    private Path ffmpegLog;
    private volatile String writeError;
    private final Map<Integer, BufferedImage> sheetImages = new HashMap<>();
    private final List<CompletableFuture<Void>> pending = new ArrayList<>();
    private long startReal;
    private long renderNanos;

    public boolean active() {
        return active;
    }

    public int frame() {
        return frame;
    }

    public Plan plan() {
        return plan;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** Starts recording at the current frame size; switches the game clock to recording time. */
    public void start(Plan p) {
        if (active) throw RpcException.busy("a capture");
        RenderTarget rt = Minecraft.getInstance().getMainRenderTarget();
        width = rt.width;
        height = rt.height;
        plan = p;
        frame = 0;
        writeError = null;
        sheetImages.clear();
        pending.clear();
        direct = BufferUtils.createByteBuffer(width * height * 4);
        if (p.video() != null) {
            try {
                startFfmpeg(p);
            } catch (IOException e) {
                throw new RpcException(RpcException.CAPTURE_FAILED, "couldn't start ffmpeg (\"" + p.ffmpeg() + "\"): " + e.getMessage());
            }
            BlockingQueue<byte[]> q = new ArrayBlockingQueue<>(3);
            BlockingQueue<byte[]> pl = new ArrayBlockingQueue<>(4);
            for (int i = 0; i < 4; i++) pl.add(new byte[width * height * 4]);
            queue = q;
            pool = pl;
            OutputStream out = ffmpeg.getOutputStream();
            writer = new Thread(() -> writeLoop(out, q, pl), "Cinema ffmpeg writer");
            writer.setDaemon(true);
            writer.start();
        }
        frameNanos = Math.round(1e9 / p.fps());
        base = FrameClock.enterVirtual();
        startReal = System.nanoTime();
        renderNanos = 0;
        active = true;
        Minecraft mc = Minecraft.getInstance();
        savedVsync = mc.options.enableVsync().get();
        savedFpsLimit = mc.options.framerateLimit().get();
        mc.options.enableVsync().set(false);
        mc.getWindow().updateVsync(false);
        mc.options.framerateLimit().set(260);
    }

    private boolean savedVsync;
    private int savedFpsLimit = 260;

    private void restoreDisplay() {
        Minecraft mc = Minecraft.getInstance();
        mc.options.enableVsync().set(savedVsync);
        mc.getWindow().updateVsync(savedVsync);
        mc.options.framerateLimit().set(savedFpsLimit);
    }

    private void startFfmpeg(Plan p) throws IOException {
        RenderSettings s = p.settings();
        Files.createDirectories(p.video().toAbsolutePath().getParent());
        List<String> cmd = new ArrayList<>(List.of(p.ffmpeg(), "-hide_banner", "-loglevel", "warning", "-y",
                "-f", "rawvideo", "-pix_fmt", "bgra", "-s", width + "x" + height, "-framerate", Integer.toString(p.fps()), "-i", "-"));
        boolean ten = s.tenBit();
        String fmt = ten ? (s.codec().equals("libx265") ? "yuv420p10le" : "p010le") : "yuv420p";
        if (s.codec().equals("png")) {
            cmd.addAll(List.of("-vf", "vflip", "-c:v", "png"));
            Files.createDirectories(p.video());
            cmd.add(p.video().resolve("frame_%05d.png").toString());
        } else {
            cmd.addAll(List.of("-vf", "vflip,scale=out_color_matrix=bt709:out_range=tv,format=" + fmt));
            switch (s.codec()) {
                case "hevc_nvenc" -> cmd.addAll(List.of("-c:v", "hevc_nvenc", "-preset", "p7", "-tune", "hq", "-rc", "constqp",
                        "-qp", Integer.toString(s.quality()), "-profile:v", ten ? "main10" : "main", "-tag:v", "hvc1"));
                case "h264_nvenc" -> cmd.addAll(List.of("-c:v", "h264_nvenc", "-preset", "p7", "-tune", "hq", "-rc", "constqp",
                        "-qp", Integer.toString(s.quality()), "-profile:v", "high"));
                case "libx264" -> cmd.addAll(List.of("-c:v", "libx264", "-preset", "slow", "-crf", Integer.toString(s.quality())));
                case "libx265" -> cmd.addAll(List.of("-c:v", "libx265", "-preset", "medium", "-crf", Integer.toString(s.quality()), "-tag:v", "hvc1"));
                default -> throw new IllegalStateException(s.codec());
            }
            cmd.addAll(List.of("-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709", "-color_range", "tv",
                    "-movflags", "+faststart", p.video().toString()));
        }
        ffmpegLog = p.video().resolveSibling(p.video().getFileName() + ".ffmpeg.log");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Files.writeString(ffmpegLog, String.join(" ", cmd) + "\n", StandardCharsets.UTF_8);
        pb.redirectError(ProcessBuilder.Redirect.appendTo(ffmpegLog.toFile()));
        ffmpeg = pb.start();
    }

    private void writeLoop(OutputStream out, BlockingQueue<byte[]> q, BlockingQueue<byte[]> pl) {
        try {
            while (true) {
                byte[] b = q.take();
                if (b == POISON) break;
                if (writeError == null) {
                    try {
                        out.write(b);
                    } catch (IOException e) {
                        writeError = "writing to ffmpeg failed: " + e.getMessage();
                    }
                }
                pl.offer(b);
            }
            out.close();
        } catch (InterruptedException | IOException e) {
            if (writeError == null) writeError = "ffmpeg writer stopped: " + e;
        }
    }

    /** Sets the clock for the frame about to be produced (call at the start of each frame). */
    public void beginFrame() {
        if (active) FrameClock.setVirtual(base + frame * frameNanos);
    }

    /** Reads back the finished frame (call just before it is shown). */
    public void endFrame() {
        if (!active) return;
        long t0 = System.nanoTime();
        RenderTarget rt = Minecraft.getInstance().getMainRenderTarget();
        if (rt.width != width || rt.height != height) {
            writeError = "the window was resized during capture (" + width + "x" + height + " -> " + rt.width + "x" + rt.height + ")";
            frame++;
            return;
        }
        int prev = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, rt.frameBufferId);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
        direct.clear();
        GL11.glReadPixels(0, 0, width, height, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, direct);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prev);
        Path still = plan.stills().get(frame);
        boolean sheet = plan.sheetFrames().contains(frame);
        if (still != null || sheet) {
            byte[] copy = new byte[width * height * 4];
            direct.rewind();
            direct.get(copy);
            BufferedImage img = toImage(copy, width, height);
            if (sheet) sheetImages.put(frame, scale(img, Math.min(640, width)));
            if (still != null) pending.add(CompletableFuture.runAsync(() -> writePng(img, still)));
        }
        if (plan.video() != null && writeError == null) {
            try {
                byte[] b = pool.poll(30, TimeUnit.SECONDS);
                if (b == null) {
                    writeError = "ffmpeg stopped accepting frames for 30 s";
                } else {
                    direct.rewind();
                    direct.get(b);
                    if (!queue.offer(b, 30, TimeUnit.SECONDS)) writeError = "ffmpeg stopped accepting frames for 30 s";
                }
            } catch (InterruptedException e) {
                writeError = "interrupted";
            }
        }
        frame++;
        renderNanos += System.nanoTime() - t0;
    }

    public String error() {
        if (writeError != null) return writeError;
        if (ffmpeg != null && !ffmpeg.isAlive() && active) return "ffmpeg exited early with code " + ffmpeg.exitValue() + ": " + logTail();
        return null;
    }

    /**
     * Ends the recording: back to real time, then finishes encoding off the render thread. The future yields stats
     * or fails with CAPTURE_FAILED.
     */
    public CompletableFuture<JsonObject> stop() {
        if (!active) return CompletableFuture.failedFuture(new RpcException(RpcException.CAPTURE_FAILED, "no capture is running"));
        active = false;
        FrameClock.exitVirtual();
        restoreDisplay();
        final BlockingQueue<byte[]> q = queue;
        final int frames = frame;
        final double realSec = (System.nanoTime() - startReal) / 1e9;
        final Plan p = plan;
        final Process proc = ffmpeg;
        final Thread w = writer;
        final Map<Integer, BufferedImage> sheet = new HashMap<>(sheetImages);
        final List<CompletableFuture<Void>> stills = new ArrayList<>(pending);
        final String earlyError = writeError;
        ffmpeg = null;
        writer = null;
        return CompletableFuture.supplyAsync(() -> {
            String err = earlyError;
            if (w != null) {
                try {
                    q.put(POISON);
                    w.join(TimeUnit.MINUTES.toMillis(2));
                } catch (InterruptedException e) {
                    err = "interrupted while finishing";
                }
            }
            if (proc != null) {
                try {
                    if (!proc.waitFor(5, TimeUnit.MINUTES)) {
                        proc.destroyForcibly();
                        err = "ffmpeg didn't finish within 5 minutes";
                    } else if (proc.exitValue() != 0 && err == null) {
                        err = "ffmpeg exited with code " + proc.exitValue();
                    }
                } catch (InterruptedException e) {
                    err = "interrupted while waiting for ffmpeg";
                }
            }
            for (CompletableFuture<Void> f : stills) f.join();
            if (p.sheet() != null && !sheet.isEmpty()) writeSheet(sheet, p);
            if (err != null) {
                JsonObject d = new JsonObject();
                d.addProperty("log", logTail());
                throw new RpcException(RpcException.CAPTURE_FAILED, err, d);
            }
            JsonObject r = new JsonObject();
            if (p.video() != null) r.addProperty("video", p.video().toString());
            JsonArray st = new JsonArray();
            p.stills().values().stream().sorted().forEach(x -> st.add(x.toString()));
            r.add("stills", st);
            if (p.sheet() != null && !sheet.isEmpty()) r.addProperty("contactSheet", p.sheet().toString());
            r.addProperty("frames", frames);
            r.addProperty("fps", p.fps());
            r.addProperty("durationSec", frames / (double) p.fps());
            r.addProperty("width", width);
            r.addProperty("height", height);
            r.addProperty("realSeconds", Math.round(realSec * 100) / 100.0);
            r.addProperty("avgRenderFps", Math.round(frames / Math.max(1e-6, realSec) * 10) / 10.0);
            r.addProperty("readbackMsPerFrame", Math.round(renderNanos / 1e6 / Math.max(1, frames) * 10) / 10.0);
            return r;
        });
    }

    /** Kills a running capture without waiting (on errors). */
    public void abort() {
        if (!active) return;
        active = false;
        FrameClock.exitVirtual();
        restoreDisplay();
        if (writer != null) writer.interrupt();
        if (ffmpeg != null) ffmpeg.destroyForcibly();
        writer = null;
        ffmpeg = null;
    }

    private String logTail() {
        try {
            if (ffmpegLog == null || !Files.exists(ffmpegLog)) return "";
            List<String> lines = Files.readAllLines(ffmpegLog, StandardCharsets.UTF_8);
            return String.join("\n", lines.subList(Math.max(0, lines.size() - 15), lines.size()));
        } catch (IOException e) {
            return "";
        }
    }

    // ---- images

    static BufferedImage toImage(byte[] bgra, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            int src = (h - 1 - y) * w * 4;
            for (int x = 0; x < w; x++) {
                int i = src + x * 4;
                row[x] = (bgra[i + 2] & 0xFF) << 16 | (bgra[i + 1] & 0xFF) << 8 | (bgra[i] & 0xFF);
            }
            img.setRGB(0, y, w, 1, row, 0, w);
        }
        return img;
    }

    static BufferedImage scale(BufferedImage src, int w) {
        int h = Math.max(1, Math.round(src.getHeight() * (w / (float) src.getWidth())));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    static void writePng(BufferedImage img, Path path) {
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            ImageIO.write(img, "png", path.toFile());
        } catch (IOException e) {
            CinemaClient.LOG.warn("Cinema: couldn't write {}", path, e);
        }
    }

    private static void writeSheet(Map<Integer, BufferedImage> frames, Plan p) {
        List<Integer> order = new ArrayList<>(frames.keySet());
        order.sort(Integer::compare);
        BufferedImage first = frames.get(order.get(0));
        int cw = first.getWidth(), chh = first.getHeight();
        int cols = Math.min(3, order.size());
        int rows = (order.size() + cols - 1) / cols;
        int label = 28, pad = 6;
        BufferedImage sheet = new BufferedImage(cols * (cw + pad) + pad, rows * (chh + label + pad) + pad, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        g.setColor(new Color(24, 24, 24));
        g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 16));
        for (int i = 0; i < order.size(); i++) {
            int x = pad + (i % cols) * (cw + pad), y = pad + (i / cols) * (chh + label + pad);
            g.drawImage(frames.get(order.get(i)), x, y + label, null);
            g.setColor(new Color(220, 220, 220));
            int f = order.get(i);
            g.drawString(String.format(Locale.ROOT, "%s  frame %d  t=%.2fs", p.label(), f, f / (double) p.fps()), x + 4, y + 19);
        }
        g.dispose();
        writePng(sheet, p.sheet());
    }

    /** A single still of the next frame, outside recordings. */
    public static final class StillGrab {
        public final Path path;
        public final CompletableFuture<JsonObject> done = new CompletableFuture<>();

        public StillGrab(Path path) {
            this.path = path;
        }

        /** Render thread: reads the current frame and writes it asynchronously. */
        public void grab() {
            RenderTarget rt = Minecraft.getInstance().getMainRenderTarget();
            int w = rt.width, h = rt.height;
            ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
            int prev = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, rt.frameBufferId);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
            GL11.glReadPixels(0, 0, w, h, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, buf);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prev);
            byte[] b = new byte[w * h * 4];
            buf.get(b);
            CompletableFuture.runAsync(() -> {
                try {
                    Files.createDirectories(path.toAbsolutePath().getParent());
                    if (!ImageIO.write(toImage(b, w, h), "png", path.toFile())) throw new IOException("no PNG writer");
                    JsonObject r = new JsonObject();
                    r.addProperty("path", path.toString());
                    r.addProperty("width", w);
                    r.addProperty("height", h);
                    done.complete(r);
                } catch (IOException e) {
                    done.completeExceptionally(new RpcException(RpcException.CAPTURE_FAILED, "couldn't write " + path + ": " + e.getMessage()));
                }
            });
        }
    }
}
