package dev.everest8000.cinema.nav;

import dev.everest8000.cinema.spec.Vec3d;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Path finding and locomotion without the game: synthetic block worlds for the rules, and the real South Col ridge
 * (heights exported from the world) for an end-to-end walk with a simple kinematic body. The demo test also draws
 * build/demo/*.png comparing the old straight-line puppet with the director's motion.
 */
class NavTest {
    /** A heightfield world: solid below top(x, z), optional ladders and hazards. */
    static class Grid implements BlockQuery {
        final int x0, z0, w, h;
        final short[] top;
        final java.util.Set<Long> ladders = new java.util.HashSet<>();

        Grid(int x0, int z0, int w, int h, short[] top) {
            this.x0 = x0;
            this.z0 = z0;
            this.w = w;
            this.h = h;
            this.top = top;
        }

        static Grid flat(int w, int h, int y) {
            short[] t = new short[w * h];
            java.util.Arrays.fill(t, (short) y);
            return new Grid(0, 0, w, h, t);
        }

        static Grid load(String resource) throws IOException {
            try (InputStream in = NavTest.class.getResourceAsStream(resource); DataInputStream d = new DataInputStream(in)) {
                int x0 = d.readInt(), z0 = d.readInt(), w = d.readInt(), h = d.readInt();
                short[] t = new short[w * h];
                for (int i = 0; i < t.length; i++) t[i] = d.readShort();
                return new Grid(x0, z0, w, h, t);
            }
        }

        boolean inside(int x, int z) {
            return x >= x0 && z >= z0 && x < x0 + w && z < z0 + h;
        }

        int topAt(int x, int z) {
            return inside(x, z) ? top[(z - z0) * w + (x - x0)] : -2000;
        }

        void set(int x, int z, int y) {
            top[(z - z0) * w + (x - x0)] = (short) y;
        }

        @Override
        public double collisionTop(int x, int y, int z) {
            if (ladders.contains(key(x, y, z))) return 0;
            return y <= topAt(x, z) ? 1 : 0;
        }

        @Override
        public boolean climbable(int x, int y, int z) {
            return ladders.contains(key(x, y, z));
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
            return inside(x, z);
        }

        static long key(int x, int y, int z) {
            return ((long) x << 40) ^ ((long) z << 16) ^ (y & 0xFFFF);
        }
    }

    /** A crude stand-in for player physics: walk along the yaw, step up 0.6, jump 1.25, fall onto the ground. */
    static final class Body {
        double x, y, z, yaw;
        boolean hcol;
        final List<double[]> trace = new ArrayList<>();

        Body(double x, double y, double z, double yaw) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
        }

        boolean tryMove(Grid g, double nx, double nz, boolean jump) {
            double ground = g.topAt((int) Math.floor(nx), (int) Math.floor(nz)) + 1;
            if (ground - y <= 0.6 || jump && ground - y <= 1.25) {
                x = nx;
                z = nz;
                y = ground;
                return true;
            }
            return false;
        }

        Locomotion.State state() {
            return new Locomotion.State(x, y, z, yaw, true, hcol, false);
        }

