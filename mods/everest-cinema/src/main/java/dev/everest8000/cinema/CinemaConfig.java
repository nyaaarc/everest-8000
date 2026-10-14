package dev.everest8000.cinema;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.everest8000.cinema.spec.JObj;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * config/everest-cinema.json. The mod stays inert unless {@code enabled} is true. The API listens on 127.0.0.1 only;
 * with a non-empty {@code token} every connection must call session.hello with it first.
 * <pre>{"enabled": true, "port": 25580, "token": "random-string", "ffmpeg": "ffmpeg"}</pre>
 */
public record CinemaConfig(boolean enabled, int port, String token, String ffmpeg) {
    public static final CinemaConfig DISABLED = new CinemaConfig(false, 25580, "", "ffmpeg");

    public static CinemaConfig load(Path file) {
        if (!Files.exists(file)) return DISABLED;
        try {
            JsonElement e = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            JObj o = JObj.of(e, "everest-cinema.json");
            CinemaConfig c = new CinemaConfig(o.bool("enabled", false), o.integer("port", 1024, 65535, 25580),
                    o.has("token") ? o.string("token") : "", o.has("ffmpeg") ? o.string("ffmpeg") : "ffmpeg");
            o.finish();
            return c;
        } catch (IOException | RuntimeException ex) {
            CinemaClient.LOG.error("Cinema: couldn't read {}, staying disabled", file, ex);
            return DISABLED;
        }
    }
}
