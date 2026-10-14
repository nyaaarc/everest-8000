package dev.everest8000.cinema.spec;

/**
 * A quick check of framing: the shot plays at scale x the render size and at a low frame rate, saving {@code stills}
 * evenly spaced PNGs (first and last frame included) and the contact sheet; a low-quality video only with video=true.
 */
public record PreviewSettings(double scale, int stills, boolean video, int fps) {
    public static PreviewSettings parse(JObj o) {
        if (o == null) return null;
        PreviewSettings p = new PreviewSettings(o.num("scale", 0.1, 1, 0.5), o.integer("stills", 1, 64, 4), o.bool("video", false),
                o.integer("fps", 1, 60, 20));
        o.finish();
        return p;
    }
}
