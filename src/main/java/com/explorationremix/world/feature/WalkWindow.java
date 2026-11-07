package com.explorationremix.world.feature;

import com.explorationremix.block.GlowBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;

/**
 * Whether a change of blocks keeps the walks round it. A window round the change ({@link #SIDE} columns past it each
 * way, {@link #ROWS} rows over and under it) is read as a walker meets it ({@link Detour}'s kinds), and its walk graph is
 * built before the change and after it with the walk tests' moves: a player stands on two open blocks over a solid one,
 * and steps to a neighbouring column on the level, one block up where the column he leaves is open a block over his
 * head, or off an edge down to the first standing place at most {@link #MAX_DROP} under him; never onto or through
 * anything that is neither open nor solid (lava). A walk comes into the window through its outermost columns, from
 * over it (a drop lands up to {@link #MAX_DROP} rows under its top) or from under it, and leaves it through its outermost
 * columns, a few rows from its top or off its bottom. Of the places that are still places after the change and that a
 * walk still comes to, the change keeps the walks when
 * <ul>
 *   <li>every one that walked out of the window before still does: no place becomes a pit that a player steps or drops
 *   into and cannot leave; and</li>
 *   <li>those that were joined both ways before (one strongly connected component of the window's graph) and walk out
 *   still are: no passage is cut or left one way.</li>
 * </ul>
 * The places the change takes are let go, and so are those no walk comes to any more (a nook the change closes off is
 * as good as taken: nobody gets there). A way round further out than the window is not looked for, so the rule may
 * refuse a change that a longer way round would allow, never the other way about within the window.
 *
 * <p>A pure rule over a block query, like {@link Detour}: the decorations ask it on the level (the Wasteland's lava pools,
 * {@link TerracePoolsFeature}, and lava falls, {@link FluidFallFeature}), the tests on their own blocks.
 */
public final class WalkWindow {
    /** Columns past the change, each way, that the window takes in. */
    public static final int SIDE = 10;
    /** Rows over and under the change that the window takes in: more than a drop, so its pits show. */
    public static final int ROWS = 20;
    /** The longest drop a move takes (blocks fallen), as the walk tests'. */
    public static final int MAX_DROP = 15;

    private static final int[] DX = {1, -1, 0, 0}, DZ = {0, 0, 1, -1};

    private WalkWindow() {}

    /** A change of blocks: where, and what each becomes to a walker ({@link Detour#SOLID}, {@link Detour#OPEN} or {@link Detour#OTHER}). */
    public static final class Change {
        private int n;
        private int[] x = new int[32], y = new int[32], z = new int[32], kind = new int[32];

        public Change add(int bx, int by, int bz, int k) {
            if (n == x.length) {
                x = Arrays.copyOf(x, n * 2);
                y = Arrays.copyOf(y, n * 2);
                z = Arrays.copyOf(z, n * 2);
                kind = Arrays.copyOf(kind, n * 2);
            }
            x[n] = bx;
            y[n] = by;
            z[n] = bz;
            kind[n] = k;
            n++;
            return this;
        }

        public int size() { return n; }

        /** A copy, to add to without changing this one. */
        public Change copy() {
            Change c = new Change();
            c.n = n;
            c.x = x.clone();
            c.y = y.clone();
            c.z = z.clone();
            c.kind = kind.clone();
            return c;
        }
    }

    /** The blocks b gives with the change made (a later entry for a block over an earlier one). */
    public static Detour.Blocks with(Detour.Blocks b, Change c) {
        if (c.n == 0) return b;
        java.util.HashMap<Long, Integer> made = new java.util.HashMap<>();
        for (int i = 0; i < c.n; i++) made.put(BlockPos.asLong(c.x[i], c.y[i], c.z[i]), c.kind[i]);
        return (x, y, z) -> {
            Integer k = made.get(BlockPos.asLong(x, y, z));
            return k != null ? k : b.at(x, y, z);
        };
    }

