package com.explorationremix.world.terrain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.explorationremix.world.terrain.GardensPlan.SY;
import static com.explorationremix.world.terrain.GardensVents.*;

/**
 * The Deep Gardens' big hollow vents over a window of rock (the previews' make3.stage_s): the door rounds drop the
 * vents whose cracks open onto nothing; then each stamp round writes the vents into the rock, sheds rubble round the
 * broken ones, settles rubble round the new walls (never up onto them), clears what floats, and runs each vent's own
 * local tests (vcheck: a two-way walk into its den, no climb onto its walls or rim, no ledge over a live mouth). A vent
 * that fails is dropped and the round runs again.
 */
final class GardensVentField {
    final GardensPlan plan;
    final GardensVents gv;
    final Bands bands;
    final CeilBelow cl;
    final GardensRock.Gates gates;
    final int x0, z0, nx, nz;
    /** The rock under the vents over the window, in {@link GardensRock}'s layout. */
    final boolean[] solid, talus;

    /** The last stamp round's vents, doors and blocks. */
    List<Vent> vents;
    int[][] doorOk;
    final Grid grid;

    // what each stage did, for the checks
    int doorRounds, checkRounds, rubble, talusAfter, looseAll, looseVent;
    final Map<Integer, Integer> droppedByDoor = new TreeMap<>();
    final Map<String, Integer> droppedByChecks = new TreeMap<>();
    final Map<Integer, List<String>> failsLeft = new TreeMap<>();
    List<GardensSites.Site> kept;
    /** Per step wall time (ms, summed over rounds), when {@link GardensDress#TIMING} is on. */
    final Map<String, Long> times = new java.util.LinkedHashMap<>();
    private long t0;

    private void lap(String name) {
        if (!GardensDress.TIMING) return;
        long t = System.nanoTime();
        if (t0 != 0) times.merge(name, (t - t0) / 1_000_000, Long::sum);
        t0 = t;
    }

    GardensVentField(GardensPlan plan, GardensVents gv, Bands bands, CeilBelow cl, GardensRock.Gates gates, int x0, int z0,
                     int nx, int nz, boolean[] solid, boolean[] talus) {
        this.plan = plan;
        this.gv = gv;
        this.bands = bands;
        this.cl = cl;
        this.gates = gates;
        this.x0 = x0;
        this.z0 = z0;
        this.nx = nx;
        this.nz = nz;
        this.solid = solid;
        this.talus = talus;
        grid = new Grid(x0, z0, nx, nz);
    }

    /** make3.stage_s: the door rounds, then the stamp rounds with each vent's local tests. */
    void run(List<GardensSites.Site> sites) {
        List<GardensSites.Site> allv = new ArrayList<>();
        for (GardensSites.Site s : sites) allv.add(s.copy());
        lap("start");
        allv = doorRounds(allv);
        lap("door rounds");
        Map<Integer, List<String>> fails = Map.of();
        for (int round = 0; round < 4; round++) {
            stampRound(allv);
            lap("stamp round");
            fails = check();
            lap("checks");
            checkRounds = round + 1;
            List<GardensSites.Site> next = new ArrayList<>();
            boolean any = false;
            for (int i = 0; i < allv.size(); i++) {
                List<String> f = fails.get(i);
                if (f != null && !f.isEmpty() && allv.get(i).form != 3) {
                    any = true;
                    droppedByChecks.merge(String.join("+", f) + " (form " + allv.get(i).form + ")", 1, Integer::sum);
                } else next.add(allv.get(i));
            }
            if (!any) break;
            allv = next;
        }
        for (Map.Entry<Integer, List<String>> e : fails.entrySet()) if (!e.getValue().isEmpty()) failsLeft.put(e.getKey(), e.getValue());
        kept = allv;
    }

    /** The door test, round by round (at most 5): a vent with no open crack is dropped, shut cracks close. */
    List<GardensSites.Site> doorRounds(List<GardensSites.Site> allv) {
        int rounds = 0;
        while (true) {
            rounds++;
            List<Vent> vt = gv.table(allv, bands);
            int[][] res = gv.doorTest(vt, solid, talus, grid, cl, DOOR_OPEN);
            List<GardensSites.Site> keep = new ArrayList<>();
            boolean changed = false;
            for (int i = 0; i < allv.size(); i++) {
                GardensSites.Site v = allv.get(i);
                boolean any = res[i][0] == 1 || res[i][1] == 1 || res[i][2] == 1;
                if (!any) {
                    changed = true;
                    droppedByDoor.merge(v.form, 1, Integer::sum);
                    continue;
                }
                for (int j = 0; j < v.ncr; j++) {
                    double c = v.cracks[j];
                    double nc = j < 3 && res[i][j] == 1 ? c : Double.NaN;
                    if (Double.isNaN(nc) != Double.isNaN(c)) changed = true;
                    v.cracks[j] = nc;
                }
                keep.add(v);
            }
            allv = keep;
            if (!changed || rounds >= 5) break;
        }
        doorRounds = rounds;
        return allv;
    }

