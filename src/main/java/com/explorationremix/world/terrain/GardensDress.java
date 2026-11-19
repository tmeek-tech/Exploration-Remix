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
    /**
     * Round 3 (Taran's playtest notes, 2026-11-19): black dripstone over the cave's surfaces (SKIN), pointed black
     * dripstone hanging (PDOWN) and standing (PUP), spire tips running down a live vent's sides (STREAK: the tip's
     * block, lighter on the life round it), blackstone dithered into the rock round a vent's foot (VBASE) and the glow
     * mat's thinner edge (MATE).
     */
    static final byte SKIN = 20, PDOWN = 21, PUP = 22, STREAK = 23, VBASE = 24, MATE = 25;

    // per band: bottom (Vent Domes), middle (Chimney Halls), top (Seep Beds)
    static final double[] MAX_H = {14.0, 10.0, 6.0}, THR_IN = {0.16, 0.22, 0.30}, KK = {0.0024, 0.0030, 0.0036},
            MIN_ROOM = {9.0, 9.0, 6.0}, CLEAR_TOP = {0.0, 0.0, 2.0}, DRIP_THR = {0.30, 0.24, 0.20},
            MAT_LO = {0.0096, 0.012, 0.0168}, MUSH_LO = {0.021, 0.030, 0.048}, CAP_R = {5.2, 4.2, 3.0},
            STEM_MAX = {16.0, 12.0, 8.0}, T1 = {-0.0416, -0.2656, -0.3367}, T2 = {0.2761, 0.1935, 0.1333},
            SKIN_THR = {0.16, 0.13, 0.14};
    static final int[] TIPC = {MAGENTA, VIOLET, LIGHTBLUE};
    static final double THR_OUT = 0.62, P = 1.55, LIVE_LO = 0.30, FLANGE = 0.06, DRIP_K = 0.010, DRIP_P = 1.35;
    /** Round 3's cones: the hot ground's extra bar (round 2: 0.3), and the most of its room's height a cone may fill. */
    static final double DRIP_HOT = 0.15, DRIP_ROOM = 0.45;
    /**
     * The glow mat's fading edge: no cover under MAT_P0 x MAT_LO of light, full cover from MAT_P1 x MAT_LO; the thinner
     * edge mat takes the blocks within MAT_EDGE under the dither.
     */
    static final double MAT_P0 = 0.55, MAT_P1 = 1.45, MAT_EDGE = 0.16;
    /**
     * The dripstone skin's bar over hot ground (deep down, by live vents) and where the mats' light reaches, the
     * dither's spread, and the margin over the bar that turns the rock behind a face too.
     */
    static final double SKIN_HOT = 0.22, SKIN_LIT = 0.30, SKIN_DITHER = 0.10, SKIN_DEEP = 0.06;
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
    /** Each vent's colour, by its place in {@link #vents} ({@link #ventColours}). */
    final byte[] vcol;
    final Map<String, Integer> counts = new LinkedHashMap<>();
    /** Per step wall time (ms), when {@link #TIMING} is on (the offline checks). */
    final Map<String, Long> times = new LinkedHashMap<>();
    static boolean TIMING = false;
    /** The offline checks' look at each finished dressing (null in the game). */
    static volatile java.util.function.Consumer<GardensDress> PROBE;
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
        vcol = new byte[vents.size()];
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
        // round 3's fields: streaks (0-2), the mats' clumps (3), the skin (4-6), the vents' feet (7)
        int[][] pr = new int[8][];
        for (int i = 0; i < 8; i++) pr[i] = plan.perm(plan.seed + 5101 + i);
        ventColours(ps[8]);
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
        int[] gc = glow(pr[0], pr[1], pr[2]);
        lap("glow");
        counts.put("crown", gc[0]);
        counts.put("fissure", gc[1]);
        counts.put("vent glow", gc[2]);
        counts.put("throat vein", gc[3]);
        counts.put("tip on a vent's top", gc[4]);
        counts.put("streak", gc[5]);
        counts.put("streak faces", gc[6]);
        columnDist(down, up);
        boolean[] dripKeep = porch.clone();
        for (int b = 0; b < N; b++) dripKeep[b] |= gk[b];
        drip(down, up, dripKeep, ps[5], ps[6]);
        lap("colDist+drip");
        float[] heat = heat();
        lap("heat");
        halo(heat);
        lap("halo");
        mats(heat, ps[7], ps[8], pr[3]);
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
        // round 3: the dripstone skin, the pointed dripstone, the vents' blackstone feet (rock for rock: nothing
        // walkable changes but the short stalagmites, which stand clear of every way through)
        boolean[] sk = gateKeep();
        for (int b = 0; b < N; b++) sk[b] |= porch[b];
        counts.put("dripstone skin", skin(heat, sk, pr[4], pr[5], pr[6], ps[5], ps[6]));
        lap("skin");
        columnDist(down, up);
        int[] pc = pointed(down, keepSp);
        counts.put("pointed hanging", pc[0]);
        counts.put("pointed standing", pc[1]);
        lap("colDist+pointed");
        counts.put("vent foot blackstone", ventFeet(pr[7]));
        lap("ventFeet");
        int[] by = new int[32];
        for (byte m : mat) by[m]++;
        counts.put("dead spire", by[DEAD]);
        counts.put("live spire", by[LIVE]);
        counts.put("tip", by[TIP]);
        counts.put("glow mat", by[MAT]);
        counts.put("edge mat", by[MATE]);
        counts.put("dripstone", by[DRIP]);
        counts.put("skin", by[SKIN]);
        counts.put("pointed", by[PDOWN] + by[PUP]);
        counts.put("cap", by[CAP]);
        counts.put("crown blocks", by[CROWN]);
        counts.put("streak blocks", by[STREAK]);
        java.util.function.Consumer<GardensDress> pr0 = PROBE;
        if (pr0 != null) pr0.accept(this);
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
                        colour[b] = vcol[vid[b] - 1];
                    } else {
                        mat[b] = TIP;
                        colour[b] = (byte) TIPC[f.dom[storeyAt(q, y + 1)][q]];
                    }
                }
            }
    }

    /**
     * Each vent's colour: the glow mats' colour at its centre, by its band, so a vent matches the mats round it
     * (Taran's pick, 2026-11-19; round 2 coloured vents by band, which put every light blue vent in the seeps); a
     * deep vent is lime, the colour of the ways down.
     */
    void ventColours(int[] pcol) {
        for (Vent v : vents) {
            int c;
            if ((int) v.t[1] == 3) c = LIME;
            else {
                int bd = Math.max(0, Math.min(2, (int) v.t[2]));
                double cn = n2(pcol, (v.cx + gv.ox) / 34.0, (v.cz + gv.oz) / 34.0);
                c = cn < T1[bd] ? MAGENTA : (cn < T2[bd] ? VIOLET : LIGHTBLUE);
            }
            vcol[v.idx] = (byte) c;
        }
    }

    /** Air of a vent's inside: its den, flue, cracks, doors, adits, hood and throat. */
    static boolean inside(byte vp) {
        return vp == VP_DEN || vp == VP_FLUE || vp == VP_CRACK || vp == VP_DOOR || vp == VP_ADIT || vp == VP_HOOD
                || vp == VP_TUBE;
    }

    /**
     * dress3._glow, round 3: live vents glow in their colour. A vent's top is a dither of CROWN and spire TIP (Taran,
     * 2026-11-19: "The spire tip and vent crown look good together so dither them on the top of vents"): crown-led
     * where the rim's angular noise is high (where round 2's crowns stood), tips taking over on its shoulders, a few
     * blocks left bare. Below the top, tips run down the outer walls in streaks (STREAK; "Have the spire tips run down
     * the sides of vents too"), about 6 to 10 round a vent, each wandering a little and fading out over 3 to 12 rows.
     * FISSURE veins in the den floor, VGLOW flecks on the den's and flue's inner faces; deep vents also get TVEIN along
     * their throat's walls (in the layer only: below its floor the throat stays dark). Dead vents give no light.
     * Returns the counts: crowns, fissures, flecks, throat veins, tips on tops, streak blocks, and the outer faces in
     * the 12 rows under the tops (where streaks may run).
     */
    int[] glow(int[] pst, int[] pstL, int[] pstM) {
        int[] cnt = new int[7];
        Geom g = new Geom();
        int[] six = {sx, -sx, 1, -1, SY, -SY};
        int[] five = {sx, -sx, SY, -SY, 1};
        int xlo = x0 + 1, xhi = x0 + nx - 1, zlo = z0 + 1, zhi = z0 + nz - 1;
        long hsTop = plan.seed + 5181, hsSide = plan.seed + 5182;
        for (Vent v : vents) {
            if (v.t[16] < 0.5f) continue;
            int vv = v.idx + 1;
            byte col = vcol[v.idx];
            int ys = (int) v.t[6];
            double H = v.t[8], R = Math.max(3.0, v.t[9]);
            double top = ys + (double) v.t[26];
            int r = (int) v.t[33] + 1;
            double u12 = u(v.k24, 12), u13 = u(v.k24, 13);
            // the streaks' place in their fields, per vent; about one streak per 3.5 blocks of radius round the wall
            double o1 = 200.0 * Hashing.unit(Hashing.mix(hsSide, v.k24, 1, 0));
            double o2 = 200.0 * Hashing.unit(Hashing.mix(hsSide, v.k24, 2, 0));
            double rs = R / 3.5, rl = R / 6.0;
            boolean deep = (int) v.t[1] == 3;
            int icx = ic(v.cx) + gv.ox, icz = ic(v.cz) + gv.oz;
            int ya = Math.max(1, Math.min(ys - 2, (int) v.t[7])), yb = Math.min(SY - 1, (int) (ys + H + 4));
            for (int x = Math.max(xlo, icx - r); x < Math.min(xhi, icx + r + 1); x++)
                for (int z = Math.max(zlo, icz - r); z < Math.min(zhi, icz + r + 1); z++) {
                    int base = ((x - x0) * nz + (z - z0)) * SY;
                    for (int y = ya; y < yb; y++) {
                        int b = base + y;
                        if (vid[b] != vv || mat[b] != VLIVE) continue;
                        gv.geom(v, x, y, z, y, g);
                        boolean outer = g.rhoE >= g.rin + 0.5;
                        // the top: the body's top row, outer side, crown and tip dithered
                        if (y >= top - 2.5 && vid[b + 1] != vv && outer) {
                            double na = n2(gv.pglow, 1.6 * Math.cos(g.th) + 10.0 * u12, 1.6 * Math.sin(g.th));
                            double pc = 0.2 + 0.6 * ramp(na, -0.45, 0.25);
                            double h = Hashing.unit(Hashing.mix(hsTop, x, y, z));
                            if (h < pc) {
                                mat[b] = CROWN;
                                colour[b] = col;
                                cnt[0]++;
                                continue;
                            }
                            if (h < pc + 0.8 * (1.0 - pc)) {
                                mat[b] = TIP;
                                colour[b] = col;
                                cnt[4]++;
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
                            continue;
                        }
                        // streaks down the outer walls, from under the top
                        if (!outer || y >= top - 1.0) continue;
                        boolean face = false;
                        for (int d : five) {
                            int o = b + d;
                            if (open(mat[o]) && !(vid[o] == vv && inside(vpart[o]))) {
                                face = true;
                                break;
                            }
                        }
                        if (!face) continue;
                        if (y >= top - 13.0) cnt[6]++;
                        // a streak narrows and thins out as it runs down, to a dotted line at its end
                        double len = 3.0 + 9.0 * ramp(n2(pstL, rl * Math.cos(g.th) + o2, rl * Math.sin(g.th) + o1), -0.35, 0.35);
                        double fr = (top - 1.0 - y) / len;
                        if (fr >= 1.0) continue;
                        double th = g.th + (1.5 / R) * n2(pstM, y / 5.0 + o1, o2);
                        double st = ramp(n2(pst, rs * Math.cos(th) + o1, rs * Math.sin(th) + o2), -0.15 + 0.3 * fr, 0.15 + 0.15 * fr);
                        double dens = st * (1.0 - 0.6 * ramp(fr, 0.3, 1.0));
                        if (dens > 0.0 && Hashing.unit(Hashing.mix(hsSide, x, y, z)) < dens) {
                            mat[b] = STREAK;
                            colour[b] = col;
                            cnt[5]++;
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

    /** The cones' field over the columns (round 3: coarser than round 2's 3.1 and 6.3, so cones gather in clusters). */
    static double dripField(int[] pd, int[] pd2, int wx, int wz) {
        return 0.6 * n2(pd, wx / 4.5, wz / 4.5) + 0.4 * n2(pd2, wx / 9.0 + 13.0, wz / 9.0 - 5.0);
    }

    /**
     * dress3._drip3, round 3: black dripstone cones hang from low ceilings (up to 14 over the floor, at least 4 clear
     * under them, filling at most DRIP_ROOM of the room's height so long views stay open), by band, about twice as many
     * columns as round 2 (Taran, 2026-11-19: "Have the black dripstone take up more space"); none over a live
     * bottom-band vent or close round it, none over a vent's mouth, fewer over hot ground.
     */
    void drip(short[] down, short[] up, boolean[] porch, int[] pd, int[] pd2) {
        double qmin = Math.min(DRIP_THR[0], Math.min(DRIP_THR[1], DRIP_THR[2]));
        for (int i = 0; i < nx; i++)
            for (int j = 0; j < nz; j++) {
                int q = i * nz + j, wx = x0 + i, wz = z0 + j, base = q * SY;
                double s = dripField(pd, pd2, wx, wz);
                if (s < qmin) continue;
                double lx = (wx - gv.ox) + 0.5, lz = (wz - gv.oz) + 0.5;
                boolean nearLive = false;
                for (Vent v : vents)
                    if (v.t[16] > 0.5 && (int) v.t[2] == 0) {
                        double dx = lx - v.cx, dz = lz - v.cz, rr = v.t[9] + 5.0;
                        if (dx * dx + dz * dz < Math.max(144.0, rr * rr)) {
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
                    if (u + 1.0 > DRIP_ROOM * (u + down[b] + 1.0)) continue;
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
                    double t = thr + DRIP_HOT * ramp(f.hot[q] + f.grow[yu], LIVE_LO - 0.15, LIVE_LO + 0.1);
                    if (s - t > DRIP_K * Math.pow(u + 1.0, DRIP_P)) mat[b] = DRIP;
                }
            }
    }

    // ================================================================================================ life

    /**
     * The light the life reads: a box mean of the glowing blocks (19 x 11 x 19): tips, live spires and crowns 1;
     * fissures, flecks and the streaks down a vent's sides 0.3 (round 3: so the life round a vent stays about as it was).
     */
    float[] heat() {
        int N = mat.length;
        int bx = 2 * REACH_XZ + 1, by = 2 * REACH_Y + 1;
        if (plan.patch) {
            // the preview's scipy uniform_filter, float32 between the passes, axis by axis
            float[] h = new float[N];
            for (int b = 0; b < N; b++) {
                byte m = mat[b];
                h[b] = (m == TIP || m == LIVE || m == CROWN) ? 1f : ((m == FISSURE || m == VGLOW || m == STREAK) ? 0.3f : 0f);
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
            a[b] = (m == TIP || m == LIVE || m == CROWN) ? 10 : ((m == FISSURE || m == VGLOW || m == STREAK) ? 3 : 0);
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

    /**
     * dress3._mats3, round 3: glow mats on bare floor where the light reaches, by band; on a live vent's skirt only on
     * its lit side. The cover fades, from none at MAT_P0 x MAT_LO of light to full at MAT_P1 x MAT_LO, each block taken
     * where the cover beats a clumpy per-block dither (a world hash and a fine noise), so a patch thins out over a few
     * blocks in small clumps instead of ending at a hard line (Taran, 2026-11-19: "Dither the glow mat so there's no
     * hard boundary"); the thinner edge mat takes the blocks just under the dither.
     */
    void mats(float[] heat, int[] pm, int[] pcol, int[] pclump) {
        long hs = plan.seed + 5191;
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
                    double side = 1.0;
                    if (vp != 0) {
                        if (vp != VP_SKIRT) continue;
                        int vi = vid[b] - 1;
                        if (vi < 0 || vents.get(vi).t[16] < 0.5) continue;
                        Vent v = vents.get(vi);
                        double th = Math.atan2(lz - v.cz, lx - v.cx);
                        double s1 = 100.0 * u(v.k24, 14), s2 = 100.0 * u(v.k24, 15);
                        side = ramp(ramp(n2(gv.pvn, 0.9 * Math.cos(th) + s2, 0.9 * Math.sin(th) - s1), -0.15, 0.25), 0.3, 0.7);
                        if (side <= 0.0) continue;
                    }
                    double p = side * ramp(heat[b + 1] * (1.0 + jit) / MAT_LO[bd], MAT_P0, MAT_P1);
                    if (p <= 0.0) continue;
                    double d = 0.65 * Hashing.unit(Hashing.mix(hs, wx, y, wz)) + 0.35 * (0.5 + 0.5 * n2(pclump, wx / 1.7, wz / 1.7));
                    if (p > d) mat[b] = MAT;
                    else if (p > d - MAT_EDGE) mat[b] = MATE;
                    else continue;
                    colour[b] = (byte) (cn < T1[bd] ? 0 : (cn < T2[bd] ? 1 : 3));
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

    // ================================================================================================ round 3 rock

    /**
     * Black dripstone over the cave's surfaces (Taran, 2026-11-19: "Have the black dripstone take up more space on the
     * ceiling, the walls, and the floor"): a rock block with an open face becomes SKIN where its patch score beats its
     * band's bar. Ceilings lean on the cones' field, so a skin rings each cluster of cones, the way dripstone spreads
     * round a cave's dripstone clusters; walls read a field stretched up and down, so it runs in streaks like
     * flowstone; floors lean on the cones' field too, under the clusters. The bar rises over hot ground (deep down and
     * by the live vents: less in the Vent Domes) and where the glow mats' light reaches, so a mat keeps bare rock round
     * it. A per-block hash dithers the edge, and where a face is well over its bar the rock behind turns too (two deep).
     * Never on a vent's own blocks or its skirt, in porches or gates. Returns blocks.
     */
    int skin(float[] heat, boolean[] keep, int[] pa, int[] pb, int[] pw, int[] pd, int[] pd2) {
        long hs = plan.seed + 5201;
        double[] sCol = new double[nx * nz];
        for (int i = 0; i < nx; i++) for (int j = 0; j < nz; j++) sCol[i * nz + j] = dripField(pd, pd2, x0 + i, z0 + j);
        boolean[] strong = new boolean[mat.length];
        int n = 0;
        for (int i = 1; i < nx - 1; i++)
            for (int j = 1; j < nz - 1; j++) {
                int q = i * nz + j, wx = x0 + i, wz = z0 + j, base = q * SY;
                for (int y = 1; y < SY - 1; y++) {
                    int b = base + y;
                    if (mat[b] != ROCK || vpart[b] != 0 || keep[b]) continue;
                    boolean below = open(mat[b - 1]), above = open(mat[b + 1]);
                    boolean side = open(mat[b + sx]) || open(mat[b - sx]) || open(mat[b + SY]) || open(mat[b - SY]);
                    if (!below && !above && !side) continue;
                    double pt = 0.65 * n3(pa, wx / 13.0, y / 9.0, wz / 13.0) + 0.35 * n3(pb, wx / 5.0, y / 5.0, wz / 5.0);
                    double sc = -9.0, sq = sCol[q];
                    if (below) sc = pt + 0.6 * Math.max(0.0, sq - 0.12);
                    if (above) sc = Math.max(sc, pt + 0.4 * Math.max(0.0, sq - 0.18));
                    if (side) sc = Math.max(sc, 0.55 * pt + 0.45 * n3(pw, wx / 4.0, y / 15.0, wz / 4.0));
                    int bd = f.dom[storeyAt(q, y + 1)][q];
                    double bar = SKIN_THR[bd] + SKIN_HOT * ramp(f.hot[q] + f.grow[y], LIVE_LO - 0.15, LIVE_LO + 0.1)
                            + SKIN_LIT * ramp(heat[b] / MAT_LO[bd], 0.25, 0.9);
                    double m = sc - bar + SKIN_DITHER * (Hashing.unit(Hashing.mix(hs, wx, y, wz)) - 0.5);
                    if (m <= 0.0) continue;
                    mat[b] = SKIN;
                    strong[b] = m > SKIN_DEEP;
                    n++;
                }
            }
        int[] six = {sx, -sx, SY, -SY, 1, -1};
        for (int i = 1; i < nx - 1; i++)
            for (int j = 1; j < nz - 1; j++) {
                int base = (i * nz + j) * SY;
                for (int y = 1; y < SY - 1; y++) {
                    int b = base + y;
                    if (mat[b] != ROCK || vpart[b] != 0 || keep[b]) continue;
                    for (int d : six)
                        if (strong[b + d]) {
                            mat[b] = SKIN;
                            n++;
                            break;
                        }
                }
            }
        return n;
    }

    /**
     * Pointed black dripstone (Taran, 2026-11-19: "Add pointed black dripstone"; card pick: under every cone, scattered
     * on dripstone ceilings, short stalagmites under the big cones). Each cone ends in points: 1 to 3 hanging from its
     * lowest block (longer under longer cones), now and then a single one under its flanks. Single points, 1 or 2 long
     * (1 in 5 longer), hang here and there from the skin on ceilings. Under the biggest cones a stalagmite of 1 to 3
     * stands clear on the floor. At least 3 rows of air stay under a hanging tip and 2 between it and a stalagmite;
     * none in porches, gates, vents or the ring round a vent's wall, none on mats, seep or oil. Never grows (the layer
     * is dry). Returns {hanging, standing}.
     */
    int[] pointed(short[] down, boolean[] keep) {
        long hs = plan.seed + 5211;
        int[] cnt = new int[2];
        int[][] four = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int i = 1; i < nx - 1; i++)
            for (int j = 1; j < nz - 1; j++) {
                int q = i * nz + j, wx = x0 + i, wz = z0 + j, base = q * SY;
                for (int y = SEAL + 4; y < SY - 1; y++) {
                    int b = base + y;
                    byte m = mat[b];
                    if ((m != DRIP && m != SKIN) || mat[b - 1] != AIR) continue;
                    int gap = down[b - 1] + 1;                      // the air under it, to the floor
                    if (gap < 4) continue;
                    double h1 = Hashing.unit(Hashing.mix(hs, wx, y, wz)), h2 = Hashing.unit(Hashing.mix(hs + 1, wx, y, wz));
                    int n, run = 0;
                    boolean tip = false;
                    if (m == DRIP) {
                        while (run < 16 && y + run < SY && mat[b + run] == DRIP) run++;
                        tip = true;
                        long hx = colhash(wx, wz);
                        for (int[] d : four) {
                            int o = b + d[0] * sx + d[1] * SY;
                            byte under = mat[o - 1];
                            if (under == DRIP) tip = false;                                        // it hangs lower
                            else if (mat[o] == DRIP && (under == AIR || under == PDOWN) && colhash(wx + d[0], wz + d[1]) < hx)
                                tip = false;                                                       // as low: one wins
                        }
                        n = tip ? Math.min(3, 1 + run / 4 + (h2 < 0.35 ? 1 : 0)) : (h1 < 0.22 ? 1 : 0);
                    } else n = h1 < 0.045 ? (h2 < 0.2 ? 3 : (h2 < 0.6 ? 2 : 1)) : 0;
                    n = Math.min(n, gap - 3);
                    int k = 0;
                    while (k < n && mat[b - 1 - k] == AIR && vpart[b - 1 - k] == 0 && !keep[b - 1 - k]) k++;
                    for (int c = 1; c <= k; c++) mat[b - c] = PDOWN;
                    cnt[0] += k;
                    if (!tip || run < 6) continue;
                    // a stalagmite under a big cone, on bare floor, standing clear
                    int fl = b - 1 - gap;
                    byte fb = mat[fl];
                    if ((fb != ROCK && fb != SKIN && fb != TALUS) || vpart[fl] != 0) continue;
                    double h3 = Hashing.unit(Hashing.mix(hs + 2, wx, y, wz));
                    int up = Math.min(h3 < 0.2 ? 3 : (h3 < 0.6 ? 2 : 1), gap - k - 2);
                    int c = 0;
                    while (c < up) {
                        int o = fl + 1 + c;
                        if (mat[o] != AIR || vpart[o] != 0 || keep[o]) break;
                        boolean clear = true;
                        for (int[] d : four) if (!open(mat[o + d[0] * sx + d[1] * SY])) clear = false;
                        if (!clear) break;
                        c++;
                    }
                    for (int e = 1; e <= c; e++) mat[fl + e] = PUP;
                    cnt[1] += c;
                }
            }
        return cnt;
    }

    /**
     * Blackstone dithered into the rock round each vent (Taran, 2026-11-19: "have them blend more seamlessly into the
     * ground under them"): plain rock and the vent's skirt turn to blackstone near the vent's own walls (its body, flare,
     * plug, hood and rim), the chance falling off over about 5 blocks (a noisy distance through the rock, dithered per
     * block), so the vent's blackstone frays out into the deepslate instead of meeting it at a line. Returns blocks.
     */
    int ventFeet(int[] pv) {
        final int DMAX = 7;
        long hs = plan.seed + 5221;
        int N = mat.length;
        byte[] dist = new byte[N];                                 // the distance + 1 through the skirt, 0 not reached
        IntList cur = new IntList(), nxt = new IntList();
        for (int b = 0; b < N; b++) {
            if (open(mat[b]) || !GardensVentField.isWallPart(vpart[b])) continue;
            dist[b] = 1;
            cur.add(b);
        }
        int n = 0;
        for (int d = 1; d <= DMAX && cur.n > 0; d++) {
            nxt.n = 0;
            for (int c = 0; c < cur.n; c++) {
                int b = cur.a[c], q = b / SY, y = b - q * SY, i = q / nz, j = q - i * nz;
                for (int e = 0; e < 6; e++) {
                    int o;
                    if (e == 0) { if (i + 1 >= nx) continue; o = b + sx; }
                    else if (e == 1) { if (i == 0) continue; o = b - sx; }
                    else if (e == 2) { if (j + 1 >= nz) continue; o = b + SY; }
                    else if (e == 3) { if (j == 0) continue; o = b - SY; }
                    else if (e == 4) { if (y + 1 >= SY) continue; o = b + 1; }
                    else { if (y == 0) continue; o = b - 1; }
                    if (dist[o] != 0 || mat[o] != ROCK || (vpart[o] != 0 && vpart[o] != VP_SKIRT)) continue;
                    dist[o] = (byte) (d + 1);
                    nxt.add(o);
                    int oq = o / SY, oy = o - oq * SY, wx = x0 + oq / nz, wz = z0 + oq % nz;
                    double p = 1.0 - ramp(d + 1.8 * n3(pv, wx / 4.5, oy / 4.5, wz / 4.5), 0.5, 5.5);
                    if (Hashing.unit(Hashing.mix(hs, wx, oy, wz)) < p) {
                        mat[o] = VBASE;
                        n++;
                    }
                }
            }
            IntList t = cur;
            cur = nxt;
            nxt = t;
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
