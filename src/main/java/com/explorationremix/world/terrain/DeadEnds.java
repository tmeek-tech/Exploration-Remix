package com.explorationremix.world.terrain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Finds the blind ends of the caves in a window of blocks, and plans a short tunnel from each to another cave nearby,
 * so the ways through the Upper Caves loop instead of ending (predators will hunt here: a way in should be a way on).
 * <p>
 * It works on a grid of 2-block cells over the window, each cell rock, air, water or outside (open and within a few
 * blocks of the ground, or above it). A blind end is the end of a narrow run of open cells: from a cell on its middle
 * line, the open cells within 12 blocks leave the sphere of 12 blocks round it in one place only (a cell in the middle
 * of a tunnel sees two places, one at a fork three), and fill less than a fifth of it (from a room every way looks the
 * same). The cell farthest out along the run is its tip. The tunnel from a tip goes on the way the cave was going, to
 * the nearest air 6 to 24 blocks away that the caves do not already join to the tip within a short walk, never
 * steeper than a walkable slope.
 * <p>
 * The game fills the grid from the blocks of 3 x 3 chunks and carves the tunnels ({@code JoinDeadEndsFeature}); tests
 * fill it by hand.
 */
public final class DeadEnds {
    /** A cell is 2 x 2 x 2 blocks. */
    public static final int CELL = 2;
    public static final byte ROCK = 0, AIR = 1, WATER = 2, OUTSIDE = 3;
    /** The sphere round a cell: 6 cells (12 blocks). */
    static final int R = 6;
    /** The open cells round a blind end fill less than this share of its sphere. */
    static final double NARROW = 0.2;
    /** Tunnels are 6 to 24 blocks long (3 to 12 cells). */
    static final int MIN_LINK = 3, MAX_LINK = 12;
    /** The caves already join two cells within this many steps (cells) for every cell between them, plus 8. */
    static final double WALK = 2.5;

    private DeadEnds() {}

    /** The cells of a window: {@code (x0, y0, z0)} is the lowest corner of cell (0, 0, 0), in blocks. */
    public static final class Grid {
        public final int x0, y0, z0, nx, ny, nz;
        final byte[] kind;
        final byte[] dist;

        public Grid(int x0, int y0, int z0, int nx, int ny, int nz) {
            this.x0 = x0;
            this.y0 = y0;
            this.z0 = z0;
            this.nx = nx;
            this.ny = ny;
            this.nz = nz;
            kind = new byte[nx * ny * nz];
            dist = new byte[kind.length];
        }

        public int index(int i, int j, int k) { return (j * nz + k) * nx + i; }

        public boolean inside(int i, int j, int k) { return i >= 0 && j >= 0 && k >= 0 && i < nx && j < ny && k < nz; }

        public void set(int i, int j, int k, byte v) { kind[index(i, j, k)] = v; }

        public byte get(int i, int j, int k) { return inside(i, j, k) ? kind[index(i, j, k)] : ROCK; }

        static boolean open(byte v) { return v != ROCK; }

        /** The block at the middle of a cell (its lower corner plus one). */
        public int blockX(int i) { return x0 + i * CELL + 1; }
        public int blockY(int j) { return y0 + j * CELL + 1; }
        public int blockZ(int k) { return z0 + k * CELL + 1; }

        /** Steps (6 ways) from each open cell to the nearest rock, at most 7. */
        void distances() {
            Arrays.fill(dist, (byte) 7);
            int[] queue = new int[kind.length];
            int head = 0, tail = 0;
            for (int n = 0; n < kind.length; n++) if (kind[n] == ROCK) { dist[n] = 0; queue[tail++] = n; }
            while (head < tail) {
                int n = queue[head++];
                int d = dist[n];
                if (d >= 6) continue;
                int i = n % nx, k = (n / nx) % nz, j = n / (nx * nz);
                for (int[] o : SIX) {
                    int a = i + o[0], b = j + o[1], c = k + o[2];
                    if (!inside(a, b, c)) continue;
                    int m = index(a, b, c);
                    if (dist[m] > d + 1) { dist[m] = (byte) (d + 1); queue[tail++] = m; }
                }
            }
        }
    }

    /** A blind end: its tip cell, and the way the cave was going there (a unit vector out of the end). */
    public record Tip(int i, int j, int k, double fx, double fy, double fz) {}

    /** A tunnel to plan: from a tip's cell to an open cell of another cave. */
    public record Link(Tip tip, int i, int j, int k) {
        public double length() { return Math.sqrt(sq(i - tip.i) + sq(j - tip.j) + sq(k - tip.k)) * CELL; }
    }