    /** make3._stamp_round: table, throats (the stand-in exits), stamp, rubble, settling round the walls, nothing floats. */
    void stampRound(List<GardensSites.Site> allv) {
        vents = gv.table(allv, bands);
        int n = vents.size();
        doorOk = new int[n][3];
        for (Vent v : vents) for (int i = 0; i < 3; i++) doorOk[v.idx][i] = Float.isNaN(v.vc[i]) ? -1 : 1;
        for (Vent v : vents) {
            if (v.form() != 3) continue;
            v.t[37] = v.site.gStand;
            double lv = Double.isNaN(v.site.throatLevel) ? 0.5 * Math.PI * (v.site.R - 3.0 + 3.8) : v.site.throatLevel;
            v.tab = throatTable(gv.throatPerm(v.site), v.t[6], v.site.gStand, v.t[36], DS, lv, v.t[35], v.t[34]);
            v.t[39] = v.tab.length;
        }
        byte[] mat = grid.mat, vpart = grid.vpart;
        int[] vid = grid.vid;
        int N = mat.length;
        for (int b = 0; b < N; b++) {
            mat[b] = solid[b] ? (talus[b] ? TALUS : ROCK) : AIR;
            vpart[b] = 0;
            vid[b] = 0;
        }
        lap("stamp: table+init");
        gv.stamp(grid, vents, doorOk, cl, x0, z0, x0 + nx, z0 + nz);
        lap("stamp: stamp");
        rubble = gv.rubble(grid, vents, doorOk);
        lap("stamp: rubble");
        boolean[] doors = gv.doorMask(grid, vents, doorOk);
        lap("stamp: doorMask");
        // the settling's region: the vents' bound discs
        boolean[] reg2 = new boolean[nx * nz];
        for (Vent v : vents) {
            double bb = plan.patch ? (double) (v.t[33] * v.t[33]) : v.bound() * v.bound();
            for (int i = 0; i < nx; i++) {
                double dx = (x0 + i - gv.ox) - v.cx;
                if (dx * dx > bb) continue;
                for (int j = 0; j < nz; j++) {
                    double dz = (z0 + j - gv.oz) - v.cz;
                    if (dx * dx + dz * dz <= bb) reg2[i * nz + j] = true;
                }
            }
        }
        // kept from rubble: the vents' own parts (not skirts), their doors, round the gate landings, and a moat of two
        // blocks round the walls above the foot, so rubble from a ledge beside a vent never ramps up onto it
        boolean[] keep = new boolean[N];
        for (int b = 0; b < N; b++) keep[b] = (vpart[b] != 0 && vpart[b] != VP_SKIRT) || doors[b];
        for (int i = 0; i < nx; i++)
            for (int j = 0; j < nz; j++) {
                if (!gates.near(x0 + i, z0 + j)) continue;
                int base = (i * nz + j) * SY;
                for (int y = 0; y < SY; y++) if (gates.keep(x0 + i, y, z0 + j)) keep[base + y] = true;
            }
        int[] ysCol = new int[nx * nz];
        java.util.Arrays.fill(ysCol, 9999);
        for (Vent v : vents) {
            int b = (int) v.t[32] + 4;
            int icx = (plan.patch ? (int) v.t[4] : (int) Math.floor(v.cx)) + gv.ox, icz = (plan.patch ? (int) v.t[5] : (int) Math.floor(v.cz)) + gv.oz;
            int ia = Math.max(0, icx - b - x0), ib = Math.min(nx, icx + b + 1 - x0);
            int ja = Math.max(0, icz - b - z0), jb = Math.min(nz, icz + b + 1 - z0);
            for (int i = ia; i < ib; i++)
                for (int j = ja; j < jb; j++) ysCol[i * nz + j] = Math.min(ysCol[i * nz + j], (int) v.t[6] + 2);
        }
        for (int i = 0; i < nx; i++)
            for (int j = 0; j < nz; j++) {
                int base = (i * nz + j) * SY;
                for (int y = ysCol[i * nz + j] + 1; y < SY; y++) {
                    int b = base + y;
                    if (isWallPart(vpart[b])) continue;
                    boolean m = false;
                    for (int di = -2; di <= 2 && !m; di++)
                        for (int dj = -2; dj <= 2 && !m; dj++) {
                            if (di * di + dj * dj > 4) continue;
                            int ii = i + di, jj = j + dj;
                            if (ii < 0 || jj < 0 || ii >= nx || jj >= nz) continue;
                            if (isWallPart(vpart[(ii * nz + jj) * SY + y])) m = true;
                        }
                    if (m) keep[b] = true;
                }
            }
        lap("stamp: keep+moat");
        boolean[] sol2 = new boolean[N], nosrc = new boolean[N];
        for (int b = 0; b < N; b++) {
            sol2[b] = mat[b] != AIR && mat[b] != OIL;
            nosrc[b] = vpart[b] != 0 && vpart[b] != VP_SKIRT;
            if (!reg2[b / SY]) keep[b] = true;
        }
        boolean[] before = sol2.clone();
        GardensRock.talusRounds(sol2, keep, nosrc, 12, 3, 80, nx, nz);
        talusAfter = 0;
        for (int b = 0; b < N; b++)
            if (sol2[b] && !before[b]) {
                talusAfter++;
                if (mat[b] == AIR) mat[b] = TALUS;
            }
        lap("stamp: talus");
        byte[] was = mat.clone();
        looseAll = keepRuns(mat, nx, nz, !plan.patch);
        lap("stamp: keepMain");
        looseVent = 0;
        for (int b = 0; b < N; b++)
            if (was[b] != mat[b]) {
                if (vpart[b] != 0) looseVent++;
                vpart[b] = 0;
                vid[b] = 0;
            }
    }

