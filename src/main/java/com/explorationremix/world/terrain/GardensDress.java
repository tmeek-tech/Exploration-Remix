package com.explorationremix.world.terrain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.explorationremix.world.terrain.GardensNoise.hash2;
import static com.explorationremix.world.terrain.GardensNoise.n2;
import static com.explorationremix.world.terrain.GardensNoise.n3;
import static com.explorationremix.world.terrain.GardensNoise.ramp;
import static com.explorationremix.world.terrain.GardensPlan.K;
import static com.explorationremix.world.terrain.GardensPlan.SEAL;
import static com.explorationremix.world.terrain.GardensPlan.SY;
import static com.explorationremix.world.terrain.GardensVents.*;

/**
 * The Deep Gardens' dressing over a window of stamped vents (the previews' dress3): spires by band round the big vents,
 * the scarp (ledges over short drops crumble), glowing tips, the live vents' crowns, fissures and flecks, the deep
 * throats' veins, black dripstone, the glow mats and mushrooms where the light reaches, oil in the floor's dips, dead
 * pits filled with rubble and the faint seep glow along the top band's oil.
 *
 * <p>Each band has its own spire, dripstone, life and colour tables (bottom: Vent Domes, middle: Chimney Halls, top:
 * Seep Beds); a block's band is the dominant style of the highest storey whose floor is at or under it.
 *
 * <p>Blocks are the {@link Grid}'s: {@code (i * nz + j) * SY + y}. In the preview's rules ({@code plan.patch}) the
 * window is the patch and every step matches the preview block for block; in the game the window is a tile with a
 * margin round it (the steps that read neighbours reach at most {@link #REACH} columns).
 */
final class GardensDress {
    static final byte SEEP = 19;

    // per band: bottom (Vent Domes), middle (Chimney Halls), top (Seep Beds)
    static final double[] MAX_H = {14.0, 10.0, 6.0}, THR_IN = {0.16, 0.22, 0.30}, KK = {0.0024, 0.0030, 0.0036},
            MIN_ROOM = {9.0, 9.0, 6.0}, CLEAR_TOP = {0.0, 0.0, 2.0}, DRIP_THR = {0.40, 0.34, 0.30},
            MAT_LO = {0.0096, 0.012, 0.0168}, MUSH_LO = {0.021, 0.030, 0.048}, CAP_R = {5.2, 4.2, 3.0},
            STEM_MAX = {16.0, 12.0, 8.0}, T1 = {-0.0416, -0.2656, -0.3367}, T2 = {0.2761, 0.1935, 0.1333};
    static final int[] TIPC = {MAGENTA, VIOLET, LIGHTBLUE};
    static final double THR_OUT = 0.62, P = 1.55, LIVE_LO = 0.30, FLANGE = 0.06, DRIP_K = 0.010, DRIP_P = 1.35;
    static final int BODY = 1, DRIP_GAP = 4, REACH_XZ = 9, REACH_Y = 5, STEM_MIN = 5;
    /** The seep glow's runs: noise length along the shore, and the bar (about 2 shore blocks in 5 glow). */
    static final double SEEP_L = 6.0, SEEP_KEEP = 0.10;
    /** How far (columns) the dressing's neighbour steps reach: the scarp's 16 rounds, the wells' 10, the heat's box. */
    static final int REACH = 24;

    /** The 2D fields the dressing reads over the window, per storey k and column q = i * nz + j. */
    static final class Fields {
        final float[][] floor = new float[K][], wd = new float[K][], mst = new float[K][], vw = new float[K][],
                pools = new float[K][];
        final int[][] dom = new int[K][], vnear = new int[K][];
        final boolean[][] foot = new boolean[K][];
        float[] hot, mound;
        /** Heat by depth, per row (gen3.g_rows). */
        final float[] grow = new float[SY];

        Fields() {
            for (int y = 0; y < SY; y++) grow[y] = (float) GardensPlan.gOf(y);
        }
    }

    final GardensPlan plan;
    final GardensVents gv;
    final Grid w;
    final List<Vent> vents;
    final int[][] doorOk;
    final Fields f;
    final GardensRock.Gates gates;
    final int x0, z0, nx, nz, sx;
    final byte[] mat, vpart;
    final int[] vid;
    /** The neon colour of every glowing block (MAGENTA, VIOLET, LIME, LIGHTBLUE). */
    final byte[] colour;
    final Map<String, Integer> counts = new LinkedHashMap<>();
    /** Per step wall time (ms), when {@link #TIMING} is on (the offline checks). */
    final Map<String, Long> times = new LinkedHashMap<>();
    static boolean TIMING = false;
    private long t0;

    private void lap(String name) {
        if (!TIMING) return;
        long t = System.nanoTime();
        if (t0 != 0) times.put(name, (t - t0) / 1_000_000);
        t0 = t;
    }

    GardensDress(GardensPlan plan, GardensVents gv, Grid w, List<Vent> vents, int[][] doorOk, Fields f,
                 GardensRock.Gates gates) {
        this.plan = plan;
        this.gv = gv;
        this.w = w;
        this.vents = vents;
        this.doorOk = doorOk;
        this.f = f;
        this.gates = gates;
        x0 = w.x0;
        z0 = w.z0;
        nx = w.nx;
        nz = w.nz;
        sx = nz * SY;
        mat = w.mat;
        vpart = w.vpart;
        vid = w.vid;
        colour = new byte[mat.length];
    }