    static final int[][] SIX = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /**
     * The blind ends whose tips lie in cells {@code i0..i1} by {@code k0..k1} (inclusive): the window's own chunk. The
     * window must reach 6 cells past them on every side, or its edge counts as a way out.
     */
    public static List<Tip> tips(Grid g, int i0, int i1, int k0, int k1) {
        g.distances();
        Ball ball = new Ball(g);
        List<double[]> found = new ArrayList<>(); // {index, offset, fx, fy, fz}
        for (int j = 1; j < g.ny - 1; j++) for (int k = k0; k <= k1; k++) for (int i = i0; i <= i1; i++) {
            int n = g.index(i, j, k);
            if (g.kind[n] != AIR || !medial(g, i, j, k)) continue;
            if (!ball.measure(i, j, k)) continue;
            if (ball.exits != 1 || ball.volume > NARROW * Ball.FULL) continue;
            double ox = i - ball.cx, oy = j - ball.cy, oz = k - ball.cz, off = Math.sqrt(ox * ox + oy * oy + oz * oz);
            if (off < 1.0) continue;
            found.add(new double[]{n, off, ox / off, oy / off, oz / off});
        }
        // Farthest out first; a tip claims the cells of its sphere, so one blind end gives one tip.
        found.sort((a, b) -> Double.compare(b[1], a[1]));
        boolean[] claimed = new boolean[g.kind.length];
        List<Tip> out = new ArrayList<>();
        for (double[] f : found) {
            int n = (int) f[0];
            if (claimed[n]) continue;
            int i = n % g.nx, k = (n / g.nx) % g.nz, j = n / (g.nx * g.nz);
            ball.measure(i, j, k);
            for (int v = 0; v < ball.count; v++) claimed[ball.cells[v]] = true;
            out.add(new Tip(i, j, k, f[2], f[3], f[4]));
        }
        return out;
    }

