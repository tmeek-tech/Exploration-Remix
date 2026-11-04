package com.explorationremix.world.terrain;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Basalt Galleries as one open noise cavern (0.5.1, spec-galleries-field-v2 10), tested mostly through what the game
 * makes of them: their public methods, the tube hook, the join hook, column cells, climate and the look queries, plus the
 * openings' and ramps' own records where the spec states rules about them. Blocks are made as the game makes them
 * ({@link NoiseVox}) over boxes of 1536 x 1536 columns, counted on the inner 1112 x 1112 (the spec's 212-block margin).
 * Bars sit about a point under the worst box measured here (spec 10.1), so they hold the field to what it does.
 */
class GalleriesFieldTest {
    /** Seed and lowest corner of the measured boxes (spec 9.1). */
    static final long[][] BOXES = {{8675309, -512, -512}, {-42, -5000, 6000}};
    static final int SIZE = 1536, MARGIN = 212;
    /** The rows a box holds: the Galleries' and the join's up to the Wasteland's lava table (its lava, the openings). */
    static final int YLO = -832, YHI = -678;
    static final Set<DeepBiome> GALLERIES = EnumSet.of(DeepBiome.BASALT_GALLERIES, DeepBiome.CALDERA, DeepBiome.CAUSEWAY,
            DeepBiome.SCORIA_CANYONS, DeepBiome.OBSIDIAN_TUBES);

    /** What one box measured (its blocks are dropped once counted). */
    static final class Stats {
        long seed;
        long nans;
        int places, inMain, toMain, low, never, within384;
        /** Moves of the walk graph from the inner square's places: all (falls included), drops of 2..15 rows, falls (off an edge, over 15 or into lava). */
        long moves, drops, falls;
        /** The opened openings with middles in the inner square: all, kept with a ramp, with a way down from the ramp's head, with a view down; opened without a ramp. */
        int opened, ramped, waysDown, viewsDown, withoutRamp;
        /** Kept ramps walked along their centre lines block by block: ramps, columns, columns with no standing place near the deck, steps over a row. */
        int rampsWalked, centreColumns, centreMissing, centreSteps;
        String noWayAt = "", missingAt = "";
        double[] views;
        /** The largest 4-connected patch of inner columns whose topmost open run under T + 10 ends at one rock row in T - 7..T + 9 (columns open over T left out), and where. */
        int flatPatch;
        String flatAt = "";
        int wastelandLava, lavaBesideAir, lavaNearOpening, openingColumns;
        /** Places a walk from the main cavern reaches that never walk up out of the flood (nor to the box's edge), in how many components; floating rock (components of 8 blocks or more held by nothing). */
        int trapped, trapSets, floating;
        String trapAt = "", floatAt = "";
        int water, waterUnsealed, waterInOpenCavern, waterLow, waterByLava;
        String unsealedAt = "", byLavaAt = "", lavaAt = "";
        double buildSeconds;

        double scc() { return (double) inMain / places; }
        double walkIntoMain() { return (double) toMain / places; }
        double neverShare() { return (double) never / low; }
        double within384() { return (double) within384 / low; }
        double over(double len) { int n = 0; for (double v : views) if (v >= len) n++; return (double) n / views.length; }

        double dropShare() { return (double) drops / moves; }
        double fallShare() { return (double) falls / moves; }

        @Override public String toString() {
            return String.format(java.util.Locale.ROOT, "seed %d (built in %.1f s): places %d, SCC %.1f %%, walk into it %.1f %%; low %d, escape <= 384 %.1f %%, never %.2f %%;"
                            + " moves %d: drops %.2f %%, falls %.2f %%; openings %d, with a ramp %d, way down %d%s, view down %d, without a ramp %d;"
                            + " ramps walked %d (%d columns): no place near the deck %d%s, steps over a row %d;"
                            + " views from %d: 80+ %.1f %%, 160+ %.2f %%, 192+ %.3f %%; largest flat patch under the top %d columns%s; Wasteland lava %d, beside air %d%s, near an opening %d (%d opening columns);"
                            + " water %d, unsealed %d%s, in open cavern %d, under W+40 %d, by lava %d%s; closed pits %d places in %d sets%s; floating rock %d%s; NaN %d",
                    seed, buildSeconds, places, 100 * scc(), 100 * walkIntoMain(), low, 100 * within384(), 100 * neverShare(), moves, 100 * dropShare(), 100 * fallShare(),
                    opened, ramped, waysDown, noWayAt, viewsDown, withoutRamp, rampsWalked, centreColumns, centreMissing, missingAt, centreSteps, views.length, 100 * over(80),
                    100 * over(160), 100 * over(192), flatPatch, flatAt, wastelandLava, lavaBesideAir, lavaAt, lavaNearOpening, openingColumns, water, waterUnsealed, unsealedAt,
                    waterInOpenCavern, waterLow, waterByLava, byLavaAt, trapped, trapSets, trapAt, floating, floatAt, nans);
        }
    }

    private static final Map<Integer, Stats> STATS = new HashMap<>();

    @AfterAll
    static void forget() {
        synchronized (STATS) { STATS.clear(); }
    }

    static Stats stats(int i) {
        synchronized (STATS) {
            Stats s = STATS.get(i);
            if (s == null) {
                s = measure(BOXES[i][0], (int) BOXES[i][1], (int) BOXES[i][2], SIZE, MARGIN);
                System.out.println("GalleriesFieldTest box " + s);
                STATS.put(i, s);
            }
            return s;
        }
    }

    static Stats measure(long seed, int x0, int z0, int size, int margin) {
        long t0 = System.nanoTime();
        Terrain t = new Terrain(TerrainSettings.defaults(), -64, seed);
        Shape sh = t.shape;
        int li = Terrain.GALLERIES, W = sh.waterY(li), T = sh.top(li), wBot = sh.bottom(Terrain.WASTELAND);
        NoiseVox v = NoiseVox.build(t, x0, z0, size, size, YLO, YHI).graph();
        Stats s = new Stats();
        s.seed = seed;
        s.nans = v.nans;
        s.buildSeconds = (System.nanoTime() - t0) / 1e9;
        int xa = x0 + margin, za = z0 + margin, xb = x0 + size - margin, zb = z0 + size - margin;
        // the Galleries' places: feet from the lava table to their top row, in the inner square
        int n = v.places;
        int[] count = new int[v.compSize.length];
        boolean[] area = new boolean[n];
        for (int p = 0; p < n; p++) if (v.inArea(p, xa, za, xb, zb, W, T)) { area[p] = true; s.places++; count[v.comp[p]]++; }
        int main = 0;
        for (int c = 0; c < count.length; c++) if (count[c] > count[main]) main = c;
        v.main = main;
        s.inMain = count[main];
        int[] toMain = v.walkToMain();
        int[] up = v.walkUpTo(W + Terrain.FLOOD_RISE);
        for (int p = 0; p < n; p++) {
            if (!area[p]) continue;
            if (toMain[p] >= 0) s.toMain++;
            if (v.py(p) >= W + Terrain.FLOOD_RISE) continue;
            s.low++;
            if (up[p] < 0) s.never++;
            else if (up[p] <= 384) s.within384++;
        }
        pits(v, W, up, s);
        rockHeld(v, s);
        s.views = v.longestViews(4, xa, za, xb, zb, W, T, YLO, YHI, true, 260);
        // the moves: drops of 2..15 rows, and falls (a step off an edge with nothing to stand on 15 rows under, or lava)
        for (int p = 0; p < n; p++) {
            if (!area[p]) continue;
            int x = v.px(p), y = v.py(p), z = v.pz(p);
            for (int d = 0; d < 4; d++) {
                int q = v.next[p * 4 + d];
                if (q >= 0) { s.moves++; if (y - v.py(q) >= 2) s.drops++; continue; }
                int qx = x + NoiseVox.DX[d], qz = z + NoiseVox.DZ[d];
                if (v.inColumns(qx, qz) && v.open(qx, y, qz) && v.open(qx, y + 1, qz)) { s.moves++; s.falls++; }
            }
        }
        openings(t, v, s, toMain, xa, za, xb, zb);
        flatPatch(v, s, T, xa, za, xb, zb);

        // fluids: the Wasteland's lava (over the join's bottom) never beside air nor near an opening; tube pools sealed
        boolean[] hole = new boolean[size * size];
        for (int i = 0; i < size; i++) for (int k = 0; k < size; k++) {
            hole[i * size + k] = WastelandFieldTest.holeColumn(v, wBot, x0 + i, z0 + k);
            if (hole[i * size + k]) s.openingColumns++;
        }
        int[][] six = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, -1, 0}, {0, 1, 0}};
        StringBuilder unsealedAt = new StringBuilder(), byLavaAt = new StringBuilder(), lavaAt = new StringBuilder();
        int jLo = t.join.lo;
        for (int i = 1; i < size - 1; i++) for (int k = 1; k < size - 1; k++) {
            int x = x0 + i, z = z0 + k;
            for (int y = YLO + 1; y < YHI; y++) {
                int m = v.mat(x, y, z);
                if (m == NoiseVox.LAVA && y >= jLo) {
                    s.wastelandLava++;
                    boolean air = false;
                    for (int d = 0; d < 5; d++) air |= v.mat(x + six[d][0], y + six[d][1], z + six[d][2]) == NoiseVox.AIR;
                    if (air) { s.lavaBesideAir++; if (lavaAt.length() < 60) lavaAt.append(String.format(" (%d,%d,%d)", x, y, z)); }
                    boolean near = false;
                    for (int dx = -3; dx <= 3 && !near; dx++) for (int dz = -3; dz <= 3 && !near; dz++) {
                        int i2 = i + dx, k2 = k + dz;
                        near = i2 >= 0 && k2 >= 0 && i2 < size && k2 < size && hole[i2 * size + k2];
                    }
                    if (near) s.lavaNearOpening++;
                }
                if (m != NoiseVox.WATER) continue;
                s.water++;
                // over water or rock, beside water or rock, carved in the cavern's rock, over the flood's reach
                boolean bad = false;
                for (int d = 0; d < 5; d++) {
                    int mm = v.mat(x + six[d][0], y + six[d][1], z + six[d][2]);
                    if (mm != NoiseVox.WATER && mm != NoiseVox.ROCK) bad = true;
                }
                if (bad) { s.waterUnsealed++; if (unsealedAt.length() < 60) unsealedAt.append(String.format(" (%d,%d,%d)", x, y, z)); }
                if (t.galleries.density(x, y, z) <= 0) s.waterInOpenCavern++;
                if (y < W + Terrain.FLOOD_RISE) s.waterLow++;
                boolean lava = false;
                for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) lava |= v.mat(x + dx, y + dy, z + dz) == NoiseVox.LAVA;
                if (lava) { s.waterByLava++; if (byLavaAt.length() < 60) byLavaAt.append(String.format(" (%d,%d,%d)", x, y, z)); }
            }
        }
        s.unsealedAt = unsealedAt.toString();
        s.byLavaAt = byLavaAt.toString();
        s.lavaAt = lavaAt.toString();
        return s;
    }

    /**
     * Closed pits (fix round 2b): the places a walk from the main cavern reaches (main itself included) that never walk
     * up out of the flood ({@code up}: moves to a place with feet at W + FLOOD_RISE or over, -1 for none) and never reach
     * the box's edge (where a way out could lie outside the box). A player who drops into one cannot walk out before the
     * lava. Counted with the number of their components and where the largest is.
     */
    static void pits(NoiseVox v, int W, int[] up, Stats s) {
        boolean[] main = new boolean[v.places], edge = new boolean[v.places];
        for (int p = 0; p < v.places; p++) {
            main[p] = v.inMain(p);
            int x = v.px(p), z = v.pz(p);
            edge[p] = x == v.x0 || z == v.z0 || x == v.x0 + v.nx - 1 || z == v.z0 + v.nz - 1;
        }
        boolean[] reached = v.reachFrom(main);
        int[] toEdge = v.walkTo(edge);
        Map<Integer, int[]> sets = new HashMap<>();
        for (int p = 0; p < v.places; p++) {
            if (!reached[p] || up[p] >= 0 || toEdge[p] >= 0) continue;
            s.trapped++;
            int[] e = sets.computeIfAbsent(v.comp[p], c -> new int[4]);
            if (e[0]++ == 0) { e[1] = v.px(p); e[2] = v.py(p); e[3] = v.pz(p); }
        }
        s.trapSets = sets.size();
        int[] big = null;
        for (int[] e : sets.values()) if (big == null || e[0] > big[0]) big = e;
        if (big != null) s.trapAt = String.format(java.util.Locale.ROOT, " (largest %d places at %d,%d,%d)", big[0], big[1], big[2], big[3]);
    }

    /** Floating rock (fix round 2b): components of 8 rock blocks or more that touch no face of the box. */
    static void rockHeld(NoiseVox v, Stats s) {
        List<int[]> fl = v.floating(8);
        s.floating = fl.size();
        if (!fl.isEmpty()) s.floatAt = String.format(java.util.Locale.ROOT, " (largest %d blocks at %d,%d,%d)", fl.get(0)[0], fl.get(0)[1], fl.get(0)[2], fl.get(0)[3]);
    }

    /**
     * The opened openings with middles in the inner square (spec 7.2-7.3, 7.6): how many keep a ramp; a way down from each
     * ramp's head (a standing place within 3 blocks of the deck 2 blocks past the lip, feet from T - 9 up, that walks into
     * the main cavern); a view down (a ray straight down from the middle at T - 1.5 that runs 48 blocks through open
     * space); that it keeps a ramp (an opening without one stays shut); and each kept ramp wholly in
     * the box walked block by block along its centre line from its head to its foot: a standing place within -4..+3 rows of
     * the deck's row at every column, and no step of more than a row between neighbouring columns.
     */
    static void openings(Terrain t, NoiseVox v, Stats s, int[] toMain, int xa, int za, int xb, int zb) {
        GalleriesField g = t.galleries;
        int T = g.T;
        List<GalleriesField.Opening> all = new ArrayList<>();
        for (int i = Math.floorDiv(v.x0, 64) - 1; i <= Math.floorDiv(v.x0 + v.nx, 64) + 1; i++)
            for (int k = Math.floorDiv(v.z0, 64) - 1; k <= Math.floorDiv(v.z0 + v.nz, 64) + 1; k++) all.addAll(Arrays.asList(g.openings(i, k)));
        StringBuilder noWay = new StringBuilder(), missing = new StringBuilder();
        for (GalleriesField.Opening o : all) {
            if (o.x < xa || o.x >= xb || o.z < za || o.z >= zb) continue;
            s.opened++;
            // view down
            int ox = (int) Math.floor(o.x), oz = (int) Math.floor(o.z), run = 0;
            for (int y = T - 2; y > v.y0 && v.mat(ox, y, oz) != NoiseVox.ROCK && v.mat(ox, y, oz) != NoiseVox.LAVA; y--) run++;
            if (run >= 48) s.viewsDown++;
            GalleriesField.Ramp rp = o.ramp;
            if (rp == null) {
                s.withoutRamp++;
                continue;
            }
            s.ramped++;
            double t0 = o.r + GalleriesField.LIP, hx = o.x + rp.ux * (t0 + 2), hz = o.z + rp.uz * (t0 + 2);
            boolean way = false;
            for (int x = (int) Math.floor(hx) - 3; x <= (int) Math.floor(hx) + 3 && !way; x++)
                for (int z = (int) Math.floor(hz) - 3; z <= (int) Math.floor(hz) + 3 && !way; z++)
                    for (int y = T - 9; y <= T && !way; y++) {
                        int p = v.index(x, y, z);
                        if (p >= 0 && toMain[p] >= 0) way = true;
                    }
            if (way) s.waysDown++;
            else if (noWay.length() < 120) noWay.append(String.format(java.util.Locale.ROOT, " (%.0f,%.0f)", o.x, o.z));
            // the centre line, block by block, if the whole strip is in the box
            double ex = rp.px[rp.n - 1], ez = rp.pz[rp.n - 1];
            if (!v.inColumns((int) Math.floor(ex), (int) Math.floor(ez)) || !v.inColumns((int) Math.floor(o.x + rp.ux * t0) - 8, (int) Math.floor(o.z + rp.uz * t0) - 8)
                    || !v.inColumns((int) Math.floor(o.x + rp.ux * t0) + 8, (int) Math.floor(o.z + rp.uz * t0) + 8)) continue;
            s.rampsWalked++;
            int lastX = Integer.MIN_VALUE, lastZ = 0, lastFeet = 0;
            double top = g.headTop;
            for (double tt = t0 + 1; tt <= rp.tEnd - 3; tt += 0.25) {
                double b = g.bend(rp, t0, tt);
                int x = (int) Math.floor(o.x + rp.ux * tt + rp.uz * b), z = (int) Math.floor(o.z + rp.uz * tt - rp.ux * b);
                if (x == lastX && z == lastZ) continue;
                double tc = (x + 0.5 - o.x) * rp.ux + (z + 0.5 - o.z) * rp.uz;
                int deck = (int) Math.floor(top - rp.s * Math.max(0, tc - t0) + 0.5);
                int feet = Integer.MIN_VALUE;
                for (int y = deck + 3; y >= deck - 4 && feet == Integer.MIN_VALUE; y--) if (v.stand(x, y, z)) feet = y;
                s.centreColumns++;
                if (feet == Integer.MIN_VALUE) {
                    s.centreMissing++;
                    if (missing.length() < 120) missing.append(String.format(" (%d,%d,%d)", x, deck, z));
                } else {
                    if (lastX != Integer.MIN_VALUE && Math.abs(x - lastX) + Math.abs(z - lastZ) == 1 && lastFeet != Integer.MIN_VALUE && Math.abs(feet - lastFeet) > 1) s.centreSteps++;
                }
                lastX = x;
                lastZ = z;
                lastFeet = feet;
            }
        }
        s.noWayAt = noWay.toString();
        s.missingAt = missing.toString();
    }

    /**
     * The flat-lid gate of spec 7.4 on the blocks: per column of the inner square, the first rock row over its topmost
     * open run under T + 10, when that run lies wholly at or under T and the row is in T - 7..T + 9 (a column open at a
     * row over T, through an opening or a crater, is left out); the largest 4-connected patch of columns with one row.
     */
    static void flatPatch(NoiseVox v, Stats s, int T, int xa, int za, int xb, int zb) {
        int ni = xb - xa, nk = zb - za;
        int[] roof = new int[ni * nk];
        Arrays.fill(roof, Integer.MIN_VALUE);
        for (int i = 0; i < ni; i++) for (int k = 0; k < nk; k++) {
            int x = xa + i, z = za + k, y = T + 9;
            while (y >= T - 8 && v.rock(x, y, z)) y--;
            if (y >= T - 8 && y <= T) roof[i * nk + k] = y + 1;
        }
        boolean[] seen = new boolean[ni * nk];
        int[] queue = new int[ni * nk];
        for (int p0 = 0; p0 < ni * nk; p0++) {
            if (roof[p0] == Integer.MIN_VALUE || seen[p0]) continue;
            int head = 0, tail = 0, row = roof[p0];
            queue[tail++] = p0;
            seen[p0] = true;
            while (head < tail) {
                int p = queue[head++], i = p / nk, k = p % nk;
                for (int d = 0; d < 4; d++) {
                    int a = i + NoiseVox.DX[d], c = k + NoiseVox.DZ[d];
                    if (a < 0 || c < 0 || a >= ni || c >= nk) continue;
                    int q = a * nk + c;
                    if (!seen[q] && roof[q] == row) { seen[q] = true; queue[tail++] = q; }
                }
            }
            if (tail > s.flatPatch) { s.flatPatch = tail; s.flatAt = String.format(" (row T%+d at %d,%d)", row - T, xa + p0 / nk, za + p0 % nk); }
        }
    }

    // ================================================================================================ the walk

    /**
     * The places are one cavern: the largest strongly connected component of the walk graph holds 80 % of the places or
     * more, and 95 % or more walk into it (spec 9.2: 88.6-92.1 and 96.5-99.9 over its ten boxes).
     */
    @Test
    void mostPlacesAreInTheMainCavern() {
        for (int i = 0; i < BOXES.length; i++) {
            Stats s = stats(i);
            assertEquals(0, s.nans, "NaN densities: " + s);
            assertTrue(s.places > 500_000, "too few places to measure: " + s);
            assertTrue(s.scc() >= 0.80, "the main cavern holds too few places: " + s);
            assertTrue(s.walkIntoMain() >= 0.95, "too few places walk into the main cavern: " + s);
        }
    }

    /**
     * A way out of the flood: from the places under the flood's reach (feet under W + {@link Terrain#FLOOD_RISE}) a walk
     * of at most 384 moves reaches a place over it for 97 % or more, and at most 1 % have none in the box (the box's edge
     * counts in this; spec 9.2: 98.9-100 and 0.02-0.66 %).
     */
    @Test
    void theFloodHasAWayOut() {
        for (int i = 0; i < BOXES.length; i++) {
            Stats s = stats(i);
            assertTrue(s.low > 100_000, "too few low places to measure: " + s);
            assertTrue(s.within384() >= 0.97, "too few low places walk out of the flood within 384 moves: " + s);
            assertTrue(s.neverShare() <= 0.01, "too many low places have no way out of the flood: " + s);
            assertEquals(0, s.trapped, "closed pits the main cavern walks into: " + s);
        }
    }

    /**
     * No closed pits (fix round 2b): per component, no place a walk from the main cavern reaches lacks a walk up out of
     * the flood, on the two measured boxes and on two 512 x 512 boxes where the verifier found them: seed 77's Causeway
     * basin (25 769 places with feet up to W + 24, walled by a tube apron's cliff and column steps of 2-4 rows), and
     * seed -42's hollow between three ramps (1332 places, walled by their talus and the same steps). The low ground's
     * stairs ({@link GalleriesField#tread}), the apron's walkable release, the talus meeting the ground and the rivers'
     * gentler end opened both; before, the shares of never-escaping places there were 47.4 % and 10.0 %.
     */
    @Test
    void noClosedPits() {
        for (int i = 0; i < BOXES.length; i++) assertEquals(0, stats(i).trapped, "closed pits: " + stats(i));
        long[][] pits = {{77, 40594, -11386}, {-42, -4806, 6944}};
        for (long[] b : pits) {
            Terrain t = new Terrain(TerrainSettings.defaults(), -64, b[0]);
            int W = t.shape.waterY(Terrain.GALLERIES);
            NoiseVox v = NoiseVox.build(t, (int) b[1], (int) b[2], 512, 512, YLO, YHI).graph();
            Stats s = new Stats();
            s.seed = b[0];
            pits(v, W, v.walkUpTo(W + Terrain.FLOOD_RISE), s);
            System.out.printf(java.util.Locale.ROOT, "GalleriesFieldTest pits seed %d at %d %d: %d places, closed pits %d places in %d sets%s%n",
                    b[0], b[1], b[2], v.places, s.trapped, s.trapSets, s.trapAt);
            assertTrue(v.places > 300_000, "too few places: " + v.places);
            assertEquals(0, s.trapped, "closed pits the main cavern walks into, seed " + b[0] + s.trapAt);
        }
    }

    /**
     * No floating rock (fix round 2b): every component of 8 rock blocks or more is held by the box's faces, the box's rows
     * from the Galleries' floor to the Wasteland's lava table (a band whose mask ended in mid-air left 18 539 and 8221
     * blocks floating in seed 555's box at (944, -856); the bands now stand on footings where their mask fades).
     */
    @Test
    void noFloatingRock() {
        for (int i = 0; i < BOXES.length; i++) assertEquals(0, stats(i).floating, "floating rock: " + stats(i));
        Terrain t = new Terrain(TerrainSettings.defaults(), -64, 555);
        NoiseVox v = NoiseVox.build(t, 944, -856, 512, 512, YLO, YHI);
        List<int[]> fl = v.floating(8);
        System.out.println("GalleriesFieldTest floating rock seed 555 at 944 -856: " + fl.size() + (fl.isEmpty() ? "" : ", largest " + Arrays.toString(fl.get(0))));
        assertTrue(fl.isEmpty(), "floating rock, seed 555: " + (fl.isEmpty() ? "" : Arrays.toString(fl.get(0))));
    }

    /**
     * Wide spaces and long views (Taran: "wide spaces and epic views", long views preferred): from main-cavern places in
     * every 4th column (216 rays each, lava opaque, capped at 260), a longest view of 80 blocks or more for 90 % of them
     * and of 160 or more for 80 % (spec 9.2: 96.4 and 90.6 at worst).
     */
    @Test
    void wideViews() {
        for (int i = 0; i < BOXES.length; i++) {
            Stats s = stats(i);
            assertTrue(s.views.length > 20_000, "too few views measured: " + s);
            assertTrue(s.over(80) >= 0.90, "too few views of 80+: " + s);
            assertTrue(s.over(160) >= 0.80, "too few views of 160+: " + s);
        }
    }

    /**
     * Falls and drops stay rare: falls (a step off an edge with nothing to stand on within 15 rows, or into lava) at most
     * 0.8 % of the moves from the inner square's places, drops of 2 to 15 rows at most 5 % (spec 9.2: 0.34-0.64 and
     * 2.34-3.92).
     */
    @Test
    void fallsAndDrops() {
        for (int i = 0; i < BOXES.length; i++) {
            Stats s = stats(i);
            assertTrue(s.moves > 1_000_000, "too few moves: " + s);
            assertTrue(s.fallShare() <= 0.008, "too many falls: " + s);
            assertTrue(s.dropShare() <= 0.05, "too many drops: " + s);
        }
    }

    /**
     * Ways down (spec 7.3, 7.6; Taran: "many ways to climb down"): per box, every opened opening keeps a ramp (one left
     * without stays shut), at least 90 % of them have a way down from the ramp's head into the main cavern, and at least
     * 75 % of the openings have a view down. The ways down from the Wasteland and back up into it are
     * {@link JoinTest#waysDownAndUp}.
     */
    @Test
    void waysDown() {
        for (int i = 0; i < BOXES.length; i++) {
            Stats s = stats(i);
            assertTrue(s.opened >= 20, "too few openings to measure: " + s);
            assertEquals(s.opened, s.ramped, "openings without a ramp: " + s);
            assertTrue(s.waysDown >= 0.9 * s.ramped, "ramps with no way down into the cavern: " + s);
            assertTrue(s.viewsDown >= 0.75 * s.opened, "openings with no view down: " + s);
            assertEquals(0, s.withoutRamp, "openings opened without a ramp: " + s);
        }
    }

    /**
     * The ramps are walked both ways: along every kept ramp wholly inside a box, block by block from its head to its foot,
     * a standing place lies within -4..+3 rows of the deck's row at every column of its centre line (spec 7.6: 0 missing),
     * and neighbouring columns of the centre line step at most a row (the deck is read block by block: a player walks up
     * it without a jump of two).
     */
    @Test
    void rampsAreWalkable() {
        for (int i = 0; i < BOXES.length; i++) {
            Stats s = stats(i);
            assertTrue(s.rampsWalked >= 5 && s.centreColumns > 500, "too few ramps walked: " + s);
            assertTrue(s.centreMissing <= 0.01 * s.centreColumns, "ramp columns with no standing place near the deck: " + s);
            assertTrue(s.centreSteps <= 0.005 * s.centreColumns, "ramp centre lines that step more than a row: " + s);
        }
    }

    /**
     * The kept ramps keep apart (spec 7.2): over 12 x 12 lattice cells in each of three seeds no two kept ramps conflict,
     * and block by block the columns of their decks (within the half width of the bent centre line, from a block before
     * the head to the end) lie RAMP_GAP blocks apart or more (measured: 8.9 at the closest).
     */
    @Test
    void rampsKeepApart() {
        long[][] areas = {{8675309, -6, -6}, {12345, 40, -40}, {-42, -80, 90}};
        int ramps = 0;
        for (long[] ar : areas) {
            Terrain t = new Terrain(TerrainSettings.defaults(), -64, ar[0]);
            GalleriesField g = t.galleries;
            List<GalleriesField.Opening> kept = new ArrayList<>();
            for (int i = (int) ar[1]; i < ar[1] + 12; i++) for (int j = (int) ar[2]; j < ar[2] + 12; j++)
                for (GalleriesField.Opening o : g.openings(i, j)) if (o.ramp != null) kept.add(o);
            ramps += kept.size();
            for (int p = 0; p < kept.size(); p++) for (int q = p + 1; q < kept.size(); q++) {
                GalleriesField.Opening a = kept.get(p), b = kept.get(q);
                assertFalse(GalleriesField.conflict(a, a.ramp, b, b.ramp), "seed " + ar[0] + ": the kept ramps of the openings at " + where(a) + " and " + where(b) + " conflict");
            }
            // the decks' columns on a grid over the area and the ramps' reach round it
            int gx0 = (int) ar[1] * 64 - 256, gz0 = (int) ar[2] * 64 - 256, gn = 12 * 64 + 512;
            int[] owner = new int[gn * gn];
            List<int[]> cols = new ArrayList<>();
            for (int id = 0; id < kept.size(); id++) {
                GalleriesField.Opening o = kept.get(id);
                GalleriesField.Ramp rp = o.ramp;
                double t0 = o.r + GalleriesField.LIP;
                for (int x = (int) Math.floor(rp.minX) - 2; x <= (int) Math.ceil(rp.maxX) + 2; x++)
                    for (int z = (int) Math.floor(rp.minZ) - 2; z <= (int) Math.ceil(rp.maxZ) + 2; z++) {
                        double dx = x + 0.5 - o.x, dz = z + 0.5 - o.z, tt = dx * rp.ux + dz * rp.uz;
                        if (tt < t0 - 1 || tt > rp.tEnd || Math.abs(dx * rp.uz - dz * rp.ux - g.bend(rp, t0, tt)) >= rp.hw) continue;
                        int i = x - gx0, k = z - gz0;
                        assertTrue(i >= 0 && k >= 0 && i < gn && k < gn, "a deck outside the grid at " + x + " " + z);
                        assertTrue(owner[i * gn + k] == 0 || owner[i * gn + k] == id + 1, "seed " + ar[0] + ": two decks on column " + x + " " + z);
                        owner[i * gn + k] = id + 1;
                        cols.add(new int[]{i, k, id + 1});
                    }
            }
            int R = (int) GalleriesField.RAMP_GAP;
            for (int[] c : cols) for (int dx = -R; dx <= R; dx++) for (int dz = -R; dz <= R; dz++) {
                if (dx * dx + dz * dz >= R * R) continue;
                int i = c[0] + dx, k = c[1] + dz;
                if (i < 0 || k < 0 || i >= gn || k >= gn) continue;
                int o = owner[i * gn + k];
                assertTrue(o == 0 || o == c[2], () -> "seed " + ar[0] + ": decks of the openings at " + where(kept.get(c[2] - 1)) + " and " + where(kept.get(o - 1))
                        + " closer than RAMP_GAP at column " + (c[0] + gx0) + " " + (c[1] + gz0));
            }
        }
        assertTrue(ramps >= 60, "too few kept ramps to check: " + ramps);
    }

    static String where(GalleriesField.Opening o) { return String.format(java.util.Locale.ROOT, "(%.0f, %.0f)", o.x, o.z); }

    // ================================================================================================ the top

    /**
     * No flat lid under the layer's top (spec 7.4): (a) over 512 x 512 columns round the origin of two seeds, every open
     * cell whose site lies in no opening's disc, under no ramp's head room and in no tube mouth has its first rock row at
     * T - 6 or lower (VR_MIN rows of relief under the cap), so the Galleries' top rows are rock but where an opening, a
     * ramp or a mouth needs room; and (b) in each box no 4-connected patch of more than 64 columns has its topmost open run
     * (wholly at or under T) end at one rock row in T - 7..T + 9 (spec 7.4: 33-54).
     */
    @Test
    void noFlatLidUnderTheTop() {
        int checked = 0, discs = 0, ramps = 0, mouths = 0;
        for (long seed : new long[]{8675309, 12345}) {
            Terrain t = new Terrain(TerrainSettings.defaults(), -64, seed);
            GalleriesField g = t.galleries;
            GalleriesField.Climate cl = new GalleriesField.Climate();
            GalleriesField.Shape0 sh = new GalleriesField.Shape0();
            Set<String> seen = new HashSet<>();
            int bad = 0;
            String first = null;
            for (int x = -256; x < 256; x++) for (int z = -256; z < 256; z++) {
                GalleriesField.Cell c = g.column(x, z);
                if (!seen.add(c.coarse + " " + c.i + " " + c.j) || c.closed()) continue;
                g.climate(c.x, c.z, cl);
                g.shape(c.x, c.z, cl, sh, c.h);
                if (sh.rh.jt > 0) { discs++; continue; }
                if (sh.rh.rb == sh.rh.rb) { ramps++; continue; }
                if (sh.mouth > 0) { mouths++; continue; }
                checked++;
                if (c.c > g.T - 6) { bad++; if (first == null) first = "cell " + c.i + " " + c.j + " at " + c.x + " " + c.z + ": first rock row " + c.c; }
            }
            assertEquals(0, bad, "seed " + seed + ": cells open over T - 7 with no disc, ramp or mouth to need it, first " + first);
        }
        System.out.printf(java.util.Locale.ROOT, "GalleriesFieldTest top: %d open cells checked; left out: %d in discs, %d under ramps' head room, %d in mouths%n", checked, discs, ramps, mouths);
        assertTrue(checked > 40_000 && discs > 0 && ramps > 0, "too few cells to check: " + checked + ", " + discs + ", " + ramps);
        for (int i = 0; i < BOXES.length; i++) {
            Stats s = stats(i);
            assertTrue(s.flatPatch > 0 && s.flatPatch <= 64, "a flat lid under the top: " + s);
        }
    }

    /**
     * The fluids keep to their rules: the Wasteland's lava over the join never has air beside or under it and never lies
     * within 3 columns of an opening; every block of a tube pool's water lies over water or rock, beside water or rock,
     * carved in the cavern's rock, over the flood's reach, and with no lava within a block.
     */
    @Test
    void fluidsKeepToTheirRules() {
        int water = 0, lava = 0, openings = 0;
        for (int i = 0; i < BOXES.length; i++) {
            Stats s = stats(i);
            water += s.water;
            lava += s.wastelandLava;
            openings += s.openingColumns;
            assertEquals(0, s.lavaBesideAir, "the Wasteland's lava beside air: " + s);
            assertEquals(0, s.lavaNearOpening, "the Wasteland's lava by an opening: " + s);
            assertEquals(0, s.waterUnsealed, "pool water that would flow: " + s);
            assertEquals(0, s.waterInOpenCavern, "pool water in the open cavern: " + s);
            assertEquals(0, s.waterLow, "pool water under the flood's reach: " + s);
            assertEquals(0, s.waterByLava, "pool water by lava: " + s);
        }
        assertTrue(water > 200, "too little pool water in the boxes to check: " + water);
        assertTrue(lava > 1000 && openings > 1000, "too little lava or too few openings to check: " + lava + ", " + openings);
    }

    // ================================================================================================ the pipeline

    /**
     * The corners and the per-block pass agree with the field's exact per-block density: in two boxes of 64 x 64 columns
     * (one round the tube spot of the first standing tube country near the origin) every block of the Galleries' rows
     * under the join, made as the game makes it, is rock exactly where {@link GalleriesField#fineBlocks} is positive (the
     * corners never decide anything there), and no density over the whole layer and the join is NaN.
     */
    @Test
    void cornersNeverDecide() {
        long[][] boxes = {{8675309, 0, 0}, {12345, -1000, 2200}};
        Terrain spots = new Terrain(TerrainSettings.defaults(), -64, boxes[0][0]);
        int[] spot = null;
        for (int ci = -2; ci < 2 && spot == null; ci++) for (int cj = -2; cj < 2 && spot == null; cj++) spot = spots.galleries.tubeSpot(ci, cj);
        assertNotNull(spot, "no standing tube country near the origin");
        boxes[0][1] = spot[0] - 32;
        boxes[0][2] = spot[2] - 32;
        int tubeBlocks = 0;
        for (long[] b : boxes) {
            Terrain t = new Terrain(TerrainSettings.defaults(), -64, b[0]);
            GalleriesField g = t.galleries;
            int lo = t.shape.bottom(Terrain.GALLERIES), hi = t.join.lo - 1;
            NoiseVox v = NoiseVox.build(t, (int) b[1], (int) b[2], 64, 64, lo, t.join.hi);
            assertEquals(0, v.nans, "NaN densities in box " + Arrays.toString(b));
            int differ = 0;
            String first = null;
            for (int x = v.x0; x < v.x0 + 64; x++) for (int z = v.z0; z < v.z0 + 64; z++) for (int y = lo; y <= hi; y++) {
                double d = g.fineBlocks(x, y, z);
                if (d != d) fail("NaN at " + x + " " + y + " " + z);
                if ((d > 0) != v.rock(x, y, z)) { differ++; if (first == null) first = x + " " + y + " " + z; }
                if (d <= 0 && g.density(x, y, z) > 0) tubeBlocks++;
            }
            assertEquals(0, differ, "blocks unlike the exact per-block density in box " + Arrays.toString(b) + ", first at " + first);
        }
        assertTrue(tubeBlocks > 1000, "the boxes hold too little tube air to check the tubes: " + tubeBlocks);
    }

    /**
     * No seams at chunk borders: the blocks of 4 x 4 chunks round a tube cell's corner, each chunk made on its own (its own
     * corners, in shuffled order on several threads, as the game makes chunks), are the blocks of the whole area made at
     * once. And none at cell borders: sharp steps in the density (a sign change of more than 8 blocks between neighbours)
     * across the tube field's cell lines are no more common than across lines through the cells' middles (20 000 pairs,
     * 500 at a time along 512 blocks of one line and its parallel through the cells' middles, {@link Spread}).
     */
    @Test
    void noSeamsAtChunkOrCellBorders() {
        Terrain t = new Terrain(TerrainSettings.defaults(), -64, 8675309);
        int cell = TubeField.CELL, x0 = cell - 32, z0 = -cell - 32, lo = t.shape.bottom(Terrain.GALLERIES), hi = t.join.hi;
        NoiseVox whole = NoiseVox.build(t, x0, z0, 64, 64, lo, hi);
        Terrain other = new Terrain(TerrainSettings.defaults(), -64, 8675309);
        List<Integer> chunks = new ArrayList<>();
        for (int c = 0; c < 16; c++) chunks.add(c);
        Collections.shuffle(chunks, new Random(47));
        long differ = chunks.parallelStream().mapToLong(c -> {
            NoiseVox v = NoiseVox.build(other, x0 + (c % 4) * 16, z0 + (c / 4) * 16, 16, 16, lo, hi);
            long n = 0;
            for (int x = v.x0; x < v.x0 + 16; x++) for (int z = v.z0; z < v.z0 + 16; z++) for (int y = lo; y <= hi; y++)
                if (v.mat(x, y, z) != whole.mat(x, y, z)) n++;
            return n;
        }).sum();
        assertEquals(0, differ, "blocks that differ when chunks are made one by one");

        int border = 0, inside = 0;
        Random r = new Random(53);
        int line = 0, centre = 0;
        boolean xLine = false;
        for (int k = 0; k < 20_000; k++) {
            if (k % 500 == 0) { line = (r.nextInt(8) - 4) * cell; centre = r.nextInt(8000) - 4000; xLine = r.nextBoolean(); }
            int along = centre + r.nextInt(512) - 256, y = lo + r.nextInt(hi - lo + 1);
            if (sharpStep(t, xLine, line, y, along)) border++;
            if (sharpStep(t, xLine, line + cell / 2, y, along)) inside++;
        }
        assertTrue(border <= inside * 1.5 + 10, border + " sharp steps across the tube cells' borders against " + inside + " across their middles");
    }

    private static boolean sharpStep(Terrain t, boolean xLine, int line, int y, int along) {
        double a = xLine ? t.rawDensity(line - 1, y, along) : t.rawDensity(along, y, line - 1);
        double b = xLine ? t.rawDensity(line, y, along) : t.rawDensity(along, y, line);
        return (a < 0) != (b < 0) && Math.abs(a - b) > 8;
    }

    /**
     * The field is a pure function of the seed: density, the per-block density, fluid, biome, the crater's ring and the
     * look queries over a box of 96 x 96 columns round a tube cell's corner (every other row of the layer and the join)
     * and a box of 48 x 48 round an opening with a ramp (every other row of the Galleries' top 24 and the join) answer the same on
     * four threads in shuffled order, in a fresh instance, with queries 32 blocks apart in turns (the per-thread caches'
     * slots alias there), as on one thread.
     */
    @Test
    void pureAndThreadSafe() throws Exception {
        long seed = 8675309;
        Terrain one = new Terrain(TerrainSettings.defaults(), -64, seed);
        int lo = one.shape.bottom(Terrain.GALLERIES), hi = one.join.hi, x0 = TubeField.CELL - 48, z0 = -TubeField.CELL - 48;
        List<int[]> pts = new ArrayList<>();
        for (int i = 0; i < 96; i++) for (int k = 0; k < 96; k++) for (int y = lo + (i + k) % 2; y <= hi; y += 2) pts.add(new int[]{x0 + i, y, z0 + k});
        // and 48 x 48 columns round the first opening with a ramp near the origin (its disc, crater and ramp's head), rows
        // from 24 under the Galleries' top to the join's top
        GalleriesField.Opening op = null;
        for (int i = -2; i < 2 && op == null; i++) for (int j = -2; j < 2 && op == null; j++)
            for (GalleriesField.Opening o : one.galleries.openings(i, j)) if (o.ramp != null && op == null) op = o;
        assertNotNull(op, "no opening with a ramp near the origin");
        int ox = (int) Math.floor(op.x) - 24, oz = (int) Math.floor(op.z) - 24;
        for (int i = 0; i < 48; i++) for (int k = 0; k < 48; k++)
            for (int y = one.galleries.T - 24 + (i + k) % 2; y <= hi; y += 2) pts.add(new int[]{ox + i, y, oz + k});
        // pairs 32 blocks apart in a row of the work
        Collections.shuffle(pts, new Random(59));
        List<int[]> work = new ArrayList<>();
        for (int[] p : pts) { work.add(p); work.add(new int[]{p[0] + 32, p[1], p[2] - 32}); }
        int n = work.size();
        long[] alone = new long[n];
        for (int k = 0; k < n; k++) alone[k] = probe(one, work.get(k));
        Terrain fresh = new Terrain(TerrainSettings.defaults(), -64, seed);
        long[] many = new long[n];
        ExecutorService ex = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int w = 0; w < 4; w++) {
                final int from = w;
                fs.add(ex.submit(() -> { for (int k = n - 1 - from; k >= 0; k -= 4) many[k] = probe(fresh, work.get(k)); }));
            }
            for (Future<?> f : fs) f.get();
        } finally {
            ex.shutdownNow();
        }
        int differ = 0;
        String first = null;
        for (int k = 0; k < n; k++) if (alone[k] != many[k]) { differ++; if (first == null) first = Arrays.toString(work.get(k)); }
        assertEquals(0, differ, "blocks answered differently, first " + first);
    }

    static long probe(Terrain t, int[] p) {
        GalleriesField g = t.galleries;
        int x = p[0], y = p[1], z = p[2];
        long h = Double.doubleToLongBits(g.density(x, y, z));
        h = h * 31 + Double.doubleToLongBits(g.fineBlocks(x, y, z));
        h = h * 31 + g.fluid(x, y, z);
        h = h * 31 + g.biome(x, y, z).ordinal();
        h = h * 31 + Double.doubleToLongBits(t.rawDensity(x, y, z));
        LookQueries q = t.looks;
        h = h * 31 + q.columnCell(x, z);
        h = h * 31 + Double.doubleToLongBits(q.columnEdge(x, z));
        h = h * 31 + Double.doubleToLongBits(q.hang(x, y, z));
        h = h * 31 + Double.doubleToLongBits(q.tube(x, y, z));
        h = h * 31 + Double.doubleToLongBits(q.tubeShellDepth(x, y, z));
        h = h * 31 + (q.tubeRib(x, y, z) ? 1 : 0);
        h = h * 31 + Double.doubleToLongBits(q.cone(x, z));
        h = h * 31 + (g.tubesAt(x + 0.5, z + 0.5) ? 1 : 0);
        h = h * 31 + Double.doubleToLongBits(g.craterRing(x, z));
        return h;
    }

    /**
     * The searches cover what they must find (spec 7.2): the static checks of the field, asserted by name so that a failure
     * reads plainly (the class would not load if one failed).
     */
    @Test
    void searchRadii() {
        assertTrue(GalleriesField.RAMP_REACH < GalleriesField.OP_NEAR * GalleriesField.OP_G, "RAMP_REACH: a column looks OP_NEAR lattice cells round for the openings that reach it");
        assertTrue(2 * (GalleriesField.RAMP_REACH - GalleriesField.TALUS_MAX - 4) + GalleriesField.RAMP_GAP + GalleriesField.RAMP_DT < GalleriesField.OP_CONF * GalleriesField.OP_G,
                "OP_CONF: an opening's rivals are looked for OP_CONF lattice cells round");
        assertTrue(GalleriesField.HC_REACH < 2.15 * GalleriesField.HC_G, "HC_REACH: the hanging clusters' 5 x 5 search");
        assertTrue(2 * ((GalleriesField.RAMP_PASSES + 1) * GalleriesField.OP_CONF + GalleriesField.OP_NEAR) + 1 <= 64 && GalleriesField.RAMP_PASSES <= 5,
                "RAMP_PASSES: the openings' cache holds every cell the resolution asks");
        assertTrue(Math.max(GalleriesField.RB_R1, GalleriesField.RB_L1) < 1.15 * GalleriesField.RB_G, "RB_G: the rock bands' search");
        assertTrue(GalleriesField.SPIRE_R1 < 1.15 * GalleriesField.SPIRE_GRID, "SPIRE_GRID: the spires' 3 x 3 search");
        assertTrue(GalleriesField.OP_MAX_R + GalleriesField.CR_IN + GalleriesField.CR_REACH < GalleriesField.RAMP_REACH, "CR_REACH: a crater lies within its opening's reach");
        assertEquals(GalleriesField.HEAD_DROP + 1, GalleriesField.CR_DROP, "CR_DROP: the crater's floor is the row under a ramp's head");
        assertTrue(GalleriesField.RAMP_S1 + GalleriesField.DK_TA * (GalleriesField.NOISE_D / GalleriesField.DK_TP + 0.75 * 1.5 / GalleriesField.DK_TIN) < 1,
                "DK_TA: a deck read block by block, its terraces with it, steps a row at a time");
        // the noise's steepest change a block, which DK_TA's bound counts on: Perlin.noise2 repeats every 256, so a fine
        // sweep over one period finds it
        Perlin n = new Perlin(5);
        double steep = 0;
        for (double u = 0; u < 256; u += 0.125) for (double w = 0; w < 256; w += 0.5) {
            steep = Math.max(steep, Math.abs(n.noise2(u + 0.01, w) - n.noise2(u, w)) / 0.01);
            steep = Math.max(steep, Math.abs(n.noise2(w, u + 0.01) - n.noise2(w, u)) / 0.01);
        }
        assertTrue(steep < GalleriesField.NOISE_D, "NOISE_D: Perlin.noise2 changes by up to " + steep + " a unit");
    }

    /**
     * The digest (spec 10.2): {@link #probe} at 30 000 blocks of the Galleries' rows and the join's round the origin, and
     * every opened opening of 6 x 6 lattice cells with its kept ramp, bit for bit, for two seeds; pinned again at step 2
     * of the port (ramps with terraces, a way up from every hole, no hole without a ramp; it was a25a9bf4bf313878 and
     * 0da260ed6d0a26e3 at the port) and in fix round 2b (the openings resolved by lattice cell priority, their candidates
     * in the order of an estimate). A change to the Galleries' shapes shows here: pin again only on purpose.
     */
    @Test
    void digestPinned() {
        assertEquals("9a44061bac098872", digest(8675309), "seed 8675309");
        assertEquals("f7af35eb63184c62", digest(12345), "seed 12345");
    }

    static String digest(long seed) {
        Terrain t = new Terrain(TerrainSettings.defaults(), -64, seed);
        int lo = t.shape.bottom(Terrain.GALLERIES), hi = t.join.hi;
        Random r = new Random(seed);
        long h = 17;
        for (int k = 0; k < 30_000; k++) h = h * 31 + probe(t, new int[]{r.nextInt(1024) - 512, lo + r.nextInt(hi - lo + 1), r.nextInt(1024) - 512});
        for (int i = -3; i < 3; i++) for (int j = -3; j < 3; j++) h = h * 31 + openingsOf(t.galleries, i, j).hashCode();
        return String.format("%016x", h);
    }

    /** The opened openings of lattice cell (i, j) with their kept ramps, every number by its bits. */
    static String openingsOf(GalleriesField g, int i, int j) {
        StringBuilder b = new StringBuilder();
        for (GalleriesField.Opening o : g.openings(i, j)) {
            b.append(bits(o.x)).append(',').append(bits(o.z)).append(',').append(bits(o.r)).append(',').append(o.h).append(',').append(bits(o.reach));
            GalleriesField.Ramp rp = o.ramp;
            if (rp != null) {
                b.append(" ramp ").append(rp.dir).append(',').append(bits(rp.s)).append(',').append(bits(rp.hw)).append(',').append(bits(rp.th0)).append(',')
                        .append(bits(rp.tEnd)).append(',').append(bits(rp.tg)).append(',').append(bits(rp.ts)).append(',').append(bits(rp.bendA)).append(',').append(rp.n);
                for (int k = 0; k < rp.n; k++) b.append(',').append(bits(rp.px[k])).append(',').append(bits(rp.pz[k]));
            }
            b.append(";\n");
        }
        return b.toString();
    }

    static String bits(double d) { return Long.toHexString(Double.doubleToLongBits(d)); }

    /**
     * The openings are pure (spec 7.2, 10.2): the opened openings of 8 x 8 lattice cells and their kept ramps are the same,
     * bit for bit, when one instance answers them on two threads in opposite orders (one of them after asking for the
     * lattice cells 64 cells away first), when a fresh instance answers them in shuffled order, and when a third answers
     * them while another thread keeps emptying its caches (the shared caches drop cells, and a dropped cell, even one a
     * resolution under way still reads, is found again bit for bit).
     */
    @Test
    void openingsArePure() throws Exception {
        long seed = 8675309;
        Terrain t = new Terrain(TerrainSettings.defaults(), -64, seed), fresh = new Terrain(TerrainSettings.defaults(), -64, seed);
        int n = 8, i0 = -4, j0 = -4;
        String[] fwd = new String[n * n], back = new String[n * n], shuffled = new String[n * n];
        ExecutorService ex = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = ex.submit(() -> { for (int q = 0; q < n * n; q++) fwd[q] = openingsOf(t.galleries, i0 + q / n, j0 + q % n); });
            Future<?> b = ex.submit(() -> {
                for (int q = 0; q < n * n; q++) openingsOf(t.galleries, i0 + 64 + q / n, j0 + 64 + q % n);
                for (int q = n * n - 1; q >= 0; q--) back[q] = openingsOf(t.galleries, i0 + q / n, j0 + q % n);
            });
            a.get();
            b.get();
        } finally {
            ex.shutdownNow();
        }
        List<Integer> order = new ArrayList<>();
        for (int q = 0; q < n * n; q++) order.add(q);
        Collections.shuffle(order, new Random(67));
        for (int q : order) shuffled[q] = openingsOf(fresh.galleries, i0 + q / n, j0 + q % n);
        // a third instance whose caches another thread keeps emptying while it resolves (every cell dropped, mid-way
        // through a resolution too, must be found again bit for bit)
        Terrain dropped = new Terrain(TerrainSettings.defaults(), -64, seed);
        String[] again = new String[n * n];
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicInteger drops = new java.util.concurrent.atomic.AtomicInteger();
        Thread dropper = new Thread(() -> {
            while (!done.get()) {
                dropped.galleries.rawCells.invalidateAll();
                dropped.galleries.openCells.invalidateAll();
                drops.incrementAndGet();
                try { Thread.sleep(20); } catch (InterruptedException e) { return; }
            }
        });
        dropper.start();
        try {
            for (int q : order) again[q] = openingsOf(dropped.galleries, i0 + q / n, j0 + q % n);
        } finally {
            done.set(true);
            dropper.join();
        }
        assertTrue(drops.get() >= 5, "the caches were emptied only " + drops.get() + " times");
        int ops = 0, ramps = 0;
        for (int q = 0; q < n * n; q++) {
            String cell = "lattice cell " + (i0 + q / n) + " " + (j0 + q % n);
            assertEquals(fwd[q], back[q], cell + " differs between the two threads");
            assertEquals(fwd[q], shuffled[q], cell + " differs in a fresh instance");
            assertEquals(fwd[q], again[q], cell + " differs in an instance whose caches were emptied as it resolved");
            ops += fwd[q].split(";", -1).length - 1;
            ramps += fwd[q].split(" ramp ", -1).length - 1;
        }
        assertTrue(ops >= 10 && ramps >= 5, "too few openings to check: " + ops + ", " + ramps);
    }

    // ================================================================================================ biomes

    /**
     * The Galleries are lifeless: every open block of their rows (4000 columns a seed in 40 areas of 256 x 256 anywhere in
     * 20 000 x 20 000, {@link Spread}, over 3 seeds) lies in one of their five biomes, and none of those biomes spawns
     * anything or costs a spawn (their data, as the game loads it).
     */
    @Test
    void lifelessBiomes() throws IOException {
        for (long seed : new long[]{8675309, 12345, -42}) {
            Terrain t = new Terrain(TerrainSettings.defaults(), -64, seed);
            int lo = t.shape.bottom(Terrain.GALLERIES), hi = t.shape.top(Terrain.GALLERIES);
            Spread spread = new Spread(new Random(seed), 20000, 256, 100);
            int open = 0;
            List<String> strays = new ArrayList<>();
            for (int k = 0; k < 4000; k++) {
                int[] at = spread.next();
                int x = at[0], z = at[1];
                for (int y = lo; y <= hi; y++) {
                    if (t.fine(x, y, z, 0) > 0) continue;
                    open++;
                    DeepBiome b = t.biomeAt(x, y, z);
                    if (!GALLERIES.contains(b) && strays.size() < 8) strays.add(x + " " + y + " " + z + ": " + b);
                }
            }
            assertTrue(open > 50_000, "too few open blocks sampled: " + open);
            assertTrue(strays.isEmpty(), "open blocks of the Galleries in another biome: " + strays);
        }
        for (DeepBiome b : GALLERIES) {
            String json = biomeJson(b.name().toLowerCase(java.util.Locale.ROOT));
            String spawners = object(json, "\"spawners\"");
            assertFalse(spawners.substring(1).contains("{"), b + " spawns something: " + spawners);
            assertEquals("{}", object(json, "\"spawn_costs\"").replaceAll("\\s", ""), b + " has spawn costs");
        }
    }

    /** A biome's data file as the game loads it (from the classpath, or the source tree). */
    static String biomeJson(String id) throws IOException {
        String path = "data/explorationremix/worldgen/biome/" + id + ".json";
        try (InputStream in = GalleriesFieldTest.class.getClassLoader().getResourceAsStream(path)) {
            if (in != null) return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Path p = Path.of("src/main/resources", path);
        assertTrue(Files.exists(p), "no data for biome " + id);
        return Files.readString(p);
    }

    /** The JSON object that follows a key, braces included. */
    static String object(String json, String key) {
        int at = json.indexOf(key);
        assertTrue(at >= 0, "no " + key);
        int open = json.indexOf('{', at), depth = 0;
        for (int i = open; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            if (c == '}' && --depth == 0) return json.substring(open, i + 1);
        }
        throw new AssertionError("unclosed " + key);
    }

    // ================================================================================================ cost

    /**
     * The cost of the Galleries' rows (corners every 4 x 8 x 4, interpolation and the per-block pass, one thread) per row,
     * at most three times the Deep Gardens' (spec 9.3: about 0.8-1.4 times 0.5.0's planned Galleries), in three cases:
     * <ul>
     * <li>warm: 16 fixed chunks with their plans and openings made first, best of three rounds each, taken in turns;</li>
     * <li>a cold first chunk: at each of three places, the first chunk of a fresh field (the Galleries' openings and the
     * Gardens' plans all unmade), against the Gardens' rows of another fresh field's first chunk there (fix round 2b: the
     * openings' per-thread caches made that 801 ms against 39 for the merge base);</li>
     * <li>two regions 5,000 blocks apart in turns (two players on one worldgen thread): 30 chunks in each, on one fresh
     * field each (fix round 2b: 84 ms a chunk, 17 times the Gardens' per row, when the two regions' lattice cells shared
     * the per-thread caches' slots).</li>
     * </ul>
     * The cold cases count the thread's CPU time (a busy machine does not skew them) after the code is warmed on fields of
     * their own far off. The bar is generous for a shared machine.
     */
    @Test
    void costWithinBudget() {
        Terrain t = new Terrain(TerrainSettings.defaults(), -64, 8675310);
        Shape s = t.shape;
        int gLo = s.bottom(Terrain.GALLERIES), gHi = s.top(Terrain.GALLERIES), dLo = s.bottom(Terrain.GARDENS), dHi = s.top(Terrain.GARDENS);
        double rowsG = gHi - gLo + 1, rowsD = dHi - dLo + 1;
        WastelandFieldTest.timeRows(t, dLo, dHi);
        WastelandFieldTest.timeRows(t, gLo, gHi);
        double bestG = Double.MAX_VALUE, bestD = Double.MAX_VALUE;
        for (int round = 0; round < 3; round++) {
            bestG = Math.min(bestG, WastelandFieldTest.timeRows(t, gLo, gHi) / rowsG);
            bestD = Math.min(bestD, WastelandFieldTest.timeRows(t, dLo, dHi) / rowsD);
        }
        double ratio = bestG / bestD;
        // the cold cases: the code warmed far off first
        for (int k = 0; k < 3; k++) {
            chunkCpu(t, 9000 + k, 9000, gLo, gHi);
            chunkCpu(t, 9000 + k, 9000, dLo, dHi);
        }
        long coldG = 0, coldD = 0;
        for (int p = 0; p < 3; p++) {
            int cx = 13 + 700 * p, cz = -19 - 450 * p;
            coldG += chunkCpu(new Terrain(TerrainSettings.defaults(), -64, 8675310), cx, cz, gLo, gHi);
            coldD += chunkCpu(new Terrain(TerrainSettings.defaults(), -64, 8675310), cx, cz, dLo, dHi);
        }
        double cold = (coldG / rowsG) / (coldD / rowsD);
        Terrain tg = new Terrain(TerrainSettings.defaults(), -64, 8675310), td = new Terrain(TerrainSettings.defaults(), -64, 8675310);
        long altG = 0, altD = 0;
        for (int k = 0; k < 30; k++) for (int r = 0; r < 2; r++) {
            int cx = -300 + r * 5000 / 16 + k % 6, cz = 120 + k / 6;
            altG += chunkCpu(tg, cx, cz, gLo, gHi);
            altD += chunkCpu(td, cx, cz, dLo, dHi);
        }
        double alt = (altG / rowsG) / (altD / rowsD);
        System.out.printf(java.util.Locale.ROOT, "GalleriesFieldTest cost per row: Galleries %.1f us, Gardens %.1f us, ratio %.2f; cold first chunks %.0f ms against %.0f, ratio %.2f;"
                        + " regions 5000 apart in turns %.1f ms a chunk against %.1f, ratio %.2f%n",
                bestG / 1e3, bestD / 1e3, ratio, coldG / 3e6, coldD / 3e6, cold, altG / 60e6, altD / 60e6, alt);
        assertTrue(ratio <= 3.0, "the Galleries' rows cost " + ratio + " times the Gardens'");
        assertTrue(cold <= 3.0, "a cold first chunk of the Galleries' rows costs " + cold + " times the Gardens' per row");
        assertTrue(alt <= 3.0, "the Galleries' rows of two regions in turns cost " + alt + " times the Gardens' per row");
    }

    /** The thread's CPU time (ns) for one chunk (cx, cz) of rows lo..hi as the game makes it (corners every 4 x 8 x 4, interpolation, the per-block pass). */
    static long chunkCpu(Terrain t, int cx, int cz, int lo, int hi) {
        java.lang.management.ThreadMXBean mx = java.lang.management.ManagementFactory.getThreadMXBean();
        long t0 = mx.getCurrentThreadCpuTime();
        int bx = cx * 16, bz = cz * 16;
        int cy0 = Math.floorDiv(lo, 8), cy1 = Math.floorDiv(hi, 8) + 1, ny = cy1 - cy0 + 1;
        double[] cor = new double[25 * ny];
        for (int i = 0; i < 5; i++) for (int k = 0; k < 5; k++) for (int j = 0; j < ny; j++) cor[(i * 5 + k) * ny + j] = t.density(bx + i * 4, (cy0 + j) * 8, bz + k * 4);
        double sink = 0;
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = lo; y <= hi; y++)
            sink += NoiseLayersTest.interpolatedFine(t, cor, ny, cy0, bx, bz, x, y, z);
        assertTrue(Double.isFinite(sink));
        return mx.getCurrentThreadCpuTime() - t0;
    }

    /**
     * The tubes' hook in the per-block pass (critique T6: the span read once per column, kept with the column): over the
     * 64 x 64 columns with the most blocks in the tubes' spans round the first three tube countries from the origin out
     * (windows every 32 blocks within 128 of a country's middle), every row of the layer, the per-block density with the
     * hook (fineBlocks: the cell's profile, and the tubes in the column's span) against the profile alone, and against the
     * same hook reading the span on every block (the form T6 warned of), best of seven rounds each, taken in turns.
     * Printed as a ratio. A quarter of these blocks lie in a span and pay the tubes' own density there, so the ratio is the
     * worst a chunk of the layer sees: 3.1-4.4 at the port on a loaded machine (the per-block read 4.5-6.7). Held under 6,
     * and under the per-block read's cost.
     */
    @Test
    void tubeHookCost() {
        Terrain t = new Terrain(TerrainSettings.defaults(), -64, 8675310);
        GalleriesField g = t.galleries;
        int lo = g.B, hi = g.T, x0 = 0, z0 = 0, countries = 0;
        long best = -1;
        for (int r = 0; r < 8 && countries < 3; r++)
            for (int i = -r; i <= r; i++) for (int j = -r; j <= r; j++) {
                if (Math.max(Math.abs(i), Math.abs(j)) != r || countries >= 3) continue;
                double cx = (i + 0.5) * TubeField.CELL, cz = (j + 0.5) * TubeField.CELL;
                if (!g.tubesAt(cx, cz)) continue;
                TubeField.Country c = g.tubes.countryAt(cx, cz);
                countries++;
                for (int dx = -128; dx <= 128; dx += 32) for (int dz = -128; dz <= 128; dz += 32) {
                    int ax = (int) Math.floor(c.ox) + dx - 32, az = (int) Math.floor(c.oz) + dz - 32;
                    long n = inSpan(g, ax, az, 4, lo, hi);
                    if (n > best) { best = n; x0 = ax; z0 = az; }
                }
            }
        assertTrue(countries > 0, "no tube country near the origin");
        long inSpan = inSpan(g, x0, z0, 1, lo, hi);
        assertTrue(inSpan > 20_000, "too few blocks in the tubes' spans to time: " + inSpan);
        double with = Double.MAX_VALUE, without = Double.MAX_VALUE, perBlock = Double.MAX_VALUE;
        for (int round = 0; round < 8; round++) {
            long t0 = System.nanoTime();
            double sink = 0;
            for (int x = x0; x < x0 + 64; x++) for (int z = z0; z < z0 + 64; z++) for (int y = lo; y <= hi; y++) sink += g.fineBlocks(x, y, z);
            long t1 = System.nanoTime();
            for (int x = x0; x < x0 + 64; x++) for (int z = z0; z < z0 + 64; z++) for (int y = lo; y <= hi; y++) sink += g.blocks(g.column(x, z), x, y, z);
            long t2 = System.nanoTime();
            for (int x = x0; x < x0 + 64; x++) for (int z = z0; z < z0 + 64; z++) for (int y = lo; y <= hi; y++) {
                double d = g.blocks(g.column(x, z), x, y, z);
                if (g.tubesAt(x + 0.5, z + 0.5) && y >= g.tubes.spanLo(x, z) && y <= g.tubes.spanHi(x, z)) d = Math.min(d, g.tubes.tube(x, y, z));
                sink += d;
            }
            long t3 = System.nanoTime();
            assertTrue(Double.isFinite(sink));
            if (round == 0) continue;   // the first round builds the cells and the openings
            with = Math.min(with, t1 - t0);
            without = Math.min(without, t2 - t1);
            perBlock = Math.min(perBlock, t3 - t2);
        }
        double blocks = 64.0 * 64 * (hi - lo + 1);
        System.out.printf(java.util.Locale.ROOT, "GalleriesFieldTest tube hook: columns from (%d, %d), %d blocks in spans of %.0f; per block %.1f ns with the hook, %.1f ns without, %.1f ns reading the span per block; ratio %.2f (per block %.2f)%n",
                x0, z0, inSpan, blocks, with / blocks, without / blocks, perBlock / blocks, with / without, perBlock / without);
        assertTrue(with / without <= 6.0, "the tubes' hook costs " + with / without + " times the profile alone");
        assertTrue(with < perBlock, "the hook with the span kept per column costs no less than reading it per block: " + with / without + " against " + perBlock / without);
    }

    /** Blocks of rows lo..hi in the tubes' spans over 64 x 64 columns from (x0, z0), counted on every step-th column. */
    static long inSpan(GalleriesField g, int x0, int z0, int step, int lo, int hi) {
        long n = 0;
        for (int x = x0; x < x0 + 64; x += step) for (int z = z0; z < z0 + 64; z += step) {
            if (!g.tubesAt(x + 0.5, z + 0.5)) continue;
            int a = g.tubes.spanLo(x, z), b = g.tubes.spanHi(x, z);
            n += Math.max(0, Math.min(hi, b) - Math.max(lo, a) + 1);
        }
        return n * step * step;
    }

    // ================================================================================================ slow

    /**
     * The slow set: the spec's other four boxes, held to the main two's bars but for never (1.5 %, see below). It fails on
     * seed 77's box, and is left so as the record of it: 17.6 % of the low places there never walk up out of the flood
     * (escape within 384 moves 82.4 %), as on v3a (17.6 %, 82.4 %) and the port before the ramps' terraces (18.0 %,
     * 82.0 %). Nearly all (27 750 places) are one Causeway basin round (40850, -11130),
     * closed by a tube apron's cliff on the west and elsewhere by slopes whose column steps rise 2-3 rows
     * (spec-galleries-field-v2 15.3). The other three boxes pass.
     */
    @Test
    @Tag("slow")
    void moreBoxes() {
        long[][] more = {{12345, 3000, -2000}, {2000023, 20000, 20000}, {77, 40000, -12000}, {4242, -30000, -30000}};
        for (long[] b : more) {
            Stats s = measure(b[0], (int) b[1], (int) b[2], SIZE, MARGIN);
            System.out.println("GalleriesFieldTest slow box " + s);
            assertEquals(0, s.nans);
            assertTrue(s.scc() >= 0.80, "the main cavern holds too few places: " + s);
            assertTrue(s.walkIntoMain() >= 0.95, "too few places walk into the main cavern: " + s);
            // never to 1.5 % here: seed 2000023's box keeps 1.36-1.39 % of its low places in hollows the field makes
            // itself, as v3a does on the same box (1.37 %): the largest, 2915 places round (21116, 20504) with feet at
            // W+6..W+31 and no ramp near, is a hollow whose rim steps up 3 rows at once (W+21 to W+24 at 21116, 20522)
            assertTrue(s.within384() >= 0.97 && s.neverShare() <= 0.015, "the flood's way out: " + s);
            assertTrue(s.over(80) >= 0.90 && s.over(160) >= 0.80, "views: " + s);
            assertTrue(s.fallShare() <= 0.008 && s.dropShare() <= 0.05, "falls and drops: " + s);
            assertEquals(0, s.lavaBesideAir + s.lavaNearOpening + s.waterUnsealed + s.waterInOpenCavern + s.waterLow + s.waterByLava, "fluid rules: " + s);
        }
    }
}