    /** dress3: every step in order. */
    void run() {
        int N = mat.length;
        lap("start");
        byte[] before = mat.clone();
        counts.put("loose before dressing", keepMain());
        lap("keepMain0");
        counts.put("loose vent blocks before dressing", changedVent(before));
        // porches: the door boxes run on out to rout + 6 and down 5 rows, kept clear of spires and caps and never
        // crumbled by the scarp, so a walkable skirt still climbs to every sill
        boolean[] doors = gv.doorMask(w, vents, doorOk);
        boolean[] porch = gv.porchMask(w, vents, doorOk, 6.0, 5);
        for (int b = 0; b < N; b++) porch[b] |= doors[b];
        // no spire or mushroom stem within 2 of a vent's wall, so none is a step onto a vent; and none in a gate's
        // landing or passage (the game's gates: the ways in and out stay clear, 0.19.1)
        boolean[] keepSp = nearWall();
        boolean[] gk = gateKeep();
        for (int b = 0; b < N; b++) keepSp[b] |= porch[b] || gk[b];
        lap("doors+porch+nearWall");
        short[] down = new short[N], up = new short[N];
        columnDist(down, up);
        int[][] ps = new int[12][];
        for (int i = 0; i < 12; i++) ps[i] = plan.perm(plan.seed + 1000 + i);
        spires(down, up, keepSp, ps[0], ps[1], ps[2], ps[3]);
        lap("spires");
        counts.put("loose spire", keepMain());
        lap("keepMain1");
        boolean[] keep = gateKeep();
        for (int b = 0; b < N; b++) keep[b] |= (vpart[b] != 0 && vpart[b] != VP_SKIRT) || porch[b];
        counts.put("scarp cut", scarp(3, 6, keep, 3, 16));
        lap("scarp");
        before = mat.clone();
        counts.put("loose after scarp", keepMain());
        lap("keepMain2");
        counts.put("loose vent blocks after scarp", changedVent(before));
        for (int b = 0; b < N; b++) if (mat[b] == AIR && vpart[b] == VP_SKIRT) vpart[b] = 0;
        tips();
        lap("tips");
        int[] gc = glow();
        lap("glow");
        counts.put("crown", gc[0]);
        counts.put("fissure", gc[1]);
        counts.put("vent glow", gc[2]);
        counts.put("throat vein", gc[3]);
        columnDist(down, up);
        boolean[] dripKeep = porch.clone();
        for (int b = 0; b < N; b++) dripKeep[b] |= gk[b];
        drip(down, up, dripKeep, ps[5], ps[6]);
        lap("colDist+drip");
        float[] heat = heat();
        lap("heat");
        halo(heat);
        lap("halo");
        mats(heat, ps[7], ps[8]);
        lap("mats");
        columnDist(down, up);
        counts.put("mushrooms", mushrooms(heat, down, up, keepSp, ps[9], ps[8]));
        lap("colDist+mushrooms");
        counts.put("oil", oil());
        lap("oil");
        before = mat.clone();
        boolean[] wk = gateKeep();
        for (int b = 0; b < N; b++) wk[b] |= porch[b];
        counts.put("wells filled", fillWells(wk, 10));
        lap("wells");
        counts.put("loose final", keepMain());
        lap("keepMain3");
        counts.put("loose vent blocks final", changedVent(before));
        counts.put("seep glow", seep(ps[11]));
        lap("seep");
        int[] by = new int[32];
        for (byte m : mat) by[m]++;
        counts.put("dead spire", by[DEAD]);
        counts.put("live spire", by[LIVE]);
        counts.put("tip", by[TIP]);
        counts.put("glow mat", by[MAT]);
        counts.put("dripstone", by[DRIP]);
        counts.put("cap", by[CAP]);
        counts.put("crown blocks", by[CROWN]);
    }

    // ================================================================================================ helpers

    /** Nothing floats: the preview keeps the window's largest piece; the game keeps every piece that reaches its edge. */
    int keepMain() {
        return GardensVentField.keepRuns(mat, nx, nz, !plan.patch);
    }

    int changedVent(byte[] before) {
        int n = 0;
        for (int b = 0; b < mat.length; b++) if (before[b] != mat[b] && vpart[b] != 0) n++;
        return n;
    }

    /** The storey a row belongs to: the highest whose floor is at or under {@code y1} (band_at passes y + 1). */
    int storeyAt(int q, int y1) {
        int k = 0;
        for (int kk = 0; kk < K; kk++) if (f.floor[kk][q] <= y1) k = kk;
        return k;
    }

    static boolean open(byte m) { return m == AIR || m == OIL; }

    /** The integer part of a table centre: numba's int() in the preview's rules, else floor. */
    int ic(double c) { return plan.patch ? (int) c : (int) Math.floor(c); }

    /** common.column_dist over mat != AIR: rows down to the floor (0 on it) and up to the ceiling (0 under it). */
    void columnDist(short[] down, short[] up) {
        Arrays.fill(down, (short) 0);
        Arrays.fill(up, (short) 0);
        for (int q = 0; q < nx * nz; q++) {
            int base = q * SY;
            int c = -1;
            for (int y = 0; y < SY; y++) {
                if (mat[base + y] != AIR) c = -1;
                else down[base + y] = (short) ++c;
            }
            c = -1;
            for (int y = SY - 1; y >= 0; y--) {
                if (mat[base + y] != AIR) c = -1;
                else up[base + y] = (short) ++c;
            }
        }
    }

    /** Within 2 (a 13-cell disc, same row) of a vent's wall, not the wall itself. */
    boolean[] nearWall() {
        boolean[] out = new boolean[mat.length];
        for (int i = 0; i < nx; i++)
            for (int j = 0; j < nz; j++) {
                int base = (i * nz + j) * SY;
                for (int y = 0; y < SY; y++) {
                    if (!GardensVentField.isWallPart(vpart[base + y])) continue;
                    for (int di = -2; di <= 2; di++)
                        for (int dj = -2; dj <= 2; dj++) {
                            if (di * di + dj * dj > 4.5) continue;
                            int ii = i + di, jj = j + dj;
                            if (ii < 0 || jj < 0 || ii >= nx || jj >= nz) continue;
                            out[(ii * nz + jj) * SY + y] = true;
                        }
                }
            }
        for (int b = 0; b < out.length; b++) if (GardensVentField.isWallPart(vpart[b])) out[b] = false;
        return out;
    }

