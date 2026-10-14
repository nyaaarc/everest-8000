package dev.everest8000.cinema.camera;

import dev.everest8000.cinema.spec.Target;
import dev.everest8000.cinema.spec.Vec3d;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Resolves targets against the client world. Nearest-entity targets lock on to the first match until it disappears. */
public final class McTargets implements TargetResolver {
    public static final McTargets INSTANCE = new McTargets();
    private final Map<Target.NearestEntity, Integer> locked = new HashMap<>();

    private McTargets() {}

    public void clearLocks() {
        locked.clear();
    }

    @Override
    public Vec3d position(Target t, float pt) {
        if (t instanceof Target.Point p) return p.pos().add(p.offset());
        Entity e = entity(t);
        String anchor = switch (t) {
            case Target.Player p -> p.anchor();
            case Target.Entity en -> en.anchor();
            case Target.NearestEntity n -> n.anchor();
            case Target.Actor a -> a.anchor();
            default -> "feet";
        };
        Vec3 feet = e.getPosition(pt);
        double dy = switch (anchor) {
            case "eyes" -> e.getEyeHeight();
            case "center" -> e.getBbHeight() / 2.0;
            default -> 0.0;
        };
        return new Vec3d(feet.x, feet.y + dy, feet.z).add(t.offset());
    }

    @Override
    public double yaw(Target t, float pt) {
        if (t instanceof Target.Point) return 0;
        Entity e = entity(t);
        if (e instanceof LivingEntity le) return net.minecraft.util.Mth.rotLerp(pt, le.yBodyRotO, le.yBodyRot);
        return e.getViewYRot(pt);
    }

    /** The entity a non-point target refers to. */
    public Entity entity(Target t) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) throw new TargetNotFoundException("not in a world");
        return switch (t) {
            case Target.Player p -> mc.player;
            case Target.Entity en -> find(en.uuid());
            case Target.NearestEntity n -> nearest(n);
            case Target.Actor a -> {
                Entity e = dev.everest8000.cinema.actor.Actors.entity(a.id());
                if (e == null) throw new TargetNotFoundException("no actor \"" + a.id() + "\"");
                yield e;
            }
            case Target.Point p -> throw new IllegalArgumentException("a point has no entity");
        };
    }

    private Entity find(UUID uuid) {
        for (Entity e : Minecraft.getInstance().level.entitiesForRendering()) {
            if (e.getUUID().equals(uuid)) return e;
        }
        throw new TargetNotFoundException("no loaded entity with UUID " + uuid);
    }

    private Entity nearest(Target.NearestEntity n) {
        Minecraft mc = Minecraft.getInstance();
        Integer id = locked.get(n);
        if (id != null) {
            Entity e = mc.level.getEntity(id);
            if (e != null && !e.isRemoved()) return e;
        }
        Optional<EntityType<?>> type = BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.parse(n.entityType()));
        if (type.isEmpty()) throw new TargetNotFoundException("unknown entity type " + n.entityType());
        Entity best = null;
        double bestD = n.maxDistance() * n.maxDistance();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e.getType() != type.get() || e.isRemoved()) continue;
            double d = e.distanceToSqr(mc.player);
            if (d <= bestD) {
                bestD = d;
                best = e;
            }
        }
        if (best == null) {
            throw new TargetNotFoundException("no " + n.entityType() + " within " + n.maxDistance() + " blocks of the player");
        }
        locked.put(n, best.getId());
        return best;
    }
}