    static boolean isWallPart(byte p) { return p == VP_BODY || p == VP_HOOD || p == VP_RIM; }

    /**
     * dress2._keep_main: solid bits not joined face to face to the window's largest piece of rock turn back to air.
     * Returns how many blocks.
     */
    static int keepMain(byte[] mat, int nx, int nz) { return keepRuns(mat, nx, nz, false); }

    /**
     * Keep-main on each column's vertical runs of solid (not air or oil): runs in neighbouring columns that share a row
     * are joined, which is the blocks' face-to-face joining. With {@code touching} false the largest piece stays (the
     * preview's rule over its patch); with it true every piece that reaches the window's sides, top or bottom row stays
     * (the game's rule over a tile's window: such a piece is taken to join the rock beyond). Returns blocks cleared.
     */
    static int keepRuns(byte[] mat, int nx, int nz, boolean touching) {
        int nc = nx * nz;
        int[] start = new int[nc + 1];
        int cap = Math.max(16, nc * 4);
        int[] y0 = new int[cap], y1 = new int[cap];
        int r = 0;
        for (int q = 0; q < nc; q++) {
            start[q] = r;
            int base = q * SY;
            int y = 0;
            while (y < SY) {
                byte m = mat[base + y];
                if (m == AIR || m == OIL) {
                    y++;
                    continue;
                }
                int a = y;
                while (y < SY && mat[base + y] != AIR && mat[base + y] != OIL) y++;
                if (r == y0.length) {
                    y0 = java.util.Arrays.copyOf(y0, r * 2);
                    y1 = java.util.Arrays.copyOf(y1, r * 2);
                }
                y0[r] = a;
                y1[r] = y - 1;
                r++;
            }
        }
        start[nc] = r;
        int[] parent = new int[r];
        for (int i = 0; i < r; i++) parent[i] = i;
        for (int i = 0; i < nx; i++)
            for (int j = 0; j < nz; j++) {
                int q = i * nz + j;
                if (j > 0) joinRuns(parent, y0, y1, start[q], start[q + 1], start[q - 1], start[q]);
                if (i > 0) joinRuns(parent, y0, y1, start[q], start[q + 1], start[q - nz], start[q - nz + 1]);
            }
        boolean[] keep = new boolean[r];
        if (touching) {
            for (int i = 0; i < nx; i++)
                for (int j = 0; j < nz; j++) {
                    int q = i * nz + j;
                    boolean side = i == 0 || j == 0 || i == nx - 1 || j == nz - 1;
                    for (int k = start[q]; k < start[q + 1]; k++)
                        if (side || y0[k] == 0 || y1[k] == SY - 1) keep[find(parent, k)] = true;
                }
        } else {
            long[] size = new long[r];
            int best = -1;
            long bestSize = 0;
            for (int k = 0; k < r; k++) size[find(parent, k)] += y1[k] - y0[k] + 1;
            for (int k = 0; k < r; k++)
                if (parent[k] == k && size[k] > bestSize) {
                    bestSize = size[k];
                    best = k;
                }
            if (best >= 0) keep[best] = true;
        }
        int loose = 0;
        for (int q = 0; q < nc; q++)
            for (int k = start[q]; k < start[q + 1]; k++) {
                if (keep[find(parent, k)]) continue;
                int base = q * SY;
                for (int y = y0[k]; y <= y1[k]; y++) mat[base + y] = AIR;
                loose += y1[k] - y0[k] + 1;
            }
        return loose;
    }