    /** layer.landing_mask(gate, 2.0) over the window: kept round each gate's landing. */
    boolean[] gateKeep() {
        boolean[] m = new boolean[mat.length];
        for (int i = 0; i < nx; i++)
            for (int j = 0; j < nz; j++) {
                if (!gates.near(x0 + i, z0 + j)) continue;
                int base = (i * nz + j) * SY;
                for (int y = 0; y < SY; y++) if (gates.keep(x0 + i, y, z0 + j)) m[base + y] = true;
            }
        return m;
    }

    static long colhash(long x, long z) {
        long h = (x * 73856093L) ^ (z * 19349663L);
        h = (h ^ (h >> 13)) * 1274126177L;
        return (h ^ (h >> 16)) & 0xFFFFFFL;
    }

    // ================================================================================================ spires

    /** dress3._spires: chimney spires by band, ringing the vents (none inside a vent's inner ring). */
    void spires(short[] down, short[] up, boolean[] keepSp, int[] ps, int[] ps2, int[] prm, int[] pk) {
        for (int i = 0; i < nx; i++)
            for (int j = 0; j < nz; j++) {
                int q = i * nz + j, wx = x0 + i, wz = z0 + j, base = q * SY;
                double s = 0.62 * n2(ps, wx / 5.2, wz / 5.2) + 0.38 * n2(ps2, wx / 8.7 + 31.0, wz / 8.7 - 7.0);
                // quick out: the lowest threshold any storey could give this column
                double cmax = 0.0;
                for (int k = 0; k < K; k++) {
                    float wdk = f.wd[k][q];
                    double c = Math.max((double) (wdk * f.mst[k][q]), (1.0 - wdk) * f.mound[q]);
                    c = Math.max(c, 0.9 * f.vw[k][q]);
                    cmax = Math.max(cmax, c);
                }
                if (s < THR_OUT + (0.16 - THR_OUT) * cmax - 0.12) continue;
                double phase = 9.0 * n2(pk, wx / 23.0, wz / 23.0);
                for (int y = SEAL + 1; y < SY - SEAL; y++) {
                    int b = base + y;
                    if (mat[b] != AIR) continue;
                    if (vpart[b] != 0 || keepSp[b]) continue;
                    double d = down[b];
                    int yb = (int) (y - d);
                    int k = storeyAt(q, yb + 1);
                    int bd = f.dom[k][q];
                    if (d > MAX_H[bd]) continue;
                    if (d + up[b] + 1 < MIN_ROOM[bd]) continue;
                    if (up[b] < CLEAR_TOP[bd]) continue;
                    float wv = f.vw[k][q];
                    if (wv >= 1.0) continue;          // inside a vent's inner ring: no spires
                    float wdk = f.wd[k][q];
                    double c = Math.max((double) (wdk * f.mst[k][q]), (1.0 - wdk) * f.mound[q]);
                    c = Math.max(c, 0.9 * wv);
                    double t = THR_OUT + (THR_IN[bd] - THR_OUT) * c;
                    double fl = Math.cos(2.0 * Math.PI * (y + phase) / 7.0);
                    if (fl > 0.0) {
                        double f2 = fl * fl, f4 = f2 * f2, f8 = f4 * f4;
                        t -= FLANGE * (f4 * f8);
                    }
                    t -= 0.10 * Math.exp(-d / 1.6);
                    t += 0.035 * n3(prm, wx / 2.6, y / 2.6, wz / 2.6);
                    if (s - t > KK[bd] * Math.pow(d + 1.0, P)) {
                        double sv = 0.0;
                        int vn = f.vnear[k][q];
                        if (vn >= 0 && wv > 0.0) sv = vents.get(vn).t[16] > 0.5 ? 1.0 : -1.0;
                        boolean live = f.hot[q] + f.grow[Math.max(yb, 0)] + 0.35 * sv * wv > LIVE_LO;
                        mat[b] = live ? LIVE : DEAD;
                    }
                }
            }
    }

    // ================================================================================================ scarp

    /**
     * common.scarp_mat2: a ledge over a drop of 2..dmax (rock) or 2..dmaxT (rubble) crumbles, round by round, unless
     * kept; at least {@code thick} solid rows must stand under it. Returns blocks cut.
     */
    int scarp(int dmax, int dmaxT, boolean[] keep, int thick, int rounds) {
        int tot = 0, dm = Math.max(dmax, dmaxT);
        int[] dirs = {sx, -sx, SY, -SY};
        // a round only looks again at columns beside last round's cuts: elsewhere nothing it reads has changed
        boolean[] dirty = new boolean[nx * nz], next = new boolean[nx * nz];
        Arrays.fill(dirty, true);
        IntList cut = new IntList();
        for (int r = 0; r < rounds; r++) {
            cut.n = 0;
            for (int i = 1; i < nx - 1; i++)
                for (int j = 1; j < nz - 1; j++) {
                    if (!dirty[i * nz + j]) continue;
                    int base = (i * nz + j) * SY;
                    for (int y = SEAL + thick + 1; y < SY - 6; y++) {
                        int b = base + y;
                        byte m = mat[b - 1];
                        if (m != ROCK && m != TALUS) continue;
                        if (!open(mat[b]) || !open(mat[b + 1])) continue;
                        if (keep[b]) continue;
                        boolean ok = true;
                        for (int jj = 2; jj < thick + 2; jj++)
                            if (open(mat[b - jj])) {
                                ok = false;
                                break;
                            }
                        if (!ok) continue;
                        int lim = m == TALUS ? dmaxT : dmax;
                        boolean hit = false;
                        for (int d4 = 0; d4 < 4 && !hit; d4++) {
                            int nb = b + dirs[d4];
                            if (!open(mat[nb]) || !open(mat[nb + 1])) continue;
                            int nbase = nb - y, yy = y - 1;
                            while (yy > y - dm - 2 && open(mat[nbase + yy])) yy--;
                            int drop = y - (yy + 1);
                            if (drop >= 2 && drop <= lim) hit = true;
                        }
                        if (hit) cut.add(b - 1);
                    }
                }
            if (cut.n == 0) break;
            Arrays.fill(next, false);
            for (int c = 0; c < cut.n; c++) {
                int b = cut.a[c];
                mat[b] = AIR;
                markAround(next, b / SY, false);
            }
            tot += cut.n;
            boolean[] t = dirty;
            dirty = next;
            next = t;
        }
        return tot;
    }

