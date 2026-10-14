package dev.everest8000.cinema.spec;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Something the camera can look at or follow: a fixed point, the local player, an entity by UUID, or the nearest
 * entity of a type. {@code anchor} picks the point on an entity (feet, center of the hitbox, or eyes) and
 * {@code offset} is added in world space.
 */
public sealed interface Target {
    Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    String[] ANCHORS = {"feet", "center", "eyes"};

    Vec3d offset();

    record Point(Vec3d pos, Vec3d offset) implements Target {}

    record Player(String anchor, Vec3d offset) implements Target {}

    record Entity(UUID uuid, String anchor, Vec3d offset) implements Target {}

    record NearestEntity(String entityType, double maxDistance, String anchor, Vec3d offset) implements Target {}

    /** A client-side actor by its id (see actor.spawn / shot.actors). */
    record Actor(String id, String anchor, Vec3d offset) implements Target {}

    static Target parse(JObj o) {
        String type = o.choice("type", "point", "player", "entity", "nearestEntity", "actor");
        Target t = switch (type) {
            case "point" -> new Point(o.vec3("pos"), o.vec3("offset", Vec3d.ZERO));
            case "player" -> new Player(o.choiceOr("anchor", "eyes", ANCHORS), o.vec3("offset", Vec3d.ZERO));
            case "entity" -> new Entity(o.uuid("uuid"), o.choiceOr("anchor", "eyes", ANCHORS), o.vec3("offset", Vec3d.ZERO));
            case "nearestEntity" -> new NearestEntity(o.string("entityType", ID, "a namespaced id like minecraft:cow"),
                    o.num("maxDistance", 1, 512, 64), o.choiceOr("anchor", "eyes", ANCHORS), o.vec3("offset", Vec3d.ZERO));
            case "actor" -> new Actor(o.string("id", ActorSpec.ID, "an actor id"), o.choiceOr("anchor", "eyes", ANCHORS),
                    o.vec3("offset", Vec3d.ZERO));
            default -> throw new IllegalStateException(type);
        };
        o.finish();
        return t;
    }
}