    /** Joins the runs [a0, a1) of one column with the runs [b0, b1) of its neighbour where they share a row. */
    private static void joinRuns(int[] parent, int[] y0, int[] y1, int a0, int a1, int b0, int b1) {
        int a = a0, b = b0;
        while (a < a1 && b < b1) {
            if (y0[a] <= y1[b] && y0[b] <= y1[a]) union(parent, a, b);
            if (y1[a] < y1[b]) a++;
            else b++;
        }
    }

    static int find(int[] p, int b) {
        while (p[b] != b) {
            p[b] = p[p[b]];
            b = p[b];
        }
        return b;
    }

    static void union(int[] p, int a, int b) {
        int ra = find(p, a), rb = find(p, b);
        if (ra != rb) p[Math.max(ra, rb)] = Math.min(ra, rb);
    }

    // ================================================================================================ vcheck

    static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /** A box of the blocks for the walk: solid flags and standing spots' ids, laid out {@code (x * BZ + z) * BY + y}. */
    static final class Box {
        final int bx0, by0, bz0, BX, BY, BZ;
        final boolean[] sol;
        final int[] idx;
        int n;
        int[] X, Y, Z;

        Box(int bx0, int by0, int bz0, int BX, int BY, int BZ) {
            this.bx0 = bx0;
            this.by0 = by0;
            this.bz0 = bz0;
            this.BX = BX;
            this.BY = BY;
            this.BZ = BZ;
            sol = new boolean[BX * BY * BZ];
            idx = new int[BX * BY * BZ];
        }

        int at(int x, int y, int z) { return (x * BZ + z) * BY + y; }

        /** walk.standing and walk.index: a spot is a solid block with two blocks of air over it. */
        void index() {
            java.util.Arrays.fill(idx, -1);
            int c = 0;
            for (int x = 0; x < BX; x++)
                for (int z = 0; z < BZ; z++)
                    for (int y = 1; y < BY - 2; y++) {
                        int b = at(x, y, z);
                        if (sol[b - 1] && !sol[b] && !sol[b + 1]) idx[b] = c++;
                    }
            n = c;
            X = new int[n];
            Y = new int[n];
            Z = new int[n];
            for (int x = 0; x < BX; x++)
                for (int z = 0; z < BZ; z++)
                    for (int y = 1; y < BY - 2; y++) {
                        int i = idx[at(x, y, z)];
                        if (i >= 0) {
                            X[i] = x;
                            Y[i] = y;
                            Z[i] = z;
                        }
                    }
        }

        /** walk.neighbours: the moves from a spot (flat, a step up, or drops of at most maxdrop); returns how many. */
        int neighbours(int x, int y, int z, int maxdrop, int[] out) {
            int m = 0;
            for (int[] d : DIRS) {
                int dx = d[0], dz = d[1], nx = x + dx, nz = z + dz;
                if (nx < 0 || nz < 0 || nx >= BX || nz >= BZ) continue;
                boolean diag = dx != 0 && dz != 0;
                if (y + 1 < BY && !sol[at(nx, y, nz)] && !sol[at(nx, y + 1, nz)]) {
                    if (diag && (sol[at(x + dx, y, z)] || sol[at(x + dx, y + 1, z)] || sol[at(x, y, z + dz)] || sol[at(x, y + 1, z + dz)]))
                        continue;
                    int ny = y;
                    while (ny >= 1 && !sol[at(nx, ny - 1, nz)]) {
                        ny--;
                        if (y - ny > maxdrop) break;
                    }
                    if (ny < 1 || y - ny > maxdrop) continue;
                    int j = idx[at(nx, ny, nz)];
                    if (j < 0) continue;
                    out[m++] = j;
                } else if (y + 2 < BY && sol[at(nx, y, nz)] && !sol[at(nx, y + 1, nz)] && !sol[at(nx, y + 2, nz)] && !sol[at(x, y + 2, z)]) {
                    if (diag && (sol[at(x + dx, y + 1, z)] || sol[at(x + dx, y + 2, z)] || sol[at(x, y + 1, z + dz)] || sol[at(x, y + 2, z + dz)]))
                        continue;
                    int j = idx[at(nx, y + 1, nz)];
                    if (j < 0) continue;
                    out[m++] = j;
                }
            }
            return m;
        }