    /** Marks column q and its 4 (or 8, with {@code diag}) neighbours. */
    void markAround(boolean[] m, int q, boolean diag) {
        int i = q / nz, j = q % nz;
        for (int di = -1; di <= 1; di++)
            for (int dj = -1; dj <= 1; dj++) {
                if (!diag && di != 0 && dj != 0) continue;
                int ii = i + di, jj = j + dj;
                if (ii >= 0 && jj >= 0 && ii < nx && jj < nz) m[ii * nz + jj] = true;
            }
    }

    /** A growable list of ints. */
    static final class IntList {
        int[] a = new int[256];
        int n;

        void add(int v) {
            if (n == a.length) a = Arrays.copyOf(a, n * 2);
            a[n++] = v;
        }
    }

    // ================================================================================================ tips and glow

    boolean tipCandidate(int b) {
        byte a = mat[b];
        if (a != LIVE && a != THROAT) return false;
        for (int jj = 1; jj < 5; jj++) if (mat[b + jj] != AIR) return false;
        for (int jj = 1; jj <= BODY; jj++) if (mat[b - jj] != LIVE) return false;
        return true;
    }

    /**
     * dress3._tips: a live spire's tip glows (TIP, its band's colour); a live rim spire's tip is a CROWN in its vent's
     * colour. The top of a live column with air over it, no live neighbour going higher, one per flat (a column hash
     * breaks ties). Columns run in order and a lit tip no longer counts as a rival, as in the preview.
     */
    void tips() {
        for (int i = 2; i < nx - 2; i++)
            for (int j = 2; j < nz - 2; j++) {
                int q = i * nz + j, base = q * SY;
                long hx = colhash(x0 + i - gv.ox, z0 + j - gv.oz);
                for (int y = BODY; y < SY - 5; y++) {
                    int b = base + y;
                    if (!tipCandidate(b)) continue;
                    boolean ok = true;
                    for (int di = -2; di <= 2; di++)
                        for (int dk = -2; dk <= 2; dk++) {
                            if (di == 0 && dk == 0) continue;
                            int nb = b + di * sx + dk * SY;
                            byte a = mat[nb];
                            if (a != LIVE && a != THROAT) continue;
                            byte bb = mat[nb + 1];
                            if (bb == LIVE || bb == THROAT || bb == DEAD) ok = false;
                            else if (tipCandidate(nb) && colhash(x0 + i + di - gv.ox, z0 + j + dk - gv.oz) < hx) ok = false;
                        }
                    if (!ok) continue;
                    if (vpart[b] == VP_RIM) {
                        mat[b] = CROWN;
                        colour[b] = (byte) (int) vents.get(vid[b] - 1).t[29];
                    } else {
                        mat[b] = TIP;
                        colour[b] = (byte) TIPC[f.dom[storeyAt(q, y + 1)][q]];
                    }
                }
            }
    }

