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
     * dripstone hanging (PDOWN) and standing (PUP), vent beads running down a live vent's sides in rivulets (STREAK:
     * the tips' block, lighter on the life round it), blackstone dithered into the rock round a vent's foot (VBASE) and
     * the glow mat's thinner edge (MATE).
     */
    static final byte SKIN = 20, PDOWN = 21, PUP = 22, STREAK = 23, VBASE = 24, MATE = 25;
    /**
     * 0.20.2, the plant life ({@link #plants}): filament grass (GRASS, a tuft or with {@link #PLANT_BIT} the taller
     * grass), tall filament grass (TALLG, both halves), tube worms (WORM), glowworm threads (THREAD, a column hanging
     * from a ceiling), seep reeds (REED, one block or two) and small neon mushrooms (SHROOM, crowded with the bit). Air to
     * everything that walks or flies (GardensField.open).
     */
    static final byte GRASS = 26, TALLG = 27, WORM = 28, THREAD = 29, REED = 30, SHROOM = 31;
    /** A plant's extra bit, kept in its colour (4) and so packed into its code's bit 7 (GardensField.PLANT_BIT). */
    static final byte PLANT_BIT = 4;

    // per band: bottom (Vent Domes), middle (Chimney Halls), top (Seep Beds)
    static final double[] MAX_H = {14.0, 10.0, 6.0}, THR_IN = {0.16, 0.22, 0.30}, KK = {0.0024, 0.0030, 0.0036},
            MIN_ROOM = {9.0, 9.0, 6.0}, CLEAR_TOP = {0.0, 0.0, 2.0}, DRIP_THR = {0.30, 0.24, 0.20},
            MAT_LO = {0.0096, 0.012, 0.0168}, MUSH_LO = {0.021, 0.030, 0.048}, CAP_R = {5.2, 4.2, 3.0},
            STEM_MAX = {16.0, 12.0, 8.0}, T1 = {-0.0416, -0.2656, -0.3367}, T2 = {0.2761, 0.1935, 0.1333},
            SKIN_THR = {0.11, 0.16, 0.15};
    static final int[] TIPC = {MAGENTA, VIOLET, LIGHTBLUE};
    static final double THR_OUT = 0.62, P = 1.55, LIVE_LO = 0.30, FLANGE = 0.06, DRIP_K = 0.010, DRIP_P = 1.35;
    /**
     * The rivulets of vent beads down a live vent's sides: one per RIV_GAP blocks round the wall, RIV_MIN to RIV_MAX;
     * how far (blocks) one wanders either way; the dithered fringe (blocks) beyond its edge.
     */
    static final double RIV_GAP = 8.0, RIV_WANDER = 1.8, RIV_EDGE = 0.8;
    static final int RIV_MIN = 4, RIV_MAX = 10;
    /**
     * Round 4, inside a live vent: the inner walls' rivulets, one per RIV_GAP_IN blocks round them (RIV_MIN_IN to
     * RIV_MAX_IN); the ring of beads where the walls meet the chamber's floor (the share of the floor's edge, and of
     * the walls' lowest row); a deep throat's beads over its first THROAT_ROWS rows under the sill (at most THROAT_BAR
     * of the noise's band, fading to none).
     */
    static final double RIV_GAP_IN = 6.0, RING_P = 0.7, RING_WALL_P = 0.45, THROAT_BAR = 0.05, FLOOR_THIN = 0.3;
    static final int RIV_MIN_IN = 3, RIV_MAX_IN = 10, THROAT_ROWS = 10;
    /** Round 3's cones: the hot ground's extra bar (round 2: 0.3), and the most of its room's height a cone may fill. */
    static final double DRIP_HOT = 0.15, DRIP_ROOM = 0.45;
    /**
     * The glow mat's fading edge: no cover under MAT_P0 x MAT_LO of light, full cover from MAT_P1 x MAT_LO; the thinner
     * edge mat takes the blocks within MAT_EDGE under the dither. Round 4 (0.19.9) widened the fade from 0.55..1.45 and
     * the edge mat from 0.16, and gave the dither MAT_HASH of a per-block hash (0.65 before) against the clump noise.
     */
    static final double MAT_P0 = 0.3, MAT_P1 = 1.75, MAT_EDGE = 0.22, MAT_HASH = 0.8;
    /**
     * Round 4: the glow mats read the light spread a further MAT_SOFT columns either way along x and z and MAT_SOFT_Y
     * rows up and down (a second box over the heat's), so its edge slopes over 2 x MAT_SOFT + 1 blocks where the heat's
     * own box ends in a step, and up a slope over 2 x MAT_SOFT_Y + 1 rows.
     */
    static final int MAT_SOFT = 4, MAT_SOFT_Y = 2;
    /**
     * Round 4: each floor block reads the mats' light from a column up to MAT_JIT away along x and z (a per-block
     * hash), so even where the light falls fast the edge scatters over at least 2 x MAT_JIT + 1 blocks.
     */
    static final int MAT_JIT = 2;
    /** Round 4: the spread of a per-block hash on the mats' colour noise, so two colours meet in a scatter too. */
    static final double MAT_COL_DITHER = 0.18;
    /** The dripstone skin's dither spread, and the margin over the bar that turns the rock behind a face too. */
    static final double SKIN_DITHER = 0.10, SKIN_DEEP = 0.06;
    static final int BODY = 1, DRIP_GAP = 4, REACH_XZ = 9, REACH_Y = 5, STEM_MIN = 5;
    /**
     * The seep glow's runs: noise length along the shore, and the bar (0.10 before the sunken pools, about 2 shore blocks
     * in 5; their dry edges give about six times the shore, so 0.55 keeps the glow's amount: 1310 blocks against 1213
     * over the 16 tiles at x and z -512..-1).
     */
    static final double SEEP_L = 6.0, SEEP_KEEP = 0.55;
    /** How far (columns) the dressing's neighbour steps reach: the scarp's 16 rounds, the wells' 10, the heat's box. */
    static final int REACH = 24;

    /** The 2D fields the dressing reads over the window, per storey k and column q = i * nz + j. */
    static final class Fields {
        final float[][] floor = new float[K][], wd = new float[K][], mst = new float[K][], vw = new float[K][];
        final int[][] dom = new int[K][], vnear = new int[K][];
        /** The storeys' footprint, and where oil may lie (GardensStoreys.oilFoot). */
        final boolean[][] foot = new boolean[K][], oil = new boolean[K][];
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
    /**
     * The glow mats' softer light ({@link #heat}): integer sums over {@link #softDiv} in the game, the preview's floats
     * in its rules.
     */
    private int[] softI;
    private float[] softF;
    private float softDiv;

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
        // round 3's fields: rivulets (0-1; 2 spare), the mats' clumps (3), the skin (4-6), the vents' feet (7)
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
        int[] gc = glow(pr[0], pr[1]);
        lap("glow");
        counts.put("crown", gc[0]);
        counts.put("inner rivulet", gc[1]);
        counts.put("inner ring", gc[2]);
        counts.put("throat beads", gc[3]);
        counts.put("tip on a vent's top", gc[4]);
        counts.put("rivulet", gc[5]);
        counts.put("rivulet faces", gc[6]);
        columnDist(down, up);
        boolean[] dripKeep = porch.clone();
        for (int b = 0; b < N; b++) dripKeep[b] |= gk[b];
        drip(down, up, dripKeep, ps[5], ps[6]);
        lap("colDist+drip");
        float[] heat = heat();
        lap("heat");
        halo(heat);
        lap("halo");
        mats(ps[7], ps[8], pr[3]);
        softI = null;
        softF = null;
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
        counts.put("oil left open, back to rock", oilHeld());
        counts.put("loose vent blocks final", changedVent(before));
        counts.put("seep glow", seep(ps[11]));
        lap("seep");
        // round 3: the dripstone skin, the pointed dripstone, the vents' blackstone feet (rock for rock: nothing
        // walkable changes but the short stalagmites, which stand clear of every way through)
        boolean[] sk = gateKeep();
        for (int b = 0; b < N; b++) sk[b] |= porch[b];
        counts.put("dripstone skin", skin(sk, pr[4], pr[5], pr[6], ps[5], ps[6]));
        lap("skin");
        columnDist(down, up);
        int[] pc = pointed(down, keepSp);
        counts.put("pointed hanging", pc[0]);
        counts.put("pointed standing", pc[1]);
        lap("colDist+pointed");
        counts.put("vent foot blackstone", ventFeet(pr[7]));
        lap("ventFeet");
        // 0.20.2: the plant life, last, on the finished rock (plants are air to every step before)
        columnDist(down, up);
        int[] pl = plants(heat, down);
        counts.put("small mushrooms", pl[0]);
        counts.put("tube worms", pl[1]);
        counts.put("seep reeds", pl[2]);
        counts.put("glowworm threads", pl[3]);
        counts.put("grass", pl[4]);
        counts.put("tall grass", pl[5]);
        lap("plants");
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
        counts.put("rivulet blocks", by[STREAK]);
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
        for (Vent v : vents) vcol[v.idx] = (byte) ventColour(v, pcol, gv.ox, gv.oz);
    }

    /** One vent's colour (see {@link #ventColours}); {@code pcol} is the plan's field for it (seed + 1008). */
    static int ventColour(Vent v, int[] pcol, int ox, int oz) {
        if ((int) v.t[1] == 3) return LIME;
        int bd = Math.max(0, Math.min(2, (int) v.t[2]));
        double cn = n2(pcol, (v.cx + ox) / 34.0, (v.cz + oz) / 34.0);
        return cn < T1[bd] ? MAGENTA : (cn < T2[bd] ? VIOLET : LIGHTBLUE);
    }

    /** Air of a vent's inside: its den, flue, cracks, doors, adits, hood and throat. */
    static boolean inside(byte vp) {
        return vp == VP_DEN || vp == VP_FLUE || vp == VP_CRACK || vp == VP_DOOR || vp == VP_ADIT || vp == VP_HOOD
                || vp == VP_TUBE;
    }

    /**
     * dress3._glow, rounds 3 and 4: live vents glow in their colour. A vent's top is a dither of CROWN and vent beads
     * (TIP) (Taran, 2026-11-19: "The spire tip and vent crown look good together so dither them on the top of vents"):
     * crown-led where the rim's angular noise is high (where round 2's crowns stood), beads taking over on its
     * shoulders, a few blocks left bare. Below the top, beads run down the outer walls in rivulets (STREAK; "Have the
     * spire tips run down the sides of vents too", card pick: "Rivulets down to the ground"): about one per RIV_GAP
     * blocks round the wall (RIV_MIN to RIV_MAX), each from under the top down to where the vent's wall meets the
     * ground, wandering a block or two either way as it runs down, two blocks wide where it leaves the top, then one or
     * two, swelling into beads here and there, its edges dithered.
     *
     * <p>Round 4 (Taran: "Get rid of den fissure, throat vein, and vent glow. Decorate the inside of vents with vent
     * beads"; card: "Rivulets down the inner walls"): beads run down the chimney's and the chamber's inner walls the
     * same way, about one per RIV_GAP_IN blocks round them (RIV_MIN_IN to RIV_MAX_IN), from under the top down into the
     * sunken chamber, and gather in a ring where the walls meet its floor (RING_P of the floor's edge, RING_WALL_P of
     * the walls' lowest row); under the sill they run on down the bowl's floor, meeting at its bottom. A deep vent's
     * throat keeps a few beads in its first THROAT_ROWS rows under the sill, fewer going down, then goes dark. Dead
     * vents give no light. Returns the counts: crowns, inner rivulet blocks, ring blocks, throat beads, beads on tops,
     * outer rivulet blocks, and the outer faces under the tops (where rivulets may run).
     */
    int[] glow(int[] pst, int[] pstL) {
        int[] cnt = new int[7];
        Geom g = new Geom();
        int[] six = {sx, -sx, 1, -1, SY, -SY};
        int[] five = {sx, -sx, SY, -SY, 1};
        int[] four = {sx, -sx, SY, -SY};
        int xlo = x0 + 1, xhi = x0 + nx - 1, zlo = z0 + 1, zhi = z0 + nz - 1;
        long hsTop = plan.seed + 5181, hsSide = plan.seed + 5182, hsIn = plan.seed + 5183;
        for (Vent v : vents) {
            if (v.t[16] < 0.5f) continue;
            int vv = v.idx + 1;
            byte col = vcol[v.idx];
            int ys = (int) v.t[6];
            double H = v.t[8], R = Math.max(3.0, v.t[9]);
            double top = ys + (double) v.t[26];
            int r = (int) v.t[33] + 1;
            double u12 = u(v.k24, 12);
            // the rivulets: evenly spread round the wall with a jitter, each wandering as it runs down (its angle per
            // row, from the meander field read down its own line) and swelling into beads (the width field)
            double o1 = 200.0 * Hashing.unit(Hashing.mix(hsSide, v.k24, 1, 0));
            double o2 = 200.0 * Hashing.unit(Hashing.mix(hsSide, v.k24, 2, 0));
            boolean deep = (int) v.t[1] == 3;
            int icx = ic(v.cx) + gv.ox, icz = ic(v.cz) + gv.oz;
            int ya = Math.max(1, Math.min(Math.min(ys - 2, (int) v.t[7]), ys - GardensVents.bowlRows(v) - 1));
            int yb = Math.min(SY - 1, (int) (ys + H + 4));
            int nr = Math.max(RIV_MIN, Math.min(RIV_MAX, (int) Math.round(2.0 * Math.PI * R / RIV_GAP)));
            double turn = 2.0 * Math.PI * Hashing.unit(Hashing.mix(hsSide, v.k24, 3, 0));
            double[][] rivA = new double[nr][yb - ya], rivW = new double[nr][yb - ya];
            for (int k = 0; k < nr; k++) {
                double a0 = turn + 2.0 * Math.PI * (k + 0.35 * (Hashing.unit(Hashing.mix(hsSide, v.k24, 4, k)) - 0.5)) / nr;
                for (int y = ya; y < yb; y++) {
                    double dn = top - y;      // rows under the top
                    double wr = Math.max(3.0, R + 1.5);  // about the outer wall's radius, for blocks to radians
                    rivA[k][y - ya] = a0 + (RIV_WANDER / wr) * n2(pst, dn / 7.0 + 11.0 * k + o1, o2 + 5.0 * k);
                    rivW[k][y - ya] = 0.5 + 0.45 * (1.0 - ramp(dn, 1.0, 6.0))
                            + 0.4 * ramp(n2(pstL, dn / 2.6 + 7.0 * k + o2, o1 - 3.0 * k), 0.25, 0.6);
                }
            }
            // the inner rivulets, on lines of their own round the inner wall (its radius at the sill, about)
            double rinB = Math.max(1.5, GardensVents.profile((int) v.t[1], v.t[9], 0.0, v.t[43], v.t[44], v.t[45]) - v.t[13]);
            int ni = Math.max(RIV_MIN_IN, Math.min(RIV_MAX_IN, (int) Math.round(2.0 * Math.PI * rinB / RIV_GAP_IN)));
            double turnIn = 2.0 * Math.PI * Hashing.unit(Hashing.mix(hsIn, v.k24, 3, 0));
            double[][] inA = new double[ni][yb - ya], inW = new double[ni][yb - ya];
            for (int k = 0; k < ni; k++) {
                double a0 = turnIn + 2.0 * Math.PI * (k + 0.35 * (Hashing.unit(Hashing.mix(hsIn, v.k24, 4, k)) - 0.5)) / ni;
                for (int y = ya; y < yb; y++) {
                    double dn = top - y;
                    double wr = Math.max(2.0, rinB);
                    inA[k][y - ya] = a0 + (RIV_WANDER / wr) * n2(pst, dn / 7.0 + 11.0 * k + o2 + 41.0, o1 + 5.0 * k);
                    inW[k][y - ya] = 0.5 + 0.45 * (1.0 - ramp(dn, 1.0, 6.0))
                            + 0.4 * ramp(n2(pstL, dn / 2.6 + 7.0 * k + o1 + 29.0, o2 - 3.0 * k), 0.25, 0.6);
                }
            }
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
                        // the inside: the chimney's and the chamber's walls and the chamber's floor
                        if (y < top - 1.0 && insideFace(b, vv, six)) {
                            boolean floor = insideAir(b + 1, vv);
                            boolean on = false, ring = false;
                            double h = Hashing.unit(Hashing.mix(hsIn, x, y, z));
                            if (floor) {
                                // the floor's edge, where a wall rises beside it (two high: not a step of the bowl)
                                for (int d : four)
                                    if (!open(mat[b + d + 1]) && !open(mat[b + d + 2])) ring = true;
                                ring &= h < RING_P;
                                // under the sill the inner rivulets run on down the bowl's floor and meet at its bottom
                                if (!ring && y < ys - 1) {
                                    // thinner than on the walls and broken here and there, so they read as beads
                                    // trickling down rather than spokes
                                    double best = 9.0;
                                    for (int k = 0; k < ni; k++)
                                        best = Math.min(best, Math.abs(wrap(g.th - inA[k][y - ya])) * Math.max(1.0, g.rhoE) - inW[k][y - ya] + FLOOR_THIN);
                                    on = (best < 0.0 && h < 0.85) || (best < RIV_EDGE && h < 0.3 * (1.0 - best / RIV_EDGE));
                                }
                            } else {
                                boolean wall = false, foot = false;
                                for (int d : four) {
                                    int o = b + d;
                                    if (insideAir(o, vv)) {
                                        wall = true;
                                        if (!open(mat[o - 1])) foot = true;
                                    }
                                }
                                if (wall) {
                                    ring = foot && h < RING_WALL_P;
                                    double best = 9.0;
                                    for (int k = 0; k < ni; k++)
                                        best = Math.min(best, Math.abs(wrap(g.th - inA[k][y - ya])) * Math.max(1.0, g.rhoE) - inW[k][y - ya]);
                                    on = best < 0.0 || (best < RIV_EDGE && h < 0.4 * (1.0 - best / RIV_EDGE));
                                }
                            }
                            if (on || ring) {
                                mat[b] = STREAK;
                                colour[b] = col;
                                cnt[on ? 1 : 2]++;
                                continue;
                            }
                        }
                        // rivulets down the outer walls, from under the top to the ground
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
                        cnt[6]++;
                        // the nearest rivulet, by the blocks along the wall from its line (half-width rivW)
                        double best = 9.0;
                        for (int k = 0; k < nr; k++)
                            best = Math.min(best, Math.abs(wrap(g.th - rivA[k][y - ya])) * Math.max(1.0, g.rhoE) - rivW[k][y - ya]);
                        boolean on = best < 0.0 || (best < RIV_EDGE && Hashing.unit(Hashing.mix(hsSide, x, y, z)) < 0.4 * (1.0 - best / RIV_EDGE));
                        if (on) {
                            mat[b] = STREAK;
                            colour[b] = col;
                            cnt[5]++;
                        }
                    }
                }
            if (deep) {
                // a few beads on the throat's walls just under the sill, fewer going down, then none
                double Rh = v.t[36];
                int rr = (int) (Rh + 9);
                int yt = Math.min(SY - 1, ys + 8), yl = Math.max(1, ys - THROAT_ROWS);
                for (int x = Math.max(xlo, icx - rr); x < Math.min(xhi, icx + rr + 1); x++)
                    for (int z = Math.max(zlo, icz - rr); z < Math.min(zhi, icz + rr + 1); z++) {
                        int base = ((x - x0) * nz + (z - z0)) * SY;
                        for (int y = yl; y < yt; y++) {
                            int b = base + y;
                            if (mat[b] != ROCK && mat[b] != SEALROCK) continue;
                            boolean nb = false;
                            for (int d : six) {
                                int o = b + d;
                                if (vpart[o] == VP_TUBE && vid[o] == vv && mat[o] == AIR) nb = true;
                            }
                            double bar = THROAT_BAR * (1.0 - ramp(ys - y, 0.0, THROAT_ROWS));
                            if (nb && Math.abs(n3(gv.pglow, x / 3.0, y / 4.0, z / 3.0)) < bar) {
                                mat[b] = STREAK;
                                colour[b] = col;
                                cnt[3]++;
                            }
                        }
                    }
            }
        }
        return cnt;
    }

    /** Whether a block is the vent's own inside air: its chimney or its chamber (the sunken bowl too). */
    boolean insideAir(int o, int vv) {
        return vid[o] == vv && (vpart[o] == VP_DEN || vpart[o] == VP_FLUE) && mat[o] == AIR;
    }

    /** Whether a block of the vent's rock faces its own inside air. */
    boolean insideFace(int b, int vv, int[] six) {
        for (int d : six) if (insideAir(b + d, vv)) return true;
        return false;
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
     * Round 4 keeps a softer copy for the glow mats ({@link #matLight}): the same box mean spread again by a box of
     * MAT_SOFT either way along x and z and MAT_SOFT_Y up and down. One box alone gives a lone bright spire a square of
     * even light that ends in a step at its reach, and its mat ended there in a hard straight line (Taran, 2026-11-19:
     * "Have more dithering for glow mats"); spread twice, the light slopes down over nine blocks instead and the dither
     * has room to scatter.
     */
    float[] heat() {
        int N = mat.length;
        int bx = 2 * REACH_XZ + 1, by = 2 * REACH_Y + 1;
        if (plan.patch) {
            // the preview's scipy uniform_filter, float32 between the passes, axis by axis
            float[] h = new float[N];
            for (int b = 0; b < N; b++) {
                byte m = mat[b];
                h[b] = (m == TIP || m == LIVE || m == CROWN) ? 1f : (m == STREAK ? 0.3f : 0f);
            }
            uniform1d(h, 0, bx);
            uniform1d(h, 1, by);
            uniform1d(h, 2, bx);
            softF = h.clone();
            uniform1d(softF, 0, 2 * MAT_SOFT + 1);
            uniform1d(softF, 1, 2 * MAT_SOFT_Y + 1);
            uniform1d(softF, 2, 2 * MAT_SOFT + 1);
            return h;
        }
        // in the game, exact integer sums (tenths), so a tile's light never depends on where its window starts
        int[] a = new int[N], t = new int[N];
        for (int b = 0; b < N; b++) {
            byte m = mat[b];
            a[b] = (m == TIP || m == LIVE || m == CROWN) ? 10 : (m == STREAK ? 3 : 0);
        }
        boxSum(a, t, 0, REACH_XZ);
        boxSum(t, a, 1, REACH_Y);
        boxSum(a, t, 2, REACH_XZ);
        float[] h = new float[N];
        float div = bx * by * bx * 10f;
        for (int b = 0; b < N; b++) h[b] = t[b] / div;
        // the mats' softer light, still exact integers (at most 10 x 19 x 11 x 19 x 9 x 5 x 9, about 16 million)
        boxSum(t, a, 0, MAT_SOFT);
        boxSum(a, t, 1, MAT_SOFT_Y);
        boxSum(t, a, 2, MAT_SOFT);
        softI = a;
        softDiv = div * (2 * MAT_SOFT + 1) * (2 * MAT_SOFT_Y + 1) * (2 * MAT_SOFT + 1);
        return h;
    }

    /** The glow mats' softer light at block b, before the halo. */
    float matLight(int b) { return softF != null ? softF[b] : softI[b] / softDiv; }

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
                    if (Double.isNaN(fac[k])) fac[k] = haloFac(q, k, lx, lz);
                    heat[b] = (float) (heat[b] * fac[k]);      // exact when fac is 1
                }
            }
    }

    /** The halo's factor for storey k at column q (local centre lx, lz): 1 away from live vents. */
    double haloFac(int q, int k, double lx, double lz) {
        int vn = f.vnear[k][q];
        if (vn < 0 || vents.get(vn).t[16] < 0.5) return 1.0;
        Vent v = vents.get(vn);
        float wv = f.vw[k][q];
        double th = Math.atan2(lz - v.cz, lx - v.cx);
        double s1 = 100.0 * u(v.k24, 14), s2 = 100.0 * u(v.k24, 15);
        double m = ramp(n2(gv.pvn, 0.9 * Math.cos(th) + s2, 0.9 * Math.sin(th) - s1), -0.15, 0.25);
        return 1.0 - wv + wv * m;
    }

    /**
     * dress3._mats3, round 3: glow mats on bare floor where the light reaches, by band; on a live vent's skirt only on
     * its lit side. The cover fades, from none at MAT_P0 x MAT_LO of light to full at MAT_P1 x MAT_LO, each block taken
     * where the cover beats a clumpy per-block dither (a world hash and a fine noise), so a patch thins out over a few
     * blocks in small clumps instead of ending at a hard line (Taran, 2026-11-19: "Dither the glow mat so there's no
     * hard boundary"); the thinner edge mat takes the blocks just under the dither. Round 4 (Taran: "Have more dithering
     * for glow mats") reads the softer light ({@link #matLight}, with the halo's lean), spreads the fade over about twice
     * the light and leans the dither on the hash, so the edge breaks into scattered single blocks and holes reaching
     * further in and out, and a lone spire's mat no longer ends in a straight line; where two colours meet they mix
     * over a few blocks too.
     */
    void mats(int[] pm, int[] pcol, int[] pclump) {
        long hs = plan.seed + 5191;
        double[] fac = new double[K];
        for (int i = 0; i < nx; i++)
            for (int j = 0; j < nz; j++) {
                int q = i * nz + j, wx = x0 + i, wz = z0 + j, base = q * SY;
                double jit = 0.5 * n2(pm, wx / 3.5, wz / 3.5);
                double cn = n2(pcol, wx / 34.0, wz / 34.0);
                double lx = (wx - gv.ox) + 0.5, lz = (wz - gv.oz) + 0.5;
                Arrays.fill(fac, Double.NaN);
                for (int y = 1; y < SY - 2; y++) {
                    int b = base + y;
                    if (mat[b] != ROCK || mat[b + 1] != AIR) continue;
                    byte vp = vpart[b];
                    int k = storeyAt(q, y + 1), bd = f.dom[k][q];
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
                    int ji = Math.max(0, Math.min(nx - 1, i + (int) (Hashing.unit(Hashing.mix(hs + 1, wx, y, wz)) * (2 * MAT_JIT + 1)) - MAT_JIT));
                    int jj = Math.max(0, Math.min(nz - 1, j + (int) (Hashing.unit(Hashing.mix(hs + 2, wx, y, wz)) * (2 * MAT_JIT + 1)) - MAT_JIT));
                    double lt = matLight((ji * nz + jj) * SY + y + 1);
                    if (lt <= 0.0) continue;
                    if (Double.isNaN(fac[k])) fac[k] = haloFac(q, k, lx, lz);
                    lt *= fac[k];
                    double p = side * ramp(lt * (1.0 + jit) / MAT_LO[bd], MAT_P0, MAT_P1);
                    if (p <= 0.0) continue;
                    double d = MAT_HASH * Hashing.unit(Hashing.mix(hs, wx, y, wz)) + (1.0 - MAT_HASH) * (0.5 + 0.5 * n2(pclump, wx / 1.7, wz / 1.7));
                    if (p > d) mat[b] = MAT;
                    else if (p > d - MAT_EDGE) mat[b] = MATE;
                    else continue;
                    double cd = cn + MAT_COL_DITHER * (Hashing.unit(Hashing.mix(hs + 3, wx, y, wz)) - 0.5);
                    colour[b] = (byte) (cd < T1[bd] ? 0 : (cd < T2[bd] ? 1 : 3));
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
     * flowstone; floors lean on the cones' field too, under the clusters. Each band's bar is set so that about a third
     * of its cave surfaces turn (card pick, 2026-11-19: "About a third everywhere", the hot Vent Domes too; with the
     * dripstone's cones, 33.5 %, 33.3 % and 33.1 % of the surfaces over 256 x 256 at seed 8675309). A per-block
     * hash dithers the edge, and where a face is well over its bar the rock behind turns too (two deep). Never on a
     * vent's own blocks or its skirt, in porches or gates. Returns blocks.
     */
    int skin(boolean[] keep, int[] pa, int[] pb, int[] pw, int[] pd, int[] pd2) {
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
                    double m = sc - SKIN_THR[bd] + SKIN_DITHER * (Hashing.unit(Hashing.mix(hs, wx, y, wz)) - 0.5);
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

    // ================================================================================================ 0.20.2 plants

    /**
     * Plant life (0.20.2; Taran, 2026-11-20: "Can you add some atmospheric grass and plant life to the Deep Gardens?"),
     * last of all, on the finished rock, in Taran's card picks: small neon mushrooms crowding round each giant's stem
     * ("Clusters round their stems"), tube worms at the live vents' feet in the vent's colour, seep reeds along the oil's
     * shores, glowworm threads from the dark halls' ceilings ("Light blue, in the dark halls"), then the filament grass on
     * the glow mats ("On the mats, spilling past"; "Meadows, thick in the middle"; "The mat's colour under it"). Each
     * takes only a floor with open air over it (outside every vent) that no plant has yet. Every choice reads world
     * hashes and noise and the blocks within a few columns, so tiles agree at their seams. Returns the blocks of each:
     * mushrooms, worms, reeds, threads, grass and tall grass.
     */
    int[] plants(float[] heat, short[] down) {
        int[] out = new int[6];
        int[][] pp = new int[5][];
        for (int i = 0; i < 5; i++) pp[i] = plan.perm(plan.seed + 5301 + i);
        out[0] = smallMushrooms();
        out[1] = tubeWorms();
        out[2] = reeds(pp[1]);
        out[3] = threads(heat, down, pp[2]);
        int[] g = grass(pp[3], pp[4]);
        out[4] = g[0];
        out[5] = g[1];
        return out;
    }

    /** Whether a plant may stand on block b: whole rock with open air over it, outside every vent. */
    boolean plantFloor(int b) {
        byte m = mat[b];
        boolean rock = m == ROCK || m == TALUS || m == MAT || m == MATE || m == SKIN || m == SEEP || m == VBASE;
        return rock && mat[b + 1] == AIR && vpart[b + 1] == 0 && (vpart[b] == 0 || vpart[b] == VP_SKIRT);
    }

    /** A plant's block, its colour and its extra bit. */
    void plant(int b, byte code, int colourCode, boolean bit) {
        mat[b] = code;
        colour[b] = (byte) (colourCode | (bit ? PLANT_BIT : 0));
    }

    /** Small mushrooms round a giant's stem, within this many columns of its foot, at most this share of floors at 1. */
    static final double SHROOM_R = 4.2, SHROOM_P = 0.7;

    /**
     * Small neon mushrooms in each giant's colour (its cap's) round its stem's foot: most likely right by the stem, few by
     * {@link #SHROOM_R}; those within two columns crowd (three to a block) half the time. Returns blocks.
     */
    int smallMushrooms() {
        long hs = plan.seed + 5311;
        Map<Integer, double[]> best = new java.util.HashMap<>();       // floor block -> (chance, colour, crowded)
        int r = (int) Math.ceil(SHROOM_R);
        for (int i = r; i < nx - r; i++)
            for (int j = r; j < nz - r; j++) {
                int base = (i * nz + j) * SY;
                for (int y = SEAL + 2; y < SY - 3; y++) {
                    int b = base + y;
                    if (mat[b] != STEM || mat[b - 1] == STEM || mat[b - 1] == AIR) continue;
                    int ci = capColour(i, j, y);
                    if (ci < 0) continue;
                    for (int a = -r; a <= r; a++)
                        for (int c = -r; c <= r; c++) {
                            double d = Math.sqrt(a * a + c * c);
                            if (d > SHROOM_R || d < 0.9) continue;
                            int cb = ((i + a) * nz + j + c) * SY;
                            for (int yy = y - 2; yy <= y + 1; yy++) {
                                int f = cb + yy - 1;                  // the floor block under the spot at row yy
                                if (yy < SEAL + 2 || !plantFloor(f)) continue;
                                double pr = SHROOM_P * Math.pow(1.0 - (d - 0.9) / (SHROOM_R - 0.9 + 0.5), 1.5);
                                double[] cur = best.get(f);
                                if (cur == null || cur[0] < pr) best.put(f, new double[]{pr, ci, d < 2.2 ? 1 : 0});
                            }
                        }
                }
            }
        int n = 0;
        Integer[] keys = best.keySet().toArray(new Integer[0]);
        Arrays.sort(keys);
        for (int f : keys) {
            double[] v = best.get(f);
            int q = f / SY, y = f - q * SY, wx = x0 + q / nz, wz = z0 + q % nz;
            double h = Hashing.unit(Hashing.mix(hs, wx, y, wz));
            if (h >= v[0] || !plantFloor(f)) continue;
            plant(f + 1, SHROOM, (int) v[1], v[2] > 0 && Hashing.unit(Hashing.mix(hs + 1, wx, y, wz)) < 0.5);
            n++;
        }
        return n;
    }

    /** The colour of the giant whose stem stands at (i, j) from row y up: its cap's, over it or over a stem beside it. */
    int capColour(int i, int j, int y) {
        int[][] cols = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : cols) {
            int ii = i + d[0], jj = j + d[1];
            if (ii < 0 || jj < 0 || ii >= nx || jj >= nz) continue;
            int cb = (ii * nz + jj) * SY;
            for (int yy = y; yy < Math.min(SY, y + 24); yy++) {
                byte m = mat[cb + yy];
                if (m == CAP) return colour[cb + yy] & 3;
                if (m != STEM && (d[0] != 0 || d[1] != 0 || yy > y)) break;
            }
        }
        return -1;
    }

    /** Tube worms within this many steps (through the open air, six ways) of a live vent's wall. */
    static final int WORM_STEPS = 4;
    /** A floor spot's share by its steps from the wall (1 to {@link #WORM_STEPS}): thick at the foot, thin further out. */
    static final double[] WORM_FLOOR = {0.0, 1.0, 0.85, 0.55, 0.25};
    /**
     * Up a vent's side, as a share of its height over its sill: worms everywhere under the first, thinning out to none
     * at the second (Taran, 2026-11-23: "mostly on the lower half of vents").
     */
    static final double WORM_HIGH = 0.38, WORM_TOP = 0.6;
    /**
     * The clumps (card pick: "Thick clumps, tallest in the middle"): one middle in each cell of a lattice this big
     * (columns, rows), somewhere in it by a hash, each a ball of its own radius; worms grow where a ball meets the vent's
     * side or the floor at its foot. A middle near the rock makes a big clump, one further off a small tuft.
     */
    static final double CLUMP_CELL = 6.0, CLUMP_CELL_Y = 5.0, CLUMP_R_MIN = 1.8, CLUMP_R_MAX = 3.0;
    /** A clump's middle, as a share of its radius, is thick (eight or nine tubes to a block); its edge thin. */
    static final double CLUMP_THICK = 0.62;

    /**
     * Tube worms round each live vent (0.20.6; Taran: "They should also be in clusters and mostly on the lower half of
     * vents"; card pick: "Lower half, on ledges and walls"), in the vent's colour: in clumps ({@link #clump}) on its
     * ledges and its steep walls (the wall's worms, their tubes curving up; world.gen.GardensBlocks sets them on the
     * wall beside them) on its lower half ({@link #WORM_HIGH}), and on the floor round its foot. Each spot is open air
     * outside every vent with open air over it (for the plumes). Returns blocks.
     */
    int tubeWorms() {
        long hs = plan.seed + 5321, hc = plan.seed + 5322;
        int n = 0;
        int[] four = {sx, -sx, SY, -SY};
        for (Vent v : vents) {
            if (!v.live()) continue;
            int vv = v.idx + 1, ys = (int) v.t[6];
            byte col = vcol[v.idx];
            double htop = Math.max(1.0, v.t[26]);
            int r = (int) v.t[33] + WORM_STEPS + 2;
            int icx = ic(v.cx) + gv.ox - x0, icz = ic(v.cz) + gv.oz - z0;
            int i0 = Math.max(1, icx - r), i1 = Math.min(nx - 2, icx + r), j0 = Math.max(1, icz - r), j1 = Math.min(nz - 2, icz + r);
            int ya = Math.max(SEAL + 2, Math.min(ys, (int) v.t[7]) - 3), yb = Math.min(SY - 3, (int) Math.ceil(ys + WORM_TOP * htop) + 1);
            if (i0 > i1 || j0 > j1 || ya > yb) continue;
            int bz = j1 - j0 + 1, by = yb - ya + 1;
            byte[] steps = new byte[(i1 - i0 + 1) * bz * by];
            IntList cur = new IntList(), nxt = new IntList();
            // the open air touching the vent's own wall is a step from it; then on out through the open air
            for (int i = i0; i <= i1; i++)
                for (int j = j0; j <= j1; j++)
                    for (int y = ya; y <= yb; y++) {
                        int b = (i * nz + j) * SY + y;
                        if (vid[b] != vv || !GardensVentField.isWallPart(vpart[b]) || !GardensField.holds(mat[b])) continue;
                        for (int e = 0; e < 6; e++) {
                            int ii = i + (e == 0 ? 1 : e == 1 ? -1 : 0), jj = j + (e == 2 ? 1 : e == 3 ? -1 : 0), yy = y + (e == 4 ? 1 : e == 5 ? -1 : 0);
                            if (ii < i0 || ii > i1 || jj < j0 || jj > j1 || yy < ya || yy > yb) continue;
                            int l = ((ii - i0) * bz + (jj - j0)) * by + (yy - ya);
                            if (steps[l] != 0 || !outsideAir((ii * nz + jj) * SY + yy)) continue;
                            steps[l] = 1;
                            cur.add(l);
                        }
                    }
            for (int d = 2; d <= WORM_STEPS && cur.n > 0; d++) {
                nxt.n = 0;
                for (int c = 0; c < cur.n; c++) {
                    int l = cur.a[c], y = l % by + ya, q = l / by, jj0 = q % bz + j0, ii0 = q / bz + i0;
                    for (int e = 0; e < 6; e++) {
                        int ii = ii0 + (e == 0 ? 1 : e == 1 ? -1 : 0), jj = jj0 + (e == 2 ? 1 : e == 3 ? -1 : 0), yy = y + (e == 4 ? 1 : e == 5 ? -1 : 0);
                        if (ii < i0 || ii > i1 || jj < j0 || jj > j1 || yy < ya || yy > yb) continue;
                        int o = ((ii - i0) * bz + (jj - j0)) * by + (yy - ya);
                        if (steps[o] != 0 || !outsideAir((ii * nz + jj) * SY + yy)) continue;
                        steps[o] = (byte) d;
                        nxt.add(o);
                    }
                }
                IntList t = cur;
                cur = nxt;
                nxt = t;
            }
            for (int i = i0; i <= i1; i++)
                for (int j = j0; j <= j1; j++)
                    for (int y = Math.max(ya, SEAL + 3); y <= yb; y++) {
                        int d = steps[((i - i0) * bz + (j - j0)) * by + (y - ya)];
                        if (d == 0) continue;
                        int b = (i * nz + j) * SY + y;
                        // room for the plumes over it, and never on another plant (worms on worms)
                        if (mat[b] != AIR || mat[b + 1] != AIR || vpart[b + 1] != 0 || GardensField.plant(mat[b - 1])) continue;
                        double share = 1.0 - ramp((y - ys) / htop, WORM_HIGH, WORM_TOP);
                        if (share <= 0.0) continue;
                        boolean ledge = vid[b - 1] == vv && GardensVentField.isWallPart(vpart[b - 1]) && GardensField.holds(mat[b - 1]);
                        if (!ledge) {
                            if (GardensField.holds(mat[b - 1])) {
                                // the floor round the foot: whole rock outside the vents
                                if (!plantFloor(b - 1)) continue;
                                share *= WORM_FLOOR[d];
                            } else {
                                // the steep wall: a block of the vent's own beside it to hold to
                                boolean wall = false;
                                for (int o : four) wall |= vid[b + o] == vv && GardensVentField.isWallPart(vpart[b + o]) && GardensField.holds(mat[b + o]);
                                if (!wall) continue;
                            }
                        }
                        int wx = x0 + i, wz = z0 + j;
                        double f = clump(hc, wx, y, wz);
                        if (f >= 1.0) continue;
                        double pr = share * (f < 0.55 ? 0.95 : 0.95 - 0.55 * (f - 0.55) / 0.45);
                        if (Hashing.unit(Hashing.mix(hs, wx, y, wz)) >= pr) continue;
                        plant(b, WORM, col, f < CLUMP_THICK);
                        n++;
                    }
        }
        return n;
    }

    /** Open air outside every vent (not its throat or bowl). */
    private boolean outsideAir(int b) { return mat[b] == AIR && vpart[b] == 0; }

    /**
     * How deep block (x, y, z) lies in the nearest tube worm clump: its distance from the clump's middle over the clump's
     * radius, the least of the clumps of the 27 cells round it (1 or more: in none). World hashes only, so tiles agree.
     */
    static double clump(long hc, int x, int y, int z) {
        int cx = (int) Math.floor(x / CLUMP_CELL), cy = (int) Math.floor(y / CLUMP_CELL_Y), cz = (int) Math.floor(z / CLUMP_CELL);
        double best = 9.0, px = x + 0.5, py = y + 0.5, pz = z + 0.5;
        for (int a = -1; a <= 1; a++)
            for (int e = -1; e <= 1; e++)
                for (int c = -1; c <= 1; c++) {
                    long h = Hashing.mix(hc, cx + a, cy + e, cz + c);
                    double mx = (cx + a + Hashing.unit(Hashing.mix(h, 1, 0, 0))) * CLUMP_CELL - px;
                    double my = (cy + e + Hashing.unit(Hashing.mix(h, 2, 0, 0))) * CLUMP_CELL_Y - py;
                    double mz = (cz + c + Hashing.unit(Hashing.mix(h, 3, 0, 0))) * CLUMP_CELL - pz;
                    double rc = CLUMP_R_MIN + (CLUMP_R_MAX - CLUMP_R_MIN) * Hashing.unit(Hashing.mix(h, 4, 0, 0));
                    best = Math.min(best, Math.sqrt(mx * mx + my * my + mz * mz) / rc);
                }
        return best;
    }

    /**
     * Seep reeds along the oil's shores: on a dry floor block beside the oil (eight ways, at the oil's row), and more
     * thinly a block further out, in stands (a noise); a little over half of them two blocks tall where there is room.
     * Light blue. Returns blocks.
     */
    int reeds(int[] pr) {
        long hs = plan.seed + 5331;
        int n = 0;
        for (int i = 2; i < nx - 2; i++)
            for (int j = 2; j < nz - 2; j++) {
                int q = i * nz + j, wx = x0 + i, wz = z0 + j, base = q * SY;
                double stand = -1;
                for (int y = SEAL + 2; y < SY - 4; y++) {
                    int b = base + y;
                    byte m = mat[b];
                    if ((m != ROCK && m != TALUS && m != SEEP && m != SKIN) || !plantFloor(b)) continue;
                    int near = 9;
                    for (int a = -2; a <= 2; a++)
                        for (int c = -2; c <= 2; c++)
                            if (mat[b + a * sx + c * SY] == OIL) near = Math.min(near, Math.max(Math.abs(a), Math.abs(c)));
                    if (near > 2) continue;
                    if (stand < -0.5) stand = ramp(n2(pr, wx / 4.0, wz / 4.0), -0.35, 0.2);
                    double p = (near == 1 ? 0.62 : 0.22) * stand;
                    if (Hashing.unit(Hashing.mix(hs, wx, y, wz)) >= p) continue;
                    boolean tall = Hashing.unit(Hashing.mix(hs + 1, wx, y, wz)) < 0.55 && mat[b + 2] == AIR && mat[b + 3] == AIR
                            && vpart[b + 2] == 0;
                    plant(b + 1, REED, LIGHTBLUE, false);
                    if (tall) plant(b + 2, REED, LIGHTBLUE, false);
                    n += tall ? 2 : 1;
                }
            }
        return n;
    }

    /** The darkest a ceiling's air may be lit for threads (of the light the glow mats need, MAT_LO); room they keep. */
    static final double THREAD_DARK = 0.2;
    static final int THREAD_ROOM = 6;
    /** The share of a drift's ceiling blocks a thread hangs from, at its thickest (0.22 first: more thread than grass). */
    static final double THREAD_P = 0.12;

    /**
     * Glowworm threads from the ceilings of the dark halls: under whole rock (not on a vent) where the vents' light is
     * under {@link #THREAD_DARK} of what the mats need, in loose drifts (a slow noise), one to six blocks long, mostly
     * short, always {@link #THREAD_ROOM} rows clear of the floor, so they never hang in anyone's way. Light blue.
     * Returns blocks.
     */
    int threads(float[] heat, short[] down, int[] pt) {
        long hs = plan.seed + 5341;
        int n = 0;
        for (int i = 1; i < nx - 1; i++)
            for (int j = 1; j < nz - 1; j++) {
                int q = i * nz + j, wx = x0 + i, wz = z0 + j, base = q * SY;
                double drift = -9;
                for (int y = SY - SEAL - 1; y > SEAL + THREAD_ROOM + 2; y--) {
                    int c = base + y;
                    byte m = mat[c];
                    if (m != ROCK && m != SKIN && m != DRIP && m != TALUS && m != SEALROCK) continue;
                    if (mat[c - 1] != AIR || vpart[c - 1] != 0 || vpart[c] != 0) continue;
                    int gap = down[c - 1] + 1;
                    if (gap < THREAD_ROOM + 2) continue;
                    int k = storeyAt(q, y - gap), bd = f.dom[k][q];
                    if (heat[c - 1] >= THREAD_DARK * MAT_LO[bd] || heat[c - gap] >= THREAD_DARK * MAT_LO[bd]) continue;
                    if (drift < -5) drift = ramp(n2(pt, wx / 11.0, wz / 11.0), 0.0, 0.45);
                    double p = THREAD_P * drift;
                    if (Hashing.unit(Hashing.mix(hs, wx, y, wz)) >= p) continue;
                    double h = Hashing.unit(Hashing.mix(hs + 1, wx, y, wz));
                    int len = Math.min(1 + (int) (h * h * 6.0), gap - THREAD_ROOM);
                    for (int e = 1; e <= len; e++) plant(c - e, THREAD, LIGHTBLUE, false);
                    n += len;
                }
            }
        return n;
    }

    /** The glow mats' cover a floor block reads, over this many columns either way and two rows up and down. */
    static final int COVER_R = 3;

    /**
     * Filament grass on the glow mats and a little past them, in meadows: each floor block reads the mats' cover round it
     * (the share of the columns within {@link #COVER_R} with a mat near its row, the thin edge mat counting half). On a
     * mat the grass grows nearly everywhere the cover is high, a meadow's heart; towards a patch's edge it thins, with
     * bare spots where the mat shows (a fine noise), and on the bare rock past it only stray tufts and a little grass.
     * Its height follows the cover with drifts (a slow noise): tall grass in the hearts, the grass, then tufts. It takes
     * the colour of the mat under it, or of most mats round it, but within LIME_REACH of a live deep vent (the lime ones,
     * as the stilt mantises' herds) it is lime. Returns blocks of grass and of tall grass (both halves).
     */
    int[] grass(int[] pdrift, int[] pbare) {
        long hs = plan.seed + 5351;
        // the columns with any mat, summed, so a block with no mat near is passed over at once
        int W = nx + 1;
        int[] sum = new int[W * (nz + 1)];
        for (int i = 0; i < nx; i++)
            for (int j = 0; j < nz; j++) {
                int base = (i * nz + j) * SY, has = 0;
                for (int y = 0; y < SY; y++) if (mat[base + y] == MAT || mat[base + y] == MATE) { has = 1; break; }
                sum[(i + 1) * (nz + 1) + j + 1] = has + sum[i * (nz + 1) + j + 1] + sum[(i + 1) * (nz + 1) + j] - sum[i * (nz + 1) + j];
            }
        List<double[]> limes = new ArrayList<>();
        for (Vent v : vents) if (v.form() == 3 && v.live()) limes.add(new double[]{v.cx + gv.ox, v.cz + gv.oz, v.site.ys, v.site.H});
        int R = COVER_R, area = (2 * R + 1) * (2 * R + 1), grass = 0, tall = 0;
        double[] votes = new double[4];
        for (int i = R; i < nx - R; i++)
            for (int j = R; j < nz - R; j++) {
                int nb = sum[(i + R + 1) * (nz + 1) + j + R + 1] - sum[(i - R) * (nz + 1) + j + R + 1] - sum[(i + R + 1) * (nz + 1) + j - R]
                        + sum[(i - R) * (nz + 1) + j - R];
                if (nb == 0) continue;
                int q = i * nz + j, wx = x0 + i, wz = z0 + j, base = q * SY;
                double drift = Double.NaN, bare = Double.NaN;
                for (int y = SEAL + 3; y < SY - 4; y++) {
                    int b = base + y;
                    if (!plantFloor(b)) continue;
                    byte m = mat[b];
                    double cover = 0;
                    Arrays.fill(votes, 0);
                    for (int a = -R; a <= R; a++)
                        for (int c = -R; c <= R; c++) {
                            int cb = ((i + a) * nz + j + c) * SY;
                            for (int yy = y - 2; yy <= y + 2; yy++) {
                                byte mm = mat[cb + yy];
                                if (mm != MAT && mm != MATE) continue;
                                double w = mm == MAT ? 1.0 : 0.5;
                                cover += w;
                                votes[colour[cb + yy] & 3] += w;
                                break;
                            }
                        }
                    if (cover <= 0) continue;
                    double g = cover / area;
                    if (Double.isNaN(drift)) {
                        drift = n2(pdrift, wx / 7.0, wz / 7.0);
                        bare = ramp(n2(pbare, wx / 3.2, wz / 3.2), 0.2, 0.55);
                    }
                    double p = m == MAT ? ramp(g, 0.08, 0.5) : (m == MATE ? 0.8 * ramp(g, 0.15, 0.6) : 0.45 * ramp(g, 0.2, 0.65));
                    p *= 1.0 - 0.85 * bare * (1.0 - ramp(g, 0.6, 0.95));
                    if (Hashing.unit(Hashing.mix(hs, wx, y, wz)) >= p) continue;
                    double h = Hashing.unit(Hashing.mix(hs + 1, wx, y, wz));
                    double score = g + 0.3 * drift + 0.12 * (h - 0.5) - (m == MAT ? 0.0 : 0.25);
                    int ci;
                    if (m == MAT || m == MATE) ci = colour[b] & 3;
                    else {
                        ci = 0;
                        for (int c = 1; c < 4; c++) if (votes[c] > votes[ci]) ci = c;
                    }
                    for (double[] v : limes) {
                        double dx = v[0] - (wx + 0.5), dz = v[1] - (wz + 0.5);
                        double dy = Math.max(0.0, Math.max(v[2] - (y + 1), (y + 1) - (v[2] + v[3])));
                        double reach = LIME_REACH - 4.0 + 8.0 * Hashing.unit(Hashing.mix(hs + 2, wx, y, wz));
                        if (dx * dx + dz * dz + dy * dy <= reach * reach) { ci = LIME; break; }
                    }
                    if (score > 0.98 && mat[b + 2] == AIR && mat[b + 3] == AIR && vpart[b + 2] == 0) {
                        plant(b + 1, TALLG, ci, false);
                        plant(b + 2, TALLG, ci, false);
                        tall += 2;
                    } else {
                        plant(b + 1, GRASS, ci, score > 0.6);
                        grass++;
                    }
                }
            }
        return new int[]{grass, tall};
    }

    /** The reach of the lime vents' colour (as creature.MantisHerds.LIME_REACH, which the herds' colour reads). */
    static final double LIME_REACH = 30.0;

    // ================================================================================================ oil, wells, seep

    /** Pools under this many columns stay dry rock. */
    static final int OIL_MIN = 4;

    /**
     * The crude oil (2026-11-19: sunken flat pools; Taran's "Knee-deep, one block"): on the oil's ground
     * (GardensStoreys.oilFoot) the floor's top block becomes oil where its four sides are rock at that row and stand at
     * the same floor (air over them) or as a wall at least two high. So every pool lies one block deep and flat, held
     * by the rock round it, and every way out is one block up: where the floor steps, the block on each side of the step
     * stays dry rock. Never under a mat, a crust or a vent part, never with anything standing on it. Pools under OIL_MIN
     * columns go. Every column reads only its neighbours and the oil's ground, so pools match across tile seams.
     * Returns blocks.
     */
    int oil() {
        IntList cand = new IntList();
        for (int k = 0; k < K; k++)
            for (int i = 1; i < nx - 1; i++)
                for (int j = 1; j < nz - 1; j++) {
                    int q = i * nz + j;
                    if (!f.foot[k][q] || !f.oil[k][q]) continue;
                    int fi = (int) Math.floor(f.floor[k][q]), base = q * SY, r = -1;
                    // the floor's top block: under the first air over rock near the storey's floor
                    for (int y = Math.max(fi - 2, SEAL + 2); y <= Math.min(fi + 2, SY - SEAL - 3); y++)
                        if (mat[base + y] == AIR && !open(mat[base + y - 1])) { r = y - 1; break; }
                    if (r < 0) continue;
                    int b = base + r;
                    if ((mat[b] != ROCK && mat[b] != TALUS) || open(mat[b - 1]) || mat[b + 1] != AIR || mat[b + 2] != AIR) continue;
                    if (vpart[b] != 0 || vpart[b + 1] != 0 || vid[b] != 0) continue;
                    boolean ok = true;
                    for (int e : new int[]{b + sx, b - sx, b + SY, b - SY}) {
                        // rock at the oil's row with rock under it (no lip over a drop: one the loose-rock pass would
                        // clear once the oil cut it off); over it air (the same floor) or a wall two high (no 1-block step)
                        if (open(mat[e]) || open(mat[e - 1]) || (!open(mat[e + 1]) && open(mat[e + 2]))) { ok = false; break; }
                    }
                    if (ok) cand.add(b);
                }
        int[] c = Arrays.copyOf(cand.a, cand.n);
        Arrays.sort(c);
        // pools: neighbours at the same row (two sides at different rows never both pass the test)
        boolean[] seen = new boolean[c.length];
        int[] queue = new int[c.length];
        int n = 0;
        for (int s0 = 0; s0 < c.length; s0++) {
            if (seen[s0]) continue;
            int qh = 0, qt = 0;
            queue[qt++] = s0;
            seen[s0] = true;
            boolean edge = false;
            while (qh < qt) {
                int s = queue[qh++], b = c[s], q = b / SY, i = q / nz, j = q - i * nz;
                if (i <= 1 || j <= 1 || i >= nx - 2 || j >= nz - 2) edge = true;
                for (int e : new int[]{b + sx, b - sx, b + SY, b - SY}) {
                    int x = Arrays.binarySearch(c, e);
                    if (x >= 0 && !seen[x]) {
                        seen[x] = true;
                        queue[qt++] = x;
                    }
                }
            }
            if (qt < OIL_MIN && !edge) continue;
            for (int u = 0; u < qt; u++) mat[c[queue[u]]] = OIL;
            n += qt;
        }
        return n;
    }

    /**
     * After the last loose-rock pass: oil with open air beside or under it (rock the pass cleared) turns back to rock, so
     * no pool spills or steps. Returns blocks.
     */
    int oilHeld() {
        int n = 0;
        for (int i = 1; i < nx - 1; i++)
            for (int j = 1; j < nz - 1; j++) {
                int base = (i * nz + j) * SY;
                for (int y = SEAL + 1; y < SY - SEAL; y++) {
                    int b = base + y;
                    if (mat[b] != OIL) continue;
                    if (mat[b - 1] == AIR || mat[b - 1] == OIL || mat[b + sx] == AIR || mat[b - sx] == AIR || mat[b + SY] == AIR || mat[b - SY] == AIR) {
                        mat[b] = ROCK;
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
     * floor block under every walkable shore spot (a floor block beside oil, 8 ways, at the oil's row: the pools are
     * sunk into the floor) becomes SEEP where a slow noise is high, so the glow runs in broken lines along the oil's
     * edge; never on a vent part. Returns blocks.
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
                        for (int dz = -1; dz <= 1; dz++) if (mat[b - 1 + dx * sx + dz * SY] == OIL) near = true;
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
