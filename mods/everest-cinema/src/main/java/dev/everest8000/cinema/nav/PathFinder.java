package dev.everest8000.cinema.nav;

import dev.everest8000.cinema.spec.Vec3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A* over standing positions on the block grid, for walkers that move with real player physics: 8-way moves, one-block
 * step-ups (taken with a jump), drops of at most {@link Options#maxDrop} blocks, ladder climbing, no corner cutting, and
 * extra cost for snow, climbing and cliff edges so routes keep away from drops. Deterministic: ties break by insertion
 * order.
 */
public final class PathFinder {
    /** heuristicWeight above 1 trades optimality for speed (weighted A*). */
    /**
     * heuristicWeight above 1 trades optimality for speed (weighted A*). steepGrade/steepPenalty make walkers traverse
     * steep slopes on a diagonal (switchbacks) instead of going straight up or down the fall line: a move whose grade
     * along its own direction exceeds steepGrade (0.58 = 30 degrees) costs steepPenalty per unit of excess per block.
     */
    public record Options(int maxNodes, int maxDrop, double edgePenalty, double snowPenalty, double climbPenalty, double upPenalty,
                          double heuristicWeight, double steepGrade, double steepPenalty) {
        public static final Options DEFAULT = new Options(150000, 3, 0.8, 0.6, 0.6, 0.6, 1.8, 0.58, 3.0);

        public Options(int maxNodes, int maxDrop, double edgePenalty, double snowPenalty, double climbPenalty, double upPenalty) {
            this(maxNodes, maxDrop, edgePenalty, snowPenalty, climbPenalty, upPenalty, 1.8, 0.58, 3.0);
        }
    }

    public enum Status { FOUND, PARTIAL, NO_START, NO_PATH }

    /** Feet positions (block centres, y = feet level), from start to goal. */
    public record Result(Status status, List<Vec3d> points, int expanded, double cost) {}

    private record Node(int x, int y, int z) {
        long key() {
            return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
        }
    }

    private static final class Open implements Comparable<Open> {
        final Node node;
        final double f;
        final long order;

        Open(Node node, double f, long order) {
            this.node = node;
            this.f = f;
            this.order = order;
        }

        @Override
        public int compareTo(Open o) {
            int c = Double.compare(f, o.f);
            return c != 0 ? c : Long.compare(order, o.order);
        }
    }

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private final BlockQuery q;
    private final Options opt;
    private final Map<Long, Boolean> standCache = new HashMap<>();

    public PathFinder(BlockQuery q, Options opt) {
        this.q = q;
        this.opt = opt;
    }

    // ---- block rules

    private boolean solid(int x, int y, int z) {
        return q.collisionTop(x, y, z) > 0.6;
    }

    private boolean space(int x, int y, int z) {
        return !solid(x, y, z) && !q.hazard(x, y, z);
    }

    /** A walker's feet can rest at (x, y, z): two blocks of room, something to stand on, nothing dangerous. */
    public boolean standable(int x, int y, int z) {
        long k = new Node(x, y, z).key();
        Boolean c = standCache.get(k);
        if (c != null) return c;
        boolean r = q.loaded(x, z) && space(x, y, z) && space(x, y + 1, z) && !q.hazard(x, y - 1, z)
                && (q.collisionTop(x, y - 1, z) > 0 || q.collisionTop(x, y, z) > 0 || q.climbable(x, y, z) || q.climbable(x, y - 1, z));
        standCache.put(k, r);
        return r;
    }

    /** Finds the standable y nearest to y (searching down first, then up) within range, or Integer.MIN_VALUE. */
    public int snapToGround(int x, int y, int z, int range) {
        for (int d = 0; d <= range; d++) {
            if (standable(x, y - d, z)) return y - d;
            if (d > 0 && standable(x, y + d, z)) return y + d;
        }
        return Integer.MIN_VALUE;
    }

    private int dropBelow(int x, int y, int z) {
        int d = 0;
        while (d <= opt.maxDrop + 1 && !solid(x, y - 1 - d, z) && q.collisionTop(x, y - 1 - d, z) <= 0 && !q.climbable(x, y - d, z)) d++;
        return d;
    }

    /** Highest climb a walker manages with a jump (a player jumps ~1.25; keep a margin). */
    static final double MAX_CLIMB = 1.12;

    /**
     * The real height of the surface a walker stands on at node (x, y, z): partial blocks count, e.g. a snow layer as
     * the floor (block below) or one lying at feet level. Grid steps of one block can really be 1.25+ (from a deep snow
     * layer up onto a block topped with more snow), which no jump clears.
     */
    double floorAt(int x, int y, int z) {
        double below = q.collisionTop(x, y - 1, z);
        double feet = q.collisionTop(x, y, z);
        double f = below > 0 ? y - 1 + Math.min(1, below) : y - 1;
        if (feet > 0) f = Math.max(f, y + feet);
        return f;
    }

    private boolean climbable(int x, int y, int z, int nx, int ny, int nz) {
        if (q.climbable(x, y, z) || q.climbable(nx, ny, nz) || q.climbable(nx, ny - 1, nz)) return true;
        return floorAt(nx, ny, nz) - floorAt(x, y, z) <= MAX_CLIMB;
    }

    /** The y a walker ends up at moving from (x,y,z) one block across to (nx,nz), or MIN_VALUE if it can't. */
    private int stepTo(int x, int y, int z, int nx, int nz) {
        if (standable(nx, y, nz)) return climbable(x, y, z, nx, y, nz) ? y : Integer.MIN_VALUE;
        // Step up one block: needs head room above the current position for the jump, and a real climb a jump clears.
        if (standable(nx, y + 1, nz) && space(x, y + 2, z)) {
            return climbable(x, y, z, nx, y + 1, nz) ? y + 1 : Integer.MIN_VALUE;
        }
        for (int d = 1; d <= opt.maxDrop; d++) {
            if (!space(nx, y - d + 1, nz)) break;
            if (standable(nx, y - d, nz)) return y - d;
        }
        return Integer.MIN_VALUE;
    }

    public Result find(Vec3d from, Vec3d to) {
        int sx = floor(from.x()), sz = floor(from.z());
        int sy = snapToGround(sx, floor(from.y() + 0.01), sz, 4);
        if (sy == Integer.MIN_VALUE) return new Result(Status.NO_START, List.of(), 0, 0);
        int gx = floor(to.x()), gz = floor(to.z());
        int gy = snapToGround(gx, floor(to.y() + 0.01), gz, 6);
        boolean goalStandable = gy != Integer.MIN_VALUE;
        if (!goalStandable) gy = floor(to.y());
        Node start = new Node(sx, sy, sz), goal = new Node(gx, gy, gz);

        PriorityQueue<Open> open = new PriorityQueue<>();
        Map<Long, Double> g = new HashMap<>();
        Map<Long, Node> parent = new HashMap<>();
        long order = 0;
        g.put(start.key(), 0.0);
        open.add(new Open(start, h(start, goal), order++));
        Node best = start;
        double bestH = h(start, goal);
        int expanded = 0;
        Node reached = null;
        while (!open.isEmpty() && expanded < opt.maxNodes) {
            Open o = open.poll();
            Node n = o.node;
            double gn = g.get(n.key());
            if (o.f > gn + h(n, goal) + 1e-9) continue; // stale entry
            expanded++;
            if (n.x == goal.x && n.z == goal.z && Math.abs(n.y - goal.y) <= 1) {
                reached = n;
                break;
            }
            double hn = h(n, goal);
            if (hn < bestH) {
                bestH = hn;
                best = n;
            }
            for (Node m : neighbours(n)) {
                double c = gn + moveCost(n, m);
                Double old = g.get(m.key());
                if (old == null || c < old - 1e-9) {
                    g.put(m.key(), c);
                    parent.put(m.key(), n);
                    open.add(new Open(m, c + h(m, goal), order++));
                }
            }
        }
        Node end = reached != null ? reached : best;
        List<Vec3d> pts = new ArrayList<>();
        for (Node n = end; n != null; n = parent.get(n.key())) pts.add(new Vec3d(n.x + 0.5, n.y, n.z + 0.5));
        Collections.reverse(pts);
        Status st = reached != null ? Status.FOUND : (end == start ? Status.NO_PATH : Status.PARTIAL);
        return new Result(st, pts, expanded, g.getOrDefault(end.key(), 0.0));
    }

    private List<Node> neighbours(Node n) {
        List<Node> out = new ArrayList<>(10);
        for (int[] d : DIRS) {
            int nx = n.x + d[0], nz = n.z + d[1];
            int ny = stepTo(n.x, n.y, n.z, nx, nz);
            if (ny == Integer.MIN_VALUE) continue;
            if (d[0] != 0 && d[1] != 0) {
                // No corner cutting: both orthogonal cells must be passable at the higher of the two levels.
                int hy = Math.max(n.y, ny);
                if (!space(n.x + d[0], hy, n.z) || !space(n.x + d[0], hy + 1, n.z)
                        || !space(n.x, hy, n.z + d[1]) || !space(n.x, hy + 1, n.z + d[1])) continue;
                if (ny != n.y) continue; // diagonal steps up or down are awkward with real physics
            }
            out.add(new Node(nx, ny, nz));
        }
        // Ladders: straight up and down the climbable column.
        if (q.climbable(n.x, n.y, n.z) || q.climbable(n.x, n.y + 1, n.z)) {
            if (space(n.x, n.y + 2, n.z) && (q.climbable(n.x, n.y + 1, n.z) || q.climbable(n.x, n.y, n.z))) {
                out.add(new Node(n.x, n.y + 1, n.z));
            }
        }
        if (q.climbable(n.x, n.y - 1, n.z) && space(n.x, n.y - 1, n.z)) out.add(new Node(n.x, n.y - 1, n.z));
        return out;
    }

    private double moveCost(Node a, Node b) {
        double dx = b.x - a.x, dz = b.z - a.z;
        double c = Math.sqrt(dx * dx + dz * dz);
        if (c == 0) c = 1; // vertical (ladder)
        int dy = b.y - a.y;
        boolean climbing = q.climbable(b.x, b.y, b.z) || q.climbable(a.x, a.y, a.z) && dx == 0 && dz == 0;
        if (climbing) c += opt.climbPenalty;
        else if (dy > 0) c += opt.upPenalty * dy;
        else if (dy < 0) c += 0.3 * -dy;
        c += opt.snowPenalty * q.snowDepth(b.x, b.y, b.z);
        if (!climbing && opt.steepPenalty() > 0 && (dx != 0 || dz != 0)) {
            double g = Math.abs(directionalGrade(a, (int) dx, (int) dz));
            if (g > opt.steepGrade()) c += opt.steepPenalty() * (g - opt.steepGrade()) * Math.sqrt(dx * dx + dz * dz);
        }
        if (nearEdge(b)) c += opt.edgePenalty;
        return c;
    }

    private final Map<Long, Integer> surfaceCache = new HashMap<>();

    /** Ground (feet) height near y at (x, z), or y if none is found within 6. */
    private int surface(int x, int y, int z) {
        long k = new Node(x, y, z).key();
        Integer c = surfaceCache.get(k);
        if (c != null) return c;
        int s = snapToGround(x, y, z, 6);
        if (s == Integer.MIN_VALUE) s = y;
        surfaceCache.put(k, s);
        return s;
    }

    /** Terrain rise over run along (dx, dz) around n, measured 2 blocks back and ahead. */
    private double directionalGrade(Node n, int dx, int dz) {
        int ahead = surface(n.x + 2 * dx, n.y, n.z + 2 * dz);
        int behind = surface(n.x - 2 * dx, n.y, n.z - 2 * dz);
        return (ahead - behind) / (4 * Math.sqrt(dx * dx + dz * dz));
    }

    /** A drop deeper than maxDrop right next to this position. */
    private boolean nearEdge(Node n) {
        for (int i = 0; i < 4; i++) {
            int nx = n.x + DIRS[i][0], nz = n.z + DIRS[i][1];
            if (!q.loaded(nx, nz)) continue;
            if (space(nx, n.y, nz) && space(nx, n.y + 1, nz) && dropBelow(nx, n.y, nz) > opt.maxDrop) return true;
        }
        return false;
    }

    private double h(Node a, Node b) {
        double dx = a.x - b.x, dz = a.z - b.z, dy = a.y - b.y;
        return opt.heuristicWeight() * Math.sqrt(dx * dx + dz * dz + 0.5 * dy * dy);
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }

    /** Drops points that lie on a straight line between their neighbours (same height), keeping turns, steps and ladders. */
    public static List<Vec3d> simplify(List<Vec3d> pts) {
        if (pts.size() < 3) return pts;
        List<Vec3d> out = new ArrayList<>();
        out.add(pts.get(0));
        for (int i = 1; i < pts.size() - 1; i++) {
            Vec3d a = out.get(out.size() - 1), b = pts.get(i), c = pts.get(i + 1);
            boolean flat = a.y() == b.y() && b.y() == c.y();
            double cross = (b.x() - a.x()) * (c.z() - b.z()) - (b.z() - a.z()) * (c.x() - b.x());
            if (flat && Math.abs(cross) < 1e-9) continue;
            out.add(b);
        }
        out.add(pts.get(pts.size() - 1));
        return out;
    }
}