    /**
     * dress3._glow: live vents glow. CROWN on the outer rim at the top, FISSURE veins in the den floor, VGLOW flecks
     * on the den's and flue's inner faces; deep vents also get TVEIN along their throat's walls (in the layer only:
     * below its floor the throat stays dark). Dead vents give no light. Returns the four counts.
     */
    int[] glow() {
        int[] cnt = new int[4];
        Geom g = new Geom();
        int[] six = {sx, -sx, 1, -1, SY, -SY};
        int xlo = x0 + 1, xhi = x0 + nx - 1, zlo = z0 + 1, zhi = z0 + nz - 1;
        for (Vent v : vents) {
            if (v.t[16] < 0.5f) continue;
            int vv = v.idx + 1;
            byte col = (byte) (int) v.t[29];
            int ys = (int) v.t[6];
            double H = v.t[8];
            int r = (int) v.t[33] + 1;
            double u12 = u(v.k24, 12), u13 = u(v.k24, 13);
            boolean deep = (int) v.t[1] == 3;
            int icx = ic(v.cx) + gv.ox, icz = ic(v.cz) + gv.oz;
            int ya = Math.max(1, ys - 2), yb = Math.min(SY - 1, (int) (ys + H + 4));
            for (int x = Math.max(xlo, icx - r); x < Math.min(xhi, icx + r + 1); x++)
                for (int z = Math.max(zlo, icz - r); z < Math.min(zhi, icz + r + 1); z++) {
                    int base = ((x - x0) * nz + (z - z0)) * SY;
                    for (int y = ya; y < yb; y++) {
                        int b = base + y;
                        if (vid[b] != vv || mat[b] != VLIVE) continue;
                        gv.geom(v, x, y, z, y, g);
                        // crown: the top row of the body, outer side
                        if (y >= ys + (double) v.t[26] - 2.5 && vid[b + 1] != vv && g.rhoE >= g.rin + 0.5) {
                            if (n2(gv.pglow, 1.6 * Math.cos(g.th) + 10.0 * u12, 1.6 * Math.sin(g.th)) > -0.2) {
                                mat[b] = CROWN;
                                colour[b] = col;
                                cnt[0]++;
                                continue;
                            }
                        }
                        // den floor veins
                        if (y == ys - 1 && g.rhoE < g.rin - 0.5) {
                            boolean lit = Math.abs(n2(gv.pglow, x / 5.0 + 10.0 * u13, z / 5.0)) < 0.07;
                            if (deep && Math.abs(wrap(g.th - v.t[35])) * g.rho < 1.0) lit = true;
                            if (lit && (vpart[b + 1] == VP_DEN || mat[b + 1] == AIR)) {
                                mat[b] = FISSURE;
                                colour[b] = col;
                                cnt[1]++;
                                continue;
                            }
                        }
                        // flecks on the inner faces of the den and the flue
                        boolean nb = false;
                        for (int d : six) {
                            int o = b + d;
                            if (vid[o] == vv && (vpart[o] == VP_DEN || vpart[o] == VP_FLUE) && mat[o] == AIR) nb = true;
                        }
                        if (nb && Math.abs(n3(gv.pglow, x / 5.0, y / 7.0, z / 5.0)) < 0.03) {
                            mat[b] = VGLOW;
                            colour[b] = col;
                            cnt[2]++;
                        }
                    }
                }
            if (deep) {
                // veins along the throat's walls
                double Rh = v.t[36];
                int rr = (int) (Rh + 9);
                int yt = Math.min(SY - 1, ys + 8);
                for (int x = Math.max(xlo, icx - rr); x < Math.min(xhi, icx + rr + 1); x++)
                    for (int z = Math.max(zlo, icz - rr); z < Math.min(zhi, icz + rr + 1); z++) {
                        int base = ((x - x0) * nz + (z - z0)) * SY;
                        for (int y = 1; y < yt; y++) {
                            int b = base + y;
                            if (mat[b] != ROCK && mat[b] != SEALROCK) continue;
                            boolean nb = false;
                            for (int d : six) {
                                int o = b + d;
                                if (vpart[o] == VP_TUBE && vid[o] == vv && mat[o] == AIR) nb = true;
                            }
                            if (nb && Math.abs(n3(gv.pglow, x / 3.0, y / 4.0, z / 3.0)) < 0.035) {
                                mat[b] = TVEIN;
                                colour[b] = col;
                                cnt[3]++;
                            }
                        }
                    }
            }
        }
        return cnt;
    }

    // ================================================================================================ dripstone

    /**
     * dress3._drip3: black dripstone hangs from low ceilings (up to 14 over the floor, at least 4 clear under it), by
     * band; none within 12 of a live bottom-band vent, none over a vent's mouth, fewer over hot ground.
     */
    void drip(short[] down, short[] up, boolean[] porch, int[] pd, int[] pd2) {
        for (int i = 0; i < nx; i++)
            for (int j = 0; j < nz; j++) {
                int q = i * nz + j, wx = x0 + i, wz = z0 + j, base = q * SY;
                double s = 0.6 * n2(pd, wx / 3.1, wz / 3.1) + 0.4 * n2(pd2, wx / 6.3 + 13.0, wz / 6.3 - 5.0);
                if (s < 0.30) continue;
                double lx = (wx - gv.ox) + 0.5, lz = (wz - gv.oz) + 0.5;
                boolean nearLive = false;
                for (Vent v : vents)
                    if (v.t[16] > 0.5 && (int) v.t[2] == 0) {
                        double dx = lx - v.cx, dz = lz - v.cz;
                        if (dx * dx + dz * dz < 144.0) {
                            nearLive = true;
                            break;
                        }
                    }
                if (nearLive) continue;
                for (int y = SEAL + 1; y < SY - SEAL; y++) {
                    int b = base + y;
                    if (mat[b] != AIR || porch[b]) continue;
                    byte vp = vpart[b];
                    if (vp != 0 && !(vp == VP_DEN && vents.get(vid[b] - 1).t[16] < 0.5)) continue;
                    double u = up[b];
                    if (u > 14) continue;
                    if (down[b] < DRIP_GAP) continue;
                    // none within 6 rows above a vent's mouth
                    boolean aboveMouth = false;
                    for (Vent v : vents) {
                        double dx = lx - v.cx, dz = lz - v.cz, rr = v.t[9] + 2.0;
                        if (dx * dx + dz * dz < rr * rr) {
                            float top = v.t[6] + v.t[26];
                            if (y >= (double) top - 2 && y <= (double) top + 6) {
                                aboveMouth = true;
                                break;
                            }
                        }
                    }
                    if (aboveMouth) continue;
                    int yu = Math.min((int) (y + u), SY - 1);
                    int bd = f.dom[storeyAt(q, yu + 1)][q];
                    double thr = DRIP_THR[bd];
                    if (s < thr) continue;
                    double t = thr + 0.3 * ramp(f.hot[q] + f.grow[yu], LIVE_LO - 0.15, LIVE_LO + 0.1);
                    if (s - t > DRIP_K * Math.pow(u + 1.0, DRIP_P)) mat[b] = DRIP;
                }
            }
    }

    // ================================================================================================ life

