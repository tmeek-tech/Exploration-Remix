package com.explorationremix.world.terrain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Wasteland and the Basalt Galleries as noise fields (0.5.1), wired into Terrain: the layers above them are what
 * they were (the Blue Reaches less their view breaker), nothing plans the noise layers, the join's rows are one
 * density, and the fields answer the same on any thread. (The fields' own statistics are their own tests'.)
 */
class NoiseLayersTest {
    /**
     * The Deep Gardens and the bands of a2c3d73, before the noise layers: per seed, the plan hashes of layer 1 over
     * cells (-1..0)^2 ({@link NetworkTest#planHash}) and a hash of every block of rows -544..-65 but the Blue Reaches'
     * own (rock after the per-block pass, fluid, biome) of five chunks, as the game builds them (corners every 4 x 8 x 4,
     * interpolated). Until the Blue Reaches lost their view breaker one line pinned both layers; these are a2c3d73's
     * hashes of the same plans and rows, split from it.
     */
    static final String[] UNCHANGED = {
            "seed 1 plans: f1ccde63e4c18e32 acd1f91d5f20af65 e8c65f191f619aa0 cbefaff340029a5c"
                    + " blocks: 36c34b204acb8011 36c34b204acb8011 5a5245a770b05e5b df147f8c7a2e80f3 36c34b204acb8011",
            "seed 2 plans: dce30161b23a0635 7a8895741a10c7e0 497eb3d2807232ab ae0b4f86342fdec8"
                    + " blocks: 36c34b204acb8011 36c34b204acb8011 14f9f144ab61c8d1 cc7bce0c3ac1647e 36c34b204acb8011",
            "seed 8004 plans: 6323b980a808815f cbad450c68a146b0 99745dccf59c17c0 4f4d78c95599c495"
                    + " blocks: 36c34b204acb8011 abed7044411556b8 b7681aecb1b07e24 1be36e9e472c26f 36c34b204acb8011"};
    /**
     * The Blue Reaches the same way: layer 0's plan hashes and its own rows (-272..-81 by default). Pinned again when
     * the view breaker went (Taran, 2026-11-02: long views are welcome, they make the caves look epic, and the breaker's
     * columns, fins and spires made the walls look unnatural): no shape stands in the deep dark or the rift only to cut
     * a view, and the rift's ledge path runs open again. The rest of each plan is a2c3d73's (its hash without the
     * breaker's shapes is the same).
     */
    static final String[] BLUE = {
            "seed 1 plans: 9ccbd3efbffd4858 2b04561b90a77625 53cd7b151cbd1796 33ea1fdad052c141"
                    + " blocks: d7a8389b4d6c059e 6c97765a11a75ff3 e985111e1ec7d967 b2d0858f44798212 eecfad9773024289",
            "seed 2 plans: bb5652fc6f97b070 4bb67f41a6e3a32a 71c98fc34025e323 2a03945ca81363f0"
                    + " blocks: 871413a11c96a0b2 86c1690fea0001f1 d1cb6de834d723de 37afd4d970cda9fe a6930c0bdb192b36",
            "seed 8004 plans: fea4dda6811c9604 f5c7c74c2cdacf1e bb5e10ae5b6af1cd 15fadf23a6d0c07"
                    + " blocks: ccfcc9e7ae39fcb5 96f095f23ac431a7 90d045b652f4d05b d90a6e2c93b653c2 8533a8c9f6e3d85b"};
    static final long[] UNCHANGED_SEEDS = {1, 2, 8004};

    /** As {@link #UNCHANGED} and {@link #BLUE} say, and building them (band 3 too) plans no other layer. */
    @Test
    void blueAndGardensUnchanged() {
        java.util.Set<Integer> planned = java.util.concurrent.ConcurrentHashMap.newKeySet();
        NetPlanner.FailHook was = NetPlanner.failHook;
        String[][] got;
        try {
            NetPlanner.failHook = (seed, li, cx, cz, attempt) -> { planned.add(li); return false; };
            got = java.util.stream.LongStream.of(UNCHANGED_SEEDS).parallel().mapToObj(NoiseLayersTest::identity).toArray(String[][]::new);
        } finally {
            NetPlanner.failHook = was;
        }
        for (int i = 0; i < got.length; i++) {
            assertEquals(UNCHANGED[i], got[i][1], "seed " + UNCHANGED_SEEDS[i] + ": the Deep Gardens and the bands");
            assertEquals(BLUE[i], got[i][0], "seed " + UNCHANGED_SEEDS[i] + ": the Blue Reaches");
        }
        assertEquals(java.util.Set.of(Terrain.BLUE, Terrain.GARDENS), planned, "the layers planned");
    }

