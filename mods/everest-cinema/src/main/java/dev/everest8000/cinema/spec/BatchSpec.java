package dev.everest8000.cinema.spec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A shots file for the batch runner (schema/shots.schema.json): render settings, setup, shots, teardown. */
public record BatchSpec(int version, RenderSettings render, List<Action> setup, List<ShotSpec> shots, List<Action> teardown) {
    public static BatchSpec parse(JObj o) {
        o.allow("$schema");
        int version = o.integer("version", 1, 1);
        RenderSettings render = RenderSettings.parse(o.optObj("render"));
        List<Action> setup = Action.parseList(o, "setup", true);
        List<ShotSpec> shots = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (JObj s : o.objList("shots", 1, 10000)) {
            ShotSpec shot = ShotSpec.parse(s);
            if (!names.add(shot.name())) throw new SpecException(s.at("name"), "duplicate shot name \"" + shot.name() + "\"");
            shots.add(shot);
        }
        List<Action> teardown = Action.parseList(o, "teardown", true);
        o.finish();
        return new BatchSpec(version, render, setup, List.copyOf(shots), teardown);
    }
}
