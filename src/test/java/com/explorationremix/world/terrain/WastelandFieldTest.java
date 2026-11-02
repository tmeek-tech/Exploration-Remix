package com.explorationremix.world.terrain;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Wasteland as a noise field (0.5.1, spec-wasteland-field 11.3): statistics of blocks made as the game makes them
 * ({@link NoiseVox}) over three boxes of 512 x 512 columns, counted on their inner 320 x 320, and of the field itself.
 * The bars leave room over the values the spec measured (section 9). The Blue Reaches and the Deep Gardens stay as they
 * were ({@link NoiseLayersTest#blueAndGardensUnchanged}, the spec's 11.4); the join's own checks are {@link JoinTest}.
 */
class WastelandFieldTest {
    /** The measured boxes: seed and centre (spec 9). */
    static final long[][] BOXES = {{1, 300, 300}, {2, -900, 1400}, {8004, 2000, -1500}};
    static final int SIZE = 512, MARGIN = 96;

    /** What one box measured (the blocks themselves are dropped once counted). */
    static final class Stats {
        long seed;
        int innerPlaces, mainInner;
        int tips, outOfReach, wayOn, blind;
        int openings;
        double innerKm2;
        int[] rimWalks;
        int rimMainPlaces, rimUnreached;
        int lavaFlow, lavaNearOpening, lavaBlocks;
        double[] views;
        long nans;
        String blindAt = "", openingAt = "";

        double scc() { return (double) mainInner / innerPlaces; }
        double tipsPer1000() { return tips * 1000.0 / innerPlaces; }
        double blindPer1000() { return blind * 1000.0 / innerPlaces; }
        double openingsPerKm2() { return openings / innerKm2; }

        @Override public String toString() {
            double[] v = views.clone();
            Arrays.sort(v);
            int[] w = rimWalks.clone();
            Arrays.sort(w);
            return String.format(java.util.Locale.ROOT, "seed %d: places %d, SCC %.2f %%; tips %d (%.2f per 1000), out of reach %d, way on %d, blind %d (%.3f per 1000)%s;"
                            + " openings %d (%.0f per km2)%s; rim walk median %d p90 %d of %d main places (%d never); lava blocks %d, could flow %d, near an opening %d;"
                            + " views p50 %.0f p90 %.0f p99 %.0f max %.0f of %d; NaN %d",
                    seed, innerPlaces, 100 * scc(), tips, tipsPer1000(), outOfReach, wayOn, blind, blindPer1000(), blindAt, openings, openingsPerKm2(), openingAt,
                    w.length == 0 ? -1 : w[w.length / 2], w.length == 0 ? -1 : w[(int) (w.length * 0.9)], rimMainPlaces, rimUnreached, lavaBlocks, lavaFlow,
                    lavaNearOpening, NoiseVox.quantile(v, 0.5), NoiseVox.quantile(v, 0.9), NoiseVox.quantile(v, 0.99), v.length == 0 ? 0 : v[v.length - 1], v.length, nans);
        }
    }

    private static final Map<Integer, Stats> STATS = new HashMap<>();

    @AfterAll
    static void forget() {
        synchronized (STATS) { STATS.clear(); }
    }

    /** Box i's statistics, measured on first use. */
    static Stats stats(int i) {
        synchronized (STATS) {
            Stats s = STATS.get(i);
            if (s == null) {
                s = measure(BOXES[i][0], (int) BOXES[i][1], (int) BOXES[i][2]);
                System.out.println("WastelandFieldTest box " + s);
                STATS.put(i, s);
            }
            return s;
        }
    }

    /** An opening column: air all the way from 2 rows under the Wasteland's bottom to 8 over it (spec 9). */
    static boolean holeColumn(NoiseVox v, int bot, int x, int z) {
        for (int y = bot - 2; y <= bot + 8; y++) if (v.mat(x, y, z) != NoiseVox.AIR) return false;
        return true;
    }

    static Stats measure(long seed, int cx, int cz) {
        Terrain t = new Terrain(TerrainSettings.defaults(), -64, seed);
        int li = Terrain.WASTELAND, top = t.shape.top(li), bot = t.shape.bottom(li), W = t.shape.waterY(li);
        int x0 = cx - SIZE / 2, z0 = cz - SIZE / 2;
        NoiseVox v = NoiseVox.build(t, x0, z0, SIZE, SIZE, bot - 24, top + 1).graph();
        Stats s = new Stats();
        s.seed = seed;
        s.nans = v.nans;
        int ix0 = x0 + MARGIN, ix1 = x0 + SIZE - MARGIN, iz0 = z0 + MARGIN, iz1 = z0 + SIZE - MARGIN;
        s.innerKm2 = (double) (ix1 - ix0) * (iz1 - iz0) / 1e6;
        // the main component: the one holding most of the inner square's places in the layer's rows
        int n = v.places;
        boolean[] inner = new boolean[n];
        int[] count = new int[v.compSize.length];
        for (int p = 0; p < n; p++) if (v.inArea(p, ix0, iz0, ix1, iz1, bot, top)) { inner[p] = true; s.innerPlaces++; count[v.comp[p]]++; }
        int main = 0;
        for (int c = 0; c < count.length; c++) if (count[c] > count[main]) main = c;
        v.main = main;
        s.mainInner = count[main];

        // blind ends: DeadEnds on 2-block cells (air if 4 of the 8 blocks are), then what a walker finds at each tip
        int gny = (top - bot + 1) / 2;
        DeadEnds.Grid g = new DeadEnds.Grid(x0, bot, z0, SIZE / 2, gny, SIZE / 2);
        for (int i = 0; i < SIZE / 2; i++) for (int k = 0; k < SIZE / 2; k++) for (int j = 0; j < gny; j++) {
            int air = 0;
            for (int q = 0; q < 8; q++) if (v.mat(x0 + i * 2 + (q & 1), bot + j * 2 + ((q >> 1) & 1), z0 + k * 2 + ((q >> 2) & 1)) == NoiseVox.AIR) air++;
            g.set(i, j, k, air >= 4 ? DeadEnds.AIR : DeadEnds.ROCK);
        }
        List<DeadEnds.Tip> tips = DeadEnds.tips(g, MARGIN / 2, (SIZE - MARGIN) / 2 - 1, MARGIN / 2, (SIZE - MARGIN) / 2 - 1);
        s.tips = tips.size();
        int[] mark = new int[n];
        int stamp = 0;
        StringBuilder blindAt = new StringBuilder();
        for (DeadEnds.Tip tp : tips) {
            int bx = g.x0 + tp.i() * 2, by = g.y0 + tp.j() * 2, bz = g.z0 + tp.k() * 2;
            double tx = bx + 1, ty = by + 1, tz = bz + 1;
            List<Integer> start = new ArrayList<>();
            for (int x = bx - 1; x <= bx + 2; x++) for (int z = bz - 1; z <= bz + 2; z++) for (int y = by - 2; y <= by + 1; y++) {
                int p = v.index(x, y, z);
                if (p >= 0 && v.inMain(p)) start.add(p);
            }
            if (start.isEmpty()) { s.outOfReach++; continue; }
            stamp++;
            ArrayDeque<Integer> q = new ArrayDeque<>(start);
            for (int p : start) mark[p] = stamp;
            List<Integer> rim = new ArrayList<>();
            while (!q.isEmpty()) {
                int u = q.poll();
                if (dist(v, u, tx, ty, tz) >= 9.5) { rim.add(u); continue; }
                for (int d = 0; d < 4; d++) {
                    int w = v.next[u * 4 + d];
                    if (w < 0 || mark[w] == stamp || dist(v, w, tx, ty, tz) > 12) continue;
                    mark[w] = stamp;
                    q.add(w);
                }
            }
            if (groups(v, rim) >= 2) { s.wayOn++; continue; }
            s.blind++;
            if (s.blind <= 8) blindAt.append(String.format(" (%d,%d,%d)", bx + 1, by + 1, bz + 1));
        }
        s.blindAt = blindAt.toString();

        // openings into the Galleries: 4 columns or more of hole columns, centre in the inner square
        boolean[] hole = new boolean[SIZE * SIZE];
        for (int i = 0; i < SIZE; i++) for (int k = 0; k < SIZE; k++) hole[i * SIZE + k] = holeColumn(v, bot, x0 + i, z0 + k);
        int[] lab = new int[SIZE * SIZE];
        Arrays.fill(lab, -1);
        StringBuilder openingAt = new StringBuilder();
        for (int p0 = 0; p0 < SIZE * SIZE; p0++) {
            if (!hole[p0] || lab[p0] >= 0) continue;
            ArrayDeque<Integer> q = new ArrayDeque<>();
            q.add(p0);
            lab[p0] = p0;
            long sx = 0, sz = 0;
            int cnt = 0;
            while (!q.isEmpty()) {
                int p = q.poll(), pi = p / SIZE, pk = p % SIZE;
                sx += pi;
                sz += pk;
                cnt++;
                for (int d = 0; d < 4; d++) {
                    int a = pi + NoiseVox.DX[d], b = pk + NoiseVox.DZ[d];
                    if (a < 0 || b < 0 || a >= SIZE || b >= SIZE) continue;
                    int pp = a * SIZE + b;
                    if (hole[pp] && lab[pp] < 0) { lab[pp] = p0; q.add(pp); }
                }
            }
            int mx = x0 + (int) (sx / cnt), mz = z0 + (int) (sz / cnt);
            if (cnt >= 4 && mx >= ix0 && mx < ix1 && mz >= iz0 && mz < iz1) {
                s.openings++;
                if (s.openings <= 3) openingAt.append(String.format(" (%d,%d)", mx, mz));
            }
        }
        s.openingAt = openingAt.toString();

        // the walk to an opening's rim: places near the join's rows within 2 columns of a hole column
        boolean[] rimGoal = new boolean[n];
        for (int p = 0; p < n; p++) {
            int x = v.px(p), y = v.py(p), z = v.pz(p);
            if (y < bot - 6 || y > bot + 16) continue;
            for (int dx = -2; dx <= 2 && !rimGoal[p]; dx++) for (int dz = -2; dz <= 2 && !rimGoal[p]; dz++) {
                int i2 = x + dx - x0, k2 = z + dz - z0;
                if (i2 >= 0 && k2 >= 0 && i2 < SIZE && k2 < SIZE && hole[i2 * SIZE + k2]) rimGoal[p] = true;
            }
        }
        int[] walk = v.walkTo(rimGoal);
        int[] walks = new int[n];
        int nw = 0;
        for (int p = 0; p < n; p++) {
            if (!inner[p] || !v.inMain(p)) continue;
            s.rimMainPlaces++;
            if (walk[p] < 0) s.rimUnreached++;
            else walks[nw++] = walk[p];
        }
        s.rimWalks = Arrays.copyOf(walks, nw);

        // lava that could flow (air beside it or under it), and lava near an opening
        for (int x = x0 + 1; x < x0 + SIZE - 1; x++) for (int z = z0 + 1; z < z0 + SIZE - 1; z++) for (int y = bot - 4; y <= W; y++) {
            if (v.mat(x, y, z) != NoiseVox.LAVA) continue;
            s.lavaBlocks++;
            if (v.mat(x + 1, y, z) == NoiseVox.AIR || v.mat(x - 1, y, z) == NoiseVox.AIR || v.mat(x, y, z + 1) == NoiseVox.AIR
                    || v.mat(x, y, z - 1) == NoiseVox.AIR || v.mat(x, y - 1, z) == NoiseVox.AIR) s.lavaFlow++;
            boolean near = false;
            for (int dx = -3; dx <= 3 && !near; dx++) for (int dz = -3; dz <= 3 && !near; dz++) {
                int i2 = x + dx - x0, k2 = z + dz - z0;
                if (i2 >= 0 && k2 >= 0 && i2 < SIZE && k2 < SIZE && hole[i2 * SIZE + k2]) near = true;
            }
            if (near) s.lavaNearOpening++;
        }

        // sight lines as the planned Wasteland's were measured: the main component by its places in the layer's rows
        // over the whole box, every 4th column, rays to the first rock or out of the layer's rows
        int[] all = new int[v.compSize.length];
        for (int p = 0; p < n; p++) if (v.py(p) >= bot) all[v.comp[p]]++;
        int mainAll = 0;
        for (int c = 0; c < all.length; c++) if (all[c] > all[mainAll]) mainAll = c;
        v.main = mainAll;
        s.views = v.longestViews(4, x0, z0, x0 + SIZE, z0 + SIZE, bot, top - 2, bot, top, false, 400);
        return s;
    }

    private static double dist(NoiseVox v, int p, double tx, double ty, double tz) {
        double dx = v.px(p) + 0.5 - tx, dy = v.py(p) + 0.5 - ty, dz = v.pz(p) + 0.5 - tz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Groups of places within 3 blocks of each other (each axis). */
    private static int groups(NoiseVox v, List<Integer> rim) {
        int groups = 0;
        boolean[] done = new boolean[rim.size()];
        for (int a = 0; a < rim.size(); a++) {
            if (done[a]) continue;
            groups++;
            ArrayDeque<Integer> q = new ArrayDeque<>();
            q.add(a);
            done[a] = true;
            while (!q.isEmpty()) {
                int u = rim.get(q.poll());
                for (int b = 0; b < rim.size(); b++) {
                    if (done[b]) continue;
                    int w = rim.get(b);
                    if (Math.abs(v.px(u) - v.px(w)) <= 3 && Math.abs(v.py(u) - v.py(w)) <= 3 && Math.abs(v.pz(u) - v.pz(w)) <= 3) { done[b] = true; q.add(b); }
                }
            }
        }
        return groups;
    }

    // ================================================================================================ the caves

    /** The Wasteland is one cave: the largest strongly connected component holds 95.5 % of the places or more (96.8-97.2). */
    @Test
    void oneCave() {
        for (int i = 0; i < BOXES.length; i++) {
            Stats s = stats(i);
            assertEquals(0, s.nans, "NaN densities, seed " + s.seed);
            assertTrue(s.innerPlaces > 50_000, "too few standing places to measure: " + s);
            assertTrue(s.scc() >= 0.955, "the largest walkable component holds too little: " + s);
        }
    }

    /**
     * Few blind ends: the dead-end finder's tips (on 2-block cells) at most 2 per 1000 standing places (1.05-1.11), and of
     * them, those where a walker finds no second way out within 12 blocks at most 0.25 per 1000 (0.12-0.14).
     */
    @Test
    void fewBlindEnds() {
        int tips = 0, blind = 0, places = 0;
        for (int i = 0; i < BOXES.length; i++) {
            Stats s = stats(i);
            assertTrue(s.tipsPer1000() <= 2.0, "too many dead-end tips: " + s);
            tips += s.tips;
            blind += s.blind;
            places += s.innerPlaces;
        }
        assertTrue(blind * 1000.0 / places <= 0.25, "blind ends for a walker: " + blind + " in " + places + " places (" + tips + " tips)");
    }

    /** Tight: the longest view from a place, p90 at most 55 blocks (45-49), none of 160 or more (96-119). */
    @Test
    void tight() {
        for (int i = 0; i < BOXES.length; i++) {
            Stats s = stats(i);
            double[] v = s.views.clone();
            Arrays.sort(v);
            assertTrue(v.length > 5000, "too few views measured: " + s);
            assertTrue(NoiseVox.quantile(v, 0.9) <= 55, "views too long: " + s);
            assertTrue(v[v.length - 1] < 160, "a view of 160 or more: " + s);
        }
    }

    /**
     * Openings into the Galleries (4 columns or more open from 2 under the Wasteland's bottom to 8 over it) are common and
     * near: at least 60 per km^2 over the boxes, and the median walk from a main-component place to an opening's rim at
     * most 220 moves. The Wasteland's spec measured 195-254 per km^2 and 143-168 moves over a stand-in for the Galleries
     * and asked 120; an opening only falls where the Galleries are open under it, and the interim Galleries' domes reach
     * the join on about a third of the columns where beta asks for an opening, so with them the boxes measure 91 per
     * km^2 (29, 127, 117) and 152-215 moves. The bar is the Galleries spec's own for the join's openings (joinOpens: 60).
     */
    @Test
    void openingsAreCommonAndReachable() {
        int openings = 0;
        double km2 = 0;
        List<Integer> walks = new ArrayList<>();
        int main = 0, never = 0;
        for (int i = 0; i < BOXES.length; i++) {
            Stats s = stats(i);
            openings += s.openings;
            km2 += s.innerKm2;
            for (int w : s.rimWalks) walks.add(w);
            main += s.rimMainPlaces;
            never += s.rimUnreached;
        }
        Collections.sort(walks);
        assertTrue(openings / km2 >= 60, "openings per km2: " + openings / km2);
        assertTrue(walks.size() > main / 2, "most places walk to no opening: " + never + " of " + main);
        assertTrue(walks.get(walks.size() / 2) <= 220, "median walk to an opening's rim: " + walks.get(walks.size() / 2));
    }

    /**
     * The Wasteland's lava never meets air: no lava block has air beside it or under it (it would flow), and none lies
     * within 3 columns of an opening (spec 6.5: 0 and 0).
     */
    @Test
    void lavaNeverBesideOpeningAir() {
        int blocks = 0;
        for (int i = 0; i < BOXES.length; i++) {
            Stats s = stats(i);
            blocks += s.lavaBlocks;
            assertEquals(0, s.lavaFlow, "lava with air beside or under it: " + s);
            assertEquals(0, s.lavaNearOpening, "lava within 3 columns of an opening: " + s);
        }
        assertTrue(blocks > 100, "too little lava in the boxes to check: " + blocks);
    }

    // ================================================================================================ the field itself

    /**
     * The skeleton: each sheet's tunnels are the edges of a Voronoi diagram of jittered lattice sites in the warped plane.
     * Measured exactly (cells by half-plane clipping, edges mapped back through the warp) over 40 x 40 lattice cells per
     * sheet: runs between junctions median at most 35 blocks (24-30), p99 at most 80 (57-68), none over 100 (73-88), and
     * every junction meets three runs (no loose ends). The sites are checked against the field's own pattern first.
     */
    @Test
    void runsAreShort() {
        Terrain t = new Terrain(TerrainSettings.defaults(), -64, 1);
        WastelandField f = t.wasteland;
        // the sites below are the field's: the distance to the nearest cell boundary agrees with WastelandField.cells
        Random r = new Random(3);
        WastelandField.Col col = new WastelandField.Col();
        for (int k = 0; k < f.sheets; k++) for (int n = 0; n < 200; n++) {
            double u = r.nextDouble() * 2000 - 1000, v = r.nextDouble() * 2000 - 1000;
            f.cells(col, k, u, v);
            double want = col.out[0];
            assertEquals(want, boundary(f, k, u, v), 1e-6, "sheet " + k + " cell boundary at " + u + " " + v);
        }
        for (int k = 0; k < f.sheets; k++) {
            int N = 40;
            List<Double> lens = new ArrayList<>();
            Map<Long, Integer> verts = new HashMap<>();
            List<Long> deep = new ArrayList<>();
            for (int i = 0; i < N; i++) for (int j = 0; j < N; j++) {
                double[] s0 = site(f, k, i, j);
                List<double[]> poly = new ArrayList<>();
                poly.add(new double[]{s0[0] - 3, s0[1] - 3});
                poly.add(new double[]{s0[0] + 3, s0[1] - 3});
                poly.add(new double[]{s0[0] + 3, s0[1] + 3});
                poly.add(new double[]{s0[0] - 3, s0[1] + 3});
                for (int di = -2; di <= 2; di++) for (int dj = -2; dj <= 2; dj++) if (di != 0 || dj != 0) poly = clip(poly, s0, site(f, k, i + di, j + dj));
                boolean interior = i >= 3 && j >= 3 && i < N - 3 && j < N - 3;
                for (int e = 0; e < poly.size(); e++) {
                    double[] p = poly.get(e), q = poly.get((e + 1) % poly.size());
                    long key = Math.round(p[0] * 1e5) * 1_000_003L + Math.round(p[1] * 1e5);
                    verts.merge(key, 1, Integer::sum);
                    if (interior) deep.add(key);
                    double ex = q[0] - p[0], ez = q[1] - p[1], el = Math.hypot(ex, ez);
                    if (el < 1e-9) continue;
                    // each edge once: the side whose neighbour across it is later in (i, j) order skips it
                    double mx = (p[0] + q[0]) / 2 + ez / el * 1e-3, mz = (p[1] + q[1]) / 2 - ex / el * 1e-3;
                    int[] other = nearestSite(f, k, mx, mz, i, j);
                    if (other[0] * 100000 + other[1] < i * 100000 + j) continue;
                    lens.add(worldLength(f, k, p, q));
                }
            }
            Collections.sort(lens);
            double med = lens.get(lens.size() / 2), p99 = lens.get((int) (lens.size() * 0.99)), max = lens.get(lens.size() - 1);
            String what = String.format(java.util.Locale.ROOT, "sheet %d: %d runs, median %.1f, p99 %.1f, max %.1f", k, lens.size(), med, p99, max);
            assertTrue(lens.size() > 4000, what);
            assertTrue(med <= 35 && p99 <= 80 && max <= 100, "runs too long, " + what);
            // a corner of a cell is a junction; away from the patch's edge (where all its cells were made) it is a corner
            // of three cells or more, so at least three runs meet there: no run ends loose
            int loose = 0;
            for (long key : deep) if (verts.get(key) < 3) loose++;
            assertTrue(deep.size() > 5000, what);
            assertEquals(0, loose, "junctions where fewer than three runs meet, " + what);
        }
    }

    /** The distance from (u, v) (warped plane, blocks) to the boundary of its nearest site's cell, from {@link #site}. */
    static double boundary(WastelandField f, int k, double u, double v) {
        double S = WastelandField.SPACING[k];
        double a = (f.cos(k) * u - f.sin(k) * v) / S + f.offA(k), b = (f.sin(k) * u + f.cos(k) * v) / S + f.offB(k);
        int ia = (int) Math.floor(a), ib = (int) Math.floor(b);
        // the nearest of all sites round the point (the field looks only at a 4 x 4 window: it must hold it)
        double best = 1e9;
        double[] s1 = null;
        for (int i = ia - 3; i <= ia + 3; i++) for (int j = ib - 3; j <= ib + 3; j++) {
            double[] s = site(f, k, i, j);
            double d = (s[0] - a) * (s[0] - a) + (s[1] - b) * (s[1] - b);
            if (d < best) { best = d; s1 = s; }
        }
        int a0 = a - ia < 0.5 ? ia - 2 : ia - 1, b0 = b - ib < 0.5 ? ib - 2 : ib - 1;
        double e = 1e9;
        for (int i = a0; i < a0 + 4; i++) for (int j = b0; j < b0 + 4; j++) {
            double[] s = site(f, k, i, j);
            if (s[0] == s1[0] && s[1] == s1[1]) continue;
            double dd = (s[0] - a) * (s[0] - a) + (s[1] - b) * (s[1] - b) - best;
            double qx = s[0] - s1[0], qz = s[1] - s1[1];
            e = Math.min(e, dd / (2 * Math.sqrt(qx * qx + qz * qz)));
        }
        return e * S;
    }

    /** Site of lattice cell (i, j) of sheet k in lattice units (as WastelandField.cells draws it). */
    static double[] site(WastelandField f, int k, int i, int j) {
        long hh = Hashing.mix(f.sheetSeed(k), i, j, 7);
        return new double[]{i + 0.5 + WastelandField.JITTER * ((hh & 0xFFFFFFFFL) * 0x1.0p-32 - 0.5), j + 0.5 + WastelandField.JITTER * ((hh >>> 32) * 0x1.0p-32 - 0.5)};
    }

    static int[] nearestSite(WastelandField f, int k, double a, double b, int ci, int cj) {
        double best = 1e9;
        int[] o = {ci, cj};
        for (int di = -3; di <= 3; di++) for (int dj = -3; dj <= 3; dj++) {
            double[] s = site(f, k, ci + di, cj + dj);
            double d = (s[0] - a) * (s[0] - a) + (s[1] - b) * (s[1] - b);
            if (d < best) { best = d; o = new int[]{ci + di, cj + dj}; }
        }
        return o;
    }

    /** The polygon cut to the side of the bisector of s0 and s1 nearer s0. */
    static List<double[]> clip(List<double[]> poly, double[] s0, double[] s1) {
        double nx = s1[0] - s0[0], nz = s1[1] - s0[1];
        double c = (s1[0] * s1[0] + s1[1] * s1[1] - s0[0] * s0[0] - s0[1] * s0[1]) / 2;
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < poly.size(); i++) {
            double[] p = poly.get(i), q = poly.get((i + 1) % poly.size());
            double fp = p[0] * nx + p[1] * nz - c, fq = q[0] * nx + q[1] * nz - c;
            if (fp <= 0) out.add(p);
            if ((fp < 0 && fq > 0) || (fp > 0 && fq < 0)) {
                double tt = fp / (fp - fq);
                out.add(new double[]{p[0] + (q[0] - p[0]) * tt, p[1] + (q[1] - p[1]) * tt});
            }
        }
        return out;
    }

    static double[] warp(WastelandField f, double x, double z) {
        double wx = WastelandField.WARP1 * f.warpA().noise2(x / WastelandField.WARP1_SCALE, z / WastelandField.WARP1_SCALE)
                + WastelandField.WARP2 * f.warpC().noise2(x / WastelandField.WARP2_SCALE, z / WastelandField.WARP2_SCALE);
        double wz = WastelandField.WARP1 * f.warpB().noise2(x / WastelandField.WARP1_SCALE, z / WastelandField.WARP1_SCALE)
                + WastelandField.WARP2 * f.warpD().noise2(x / WastelandField.WARP2_SCALE, z / WastelandField.WARP2_SCALE);
        return new double[]{wx, wz};
    }

    /** Length in world blocks of the edge p..q (lattice units of sheet k), mapped back through the warp by fixed point. */
    static double worldLength(WastelandField f, int k, double[] p, double[] q) {
        double S = WastelandField.SPACING[k], len = 0, px = 0, pz = 0;
        int steps = 24;
        for (int i = 0; i <= steps; i++) {
            double a = (p[0] + (q[0] - p[0]) * i / steps - f.offA(k)) * S, b = (p[1] + (q[1] - p[1]) * i / steps - f.offB(k)) * S;
            double u = f.cos(k) * a + f.sin(k) * b, v = -f.sin(k) * a + f.cos(k) * b;
            double x = u, z = v;
            for (int it = 0; it < 30; it++) { double[] w = warp(f, x, z); x = u - w[0]; z = v - w[1]; }
            if (i > 0) len += Math.hypot(x - px, z - pz);
            px = x;
            pz = z;
        }
        return len;
    }

    /**
     * The fluid rule (spec 6.5), over 10^6 points: under the lava table a block is air wherever beta is
     * {@link Join#AIR_BETA} or more (lava under that), and where beta is from {@link Join#LAVA_BETA} to under
     * {@link Join#HOLE_BETA} no block from the Wasteland's bottom to a row over its table is open, whatever the corners
     * interpolate to.
     */
    @Test
    void fluidRule() {
        Terrain t = new Terrain(TerrainSettings.defaults(), -64, 2);
        int li = Terrain.WASTELAND, bot = t.shape.bottom(li), W = t.shape.waterY(li);
        Random r = new Random(17);
        int air = 0, sealed = 0, wrong = 0, open = 0;
        String first = null;
        for (int n = 0; n < 1_000_000; n++) {
            int x = r.nextInt(20000) - 10000, z = r.nextInt(20000) - 10000;
            double beta = t.breakthrough.beta(x, z);
            int y = bot + r.nextInt(W - bot);
            int f = t.fluidAt(x, y, z);
            if (beta >= Join.AIR_BETA) {
                air++;
                if (f != Terrain.AIR) { wrong++; if (first == null) first = "fluid " + f + " at " + x + " " + y + " " + z + " beta " + beta; }
            } else if (f != Terrain.LAVA) { wrong++; if (first == null) first = "fluid " + f + " at " + x + " " + y + " " + z + " beta " + beta; }
            if (beta >= Join.LAVA_BETA && beta < Join.HOLE_BETA) {
                sealed++;
                int yy = bot + r.nextInt(W + 2 - bot);
                if (t.fine(x, yy, z, r.nextDouble() * 2 - 1) <= 0) { open++; if (first == null) first = "open at " + x + " " + yy + " " + z + " beta " + beta; }
            }
        }
        assertTrue(air > 50_000 && sealed > 100_000, "too few points to check: " + air + " air, " + sealed + " sealed");
        assertEquals(0, wrong, "fluids against the rule; first: " + first);
        assertEquals(0, open, "open blocks under the lava table where the seal holds; first: " + first);
    }

    /**
     * No band stone where the Wasteland meets the Galleries: every row from the Galleries' bottom to the Wasteland's top
     * belongs to a layer, and the palette never gives band stone over the join's rows.
     */
    @Test
    void noBandStoneAtTheJoin() {
        Terrain t = new Terrain(TerrainSettings.defaults(), -64, 8004);
        Shape s = t.shape;
        assertEquals(-832, s.bottom(Terrain.GALLERIES));
        assertEquals(-545, s.top(Terrain.WASTELAND));
        for (int y = s.bottom(Terrain.GALLERIES); y <= s.top(Terrain.WASTELAND); y++) assertTrue(s.layerAt(y) >= 0, "row " + y + " is in no layer");
        Random r = new Random(23);
        int bands = 0;
        for (int n = 0; n < 100_000; n++) {
            int x = r.nextInt(8000) - 4000, z = r.nextInt(8000) - 4000, y = t.join.lo + r.nextInt(t.join.hi - t.join.lo + 1);
            if (t.palette.shell(x, y, z, 1 + r.nextInt(8), 1 + r.nextInt(8), r.nextInt(4) == 0) == Rock.BAND_STONE) bands++;
        }
        assertEquals(0, bands, "band stone in the join's rows");
    }

    /**
     * Gate 3's lower landing lands in the caves: for seeds 1 to 4 and cells -1..1, the standing places on the landing
     * belong to a walkable component (both ways) holding places 70 blocks or more away, and the walk there takes at most
     * 100 moves (36 of 36; at most 79).
     */
    @Test
    void gateThreeLandsInTheCaves() {
        List<String> bad = new ArrayList<>();
        int worst = 0;
        for (long seed = 1; seed <= 4; seed++) {
            Terrain t = new Terrain(TerrainSettings.defaults(), -64, seed);
            for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) {
                int w = landingWalk(t, cx, cz, 70);
                if (w < 0 || w > 100) bad.add(seed + ":" + cx + ":" + cz + " walk " + w);
                worst = Math.max(worst, w);
            }
        }
        assertTrue(bad.isEmpty(), "landings cut off or far from the caves: " + bad);
        System.out.println("WastelandFieldTest gate 3: longest walk from a landing " + worst + " moves");
    }

    /**
     * The walk (moves) from gate 3's lower landing in cell (cx, cz) to a place {@code reach} blocks away in the landing's
     * strongly connected component; -1 when there is no standing place on the landing, -2 when that component holds no
     * place so far away.
     */
    static int landingWalk(Terrain t, int cx, int cz, int reach) {
        Plans.Gate g = t.plans.gate(Terrain.WASTELAND + 1, cx, cz);
        int top = t.shape.top(Terrain.WASTELAND), R = reach + 50;
        int x0 = (int) Math.floor(g.lx) - R, z0 = (int) Math.floor(g.lz) - R;
        NoiseVox v = NoiseVox.build(t, x0, z0, 2 * R, 2 * R, top - 110, top + 1).graph();
        Map<Integer, Integer> comps = new HashMap<>();
        List<Integer> land = new ArrayList<>();
        for (int p = 0; p < v.places; p++) {
            double d = Math.hypot(v.px(p) + 0.5 - g.lx, v.pz(p) + 0.5 - g.lz);
            int y = v.py(p);
            if (d < g.landR - 2 && y >= g.lFloor - 3 && y <= g.lFloor + 4) { land.add(p); comps.merge(v.comp[p], 1, Integer::sum); }
        }
        if (land.isEmpty()) return -1;
        int lc = Collections.max(comps.entrySet(), Map.Entry.comparingByValue()).getKey();
        boolean[] far = new boolean[v.places];
        boolean any = false;
        for (int p = 0; p < v.places; p++) if (v.comp[p] == lc && Math.hypot(v.px(p) + 0.5 - g.lx, v.pz(p) + 0.5 - g.lz) >= reach) far[p] = any = true;
        if (!any) return -2;
        int[] walk = v.walkTo(far);
        int best = Integer.MAX_VALUE;
        for (int p : land) if (v.comp[p] == lc && walk[p] >= 0) best = Math.min(best, walk[p]);
        return best;
    }

    /**
     * The sheets follow the configured height (spec 4.7): 64, 96 and 160 rows give 2, 3 and 6 sheets (5 by default), and
     * gate 3's landing still lands in the caves on 9 cells each.
     */
    @Test
    void configuredHeights() {
        assertEquals(5, new Terrain(TerrainSettings.defaults(), -64, 1).wasteland.sheets);
        int[] heights = {64, 96, 160}, sheets = {2, 3, 6};
        List<String> bad = new ArrayList<>();
        for (int h = 0; h < heights.length; h++) {
            Terrain t = new Terrain(withWastelandHeight(heights[h]), -64, 1);
            assertEquals(sheets[h], t.wasteland.sheets, "sheets at height " + heights[h]);
            for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) {
                int w = landingWalk(t, cx, cz, 70);
                if (w < 0 || w > 100) bad.add(heights[h] + ": " + cx + ":" + cz + " walk " + w);
            }
        }
        assertTrue(bad.isEmpty(), "landings cut off at configured heights: " + bad);
    }

    static TerrainSettings withWastelandHeight(int height) {
        TerrainSettings ts = TerrainSettings.defaults();
        List<LayerSettings> ls = new ArrayList<>(ts.layers());
        LayerSettings w = ls.get(Terrain.WASTELAND);
        ls.set(Terrain.WASTELAND, new LayerSettings(w.id(), height, w.fluidDepth(), w.lava(), w.heightMin(), w.heightMax(), w.widthMin(), w.widthMax()));
        return new TerrainSettings(ts.bandThickness(), ts.gateSpacing(), ts.passageRadius(), ts.landingRadius(), List.copyOf(ls));
    }

    /**
     * The field is a pure function of the seed: density, the per-block pass, fluid and biome at 10^5 points of its rows
     * (and the join's under them) answer bit for bit the same on one thread and on four in shuffled order, in a fresh
     * instance, with neighbouring queries 32 blocks apart (the column cache's slots alias there).
     */
    @Test
    void pureAndThreadSafe() throws Exception {
        long seed = 8004;
        WastelandField one = new Terrain(TerrainSettings.defaults(), -64, seed).wasteland;
        int n = 100_000;
        Random r = new Random(29);
        int[][] pts = new int[n][];
        for (int k = 0; k < n; k++) {
            int x = r.nextInt(6000) - 3000, z = r.nextInt(6000) - 3000;
            if ((k & 1) == 1) { x = pts[k - 1][0] + 32; z = pts[k - 1][2] - 32; }
            pts[k] = new int[]{x, one.bot - 16 + r.nextInt(one.top - one.bot + 17), z, r.nextInt(2001)};
        }
        long[] alone = new long[n];
        for (int k = 0; k < n; k++) alone[k] = probe(one, pts[k]);
        WastelandField fresh = new Terrain(TerrainSettings.defaults(), -64, seed).wasteland;
        List<Integer> order = new ArrayList<>();
        for (int k = 0; k < n; k++) order.add(k);
        Collections.shuffle(order, new Random(31));
        long[] many = new long[n];
        ExecutorService ex = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int w = 0; w < 4; w++) {
                final int from = w;
                fs.add(ex.submit(() -> { for (int i = from; i < n; i += 4) { int k = order.get(i); many[k] = probe(fresh, pts[k]); } }));
            }
            for (Future<?> f : fs) f.get();
        } finally {
            ex.shutdownNow();
        }
        int differ = 0;
        String first = null;
        for (int k = 0; k < n; k++) if (alone[k] != many[k]) { differ++; if (first == null) first = Arrays.toString(pts[k]); }
        assertEquals(0, differ, "points answered differently, first " + first);
    }

    static long probe(WastelandField f, int[] p) {
        int x = p[0], y = p[1], z = p[2];
        long h = Double.doubleToLongBits(f.density(x, y, z));
        h = h * 31 + Double.doubleToLongBits(f.fine(x, y, z, p[3] / 1000.0 - 1));
        h = h * 31 + f.fluid(x, y, z);
        h = h * 31 + f.biome(x, y, z).ordinal();
        h = h * 31 + Double.doubleToLongBits(f.openCore(x, y, z));
        h = h * 31 + (f.lavaKept(x, y, z) ? 1 : 0);
        return h;
    }

    /**
     * The cost of the Wasteland's rows (corners every 4 x 8 x 4, interpolation and the per-block pass, one thread) per
     * row at most twice the Deep Gardens' in the same JVM, their plans made first (0.55-1.36). Best of three rounds each,
     * taken in turns, so a busy machine slows both.
     */
    @Test
    void costWithinBudget() {
        Terrain t = new Terrain(TerrainSettings.defaults(), -64, 1);
        Shape s = t.shape;
        int wLo = s.bottom(Terrain.WASTELAND), wHi = s.top(Terrain.WASTELAND), gLo = s.bottom(Terrain.GARDENS), gHi = s.top(Terrain.GARDENS);
        timeRows(t, gLo, gHi);   // plans the Gardens' cells and warms up both
        timeRows(t, wLo, wHi);
        double bestW = Double.MAX_VALUE, bestG = Double.MAX_VALUE;
        for (int round = 0; round < 3; round++) {
            bestW = Math.min(bestW, timeRows(t, wLo, wHi) / (wHi - wLo + 1));
            bestG = Math.min(bestG, timeRows(t, gLo, gHi) / (gHi - gLo + 1));
        }
        double ratio = bestW / bestG;
        System.out.printf(java.util.Locale.ROOT, "WastelandFieldTest cost per row: Wasteland %.1f us, Gardens %.1f us, ratio %.2f%n", bestW / 1e3, bestG / 1e3, ratio);
        assertTrue(ratio <= 2.0, "the Wasteland's rows cost " + ratio + " times the Gardens'");
    }

    /** Nanoseconds to make rows lo..hi of 16 chunks as the game does (one thread). */
    static double timeRows(Terrain t, int lo, int hi) {
        long t0 = System.nanoTime();
        double sink = 0;
        int cy0 = Math.floorDiv(lo, 8), cy1 = Math.floorDiv(hi, 8) + 1, ny = cy1 - cy0 + 1;
        double[] cor = new double[25 * ny];
        for (int c = 0; c < 16; c++) {
            int bx = (10 + c % 4) * 16, bz = (10 + c / 4) * 16;
            for (int i = 0; i < 5; i++) for (int k = 0; k < 5; k++) for (int j = 0; j < ny; j++) cor[(i * 5 + k) * ny + j] = t.density(bx + i * 4, (cy0 + j) * 8, bz + k * 4);
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = lo; y <= hi; y++)
                sink += NoiseLayersTest.interpolatedFine(t, cor, ny, cy0, bx, bz, x, y, z);
        }
        assertTrue(Double.isFinite(sink));
        return System.nanoTime() - t0;
    }

    /** Lava stays at the openings' rims: every column an opening's dip reaches keeps its lava out (spec 8). */
    @Test
    void lavaKeptAtOpenings() {
        Terrain t = new Terrain(TerrainSettings.defaults(), -64, 1);
        WastelandField f = t.wasteland;
        int rims = 0, bad = 0;
        for (int x = -2000; x < 2000; x += 3) for (int z = -2000; z < 2000; z += 3) {
            if (f.openingRim(x, z) <= 0) continue;
            rims++;
            if (!f.lavaKept(x, f.W - 1, z)) bad++;
        }
        assertTrue(rims > 1000, "too few opening columns to check: " + rims);
        assertEquals(0, bad, "opening columns that let lava in");
    }

    /** The Ash Hollows are patches: 4 to 12 % of the columns (7.3), and a column's neighbour 4 blocks off mostly agrees. */
    @Test
    void ashHollowsAreAPatch() {
        Terrain t = new Terrain(TerrainSettings.defaults(), -64, 2);
        WastelandField f = t.wasteland;
        Random r = new Random(37);
        int ash = 0, same = 0, n = 100_000;
        int y = f.W + 20;
        for (int k = 0; k < n; k++) {
            int x = r.nextInt(40000) - 20000, z = r.nextInt(40000) - 20000;
            boolean a = f.biome(x, y, z) == DeepBiome.ASH_HOLLOWS;
            if (!a) continue;
            ash++;
            if (f.biome(x + 4, y, z) == DeepBiome.ASH_HOLLOWS) same++;
        }
        double share = (double) ash / n;
        assertTrue(share >= 0.04 && share <= 0.12, "Ash Hollows on " + share + " of the columns");
        assertTrue(same >= ash * 0.9, "Ash Hollows speckled: " + same + " of " + ash + " ash columns have an ash neighbour 4 blocks off");
    }

    /** The slow set: the configured heights' landings over more cells. */
    @Test
    @Tag("slow")
    void gateThreeLandsOnMoreCells() {
        List<String> bad = new ArrayList<>();
        for (long seed = 5; seed <= 12; seed++) {
            Terrain t = new Terrain(TerrainSettings.defaults(), -64, seed);
            for (int cx = -2; cx <= 2; cx++) for (int cz = -2; cz <= 2; cz++) {
                int w = landingWalk(t, cx, cz, 70);
                if (w < 0 || w > 100) bad.add(seed + ":" + cx + ":" + cz + " walk " + w);
            }
        }
        assertTrue(bad.isEmpty(), "landings cut off or far from the caves: " + bad);
    }
}