    /** On the cave's middle line: no open cell round it lies farther from the rock. */
    private static boolean medial(Grid g, int i, int j, int k) {
        int d = g.dist[g.index(i, j, k)];
        for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) for (int c = -1; c <= 1; c++) {
            if ((a | b | c) == 0 || !g.inside(i + a, j + b, k + c)) continue;
            if (g.dist[g.index(i + a, j + b, k + c)] > d) return false;
        }
        return true;
    }

    /** The open cells within the sphere round a cell that a walk from it reaches, and where they leave the sphere. */
    static final class Ball {
        static final double FULL = 4.0 / 3.0 * Math.PI * R * R * R;
        final Grid g;
        final int[] stamp, exitStamp;
        final int[] cells;
        int mark, count, exits;
        double volume, cx, cy, cz;

        Ball(Grid g) {
            this.g = g;
            stamp = new int[g.kind.length];
            exitStamp = new int[g.kind.length];
            cells = new int[(2 * R + 1) * (2 * R + 1) * (2 * R + 1)];
        }

        /** Walks the sphere round a cell; false if it is not open. */
        boolean measure(int i0, int j0, int k0) {
            int start = g.index(i0, j0, k0);
            if (!Grid.open(g.kind[start])) return false;
            mark++;
            count = 0;
            cells[count++] = start;
            stamp[start] = mark;
            double sx = 0, sy = 0, sz = 0;
            int r2 = R * R, shell = (R - 1) * (R - 1);
            List<Integer> rim = new ArrayList<>();
            for (int h = 0; h < count; h++) {
                int n = cells[h];
                int i = n % g.nx, k = (n / g.nx) % g.nz, j = n / (g.nx * g.nz);
                sx += i;
                sy += j;
                sz += k;
                int d2 = sq(i - i0) + sq(j - j0) + sq(k - k0);
                boolean edge = i == 0 || j == 0 || k == 0 || i == g.nx - 1 || j == g.ny - 1 || k == g.nz - 1;
                if (d2 >= shell || g.kind[n] == OUTSIDE || edge) rim.add(n);
                if (g.kind[n] == OUTSIDE || edge) continue; // the way out goes on past it
                for (int[] o : SIX) {
                    int a = i + o[0], b = j + o[1], c = k + o[2];
                    if (!g.inside(a, b, c) || sq(a - i0) + sq(b - j0) + sq(c - k0) > r2) continue;
                    int m = g.index(a, b, c);
                    if (stamp[m] == mark || !Grid.open(g.kind[m])) continue;
                    stamp[m] = mark;
                    cells[count++] = m;
                }
            }
            volume = count;
            cx = sx / count;
            cy = sy / count;
            cz = sz / count;
            // Count the places the walk leaves the sphere: groups of rim cells touching each other (26 ways).
            for (int n : rim) exitStamp[n] = mark;
            exits = 0;
            int[] queue = new int[rim.size()];
            for (int n : rim) {
                if (exitStamp[n] != mark) continue;
                exits++;
                int head = 0, tail = 0;
                queue[tail++] = n;
                exitStamp[n] = -mark;
                while (head < tail) {
                    int m = queue[head++];
                    int i = m % g.nx, k = (m / g.nx) % g.nz, j = m / (g.nx * g.nz);
                    for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) for (int c = -1; c <= 1; c++) {
                        if (!g.inside(i + a, j + b, k + c)) continue;
                        int q = g.index(i + a, j + b, k + c);
                        if (exitStamp[q] == mark) { exitStamp[q] = -mark; queue[tail++] = q; }
                    }
                }
            }
            return true;
        }
    }

    /**
     * The tunnel for a tip: to the best open cell 3 to 12 cells away, not joined to the tip by the caves within
     * {@code WALK} steps a cell of distance (plus 8), no steeper than about 37 degrees; ahead of the tip if it can.
     * Null when there is none.
     */
    public static Link link(Grid g, Tip t) {
        // How far a walk through the caves from the tip is to each cell, up to 40 steps.
        int[] steps = new int[g.kind.length];
        Arrays.fill(steps, Integer.MAX_VALUE);
        int[] queue = new int[g.kind.length];
        int head = 0, tail = 0, s0 = g.index(t.i, t.j, t.k);
        steps[s0] = 0;
        queue[tail++] = s0;
        while (head < tail) {
            int n = queue[head++];
            if (steps[n] >= 40) continue;
            int i = n % g.nx, k = (n / g.nx) % g.nz, j = n / (g.nx * g.nz);
            for (int[] o : SIX) {
                int a = i + o[0], b = j + o[1], c = k + o[2];
                if (!g.inside(a, b, c)) continue;
                int m = g.index(a, b, c);
                if (steps[m] != Integer.MAX_VALUE || !Grid.open(g.kind[m])) continue;
                steps[m] = steps[n] + 1;
                queue[tail++] = m;
            }
        }
        double best = Double.MAX_VALUE;
        int bi = 0, bj = 0, bk = 0;
        for (int dj = -MAX_LINK; dj <= MAX_LINK; dj++) for (int dk = -MAX_LINK; dk <= MAX_LINK; dk++) for (int di = -MAX_LINK; di <= MAX_LINK; di++) {
            int i = t.i + di, j = t.j + dj, k = t.k + dk;
            if (!g.inside(i, j, k)) continue;
            int n = g.index(i, j, k);
            if (g.kind[n] != AIR) continue;
            double e = Math.sqrt(di * di + dj * dj + dk * dk);
            if (e < MIN_LINK || e > MAX_LINK) continue;
            if (steps[n] <= WALK * e + 8) continue;
            double hz = Math.sqrt(di * di + dk * dk);
            if (Math.abs(dj) > 0.75 * hz + 1) continue;
            double ahead = (di * t.fx + dj * t.fy + dk * t.fz) / e;
            double score = e + 1.5 * Math.abs(dj) - 3 * ahead - 0.5 * Math.min(3, g.dist[n]);
            if (score < best) {
                best = score;
                bi = i;
                bj = j;
                bk = k;
            }
        }
        return best == Double.MAX_VALUE ? null : new Link(t, bi, bj, bk);
    }

    /** Marks a planned tunnel's cells open, so the next tips see the cave it joins. */
    public static void open(Grid g, Link l) {
        double len = Math.max(1, Math.sqrt(sq(l.i - l.tip.i) + sq(l.j - l.tip.j) + sq(l.k - l.tip.k)));
        for (double s = 0; s <= len; s += 0.5) {
            double f = s / len;
            int i = (int) Math.round(l.tip.i + (l.i - l.tip.i) * f), j = (int) Math.round(l.tip.j + (l.j - l.tip.j) * f),
                    k = (int) Math.round(l.tip.k + (l.k - l.tip.k) * f);
            if (g.inside(i, j, k) && g.get(i, j, k) == ROCK) g.set(i, j, k, AIR);
        }
    }

    static int sq(int v) { return v * v; }
}
