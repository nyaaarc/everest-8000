package dev.everest8000.cinema.spec;

/** A spec or RPC parameter failed validation. {@link #path} is a JSON-pointer-like location such as {@code shot.camera.keyframes[2].pos}. */
public final class SpecException extends RuntimeException {
    private final String path;

    public SpecException(String path, String message) {
        super(path + ": " + message);
        this.path = path;
    }

    public String path() {
        return path;
    }

    /** The message without the path prefix. */
    public String reason() {
        String m = getMessage();
        return m.substring(Math.min(m.length(), path.length() + 2));
    }
}