        void step(Grid g, double forward, boolean jump, double newYaw, double headYaw) {
            yaw = newYaw;
            double speed = forward * 4.317 / 20;
            double nx = x - Math.sin(Math.toRadians(yaw)) * speed, nz = z + Math.cos(Math.toRadians(yaw)) * speed;
            hcol = false;
            // Like Minecraft collision: if the full move is blocked, slide along whichever axis is free.
            if (!tryMove(g, nx, nz, jump)) {
                hcol = true;
                if (!tryMove(g, nx, z, jump)) tryMove(g, x, nz, jump);
            }
            trace.add(new double[]{x, y, z, forward, yaw, headYaw});
        }
    }

    @Test
    void pathGoesAroundACliffInsteadOfDroppingOff() {
        Grid g = Grid.flat(40, 40, 10);
        // A 12-deep pit across the middle with a 3-wide land bridge at x 30..32.
        for (int x = 0; x < 40; x++) for (int z = 18; z < 22; z++) if (x < 30 || x > 32) g.set(x, z, -2);
        PathFinder pf = new PathFinder(g, PathFinder.Options.DEFAULT);
        PathFinder.Result r = pf.find(new Vec3d(5.5, 11, 5.5), new Vec3d(5.5, 11, 35.5));
        assertEquals(PathFinder.Status.FOUND, r.status());
        for (Vec3d p : r.points()) assertTrue(p.y() >= 11, "never drops into the pit: " + p);
        assertTrue(r.points().stream().anyMatch(p -> p.x() >= 30 && p.x() <= 33), "crosses on the bridge");
    }

    @Test
    void stepsUpOneBlockButNotTwo() {
        Grid g = Grid.flat(20, 20, 10);
        for (int z = 0; z < 20; z++) g.set(10, z, 12); // a 2-high wall
        for (int z = 15; z < 20; z++) g.set(10, z, 11); // with a 1-high section
        PathFinder pf = new PathFinder(g, PathFinder.Options.DEFAULT);
        PathFinder.Result r = pf.find(new Vec3d(5.5, 11, 5.5), new Vec3d(15.5, 11, 5.5));
        assertEquals(PathFinder.Status.FOUND, r.status());
        assertTrue(r.points().stream().anyMatch(p -> p.x() > 10 && p.x() < 11 && p.z() >= 15), "goes over the low part");
    }

    @Test
    void climbsALadder() {
        Grid g = Grid.flat(20, 20, 10);
        for (int x = 0; x < 20; x++) for (int z = 10; z < 20; z++) g.set(x, z, 18); // a cliff 8 blocks up
        for (int y = 11; y <= 18; y++) g.ladders.add(Grid.key(5, y, 9)); // ladder against it
        PathFinder pf = new PathFinder(g, PathFinder.Options.DEFAULT);
        PathFinder.Result r = pf.find(new Vec3d(5.5, 11, 2.5), new Vec3d(5.5, 19, 15.5));
        assertEquals(PathFinder.Status.FOUND, r.status());
        long vertical = 0;
        for (int i = 1; i < r.points().size(); i++) {
            Vec3d a = r.points().get(i - 1), b = r.points().get(i);
            if (a.x() == b.x() && a.z() == b.z() && b.y() > a.y()) vertical++;
        }
        assertTrue(vertical >= 6, "climbs the ladder column");
    }

    @Test
    void walksTheRealRidgeDeterministically() throws IOException {
        Grid g = Grid.load("/terrain_southcol.bin");
        Body a = walk(g, 42, null), b = walk(g, 42, null);
        assertEquals(a.trace.size(), b.trace.size());
        double[] la = a.trace.get(a.trace.size() - 1), lb = b.trace.get(b.trace.size() - 1);
        assertEquals(la[0], lb[0], 1e-12);
        assertEquals(la[2], lb[2], 1e-12);
        assertTrue(Math.hypot(la[0] - 21043.5, la[2] - 8036.5) < 1.5, "arrived near the goal: " + la[0] + " " + la[2]
                + " after " + a.trace.size() / 20.0 + " s");
    }

    static final Vec3d START = new Vec3d(21062.5, 1550, 8519.5), GOAL = new Vec3d(21043.5, 1736, 8036.5);
    /** A climber at altitude: slower pace, big slow wander, breathers that come more often on climbs and higher up. */
    static final Gait EVEREST = new Gait(0.7, 140, 0.7, 1.2, 18, 2.4, 0.45, 0.35, new double[]{22, 40}, new double[]{2.0, 5.0}, 0.8,
            new double[][]{{1200, 0.25}, {1555, 1.0}, {1800, 1.3}, {1980, 1.8}},
            new double[][]{{1200, 1.0}, {1555, 0.8}, {1800, 0.68}, {2000, 0.58}}, true);

    static Body walk(Grid g, long seed, List<Integer> breathersOut) {
        PathFinder pf = new PathFinder(g, PathFinder.Options.DEFAULT);
        PathFinder.Result r = pf.find(START, GOAL);
        assertEquals(PathFinder.Status.FOUND, r.status(), "expanded " + r.expanded());
        Gait gait = EVEREST;
        Locomotion loco = new Locomotion(gait, seed);
        loco.setWalkable(pf);
        LookControl look = new LookControl(seed, true);
        loco.follow(RouteShaper.shape(r.points(), pf, seed, gait.wander(), gait.wanderWavelength()), "walk", 0.6, true);
        Body body = new Body(START.x(), r.points().get(0).y(), START.z(), 180);
        int repaths = 0;
        for (int t = 0; t < 20 * 900 && loco.outcome() == Locomotion.Outcome.WALKING; t++) {
            Locomotion.Control c = loco.step(body.state(), g);
            if (breathersOut != null && loco.inBreather()) breathersOut.add(t);
            LookControl.Look lk = look.step(c.yaw(), Math.abs(c.forward()) > 0.05, 10,
                    new LookControl.Breather(loco.breatherPhase(), loco.breatherProgress(), loco.breatherCount(), Double.NaN));
            body.step(g, c.forward(), c.jump(), lk.bodyYaw(), lk.headYaw());
            if (loco.outcome() == Locomotion.Outcome.STUCK && repaths < 3) {
                // Same as the in-game actor: route again from where it is.
                repaths++;
                PathFinder.Result rr = pf.find(new Vec3d(body.x, body.y, body.z), GOAL);
                if (!rr.points().isEmpty()) loco.follow(RouteShaper.shape(rr.points(), pf, seed + repaths, gait.wander(),
                        gait.wanderWavelength()), "walk", 0.6, true);
            }
        }
        if (loco.outcome() != Locomotion.Outcome.ARRIVED) {
            int bx = (int) Math.floor(body.x), bz = (int) Math.floor(body.z);
            StringBuilder sb = new StringBuilder("DEBUG " + loco.stuckInfo() + " outcome " + loco.outcome() + " at " + body.x + " " + body.y + " " + body.z
                    + " next " + loco.remainingPath().stream().limit(4).toList() + "\n");
            for (int dz = -3; dz <= 3; dz++) {
                for (int dx = -3; dx <= 3; dx++) sb.append(String.format("%5d", g.topAt(bx + dx, bz + dz)));
                sb.append("\n");
            }
            System.out.println(sb);
        }
        return body;
    }

    /** The v1 puppet: steer straight at each waypoint at a fixed rate, full speed, no wander, no ramps. */
    static Body walkOld(Grid g) {
        PathFinder pf = new PathFinder(g, PathFinder.Options.DEFAULT);
        List<Vec3d> pts = PathFinder.simplify(pf.find(START, GOAL).points());
        Body body = new Body(START.x(), pts.get(0).y(), START.z(), 180);
        int i = 0;
        for (int t = 0; t < 20 * 400 && i < pts.size(); t++) {
            Vec3d p = pts.get(i);
            if (Math.hypot(p.x() - body.x, p.z() - body.z) < 0.6) {
                i++;
                continue;
            }
            double want = Math.toDegrees(Math.atan2(-(p.x() - body.x), p.z() - body.z));
            double d = Locomotion.wrap(want - body.yaw);
            double yaw = body.yaw + Math.max(-14, Math.min(14, d));
            double off = Math.abs(d);
            double f = off > 60 ? 0.2 : off > 25 ? 0.6 : 1.0;
            body.step(g, f, body.hcol, yaw, yaw);
        }
        return body;
    }

    @Test
    void demo() throws IOException {
        Grid g = Grid.load("/terrain_southcol.bin");
        List<Integer> breathers = new ArrayList<>();
        Body director = walk(g, 7, breathers);
        List<Integer> breathers2 = new ArrayList<>();
        Body second = walk(g, 99, breathers2);
        Body old = walkOld(g);
        Path dir = Path.of(System.getProperty("cinema.projectDir", ".")).resolve("build").resolve("demo");
        Files.createDirectories(dir);
        drawMap(g, old, director, breathers, dir.resolve("director_vs_puppet_map.png"));
        drawTwo(g, director, second, breathers, breathers2, dir.resolve("two_actors_two_seeds.png"));
        drawSeries(old, director, dir.resolve("director_vs_puppet_series.png"));
        assertTrue(!breathers.isEmpty(), "takes breathers on a long climb");
        System.out.println("demo written to " + dir.toAbsolutePath() + "; director " + director.trace.size() / 20.0 + " s, second "
                + second.trace.size() / 20.0 + " s, puppet " + old.trace.size() / 20.0 + " s, breathers " + breathers.size() / 20.0
                + " s / " + breathers2.size() / 20.0 + " s");
        double maxLat = 0;
        for (double[] p : director.trace) maxLat = Math.max(maxLat, lateral(old, p));
        System.out.println("max lateral offset from the straight-line route: " + Math.round(maxLat * 10) / 10.0 + " blocks");
    }

    private static void drawMap(Grid g, Body old, Body director, List<Integer> breathers, Path out) throws IOException {
        // Crop to the walked area, scaled up.
        double minX = 1e9, maxX = -1e9, minZ = 1e9, maxZ = -1e9;
        for (Body b : List.of(old, director)) for (double[] p : b.trace) {
            minX = Math.min(minX, p[0]);
            maxX = Math.max(maxX, p[0]);
            minZ = Math.min(minZ, p[2]);
            maxZ = Math.max(maxZ, p[2]);
        }
        int x0 = (int) minX - 12, z0 = (int) minZ - 12, w = (int) (maxX - minX) + 24, h = (int) (maxZ - minZ) + 24;
        int s = 6;
        BufferedImage img = new BufferedImage(w * s + 260, h * s, BufferedImage.TYPE_INT_RGB);
        Graphics2D gr = img.createGraphics();
        gr.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int lo = 9999, hi = -9999;
        for (int z = z0; z < z0 + h; z++) for (int x = x0; x < x0 + w; x++) {
            int t = g.topAt(x, z);
            if (t > -1000) {
                lo = Math.min(lo, t);
                hi = Math.max(hi, t);
            }
        }
        for (int z = z0; z < z0 + h; z++) for (int x = x0; x < x0 + w; x++) {
            int t = g.topAt(x, z);
            int sh = g.topAt(x - 1, z - 1);
            float v = (t - lo) / (float) Math.max(1, hi - lo);
            float light = t > sh ? 1.1f : t < sh ? 0.8f : 1f;
            int c = (int) Math.max(0, Math.min(255, (70 + 170 * v) * light));
            gr.setColor(new Color(c, c, Math.min(255, c + 12)));
            gr.fillRect((x - x0) * s, (z - z0) * s, s, s);
        }
        drawTrace(gr, old, x0, z0, s, new Color(220, 60, 60), false);
        drawTrace(gr, director, x0, z0, s, new Color(40, 120, 255), true);
        gr.setColor(new Color(255, 200, 0));
        for (int t : breathers) {
            double[] p = director.trace.get(Math.min(t, director.trace.size() - 1));
            gr.fillOval((int) ((p[0] - x0) * s) - 4, (int) ((p[2] - z0) * s) - 4, 8, 8);
        }
        gr.setColor(new Color(24, 24, 24));
        gr.fillRect(w * s, 0, 260, h * s);
        gr.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        gr.setColor(new Color(220, 60, 60));
        gr.drawString("v1 puppet (straight lines)", w * s + 12, 30);
        gr.setColor(new Color(40, 120, 255));
        gr.drawString("director (pursuit + wander)", w * s + 12, 55);
        gr.setColor(new Color(255, 200, 0));
        gr.drawString("breathers", w * s + 12, 80);
        gr.setColor(Color.WHITE);
        gr.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        gr.drawString("Real terrain: South Col to the", w * s + 12, 115);
        gr.drawString("south-east ridge (top-down,", w * s + 12, 132);
        gr.drawString("brighter = higher).", w * s + 12, 149);
        gr.dispose();
        ImageIO.write(img, "png", out.toFile());
    }

    /** Distance from p to the nearest point of the other trace (horizontal). */
    static double lateral(Body ref, double[] p) {
        double best = 1e9;
        for (double[] q : ref.trace) best = Math.min(best, Math.hypot(q[0] - p[0], q[2] - p[2]));
        return best;
    }

    /** Two actors, same route and gait, different seeds, over a zoomed stretch, plus their speed traces. */
    private static void drawTwo(Grid g, Body a, Body b, List<Integer> ba, List<Integer> bb, Path out) throws IOException {
        int x0 = 21035, z0 = 8330, w = 60, h = 120, s = 10;
        int W = w * s + 700;
        BufferedImage img = new BufferedImage(W, h * s, BufferedImage.TYPE_INT_RGB);
        Graphics2D gr = img.createGraphics();
        gr.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int lo = 9999, hi = -9999;
        for (int z = z0; z < z0 + h; z++) for (int x = x0; x < x0 + w; x++) {
            int t = g.topAt(x, z);
            if (t > -1000) {
                lo = Math.min(lo, t);
                hi = Math.max(hi, t);
            }
        }
        for (int z = z0; z < z0 + h; z++) for (int x = x0; x < x0 + w; x++) {
            int t = g.topAt(x, z), sh = g.topAt(x - 1, z - 1);
            float v = (t - lo) / (float) Math.max(1, hi - lo);
            float light = t > sh ? 1.1f : t < sh ? 0.8f : 1f;
            int c = (int) Math.max(0, Math.min(255, (70 + 170 * v) * light));
            gr.setColor(new Color(c, c, Math.min(255, c + 12)));
            gr.fillRect((x - x0) * s, (z - z0) * s, s, s);
        }
        drawTrace(gr, a, x0, z0, s, new Color(40, 120, 255), false);
        drawTrace(gr, b, x0, z0, s, new Color(255, 120, 40), false);
        gr.setColor(new Color(24, 24, 28));
        gr.fillRect(w * s, 0, 700, h * s);
        int px0 = w * s + 30, pw = 640, top = 90, ph = 260;
        gr.setColor(new Color(70, 70, 80));
        gr.drawRect(px0, top, pw, ph);
        int n = Math.max(a.trace.size(), b.trace.size());
        for (int k = 0; k < 2; k++) {
            Body bd = k == 0 ? a : b;
            gr.setColor(k == 0 ? new Color(40, 120, 255) : new Color(255, 120, 40));
            int lx = -1, ly = -1;
            for (int i = 0; i < bd.trace.size(); i++) {
                int x = px0 + (int) (pw * i / (double) n);
                int y = top + ph - (int) (ph * Math.min(1, bd.trace.get(i)[3]));
                if (lx >= 0) gr.drawLine(lx, ly, x, y);
                lx = x;
                ly = y;
            }
        }
        // Inset: 12 s around the first breather of the first climber, to show the ease in and out.
        int first = ba.isEmpty() ? 0 : ba.get(0);
        int i0 = Math.max(0, first - 60), i1 = Math.min(a.trace.size(), i0 + 240);
        int itop = top + ph + 90, ih = 200;
        gr.setColor(new Color(70, 70, 80));
        gr.drawRect(px0, itop, pw, ih);
        gr.setColor(new Color(40, 120, 255));
        int lx2 = -1, ly2 = -1;
        for (int i = i0; i < i1; i++) {
            int x = px0 + (int) (pw * (i - i0) / 240.0);
            int y = itop + ih - (int) (ih * Math.min(1, a.trace.get(i)[3]));
            if (lx2 >= 0) gr.drawLine(lx2, ly2, x, y);
            lx2 = x;
            ly2 = y;
        }
        gr.setColor(new Color(90, 90, 100));
        for (int sec = 0; sec <= 12; sec++) {
            int x = px0 + (int) (pw * sec * 20 / 240.0);
            gr.drawLine(x, itop + ih, x, itop + ih + 5);
        }
        gr.setColor(Color.WHITE);
        gr.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        gr.drawString("zoom: 12 s around the first breather (ticks every second): eased stop and restart", px0, itop - 8);
        gr.setColor(Color.WHITE);
        gr.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        gr.drawString("Two climbers, same route and gait, seeds 7 and 99", px0, 30);
        gr.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        gr.drawString("left: zoomed stretch above the South Col (top-down)", px0, 52);
        gr.drawString("below: forward input over the whole climb (0 = breather)", px0, 72);
        gr.drawString("blue: seed 7, " + a.trace.size() / 20 + " s, breathers " + ba.size() / 20 + " s", px0, top + ph + 30);
        gr.drawString("orange: seed 99, " + b.trace.size() / 20 + " s, breathers " + bb.size() / 20 + " s", px0, top + ph + 50);
        gr.dispose();
        ImageIO.write(img, "png", out.toFile());
    }

    private static void drawTrace(Graphics2D gr, Body b, int x0, int z0, int s, Color c, boolean bySpeed) {
        gr.setStroke(new BasicStroke(2.5f));
        for (int i = 1; i < b.trace.size(); i++) {
            double[] p = b.trace.get(i - 1), q = b.trace.get(i);
            if (bySpeed) {
                float f = (float) Math.max(0, Math.min(1, q[3]));
                gr.setColor(new Color((int) (40 + 60 * (1 - f)), (int) (120 + 60 * (1 - f)), 255));
            } else {
                gr.setColor(c);
            }
            gr.drawLine((int) ((p[0] - x0) * s), (int) ((p[2] - z0) * s), (int) ((q[0] - x0) * s), (int) ((q[2] - z0) * s));
        }
    }

    private static void drawSeries(Body old, Body director, Path out) throws IOException {
        int n = Math.max(old.trace.size(), director.trace.size());
        int W = 1600, H = 620, pad = 50;
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(20, 20, 24));
        g.fillRect(0, 0, W, H);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        String[] titles = {"forward input (speed)", "body yaw vs head yaw (director); puppet yaw"};
        for (int panel = 0; panel < 2; panel++) {
            int top = pad + panel * (H / 2), ph = H / 2 - pad - 10;
            g.setColor(new Color(60, 60, 70));
            g.drawRect(pad, top, W - 2 * pad, ph);
            g.setColor(Color.WHITE);
            g.drawString(titles[panel], pad + 6, top - 8);
            for (int k = 0; k < 2; k++) {
                Body b = k == 0 ? old : director;
                Color c = k == 0 ? new Color(220, 60, 60) : new Color(40, 140, 255);
                double y0 = b.trace.isEmpty() ? 0 : b.trace.get(0)[4];
                int px = -1, py = -1, hx = -1, hy = -1;
                for (int i = 0; i < b.trace.size(); i++) {
                    double[] p = b.trace.get(i);
                    int x = pad + (int) ((W - 2 * pad) * i / (double) n);
                    double v = panel == 0 ? p[3] : 0.5 + Locomotion.wrap(p[4] - y0) / 180.0;
                    int y = top + ph - (int) (ph * Math.max(0, Math.min(1, v)));
                    if (px >= 0) {
                        g.setColor(c);
                        g.drawLine(px, py, x, y);
                    }
                    px = x;
                    py = y;
                    if (panel == 1 && k == 1) {
                        int y2 = top + ph - (int) (ph * Math.max(0, Math.min(1, 0.5 + Locomotion.wrap(p[5] - y0) / 180.0)));
                        if (hx >= 0) {
                            g.setColor(new Color(255, 200, 0));
                            g.drawLine(hx, hy, x, y2);
                        }
                        hx = x;
                        hy = y2;
                    }
                }
            }
        }
        g.setColor(new Color(255, 200, 0));
        g.drawString("yellow: director head yaw (leads the body, idle glances)", W - 520, H - 12);
        g.dispose();
        ImageIO.write(img, "png", out.toFile());
    }
}