    /** One seed's lines: {its line of {@link #BLUE}, its line of {@link #UNCHANGED}}. */
    static String[] identity(long seed) {
        int[][] chunks = {{0, 0}, {37, -12}, {-20, 15}, {5, 30}, {-41, -38}};
        Terrain t = new Terrain(TerrainSettings.defaults(), -64, seed);
        StringBuilder[] sb = {new StringBuilder("seed " + seed + " plans:"), new StringBuilder("seed " + seed + " plans:")};
        for (int li = 0; li < 2; li++) for (int cx = -1; cx <= 0; cx++) for (int cz = -1; cz <= 0; cz++)
            sb[li].append(' ').append(Long.toHexString(NetworkTest.planHash(t.network(li, cx, cz))));
        sb[0].append(" blocks:");
        sb[1].append(" blocks:");
        int ylo = -544, yhi = -65, blo = t.shape.bottom(Terrain.BLUE), bhi = t.shape.top(Terrain.BLUE);
        for (int[] c : chunks) {
            long hb = 17, hr = 17;
            int bx = c[0] * 16, bz = c[1] * 16;
            int cy0 = Math.floorDiv(ylo, 8), cy1 = Math.floorDiv(yhi, 8) + 1, ny = cy1 - cy0 + 1;
            double[] cor = new double[25 * ny];
            for (int i = 0; i < 5; i++) for (int k = 0; k < 5; k++) for (int j = 0; j < ny; j++) cor[(i * 5 + k) * ny + j] = t.density(bx + i * 4, (cy0 + j) * 8, bz + k * 4);
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = ylo; y <= yhi; y++) {
                double d = interpolatedFine(t, cor, ny, cy0, bx, bz, x, y, z);
                int f = d > 0 ? 9 : t.fluidAt(bx + x, y, bz + z);
                DeepBiome b = t.biomeAt(bx + x, y, bz + z);
                long v = Double.doubleToLongBits(d) * 31 + f * 7 + (b == null ? -1 : b.ordinal());
                if (y >= blo && y <= bhi) hb = hb * 1000003L + v;
                else hr = hr * 1000003L + v;
            }
            sb[0].append(' ').append(Long.toHexString(hb));
            sb[1].append(' ').append(Long.toHexString(hr));
        }
        return new String[]{sb[0].toString(), sb[1].toString()};
    }

    /** The per-block pass at a block of a chunk, over the corners' interpolation (as NoiseChunk does it). */
    static double interpolatedFine(Terrain t, double[] cor, int ny, int cy0, int bx, int bz, int x, int y, int z) {
        int i = x >> 2, k = z >> 2, j = Math.floorDiv(y, 8) - cy0;
        double fx = (x & 3) / 4.0, fz = (z & 3) / 4.0, fy = (y - Math.floorDiv(y, 8) * 8) / 8.0;
        double x00 = cor[(i * 5 + k) * ny + j] + (cor[((i + 1) * 5 + k) * ny + j] - cor[(i * 5 + k) * ny + j]) * fx;
        double x10 = cor[(i * 5 + k) * ny + j + 1] + (cor[((i + 1) * 5 + k) * ny + j + 1] - cor[(i * 5 + k) * ny + j + 1]) * fx;
        double x01 = cor[(i * 5 + k + 1) * ny + j] + (cor[((i + 1) * 5 + k + 1) * ny + j] - cor[(i * 5 + k + 1) * ny + j]) * fx;
        double x11 = cor[(i * 5 + k + 1) * ny + j + 1] + (cor[((i + 1) * 5 + k + 1) * ny + j + 1] - cor[(i * 5 + k + 1) * ny + j + 1]) * fx;
        double y0 = x00 + (x10 - x00) * fy, y1 = x01 + (x11 - x01) * fy;
        return t.fine(bx + x, y, bz + z, y0 + (y1 - y0) * fz);
    }

    /**
     * Nothing plans the Wasteland or the Galleries (Terrain.planned is the one switch): every query the game, the
     * features and the dev commands make of their rows is answered with no plan made at all, the plan queries come back
     * empty, and asking for a noise layer's plan throws. So does every query of the network itself for a noise layer,
     * so a caller that goes round Terrain fails as loudly. (400 columns, 16 areas of 256 x 256 anywhere in 4000 x 4000,
     * {@link Spread}.)
     */
    @Test
    void noiseLayersPlanNothing() {
        Terrain t = new Terrain(TerrainSettings.defaults(), -64, 4242);
        Shape s = t.shape;
        int lo = s.floorY + 1, hi = s.top(Terrain.WASTELAND);
        Random r = new Random(5);
        Spread spread = new Spread(r, 4000, 256, 25);
        double sink = 0;
        for (int k = 0; k < 400; k++) {
            int[] at = spread.next();
            int x = at[0], z = at[1];
            for (int y = lo; y <= hi; y += 3) {
                sink += t.density(x, y, z) + t.rawDensity(x, y, z) + t.fine(x, y, z, r.nextDouble() * 2 - 1);
                sink += t.fluidAt(x, y, z) + t.biomeAt(x, y, z).ordinal();
                sink += t.geodeShellDepth(x, y, z) + t.tubeShellDepth(x, y, z);
                LookQueries q = t.looks;
                sink += q.columnCell(x, z) + q.columnEdge(x, z) + q.hang(x, y, z) + q.tube(x, y, z) + q.tubeShellDepth(x, y, z)
                        + (q.tubeRib(x, y, z) ? 1 : 0) + q.cone(x, z) + (q.lavaKept(x, y, z) ? 1 : 0);
                sink += t.palette.shell(x, y, z, 1, 8, false).ordinal();
            }
            for (int li = Terrain.WASTELAND; li < s.layerCount; li++) {
                assertEquals(0, t.wayLinesAt(li, x, z, 8).n, "ways in noise layer " + li);
                assertEquals(0, t.carvedLinesAt(li, x, z, 8).n, "carved ways in noise layer " + li);
                assertTrue(t.shaftsIn(x - 8, z - 8, x + 8, z + 8, li).isEmpty(), "climbs in noise layer " + li);
            }
            if (k % 40 == 0) {
                assertFalse(t.describe(x, s.waterY(Terrain.WASTELAND) + 20, z).isEmpty());
                assertFalse(t.describe(x, s.top(Terrain.GALLERIES) - 2, z).isEmpty());
                assertFalse(t.describe(x, s.waterY(Terrain.GALLERIES) + 40, z).isEmpty());
                t.ventsNear(x, z, 200);
            }
        }
        for (DeepBiome b : new DeepBiome[]{DeepBiome.WASTELAND, DeepBiome.ASH_HOLLOWS, DeepBiome.BASALT_GALLERIES, DeepBiome.CALDERA,
                DeepBiome.CAUSEWAY, DeepBiome.SCORIA_CANYONS, DeepBiome.OBSIDIAN_TUBES})
            sink += t.places(b, 100, -300).size();
        assertTrue(Double.isFinite(sink), "a query came back NaN");
        Network w = t.network;
        boolean was = Network.strict;
        Network.strict = true;
        try {
            for (int li = Terrain.WASTELAND; li < s.layerCount; li++) {
                final int L = li, y = s.waterY(li) + 20;
                final DeepBiome b = Terrain.routeBiome(li);
                assertThrows(IllegalArgumentException.class, () -> t.network(L, 0, 0), "a plan of noise layer " + li);
                List<org.junit.jupiter.api.function.Executable> asks = List.of(() -> w.net(L, 0, 0), () -> w.wayLinesAt(L, 0, 0, 8),
                        () -> w.carvedLinesAt(L, 0, 0, 8), () -> w.shaftsIn(L, -8, -8, 8, 8), () -> w.portalsOfEdge(L, true, 0, 0),
                        () -> w.layerDensity(L, 0, y, 0), () -> w.fine(L, 0, y, 0, 0.5), () -> w.fluidAt(L, 0, y, 0),
                        () -> w.biomeAt(L, 0, y, 0), () -> w.describe(L, 0, y, 0), () -> w.geodeShellDepth(L, 0, y, 0),
                        () -> w.rough(L, 0, y, 0), () -> w.places(b, 0, 0));
                for (int i = 0; i < asks.size(); i++)
                    assertThrows(IllegalArgumentException.class, asks.get(i), "network query " + i + " answered for noise layer " + li);
            }
        } finally {
            Network.strict = was;
        }
        assertEquals(0, t.network.plansMade.sum(), "plans made for the noise layers");
    }

    /**
     * In the game (not strict) a question to the plans about a noise layer gets an empty answer instead of an exception,
     * so a feature or command that still asks cannot stop world generation: no way lines, no climbs, no portals, no words,
     * no geode, and no plan, which the bedrock command refuses. Nothing is planned for it either way.
     */
    @Test
    void noiseLayersAnswerEmptyInTheGame() {
        Terrain t = new Terrain(TerrainSettings.defaults(), -64, 4243);
        Shape s = t.shape;
        Network w = t.network;
        boolean was = Network.strict;
        Network.strict = false;
        try {
            for (int li = Terrain.WASTELAND; li < s.layerCount; li++) {
                int y = s.waterY(li) + 20;
                assertNull(t.network(li, 0, 0), "a plan of noise layer " + li);
                assertNull(w.network(li, 3, -2), "a plan of noise layer " + li);
                assertEquals(0, w.wayLinesAt(li, 0, 0, 8).n, "way lines in noise layer " + li);
                assertEquals(0, w.carvedLinesAt(li, 0, 0, 8).n, "carved lines in noise layer " + li);
                assertTrue(w.shaftsIn(li, -8, -8, 8, 8).isEmpty(), "climbs in noise layer " + li);
                assertTrue(w.portalsOfEdge(li, true, 0, 0).isEmpty(), "portals in noise layer " + li);
                assertTrue(w.describe(li, 0, y, 0).isEmpty(), "plan words for noise layer " + li);
                assertEquals(1e9, w.geodeShellDepth(li, 0, y, 0), "a geode in noise layer " + li);
                assertEquals(0, t.wayLinesAt(li, 0, 0, 8).n);
                assertTrue(t.shaftsIn(-8, -8, 8, 8, li).isEmpty());
            }
        } finally {
            Network.strict = was;
        }
        assertEquals(0, w.plansMade.sum(), "plans made for the noise layers");
    }

    /**
     * The join's rows are one density: Terrain routes them to {@link Join#density} (the two fields' smooth minimum and
     * the seal), and at the band's top and bottom rows the combined density has the sign of the lone field there, so no
     * lid or step forms where the band begins. Where beta is from {@link Join#LAVA_BETA} to under {@link Join#HOLE_BETA}
     * no block of the Wasteland's rows under a row over its lava table is open, whatever the interpolation gives, but in
     * the craters round the holes the Galleries open with a ramp out to {@link WastelandField#crDry} from the hole's edge,
     * which go under the table on purpose ({@link Join#seal} keeps out of them): there every open block under the table is
     * air. A crater's outer ring, where its floor lies over the table and the fluid under it is lava, is sealed like the
     * rest (20 000 columns, 20 areas of 512 x 512 anywhere in 6000 x 6000, {@link Spread}; the craters' fluid rule over
     * more points is WastelandFieldTest.fluidRule).
     */
    @Test
    void joinIsOneDensity() {
        Terrain t = new Terrain(TerrainSettings.defaults(), -64, 8675309L);
        Join j = t.join;
        assertNotNull(j);
        assertTrue(j.on, "the default layout has the join");
        assertEquals(-704, j.lo);
        assertEquals(-673, j.hi);
        Random r = new Random(9);
        Spread spread = new Spread(r, 6000, 512, 1000);
        int bottomFlips = 0, topFlips = 0, sealed = 0, open = 0, craterOpen = 0, craterNotAir = 0;
        String first = null;
        for (int k = 0; k < 20000; k++) {
            int[] at = spread.next();
            int x = at[0], z = at[1];
            int y = j.lo + r.nextInt(j.hi - j.lo + 1);
            double want = Math.max(Network.smin(t.wasteland.density(x, y, z), t.galleries.density(x, y, z), Join.K), j.seal(x, y, z));
            assertEquals(want, t.rawDensity(x, y, z), "the join's density at " + x + " " + y + " " + z);
            if ((t.rawDensity(x, j.lo, z) > 0) != (t.galleries.density(x, j.lo, z) > 0)) bottomFlips++;
            if ((t.rawDensity(x, j.hi, z) > 0) != (t.wasteland.density(x, j.hi, z) > 0)) topFlips++;
            double beta = t.breakthrough.beta(x, z);
            if (beta >= Join.LAVA_BETA && beta < Join.HOLE_BETA) {
                WastelandField.Col c = t.wasteland.column(x, z);
                boolean crater = c.crater > 0 && c.ring < t.wasteland.crDry;
                int yy = j.wBot + r.nextInt(j.W - j.wBot + 1);
                boolean isOpen = t.fine(x, yy, z, r.nextDouble() * 2 - 1) <= 0;
                if (!crater) {
                    sealed++;
                    if (isOpen) { open++; if (first == null) first = x + " " + yy + " " + z + " beta " + beta; }
                } else if (isOpen) {
                    craterOpen++;
                    if (t.fluidAt(x, yy, z) != Terrain.AIR) craterNotAir++;
                }
            }
        }
        assertEquals(0, bottomFlips, "columns whose band's bottom row differs from the Galleries' alone");
        assertEquals(0, topFlips, "columns whose band's top row differs from the Wasteland's alone");
        assertTrue(sealed > 1000, "too few sealed columns to check: " + sealed);
        assertEquals(0, open, "open blocks under the Wasteland's lava table where the seal holds, first at " + first);
        assertEquals(0, craterNotAir, "open blocks under the table in a crater that are not air, of " + craterOpen);
    }

    /**
     * The fields are pure functions of the seed: density, the per-block pass, fluid, biome and the look queries answer
     * the same on four threads in shuffled order as on one, and as a fresh instance does (their caches are per thread
     * and checked by position, so far apart queries that share a cache slot do not mix).
     */
    @Test
    void fieldsArePureOnAnyThread() throws Exception {
        long seed = 12345L;
        Terrain one = new Terrain(TerrainSettings.defaults(), -64, seed);
        Shape s = one.shape;
        Random r = new Random(11);
        int n = 20000;
        int[][] pts = new int[n][];
        for (int k = 0; k < n; k++) {
            // pairs 32 blocks apart, which share the column caches' slots
            int x = r.nextInt(3000) - 1500, z = r.nextInt(3000) - 1500;
            if ((k & 1) == 1) { x = pts[k - 1][0] + 32; z = pts[k - 1][2]; }
            pts[k] = new int[]{x, s.floorY + 1 + r.nextInt(s.top(Terrain.WASTELAND) - s.floorY), z, r.nextInt(2001)};
        }
        long[] alone = new long[n];
        for (int k = 0; k < n; k++) alone[k] = probe(one, pts[k]);
        Terrain fresh = new Terrain(TerrainSettings.defaults(), -64, seed);
        List<Integer> order = new ArrayList<>();
        for (int k = 0; k < n; k++) order.add(k);
        Collections.shuffle(order, new Random(13));
        long[] many = new long[n];
        ExecutorService ex = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int w = 0; w < 4; w++) {
                final int W = w;
                fs.add(ex.submit(() -> { for (int i = W; i < n; i += 4) { int k = order.get(i); many[k] = probe(fresh, pts[k]); } }));
            }
            for (Future<?> f : fs) f.get();
        } finally {
            ex.shutdownNow();
        }
        int differ = 0;
        String first = null;
        for (int k = 0; k < n; k++) if (alone[k] != many[k]) {
            differ++;
            if (first == null) first = pts[k][0] + " " + pts[k][1] + " " + pts[k][2];
        }
        assertEquals(0, differ, "points that answer differently on four threads, first at " + first);
    }

    /** Everything the game asks of a block of the noise layers, hashed. */
    static long probe(Terrain t, int[] p) {
        int x = p[0], y = p[1], z = p[2];
        double coarse = p[3] / 1000.0 - 1;
        long h = Double.doubleToLongBits(t.density(x, y, z));
        h = h * 31 + Double.doubleToLongBits(t.fine(x, y, z, coarse));
        h = h * 31 + t.fluidAt(x, y, z);
        h = h * 31 + t.biomeAt(x, y, z).ordinal();
        LookQueries q = t.looks;
        h = h * 31 + q.columnCell(x, z);
        h = h * 31 + Double.doubleToLongBits(q.columnEdge(x, z));
        h = h * 31 + Double.doubleToLongBits(q.hang(x, y, z));
        h = h * 31 + Double.doubleToLongBits(q.tube(x, y, z));
        h = h * 31 + Double.doubleToLongBits(q.tubeShellDepth(x, y, z));
        h = h * 31 + (q.tubeRib(x, y, z) ? 1 : 0);
        h = h * 31 + Double.doubleToLongBits(q.cone(x, z));
        h = h * 31 + (q.lavaKept(x, y, z) ? 1 : 0);
        return h;
    }
}
