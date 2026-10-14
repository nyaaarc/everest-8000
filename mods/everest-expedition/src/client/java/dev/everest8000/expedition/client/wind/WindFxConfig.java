package dev.everest8000.expedition.client.wind;

import dev.everest8000.expedition.EverestExpedition;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Client settings for the wind effects, in config/everest_expedition_client.properties. Missing or broken values
 * fall back to the defaults, and the file is rewritten with every key so it documents itself.
 */
public final class WindFxConfig {
    private static final String FILE = "everest_expedition_client.properties";

    /** 0 to 2: how much blown snow (spindrift, ground drift, air streaks). 0 turns them off. */
    public static float windParticles = 1f;
    /** Upper bound on live wind particles, whatever the weather. */
    public static int maxWindParticles = 2400;
    /** The summit plume and the storm's cloud cap. */
    public static boolean summitPlume = true;
    /** 1 low (fewer steps, third resolution), 2 normal, 3 high. */
    public static int plumeQuality = 2;
    /** A slight unsteadiness of the view in strong gusts. Also scaled by the game's Distortion Effects slider. */
    public static boolean cameraBuffet = true;

    private WindFxConfig() {}

    /** For the in-game tests: draws blown snow bright red and opaque, to see where it is and that it draws. */
    public static boolean debugColour;

    public static void setDebugColour(double on) {
        debugColour = on > 0.5;
    }

    /** Changes the blown-snow amount for this session only (used by the in-game tests to compare with and without). */
    public static void setWindParticles(double amount) {
        windParticles = clamp((float) amount, 0f, 2f);
    }

    public static void load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE);
        Properties p = new Properties();
        if (Files.exists(path)) {
            try (Reader r = Files.newBufferedReader(path)) {
                p.load(r);
            } catch (IOException e) {
                EverestExpedition.LOG.warn("Couldn't read {}; using defaults", path, e);
            }
        }
        windParticles = clamp(number(p, "wind_particles", windParticles), 0f, 2f);
        maxWindParticles = (int) clamp(number(p, "max_wind_particles", maxWindParticles), 0f, 16000f);
        summitPlume = bool(p, "summit_plume", summitPlume);
        plumeQuality = (int) clamp(number(p, "plume_quality", plumeQuality), 1f, 3f);
        cameraBuffet = bool(p, "camera_buffet", cameraBuffet);
        save(path);
    }

    private static void save(Path path) {
        String text = """
                # Everest Expedition: wind effects on this computer. Changes apply on the next game start.
                # wind_particles      0 to 2: amount of blown snow (ridge spindrift, ground drift, air streaks); 0 = off
                # max_wind_particles  cap on live wind particles
                # summit_plume        true/false: the jet-stream plume off the summits and the storm cloud cap
                # plume_quality       1 low, 2 normal, 3 high
                # camera_buffet       true/false: slight view shake in strong gusts (also follows Distortion Effects)
                wind_particles=%s
                max_wind_particles=%d
                summit_plume=%b
                plume_quality=%d
                camera_buffet=%b
                """.formatted(windParticles, maxWindParticles, summitPlume, plumeQuality, cameraBuffet);
        try {
            Files.createDirectories(path.getParent());
            try (Writer w = Files.newBufferedWriter(path)) {
                w.write(text);
            }
        } catch (IOException e) {
            EverestExpedition.LOG.warn("Couldn't write {}", path, e);
        }
    }

    private static float number(Properties p, String key, float fallback) {
        try {
            String v = p.getProperty(key);
            return v == null ? fallback : Float.parseFloat(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean bool(Properties p, String key, boolean fallback) {
        String v = p.getProperty(key);
        return v == null ? fallback : Boolean.parseBoolean(v.trim());
    }

    private static float clamp(float v, float lo, float hi) {
        return Float.isNaN(v) ? lo : Math.max(lo, Math.min(hi, v));
    }
}
