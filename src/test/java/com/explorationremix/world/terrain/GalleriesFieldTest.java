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
 * The Basalt Galleries as a noise field (0.5.1, spec-galleries-field 10.2), tested through what the game makes of them
 * and nothing of their layout: the interim field's domes are being replaced, and these tests hold for any layout that
 * keeps the field's contract (its public methods, the tube hook, the join hook, column cells, climate and the look
 * queries). Blocks are made as the game makes them ({@link NoiseVox}) over boxes of 1536 x 1536 columns, counted on the
 * inner 1112 x 1112 (the spec's 212-block margin). Bars sit under the worst value the spec measured, with margin.
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
        double[] views;
        int wastelandLava, lavaBesideAir, lavaNearOpening, openingColumns;
        int water, waterUnsealed, waterInOpenCavern, waterLow, waterByLava;
        String unsealedAt = "", byLavaAt = "", lavaAt = "";
        double buildSeconds;

        double scc() { return (double) inMain / places; }
        double walkIntoMain() { return (double) toMain / places; }
        double neverShare() { return (double) never / low; }
        double within384() { return (double) within384 / low; }
        double over(double len) { int n = 0; for (double v : views) if (v >= len) n++; return (double) n / views.length; }

        @Override public String toString() {
            return String.format(java.util.Locale.ROOT, "seed %d (built in %.1f s): places %d, SCC %.1f %%, walk into it %.1f %%; low %d, escape <= 384 %.1f %%, never %.2f %%;"
                            + " views from %d: 80+ %.1f %%, 160+ %.2f %%, 192+ %.3f %%; Wasteland lava %d, beside air %d%s, near an opening %d (%d opening columns);"
                            + " water %d, unsealed %d%s, in open cavern %d, under W+40 %d, by lava %d%s; NaN %d",
                    seed, buildSeconds, places, 100 * scc(), 100 * walkIntoMain(), low, 100 * within384(), 100 * neverShare(), views.length, 100 * over(80),
                    100 * over(160), 100 * over(192), wastelandLava, lavaBesideAir, lavaAt, lavaNearOpening, openingColumns, water, waterUnsealed, unsealedAt,
                    waterInOpenCavern, waterLow, waterByLava, byLavaAt, nans);
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
        s.views = v.longestViews(4, xa, za, xb, zb, W, T, YLO, YHI, true, 260);

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

    // ================================================================================================ the walk

    /**
     * The places are one cavern: the largest strongly connected component of the walk graph holds 60 % of the places or
     * more (spec: 72.6-74.5), and 85 % or more walk into it (87.7-90.8).
     */
    @Test
    void mostPlacesAreInTheMainCavern() {
        for (int i = 0; i < BOXES.length; i++) {
            Stats s = stats(i);
            assertEquals(0, s.nans, "NaN densities: " + s);
            assertTrue(s.places > 500_000, "too few places to measure: " + s);
            assertTrue(s.scc() >= 0.60, "the main cavern holds too few places: " + s);
            assertTrue(s.walkIntoMain() >= 0.85, "too few places walk into the main cavern: " + s);
        }
    }

    /**
     * A way out of the flood: from the places under the flood's reach (feet under W + {@link Terrain#FLOOD_RISE}) a walk
     * of at most 384 moves reaches a place over it for 88 % or more (91.8-94.8), and at most 8 % have none in the box
     * (3.6-6.2; the box's edge counts in this).
     */
    @Test
    void theFloodHasAWayOut() {
        for (int i = 0; i < BOXES.length; i++) {
            Stats s = stats(i);
            assertTrue(s.low > 100_000, "too few low places to measure: " + s);
            assertTrue(s.within384() >= 0.88, "too few low places walk out of the flood within 384 moves: " + s);
            assertTrue(s.neverShare() <= 0.08, "too many low places have no way out of the flood: " + s);
        }
    }

    /**
     * Views stay inside the render distance: from main-cavern places in every 4th column (216 rays each, lava opaque), a
     * longest view of 192 blocks or more for at most 0.3 % of them (0.137-0.139), 160 or more for at most 2 % (0.79-0.85);
     * and the halls are wide: 80 or more for 55 % or more (64.8-67.4).
     */
    @Test
    void viewsStayInsideTheRenderDistance() {
        for (int i = 0; i < BOXES.length; i++) {
            Stats s = stats(i);
            assertTrue(s.views.length > 20_000, "too few views measured: " + s);
            assertTrue(s.over(192) <= 0.003, "too many views of 192+: " + s);
            assertTrue(s.over(160) <= 0.02, "too many views of 160+: " + s);
            assertTrue(s.over(80) >= 0.55, "too few wide views: " + s);
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
     * (one in a tube country) every block of the Galleries' rows under the join, made as the game makes it, is rock exactly
     * where {@link GalleriesField#fineBlocks} is positive (the corners never decide anything there), and no density over
     * the whole layer and the join is NaN.
     */
    @Test
    void cornersNeverDecide() {
        long[][] boxes = {{8675309, 154, -306}, {12345, -1000, 2200}};
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
     * across the tube field's cell lines are no more common than across lines through the cells' middles.
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
        for (int k = 0; k < 20_000; k++) {
            int line = (r.nextInt(8) - 4) * cell, along = r.nextInt(8000) - 4000, y = lo + r.nextInt(hi - lo + 1);
            boolean xLine = r.nextBoolean();
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
     * The field is a pure function of the seed: density, the per-block density, fluid, biome and the look queries over a
     * box of 96 x 96 columns round a tube cell's corner (every other row of the layer and the join) answer the same on
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
        h = h * 31 + Double.doubleToLongBits(q.coneTop(x, z));
        h = h * 31 + (g.tubesAt(x + 0.5, z + 0.5) ? 1 : 0);
        return h;
    }

    // ================================================================================================ biomes

    /**
     * The Galleries are lifeless: every open block of their rows (sampled over 3 seeds) lies in one of their five biomes,
     * and none of those biomes spawns anything or costs a spawn (their data, as the game loads it).
     */
    @Test
    void lifelessBiomes() throws IOException {
        for (long seed : new long[]{8675309, 12345, -42}) {
            Terrain t = new Terrain(TerrainSettings.defaults(), -64, seed);
            int lo = t.shape.bottom(Terrain.GALLERIES), hi = t.shape.top(Terrain.GALLERIES);
            Random r = new Random(seed);
            int open = 0;
            List<String> strays = new ArrayList<>();
            for (int k = 0; k < 4000; k++) {
                int x = r.nextInt(20000) - 10000, z = r.nextInt(20000) - 10000;
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
     * at most three times the Deep Gardens' in the same JVM with their plans made first (spec 9.3: about 0.8-1.4 times
     * 0.5.0's planned Galleries). Best of three rounds each, taken in turns; the bar is generous for a shared machine.
     */
    @Test
    void costWithinBudget() {
        Terrain t = new Terrain(TerrainSettings.defaults(), -64, 8675310);
        Shape s = t.shape;
        int gLo = s.bottom(Terrain.GALLERIES), gHi = s.top(Terrain.GALLERIES), dLo = s.bottom(Terrain.GARDENS), dHi = s.top(Terrain.GARDENS);
        WastelandFieldTest.timeRows(t, dLo, dHi);
        WastelandFieldTest.timeRows(t, gLo, gHi);
        double bestG = Double.MAX_VALUE, bestD = Double.MAX_VALUE;
        for (int round = 0; round < 3; round++) {
            bestG = Math.min(bestG, WastelandFieldTest.timeRows(t, gLo, gHi) / (gHi - gLo + 1));
            bestD = Math.min(bestD, WastelandFieldTest.timeRows(t, dLo, dHi) / (dHi - dLo + 1));
        }
        double ratio = bestG / bestD;
        System.out.printf(java.util.Locale.ROOT, "GalleriesFieldTest cost per row: Galleries %.1f us, Gardens %.1f us, ratio %.2f%n", bestG / 1e3, bestD / 1e3, ratio);
        assertTrue(ratio <= 3.0, "the Galleries' rows cost " + ratio + " times the Gardens'");
    }

    // ================================================================================================ slow

    /** The slow set: the spec's other four boxes (main cavern 55 % or more there, 61.9 at worst; the rest as above). */
    @Test
    @Tag("slow")
    void moreBoxes() {
        long[][] more = {{12345, 3000, -2000}, {2000023, 20000, 20000}, {77, 40000, -12000}, {4242, -30000, -30000}};
        for (long[] b : more) {
            Stats s = measure(b[0], (int) b[1], (int) b[2], SIZE, MARGIN);
            System.out.println("GalleriesFieldTest slow box " + s);
            assertEquals(0, s.nans);
            assertTrue(s.scc() >= 0.55, "the main cavern holds too few places: " + s);
            assertTrue(s.walkIntoMain() >= 0.85, "too few places walk into the main cavern: " + s);
            assertTrue(s.within384() >= 0.88 && s.neverShare() <= 0.08, "the flood's way out: " + s);
            assertTrue(s.over(192) <= 0.003, "too many views of 192+: " + s);
            assertEquals(0, s.lavaBesideAir + s.lavaNearOpening + s.waterUnsealed + s.waterInOpenCavern + s.waterLow + s.waterByLava, "fluid rules: " + s);
        }
    }
}