    /** The level's blocks as a walker meets them ({@link #kind}). */
    public static Detour.Blocks of(BlockGetter level) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        return (x, y, z) -> kind(level, p.set(x, y, z));
    }

    /**
     * A block as a walker meets it: open where nothing stops a player (air, the glow, and what has no collision and no
     * fluid: lichen, a plant), solid where it is solid without fluid, anything else (fluids, shards) neither.
     */
    public static int kind(BlockGetter level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        if (GlowBlock.open(s)) return Detour.OPEN;
        if (!s.getFluidState().isEmpty()) return Detour.OTHER;
        if (s.isSolid()) return Detour.SOLID;
        return s.getCollisionShape(level, pos).isEmpty() ? Detour.OPEN : Detour.OTHER;
    }

    /** Whether the change keeps the walks round it (see the class comment). */
    public static boolean keeps(Detour.Blocks b, Change c) {
        if (c.n == 0) return true;
        int ax = Integer.MAX_VALUE, ay = Integer.MAX_VALUE, az = Integer.MAX_VALUE, bx = Integer.MIN_VALUE, by = Integer.MIN_VALUE, bz = Integer.MIN_VALUE;
        for (int i = 0; i < c.n; i++) {
            ax = Math.min(ax, c.x[i]);
            ay = Math.min(ay, c.y[i]);
            az = Math.min(az, c.z[i]);
            bx = Math.max(bx, c.x[i]);
            by = Math.max(by, c.y[i]);
            bz = Math.max(bz, c.z[i]);
        }
        int x0 = ax - SIDE, y0 = ay - ROWS, z0 = az - SIDE;
        int nx = bx - ax + 1 + 2 * SIDE, ny = by - ay + 1 + 2 * ROWS, nz = bz - az + 1 + 2 * SIDE;
        byte[] before = new byte[nx * ny * nz];
        for (int i = 0; i < nx; i++) for (int k = 0; k < nz; k++) {
            int base = (i * nz + k) * ny;
            for (int j = 0; j < ny; j++) before[base + j] = (byte) b.at(x0 + i, y0 + j, z0 + k);
        }
        byte[] after = before.clone();
        for (int i = 0; i < c.n; i++) after[((c.x[i] - x0) * nz + (c.z[i] - z0)) * ny + (c.y[i] - y0)] = (byte) c.kind[i];
        Graph g0 = new Graph(before, nx, ny, nz), g1 = new Graph(after, nx, ny, nz);
        boolean[] out0 = g0.walksOut(), out1 = g1.walksOut(), in1 = g1.walkedInto();
        int[] to = new int[g0.comps];
        Arrays.fill(to, -1);
        for (int p = 0; p < g0.n; p++) {
            int q = g1.id[g0.pos[p]];
            if (q < 0 || !in1[q]) continue;                         // taken, or closed off: let go
            if (!out1[q]) {
                if (out0[p]) return false;                          // a new pit
                continue;
            }
            int c0 = g0.comp[p], c1 = g1.comp[q];
            if (to[c0] < 0) to[c0] = c1;
            else if (to[c0] != c1) return false;                    // joined both ways before, not now: cut or one way
        }
        return true;
    }

    /** A window's walk graph: places, moves (4 a place, -1 for none), the places that walk out, the strongly connected components. */
    private static final class Graph {
        final byte[] m;
        final int nx, ny, nz;
        /** Block index ((i * nz + k) * ny + j, j the feet row) to place, or -1. */
        final int[] id;
        int n, comps;
        int[] pos, next, comp;
        /** The moves reversed: radj[rstart[u]..rstart[u + 1]) are the places that step to u. */
        int[] radj, rstart;
        /** The places a walk leaves the window from, and those it comes into the window to. */
        boolean[] exit, entry;

        Graph(byte[] m, int nx, int ny, int nz) {
            this.m = m;
            this.nx = nx;
            this.ny = ny;
            this.nz = nz;
            id = new int[m.length];
            Arrays.fill(id, -1);
            pos = new int[256];
            for (int i = 0; i < nx; i++) for (int k = 0; k < nz; k++) for (int j = 1; j + 1 < ny; j++) {
                if (!stand(i, j, k)) continue;
                if (n == pos.length) pos = Arrays.copyOf(pos, n * 2);
                int l = (i * nz + k) * ny + j;
                id[l] = n;
                pos[n++] = l;
            }
            next = new int[n * 4];
            exit = new boolean[n];
            entry = new boolean[n];
            for (int p = 0; p < n; p++) {
                int l = pos[p], j = l % ny, k = l / ny % nz, i = l / ny / nz;
                boolean rim = i == 0 || k == 0 || i == nx - 1 || k == nz - 1;
                // out: the outermost columns, the top row's (a step up from there leaves the window), off the bottom (move)
                exit[p] = rim || j >= ny - 2;
                // in: the outermost columns, a step up from under the window, a drop from over it down an open column
                entry[p] = rim || j <= 1 || j >= ny - MAX_DROP && openAbove(i, j, k);
                for (int d = 0; d < 4; d++) next[p * 4 + d] = move(p, i, j, k, d);
            }
            reverse();
            comp = components();
        }

        int at(int i, int j, int k) {
            if (j < 0 || j >= ny) return Detour.SOLID;
            return m[(i * nz + k) * ny + j];
        }

        boolean open(int i, int j, int k) { return at(i, j, k) == Detour.OPEN; }

        /** Whether column (i, k) is open from row j up through the window's top. */
        boolean openAbove(int i, int j, int k) {
            for (int jj = j; jj < ny; jj++) if (!open(i, jj, k)) return false;
            return true;
        }

        boolean stand(int i, int j, int k) { return open(i, j, k) && open(i, j + 1, k) && at(i, j - 1, k) == Detour.SOLID; }

        /** Where a step from place p toward d lands (NoiseVox.move's rule), or -1; a fall on past the window's bottom walks out. */
        int move(int p, int i, int j, int k, int d) {
            int qi = i + DX[d], qk = k + DZ[d];
            if (qi < 0 || qk < 0 || qi >= nx || qk >= nz) return -1;
            if (j + 2 < ny && stand(qi, j + 1, qk) && open(i, j + 2, k)) return id[(qi * nz + qk) * ny + j + 1];
            if (!open(qi, j, qk) || !open(qi, j + 1, qk)) return -1;
            for (int jj = j; jj >= j - MAX_DROP; jj--) {
                if (!open(qi, jj, qk)) return -1;
                if (jj == 0) { exit[p] = true; return -1; }
                if (at(qi, jj - 1, qk) == Detour.SOLID) return id[(qi * nz + qk) * ny + jj];
            }
            return -1;
        }

        /** Strongly connected components (Kosaraju, iterative); sets comps. */
        int[] components() {
            int[] order = new int[n], stack = new int[n], it = new int[n];
            boolean[] seen = new boolean[n];
            int on = 0;
            for (int s = 0; s < n; s++) {
                if (seen[s]) continue;
                int sp = 0;
                stack[sp] = s;
                it[sp++] = 0;
                seen[s] = true;
                while (sp > 0) {
                    int u = stack[sp - 1];
                    if (it[sp - 1] < 4) {
                        int w = next[u * 4 + it[sp - 1]++];
                        if (w >= 0 && !seen[w]) { seen[w] = true; stack[sp] = w; it[sp++] = 0; }
                    } else { order[on++] = u; sp--; }
                }
            }
            int[] rs = rstart;
            int[] c = new int[n];
            Arrays.fill(c, -1);
            for (int oi = n - 1; oi >= 0; oi--) {
                int s = order[oi];
                if (c[s] >= 0) continue;
                int sp = 0;
                stack[sp++] = s;
                c[s] = comps;
                while (sp > 0) {
                    int u = stack[--sp];
                    for (int e = rs[u]; e < rs[u + 1]; e++) if (c[radj[e]] < 0) { c[radj[e]] = comps; stack[sp++] = radj[e]; }
                }
                comps++;
            }
            return c;
        }

        void reverse() {
            rstart = new int[n + 1];
            for (int e : next) if (e >= 0) rstart[e + 1]++;
            for (int i = 0; i < n; i++) rstart[i + 1] += rstart[i];
            radj = new int[rstart[n]];
            int[] fill = Arrays.copyOf(rstart, n);
            for (int u = 0; u < n; u++) for (int d = 0; d < 4; d++) {
                int w = next[u * 4 + d];
                if (w >= 0) radj[fill[w]++] = u;
            }
        }

        /** The places a walk comes to: on from where walks come into the window. */
        boolean[] walkedInto() {
            boolean[] in = new boolean[n];
            int[] q = new int[n];
            int h = 0, t = 0;
            for (int p = 0; p < n; p++) if (entry[p]) { in[p] = true; q[t++] = p; }
            while (h < t) {
                int u = q[h++];
                for (int d = 0; d < 4; d++) {
                    int w = next[u * 4 + d];
                    if (w >= 0 && !in[w]) { in[w] = true; q[t++] = w; }
                }
            }
            return in;
        }

        /** The places that walk out of the window: back from where walks leave it, over the reversed moves. */
        boolean[] walksOut() {
            int[] rs = rstart;
            boolean[] out = new boolean[n];
            int[] q = new int[n];
            int h = 0, t = 0;
            for (int p = 0; p < n; p++) if (exit[p]) { out[p] = true; q[t++] = p; }
            while (h < t) {
                int u = q[h++];
                for (int e = rs[u]; e < rs[u + 1]; e++) if (!out[radj[e]]) { out[radj[e]] = true; q[t++] = radj[e]; }
            }
            return out;
        }
    }
}