    /** The light the life reads: a box mean of the glowing blocks (19 x 11 x 19), tips, live spires and crowns 1. */
    float[] heat() {
        int N = mat.length;
        int bx = 2 * REACH_XZ + 1, by = 2 * REACH_Y + 1;
        if (plan.patch) {
            // the preview's scipy uniform_filter, float32 between the passes, axis by axis
            float[] h = new float[N];
            for (int b = 0; b < N; b++) {
                byte m = mat[b];
                h[b] = (m == TIP || m == LIVE || m == CROWN) ? 1f : ((m == FISSURE || m == VGLOW) ? 0.3f : 0f);
            }
            uniform1d(h, 0, bx);
            uniform1d(h, 1, by);
            uniform1d(h, 2, bx);
            return h;
        }
        // in the game, exact integer sums (tenths), so a tile's light never depends on where its window starts
        int[] a = new int[N], t = new int[N];
        for (int b = 0; b < N; b++) {
            byte m = mat[b];
            a[b] = (m == TIP || m == LIVE || m == CROWN) ? 10 : ((m == FISSURE || m == VGLOW) ? 3 : 0);
        }
        boxSum(a, t, 0, REACH_XZ);
        boxSum(t, a, 1, REACH_Y);
        boxSum(a, t, 2, REACH_XZ);
        float[] h = new float[N];
        float div = bx * by * bx * 10f;
        for (int b = 0; b < N; b++) h[b] = t[b] / div;
        return h;
    }

    int lineLen(int axis) { return axis == 0 ? nx : (axis == 1 ? SY : nz); }

    int lineStride(int axis) { return axis == 0 ? sx : (axis == 1 ? 1 : SY); }

    /** The starts of every line along an axis. */
    int[] lineStarts(int axis) {
        int[] out;
        int c = 0;
        if (axis == 0) {
            out = new int[nz * SY];
            for (int j = 0; j < nz; j++) for (int y = 0; y < SY; y++) out[c++] = j * SY + y;
        } else if (axis == 1) {
            out = new int[nx * nz];
            for (int q = 0; q < nx * nz; q++) out[c++] = q * SY;
        } else {
            out = new int[nx * SY];
            for (int i = 0; i < nx; i++) for (int y = 0; y < SY; y++) out[c++] = i * sx + y;
        }
        return out;
    }

    /** scipy.ndimage.uniform_filter1d, mode constant 0, in place (double line, running sum, float32 store). */
    void uniform1d(float[] a, int axis, int size) {
        int len = lineLen(axis), st = lineStride(axis);
        int size1 = size / 2;
        double[] ext = new double[len + size - 1];
        for (int s0 : lineStarts(axis)) {
            Arrays.fill(ext, 0.0);
            for (int t = 0; t < len; t++) ext[t + size1] = a[s0 + t * st];
            double tmp = 0.0;
            for (int ll = 0; ll < size; ll++) tmp += ext[ll];
            tmp /= size;
            a[s0] = (float) tmp;
            for (int ll = 1; ll < len; ll++) {
                tmp += (ext[ll + size - 1] - ext[ll - 1]) / size;
                a[s0 + ll * st] = (float) tmp;
            }
        }
    }

    /** Integer box sums of half-width r along an axis (zero outside the window). */
    void boxSum(int[] src, int[] dst, int axis, int r) {
        int len = lineLen(axis), st = lineStride(axis);
        int[] pre = new int[len + 1];
        for (int s0 : lineStarts(axis)) {
            for (int t = 0; t < len; t++) pre[t + 1] = pre[t] + src[s0 + t * st];
            for (int t = 0; t < len; t++) dst[s0 + t * st] = pre[Math.min(len, t + r + 1)] - pre[Math.max(0, t - r)];
        }
    }

    /** dress3._halo: near each live vent the life leans to one side, heat *= 1 - w + w * m(theta). */
    void halo(float[] heat) {
        double[] fac = new double[K];
        for (int i = 0; i < nx; i++)
            for (int j = 0; j < nz; j++) {
                int q = i * nz + j, base = q * SY;
                double lx = (x0 + i - gv.ox) + 0.5, lz = (z0 + j - gv.oz) + 0.5;
                Arrays.fill(fac, Double.NaN);
                for (int y = 0; y < SY; y++) {
                    int b = base + y;
                    if (heat[b] <= 0f) continue;
                    int k = storeyAt(q, y + 1);
                    if (Double.isNaN(fac[k])) {
                        int vn = f.vnear[k][q];
                        if (vn < 0 || vents.get(vn).t[16] < 0.5) fac[k] = 1.0;
                        else {
                            Vent v = vents.get(vn);
                            float wv = f.vw[k][q];
                            double th = Math.atan2(lz - v.cz, lx - v.cx);
                            double s1 = 100.0 * u(v.k24, 14), s2 = 100.0 * u(v.k24, 15);
                            double m = ramp(n2(gv.pvn, 0.9 * Math.cos(th) + s2, 0.9 * Math.sin(th) - s1), -0.15, 0.25);
                            fac[k] = 1.0 - wv + wv * m;
                        }
                    }
                    heat[b] = (float) (heat[b] * fac[k]);      // exact when fac is 1
                }
            }
    }

    /** dress3._mats3: glow mats on bare floor where the light reaches, by band; on a live vent's skirt only on its lit side. */
    void mats(float[] heat, int[] pm, int[] pcol) {
        for (int i = 0; i < nx; i++)
            for (int j = 0; j < nz; j++) {
                int q = i * nz + j, wx = x0 + i, wz = z0 + j, base = q * SY;
                double jit = 0.5 * n2(pm, wx / 3.5, wz / 3.5);
                double cn = n2(pcol, wx / 34.0, wz / 34.0);
                double lx = (wx - gv.ox) + 0.5, lz = (wz - gv.oz) + 0.5;
                for (int y = 1; y < SY - 2; y++) {
                    int b = base + y;
                    if (mat[b] != ROCK || mat[b + 1] != AIR) continue;
                    byte vp = vpart[b];
                    int bd = f.dom[storeyAt(q, y + 1)][q];
                    if (vp != 0) {
                        if (vp != VP_SKIRT) continue;
                        int vi = vid[b] - 1;
                        if (vi < 0 || vents.get(vi).t[16] < 0.5) continue;
                        Vent v = vents.get(vi);
                        double th = Math.atan2(lz - v.cz, lx - v.cx);
                        double s1 = 100.0 * u(v.k24, 14), s2 = 100.0 * u(v.k24, 15);
                        if (ramp(n2(gv.pvn, 0.9 * Math.cos(th) + s2, 0.9 * Math.sin(th) - s1), -0.15, 0.25) <= 0.5) continue;
                    }
                    if (heat[b + 1] * (1.0 + jit) > MAT_LO[bd]) {
                        mat[b] = MAT;
                        colour[b] = (byte) (cn < T1[bd] ? 0 : (cn < T2[bd] ? 1 : 3));
                    }
                }
            }
    }

