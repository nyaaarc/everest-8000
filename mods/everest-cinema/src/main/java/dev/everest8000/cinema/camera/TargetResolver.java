package dev.everest8000.cinema.camera;

import dev.everest8000.cinema.spec.Target;
import dev.everest8000.cinema.spec.Vec3d;

/** Turns targets into world positions for the current frame. Throws {@link TargetNotFoundException} if an entity is gone. */
public interface TargetResolver {
    Vec3d position(Target target, float partialTick);

    /** Body yaw of the target (0 for points). */
    double yaw(Target target, float partialTick);

    final class TargetNotFoundException extends RuntimeException {
        public TargetNotFoundException(String message) {
            super(message);
        }
    }

    /** Resolves points only; for tests. */
    TargetResolver POINTS_ONLY = new TargetResolver() {
        @Override
        public Vec3d position(Target target, float partialTick) {
            if (target instanceof Target.Point p) return p.pos().add(p.offset());
            throw new TargetNotFoundException("no world: " + target);
        }

        @Override
        public double yaw(Target target, float partialTick) {
            return 0;
        }
    };
}
