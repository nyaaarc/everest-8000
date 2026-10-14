package dev.everest8000.cinema.spec;

/**
 * Output settings for a render. width/height set the game window (the frame size). quality is the constant QP for
 * NVENC or the CRF for x264/x265 (lower is better). tenBit encodes HEVC as Main10. serverTickSync slows the server's
 * tick rate to match the render speed so server-driven motion (mobs, weather) also plays back at real speed.
 * sheetFrames is how many frames go in the per-shot contact sheet. offscreen: "auto" resizes the window and, if the
 * screen can't hold that size, renders offscreen at it instead (the window shows the frame scaled); "always" renders
 * offscreen; "never" records at whatever size the window reaches.
 */
public record RenderSettings(int width, int height, int fps, String codec, int quality, boolean tenBit, boolean serverTickSync,
                             int sheetFrames, String offscreen) {
    public static final String[] CODECS = {"hevc_nvenc", "h264_nvenc", "libx264", "libx265", "png"};
    public static final RenderSettings DEFAULT = new RenderSettings(1920, 1080, 60, "hevc_nvenc", 18, true, false, 6, "auto");

    public static RenderSettings parse(JObj o) {
        if (o == null) return DEFAULT;
        String codec = o.choiceOr("codec", DEFAULT.codec, CODECS);
        boolean tenBit = o.bool("tenBit", codec.equals("hevc_nvenc") || codec.equals("libx265"));
        if (tenBit && !(codec.equals("hevc_nvenc") || codec.equals("libx265"))) {
            throw new SpecException(o.at("tenBit"), "only hevc_nvenc and libx265 encode 10-bit");
        }
        int width = o.integer("width", 16, 7680, DEFAULT.width);
        int height = o.integer("height", 16, 4320, DEFAULT.height);
        if (width % 2 != 0 || height % 2 != 0) throw new SpecException(o.path(), "width and height must be even");
        RenderSettings r = new RenderSettings(width, height, o.integer("fps", 1, 240, DEFAULT.fps), codec,
                o.integer("quality", 0, 51, DEFAULT.quality), tenBit, o.bool("serverTickSync", false), o.integer("sheetFrames", 0, 24, 6),
                o.choiceOr("offscreen", "auto", "auto", "always", "never"));
        o.finish();
        return r;
    }

    public String extension() {
        return codec.equals("png") ? "" : ".mp4";
    }
}