    /**
     * dress3._mushrooms3: a neon mushroom on the floor at each peak of a slow noise where the light reaches and the
     * room is tall enough; stem, cap and colour by band (lime only by the ways down). Returns how many.
     */
    int mushrooms(float[] heat, short[] down, short[] up, boolean[] keepSp, int[] pm, int[] pcol) {
        double[] fv = new double[nx * nz];
        for (int i = 0; i < nx; i++)
            for (int j = 0; j < nz; j++) fv[i * nz + j] = n2(pm, (x0 + i) / 5.5, (z0 + j) / 5.5);
        List<double[]> deep = new ArrayList<>();
        for (Vent v : vents) if ((int) v.t[1] == 3) deep.add(new double[]{v.cx, v.cz});
        int[] four = {sx, -sx, SY, -SY};
        int n = 0;
        for (int i = 6; i < nx - 6; i++)
            for (int j = 6; j < nz - 6; j++) {
                int q = i * nz + j, wx = x0 + i, wz = z0 + j, base = q * SY;
                double v0 = fv[q];
                if (v0 < 0.2) continue;
                boolean peak = true;
                for (int a = -3; a <= 3; a++)
                    for (int c = -3; c <= 3; c++) if ((a != 0 || c != 0) && fv[(i + a) * nz + j + c] > v0) peak = false;
                if (!peak) continue;
                double h0 = hash2(plan.seed, wx, wz);
                boolean nearDeep = false;
                double lxi = wx - gv.ox, lzi = wz - gv.oz;
                for (double[] d : deep) {
                    double dx = lxi - d[0], dz = lzi - d[1];
                    if (dx * dx + dz * dz < 1600.0) nearDeep = true;
                }
                double cn = n2(pcol, wx / 34.0, wz / 34.0);
                for (int y = SEAL + 1; y < SY - 20; y++) {
                    int b = base + y;
                    if (mat[b] != AIR || down[b] != 0) continue;
                    if (vpart[b] != 0 || keepSp[b]) continue;
                    int k = storeyAt(q, y + 1), bd = f.dom[k][q];
                    if (f.vw[k][q] >= 1.0) continue;
                    if (heat[b] < MUSH_LO[bd]) continue;
                    int room = up[b];
                    if (room < STEM_MIN + 5) continue;
                    double h1 = hash2(plan.seed + 7, wx, wz);
                    int hgt = (int) Math.min((double) (room - 5), STEM_MIN - 2 + (room - STEM_MIN) * (0.1 + 0.55 * h1));
                    hgt = Math.max(3, Math.min(hgt, (int) STEM_MAX[bd]));
                    double r = 1.6 + (CAP_R[bd] - 1.6) * Math.min(1.0, Math.max(0.0, (hgt - 3.0) / 9.0) * (0.5 + 0.5 * h0));
                    int ci = cn < T1[bd] ? 0 : (cn < T2[bd] ? 1 : 3);
                    if ((int) (h0 * 997.0) % 8 >= 6) ci = ci != 3 ? (ci + 1) % 2 : 1;   // a few caps break from their patch's colour
                    if (bd == 2 && ci == 3) ci = 1;                                    // light blue stays on the top's crowns and tips
                    if (nearDeep && bd == 0 && h0 > 0.5) ci = LIME;                     // lime only by the ways down
                    boolean thick = r >= 3.6;
                    boolean ok = true;
                    for (int jj = 0; jj < hgt + 3; jj++) if (mat[b + jj] != AIR || vpart[b + jj] != 0) ok = false;
                    if (!ok) continue;
                    for (int jj = 0; jj < hgt; jj++) {
                        mat[b + jj] = STEM;
                        if (thick && jj < hgt * 0.7)
                            for (int d : four) {
                                int o = b + jj + d;
                                if (mat[o] == AIR && vpart[o] == 0) mat[o] = STEM;
                            }
                    }
                    int ri = (int) r + 1;
                    for (int a = -ri; a <= ri; a++)
                        for (int c = -ri; c <= ri; c++) {
                            double dd = Math.sqrt(a * a + c * c);
                            if (dd > r) continue;
                            double e = dd / r;
                            int top = y + hgt + (int) Math.rint(1.6 * (1.0 - e * e));
                            int bot = y + hgt - (dd > r - 1.2 ? 1 : 0);
                            int xi = i + a, zj = j + c;
                            if (xi < 0 || zj < 0 || xi >= nx || zj >= nz) continue;
                            int cb = (xi * nz + zj) * SY;
                            for (int yy = bot; yy <= top && yy < SY; yy++) {
                                int o = cb + yy;
                                if (mat[o] == AIR && vpart[o] == 0 && !keepSp[o]) {
                                    mat[o] = CAP;
                                    colour[o] = (byte) ci;
                                }
                            }
                        }
                    n++;
                }
            }
        return n;
    }

    // ================================================================================================ oil, wells, seep

