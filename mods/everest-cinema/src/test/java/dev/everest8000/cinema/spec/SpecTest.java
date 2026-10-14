package dev.everest8000.cinema.spec;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.everest8000.cinema.camera.MovePlayer;
import dev.everest8000.cinema.camera.TargetResolver;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Java validator accepts every published example (which the Python client validates against the JSON Schema). */
class SpecTest {
    private static final Path DIR = Path.of(System.getProperty("cinema.projectDir", "."));

    static JObj read(Path p) throws IOException {
        return JObj.of(JsonParser.parseString(Files.readString(p)), "");
    }

    static JObj parse(String json) {
        return JObj.of(JsonParser.parseString(json), "shot");
    }

    @Test
    void examplesParse() throws IOException {
        try (Stream<Path> files = Files.list(DIR.resolve("examples"))) {
            for (Path p : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                BatchSpec b = BatchSpec.parse(read(p));
                assertTrue(b.shots().size() >= 1, p.toString());
            }
        }
    }

    /** gradle test -PcinemaParse=a.json,b.json: batch files from elsewhere (e.g. a project's shot list) must parse too. */
    @Test
    void extraFilesParse() throws IOException {
        String files = System.getProperty("cinema.parse", "");
        if (files.isBlank()) return;
        for (String f : files.split(",")) {
            BatchSpec b = BatchSpec.parse(read(Path.of(f.trim())));
            System.out.println("PARSED " + f + ": " + b.shots().size() + " shots");
        }
    }

    @Test
    void unknownFieldsAreRejected() {
        SpecException e = assertThrows(SpecException.class, () -> ShotSpec.parse(parse(
                "{\"name\":\"a\",\"durationSec\":2,\"camera\":{\"type\":\"static\",\"pos\":[0,0,0],\"yaw\":0,\"pitch\":0,\"bogus\":1}}")));
        assertTrue(e.getMessage().contains("bogus"), e.getMessage());
        assertTrue(e.path().startsWith("shot.camera"), e.path());
    }

    @Test
    void keyframeTimesMustBeAllOrNone() {
        assertThrows(SpecException.class, () -> ShotSpec.parse(parse(
                "{\"name\":\"a\",\"durationSec\":2,\"camera\":{\"type\":\"keyframes\",\"keyframes\":["
                        + "{\"t\":0,\"pos\":[0,0,0],\"yaw\":0,\"pitch\":0},{\"pos\":[1,0,0],\"yaw\":0,\"pitch\":0}]}}")));
    }

    @Test
    void blockingActionsAreNotEvents() {
        assertThrows(SpecException.class, () -> ShotSpec.parse(parse(
                "{\"name\":\"a\",\"durationSec\":2,\"camera\":{\"type\":\"static\",\"pos\":[0,0,0],\"yaw\":0,\"pitch\":0},"
                        + "\"events\":[{\"atSec\":1,\"action\":{\"type\":\"wait\",\"seconds\":1}}]}")));
    }

    @Test
    void splinePassesThroughKeys() {
        Move m = Move.parse(JObj.of(JsonParser.parseString("{\"type\":\"keyframes\",\"keyframes\":["
                + "{\"t\":0,\"pos\":[0,0,0],\"yaw\":0,\"pitch\":0},{\"t\":1,\"pos\":[10,0,0],\"yaw\":90,\"pitch\":0},"
                + "{\"t\":3,\"pos\":[10,0,20],\"yaw\":180,\"pitch\":10}]}"), "m"));
        MovePlayer p = new MovePlayer(m, Double.NaN);
        Pose a = p.sample(1.0, 1f, TargetResolver.POINTS_ONLY);
        assertEquals(10, a.pos().x(), 1e-9);
        assertEquals(90, a.yaw(), 1e-9);
        Pose b = p.sample(3.0, 1f, TargetResolver.POINTS_ONLY);
        assertEquals(20, b.pos().z(), 1e-9);
        assertEquals(10, b.pitch(), 1e-9);
    }

    @Test
    void orbitBearingConvention() {
        Move m = Move.parse(JObj.of(JsonParser.parseString("{\"type\":\"orbit\",\"center\":{\"type\":\"point\",\"pos\":[0,0,0]},"
                + "\"radius\":10,\"startDeg\":0,\"endDeg\":90,\"durationSec\":1}"), "m"));
        MovePlayer p = new MovePlayer(m, Double.NaN);
        Pose s = p.sample(0, 1f, TargetResolver.POINTS_ONLY);
        assertEquals(10, s.pos().z(), 1e-9);     // azimuth 0: camera south of the center
        assertEquals(180, Math.abs(s.yaw()), 1e-6); // looking north
        Pose e = p.sample(1, 1f, TargetResolver.POINTS_ONLY);
        assertEquals(-10, e.pos().x(), 1e-9);    // azimuth 90: camera west
    }

    @Test
    void radioAmountOnlyWithRaise() {
        ActorAction a = ActorAction.parse(JObj.of(JsonParser.parseString("{\"type\":\"pose\",\"pose\":\"raise\",\"amount\":0.5}"), "a"));
        assertEquals(0.5, ((ActorAction.Pose) a).amount(), 1e-12);
        assertTrue(Double.isNaN(((ActorAction.Pose) ActorAction.parse(JObj.of(
                JsonParser.parseString("{\"type\":\"pose\",\"pose\":\"raise\"}"), "a"))).amount()));
        assertThrows(SpecException.class, () -> ActorAction.parse(JObj.of(
                JsonParser.parseString("{\"type\":\"pose\",\"pose\":\"spyglass\",\"amount\":0.5}"), "a")));
    }

    @Test
    void scoutAndPlayerValidation() {
        assertThrows(SpecException.class, () -> ShotSpec.parse(parse(
                "{\"name\":\"a\",\"durationSec\":2,\"camera\":{\"type\":\"firstPerson\"},\"player\":{\"ride\":true,\"visible\":false}}")));
        JsonElement ok = JsonParser.parseString("{\"name\":\"a\",\"durationSec\":2,\"camera\":{\"type\":\"firstPerson\"}}");
        assertEquals("a", ShotSpec.parse(JObj.of(ok, "shot")).name());
    }
}
