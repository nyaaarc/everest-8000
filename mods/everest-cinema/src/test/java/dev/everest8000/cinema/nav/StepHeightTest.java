package dev.everest8000.cinema.nav;

import dev.everest8000.cinema.spec.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Western Cwm case from the v2 review cut (d_backpack, 1:12): a climber standing on a deep snow layer faced a
 * "one block" step onto a block topped with more snow, a real climb of about 1.5 blocks that no jump clears, and hopped
 * in place. Steps are now judged on real surface heights, so the route goes round.
 */
class StepHeightTest {
    /** Blocks by (x, y, z): collision height. Everything else is air. Ground is full blocks up to y 9 everywhere. */
    static final class Blocks implements BlockQuery {
        final Map<Long, Double> top = new HashMap<>();

        static long k(int x, int y, int z) {
            return ((long) x & 0xFFFF) << 32 | ((long) y & 0xFFFF) << 16 | ((long) z & 0xFFFF);
        }

        void set(int x, int y, int z, double h) {
            top.put(k(x, y, z), h);
        }

        @Override
        public double collisionTop(int x, int y, int z) {
            if (y <= 9) return 1;
            return top.getOrDefault(k(x, y, z), 0.0);
        }

        @Override
        public boolean climbable(int x, int y, int z) {
            return false;
        }

        @Override
        public boolean hazard(int x, int y, int z) {
            return false;
        }

        @Override
        public double snowDepth(int x, int y, int z) {
            return 0;
        }

        @Override
        public boolean loaded(int x, int z) {
            return x >= 0 && z >= 0 && x < 12 && z < 8;
        }
    }

    @Test
    void stepFromDeepSnowOntoSnowToppedBlockIsNotTaken() {
        Blocks b = new Blocks();
        // x 0..3: a deep snow layer (0.75) on the ground; x 4..11: a "one block" step up, two blocks of snow block
        // topped with a thin layer (0.25): a real climb of 1.5 from the low side.
        for (int z = 0; z < 8; z++) {
            for (int x = 0; x < 4; x++) b.set(x, 10, z, 0.75);
            for (int x = 4; x < 12; x++) {
                b.set(x, 10, z, 1);
                b.set(x, 11, z, 1);
                b.set(x, 12, z, 0.25);
            }
        }
        // A ramp at z 6..7: at x 3 a snow block with a thin layer makes the climb two easy steps there.
        for (int z = 6; z < 8; z++) {
            b.set(3, 10, z, 1);
            b.set(3, 11, z, 0.25);
        }
        PathFinder pf = new PathFinder(b, PathFinder.Options.DEFAULT);
        assertEquals(10.75, pf.floorAt(1, 11, 1), 1e-9);
        assertEquals(12.25, pf.floorAt(6, 12, 1), 1e-9);
        PathFinder.Result r = pf.find(new Vec3d(1.5, 11, 1.5), new Vec3d(8.5, 12, 1.5));
        assertEquals(PathFinder.Status.FOUND, r.status(), "expanded " + r.expanded());
        // Every step of the route is a climb a jump clears.
        for (int i = 1; i < r.points().size(); i++) {
            Vec3d a = r.points().get(i - 1), c = r.points().get(i);
            double up = pf.floorAt((int) Math.floor(c.x()), (int) Math.floor(c.y()), (int) Math.floor(c.z()))
                    - pf.floorAt((int) Math.floor(a.x()), (int) Math.floor(a.y()), (int) Math.floor(a.z()));
            assertTrue(up <= PathFinder.MAX_CLIMB + 1e-9, "step " + a + " -> " + c + " climbs " + up);
        }
        // ...and it goes round by the ramp.
        assertTrue(r.points().stream().anyMatch(p -> p.z() >= 6), "route " + r.points());
    }
}