        /** vcheck._bfs: the spots reached from the starts (within {@code limit} moves when it is not negative). */
        boolean[] bfs(int[] starts, int ns, int maxdrop, int limit, boolean[] goal, boolean[] hit) {
            boolean[] seen = new boolean[n];
            int[] q = new int[n], dq = new int[n];
            int qh = 0, qt = 0;
            for (int i = 0; i < ns; i++) {
                int s = starts[i];
                if (!seen[s]) {
                    seen[s] = true;
                    q[qt] = s;
                    dq[qt++] = 0;
                }
            }
            int[] nb = new int[16];
            while (qh < qt) {
                int u = q[qh], d = dq[qh++];
                if (goal != null && goal[u]) {
                    hit[0] = true;
                    return seen;
                }
                if (limit >= 0 && d >= limit) continue;
                int m = neighbours(X[u], Y[u], Z[u], maxdrop, nb);
                for (int k = 0; k < m; k++) {
                    int j = nb[k];
                    if (!seen[j]) {
                        seen[j] = true;
                        q[qt] = j;
                        dq[qt++] = d + 1;
                    }
                }
            }
            return seen;
        }
    }

    /** vcheck.check: each stamped vent's own local tests, inside its bound. Returns the failures by vent. */
    Map<Integer, List<String>> check() {
        Map<Integer, List<String>> out = new TreeMap<>();
        byte[] mat = grid.mat, vpart = grid.vpart;
        int[] vid = grid.vid;
        Geom g = new Geom();
        for (Vent v : vents) {
            int vi = v.idx;
            float[] T = v.t;
            int ys = (int) T[6];
            int r = (int) (T[33] + 4);
            int icx = (plan.patch ? (int) T[4] : (int) Math.floor(v.cx)) + gv.ox, icz = (plan.patch ? (int) T[5] : (int) Math.floor(v.cz)) + gv.oz;
            int bx0 = icx - r, bx1 = icx + r + 1, bz0 = icz - r, bz1 = icz + r + 1;
            if (plan.patch) {
                bx0 = Math.max(plan.px0 + 1, bx0);
                bx1 = Math.min(plan.px0 + plan.psx - 1, bx1);
                bz0 = Math.max(plan.pz0 + 1, bz0);
                bz1 = Math.min(plan.pz0 + plan.psz - 1, bz1);
            }
            bx0 = Math.max(bx0, x0);
            bx1 = Math.min(bx1, x0 + nx);
            bz0 = Math.max(bz0, z0);
            bz1 = Math.min(bz1, z0 + nz);
            int by0 = Math.max(1, (int) T[7] - 8), by1 = Math.min(SY - 1, (int) (ys + T[8] + 12));
            Box box = new Box(bx0, by0, bz0, bx1 - bx0, by1 - by0, bz1 - bz0);
            for (int x = 0; x < box.BX; x++)
                for (int z = 0; z < box.BZ; z++) {
                    int base = grid.at(bx0 + x, 0, bz0 + z);
                    for (int y = 0; y < box.BY; y++) {
                        byte m = mat[base + by0 + y];
                        box.sol[box.at(x, y, z)] = m != AIR && m != OIL;
                    }
                }
            box.index();
            int n = box.n;
            List<String> fails = new ArrayList<>();
            if (n == 0) {
                fails.add("no ground");
                out.put(vi, fails);
                continue;
            }
            int[] WX = new int[n], WY = new int[n], WZ = new int[n];
            for (int i = 0; i < n; i++) {
                WX[i] = box.X[i] + bx0;
                WY[i] = box.Y[i] + by0;
                WZ[i] = box.Z[i] + bz0;
            }
            // ---- den: a two-way walk in from 3-5 outside some kept crack, within 40 moves
            boolean[] den = new boolean[n];
            boolean anyDen = false;
            for (int i = 0; i < n; i++) {
                int b = grid.at(WX[i], WY[i], WZ[i]);
                den[i] = vpart[b] == VP_DEN && vid[b] == vi + 1;
                anyDen |= den[i];
            }
            if (!anyDen)
                for (int i = 0; i < n; i++) {
                    int b = grid.at(WX[i], WY[i], WZ[i]);
                    den[i] = vpart[b] == VP_FLUE && vid[b] == vi + 1;
                }
            double[] rhoE = new double[n], th = new double[n], rout = new double[n];
            for (int i = 0; i < n; i++) {
                gv.geom(v, WX[i], ys, WZ[i], ys, g);
                rhoE[i] = g.rhoE;
                th[i] = g.th;
                rout[i] = g.rout;
            }
            boolean okDen = false;
            int[] starts = new int[n];
            boolean[] hit = new boolean[1];
            float lim = T[19] + 1.35f;
            for (int c = 0; c < 3 && !okDen; c++) {
                if (doorOk[vi][c] != 1) continue;
                int ns = 0;
                for (int i = 0; i < n; i++)
                    if (Math.abs(WY[i] - ys) <= 2 && rhoE[i] >= rout[i] + 3 && rhoE[i] <= rout[i] + 5
                            && Math.abs(wrap(th[i] - v.vc[c])) * rhoE[i] < lim) starts[ns++] = i;
                if (ns == 0) continue;
                hit[0] = false;
                box.bfs(starts, ns, 1, 40, den, hit);
                if (hit[0]) okDen = true;
            }
            if (!okDen) fails.add("den");
            // ---- climb and mouth: forward reach (drops of at most 3) from the ground round the vent
            int ns = 0;
            for (int i = 0; i < n; i++) {
                int bf = grid.at(WX[i], WY[i] - 1, WZ[i]);
                boolean onWall = isWallPart(vpart[bf]);
                int fy2 = Math.max(WY[i] - 2, 0);
                boolean rub = mat[bf] == TALUS && vpart[bf] == 0 && isWallPart(vpart[grid.at(WX[i], fy2, WZ[i])]);
                if (!onWall && !rub && rhoE[i] > rout[i] + 1.0) starts[ns++] = i;
            }
            boolean[] reach = box.bfs(starts, ns, 3, -1, null, hit);
            float half = ys + 0.5f * T[8];
            boolean climb = false;
            for (int i = 0; i < n && !climb; i++) {
                if (!reach[i]) continue;
                for (int dy = 1; dy <= 2; dy++) {
                    int yy = WY[i] - dy;
                    int b = grid.at(WX[i], yy, WZ[i]);
                    byte p = vpart[b];
                    boolean under = true;
                    if (dy == 2) {
                        int b1 = grid.at(WX[i], WY[i] - 1, WZ[i]);
                        under = mat[b1] == TALUS && vpart[b1] == 0;
                    }
                    boolean mine = vid[b] == vi + 1 && under;
                    if (mine && (p == VP_RIM || ((p == VP_BODY || p == VP_HOOD) && yy > half))) climb = true;
                }
            }
            if (climb) fails.add("climb");
            if (T[16] > 0.5f) {
                int top = -1;
                for (int x = bx0; x < bx1; x++)
                    for (int z = bz0; z < bz1; z++) {
                        int base = grid.at(x, 0, z);
                        for (int y = 0; y < SY; y++) {
                            byte p = vpart[base + y];
                            if ((p == VP_DEN || p == VP_FLUE) && vid[base + y] == vi + 1) top = Math.max(top, y);
                        }
                    }
                if (top >= 0) {
                    List<int[]> ms = new ArrayList<>();
                    for (int x = bx0; x < bx1; x++)
                        for (int z = bz0; z < bz1; z++) {
                            int base = grid.at(x, 0, z);
                            for (int y = top - 1; y <= top; y++) {
                                if (y < 0) continue;
                                byte p = vpart[base + y];
                                if ((p == VP_DEN || p == VP_FLUE) && vid[base + y] == vi + 1) ms.add(new int[]{x, z});
                            }
                        }
                    int mouth = top + 1;
                    boolean near = false;
                    for (int i = 0; i < n && !near; i++) {
                        if (!reach[i] || WY[i] <= mouth - 2 || WY[i] > mouth + 6) continue;
                        for (int[] mz : ms) {
                            long ddx = WX[i] - mz[0], ddz = WZ[i] - mz[1];
                            if (ddx * ddx + ddz * ddz <= 9) {
                                near = true;
                                break;
                            }
                        }
                    }
                    if (near) fails.add("mouth");
                }
            }
            out.put(vi, fails);
        }
        return out;
    }
}