    /** dress3._oil3: oil fills each storey's dips up to its pool level (a thin pool on rock). Returns blocks. */
    int oil() {
        int n = 0;
        for (int k = 0; k < K; k++)
            for (int q = 0; q < nx * nz; q++) {
                if (!f.foot[k][q]) continue;
                float fl = f.floor[k][q], lvl = f.pools[k][q];
                if (lvl - fl < 0.6) continue;
                int fi = (int) Math.floor(fl), top = (int) Math.floor(lvl), base = q * SY;
                for (int y = Math.max(fi - 2, SEAL + 1); y < Math.min(top, SY - SEAL); y++) {
                    int b = base + y;
                    if (mat[b] == AIR && mat[b - 1] != AIR && vpart[b] == 0) {
                        mat[b] = OIL;
                        n++;
                    }
                }
            }
        return n;
    }

    /**
     * dress3._wells_pass: standing spots walled on all four sides at the feet with no way out by steps of at most 1
     * (diagonals needing both sides clear): pits a player could drop into and never leave.
     */
    int wellsPass(boolean[] keep, boolean[] dirty, IntList out) {
        int n0 = out.n;
        int[] ddx = {1, -1, 0, 0, 1, 1, -1, -1}, ddz = {0, 0, 1, -1, 1, -1, 1, -1};
        for (int i = 2; i < nx - 2; i++)
            for (int j = 2; j < nz - 2; j++) {
                if (!dirty[i * nz + j]) continue;
                int base = (i * nz + j) * SY;
                for (int y = SEAL + 2; y < SY - 4; y++) {
                    int b = base + y;
                    if (!(open(mat[b]) && open(mat[b + 1])) || open(mat[b - 1])) continue;
                    if (vpart[b] != 0 || vpart[b + 1] != 0 || keep[b]) continue;
                    if (open(mat[b + sx]) || open(mat[b - sx]) || open(mat[b + SY]) || open(mat[b - SY])) continue;
                    boolean ex = false;
                    for (int di = 0; di < 8 && !ex; di++) {
                        int dx = ddx[di], dz = ddz[di];
                        int o = b + dx * sx + dz * SY, ox_ = b + dx * sx, oz_ = b + dz * SY;
                        boolean diag = dx != 0 && dz != 0;
                        if (open(mat[o]) && open(mat[o + 1])) {
                            if (diag && !(open(mat[ox_]) && open(mat[ox_ + 1]) && open(mat[oz_]) && open(mat[oz_ + 1]))) continue;
                            if (!open(mat[o - 1]) || !open(mat[o - 2])) ex = true;
                        } else if (!open(mat[o]) && open(mat[o + 1]) && open(mat[o + 2]) && open(mat[b + 2])) {
                            if (diag && !(open(mat[ox_ + 1]) && open(mat[ox_ + 2]) && open(mat[oz_ + 1]) && open(mat[oz_ + 2]))) continue;
                            ex = true;
                        }
                    }
                    if (!ex) out.add(b);
                }
            }
        return out.n - n0;
    }

    /** dress3.fill_wells: fills dead pits with rubble, round by round. Returns blocks. */
    int fillWells(boolean[] keep, int rounds) {
        boolean[] dirty = new boolean[nx * nz], next = new boolean[nx * nz];
        Arrays.fill(dirty, true);
        IntList out = new IntList();
        int tot = 0;
        for (int r = 0; r < rounds; r++) {
            out.n = 0;
            int n = wellsPass(keep, dirty, out);
            if (n == 0) break;
            Arrays.fill(next, false);
            for (int c = 0; c < out.n; c++) {
                mat[out.a[c]] = TALUS;
                markAround(next, out.a[c] / SY, true);
            }
            tot += n;
            boolean[] t = dirty;
            dirty = next;
            next = t;
        }
        return tot;
    }

    /**
     * dress3._seep3 (Taran's pick, 2026-11-18): a faint light-blue glow along the oil seeps in the top band. The dry
     * floor block under every walkable shore spot (air beside oil, 8 ways, at the oil's row) becomes SEEP where a slow
     * noise is high, so the glow runs in broken lines along the oil's edge; never on a vent part. Returns blocks.
     */
    int seep(int[] p) {
        int n = 0;
        for (int i = 1; i < nx - 1; i++)
            for (int j = 1; j < nz - 1; j++) {
                int q = i * nz + j, wx = x0 + i, wz = z0 + j, base = q * SY;
                if (n2(p, wx / SEEP_L, wz / SEEP_L) < SEEP_KEEP) continue;
                for (int y = SEAL + 1; y < SY - SEAL; y++) {
                    int b = base + y;
                    if (mat[b] != AIR || vpart[b] != 0 || vpart[b - 1] != 0 || vid[b - 1] != 0) continue;
                    byte fb = mat[b - 1];
                    if (fb != ROCK && fb != TALUS) continue;
                    boolean near = false;
                    for (int dx = -1; dx <= 1; dx++)
                        for (int dz = -1; dz <= 1; dz++) if (mat[b + dx * sx + dz * SY] == OIL) near = true;
                    if (!near) continue;
                    if (f.dom[storeyAt(q, y)][q] != 2) continue;   // the band of the crust block itself (row y - 1)
                    mat[b - 1] = SEEP;
                    colour[b - 1] = LIGHTBLUE;
                    n++;
                }
            }
        return n;
    }

    // ================================================================================================ float16

    /** numpy's float32 to float16 and back (round half to even), for the preview's float16 info arrays. */
    static float f16(float v) {
        if (v == 0f || Float.isNaN(v) || Float.isInfinite(v)) return v;
        float a = Math.abs(v);
        if (a >= 65520f) return Math.copySign(Float.POSITIVE_INFINITY, v);
        int e = Math.max(Math.getExponent(a), -14);
        double qn = Math.scalb(1.0, e - 10);
        return (float) Math.copySign(Math.rint(a / qn) * qn, v);
    }
}
