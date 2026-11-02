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
 * they were, nothing plans the noise layers, the join's rows are one density, and the fields answer the same on any
 * thread. (The fields' own statistics are their own tests'.)
 */
class NoiseLayersTest {
    /**
     * The Blue Reaches and the Deep Gardens of a2c3d73, before the noise layers: per seed, the plan hashes of layers 0
     * and 1 over cells (-1..0)^2 ({@link NetworkTest#planHash}) and a hash of every block of rows -544..-65 (rock after
     * the per-block pass, fluid, biome) of five chunks, as the game builds them (corners every 4 x 8 x 4, interpolated).
     */
    static final String[] UNCHANGED = {
            "seed 1 plans: 1cb1d99698306a18 38368ad72467ffd6 287f2cb1485e3c19 5065fd4849daae43 f1ccde63e4c18e32 acd1f91d5f20af65 e8c65f191f619aa0 cbefaff340029a5c"
                    + " blocks: d8018a96a816c61c 8804e739ba0e13f3 c6cccb1b4a189ac9 86b566fe72f1a30a 5bac46041966889",
            "seed 2 plans: d22112343740a27e 2b4cb5e8fadebdae f60ee4f5b8188ed2 33b535e1e6df347 dce30161b23a0635 7a8895741a10c7e0 497eb3d2807232ab ae0b4f86342fdec8"
                    + " blocks: 81d17c6176534df5 2e4d380bcdd24f25 f7d15dfba1f6a9de f14a28770c5f9f2b 248b6ba20f3408eb",
            "seed 8004 plans: c3714fa6e0701269 4d28cdd3c32a4b52 da8333644be6a5d6 422e36749627dd52 6323b980a808815f cbad450c68a146b0 99745dccf59c17c0 4f4d78c95599c495"
                    + " blocks: 3d086b021e78d835 e40937beb10cd683 fb2d790438f75bb2 a528da6c74bc1260 be99a35f729cafdb"};
    static final long[] UNCHANGED_SEEDS = {1, 2, 8004};

    /** As {@link #UNCHANGED} says, and building them (band 3 too) plans no other layer. */
    @Test
    void blueAndGardensUnchanged() {
        java.util.Set<Integer> planned = java.util.concurrent.ConcurrentHashMap.newKeySet();
        NetPlanner.FailHook was = NetPlanner.failHook;
        String[] got;
        try {
            NetPlanner.failHook = (seed, li, cx, cz, attempt) -> { planned.add(li); return false; };
            got = java.util.stream.LongStream.of(UNCHANGED_SEEDS).parallel().mapToObj(NoiseLayersTest::identity).toArray(String[]::new);
        } finally {
            NetPlanner.failHook = was;
        }
        for (int i = 0; i < got.length; i++) assertEquals(UNCHANGED[i], got[i], "seed " + UNCHANGED_SEEDS[i]);
        assertEquals(java.util.Set.of(Terrain.BLUE, Terrain.GARDENS), planned, "the layers planned");
    }

    /** One seed's line of {@link #UNCHANGED}. */
    static String identity(long seed) {
        int[][] chunks = {{0, 0}, {37, -12}, {-20, 15}, {5, 30}, {-41, -38}};
        Terrain t = new Terrain(TerrainSettings.defaults(), -64, seed);
        StringBuilder sb = new StringBuilder("seed " + seed + " plans:");
        for (int li = 0; li < 2; li++) for (int cx = -1; cx <= 0; cx++) for (int cz = -1; cz <= 0; cz++)
            sb.append(' ').append(Long.toHexString(NetworkTest.planHash(t.network(li, cx, cz))));
        sb.append(" blocks:");
        int ylo = -544, yhi = -65;
        for (int[] c : chunks) {
            long h = 17;
            int bx = c[0] * 16, bz = c[1] * 16;
            int cy0 = Math.floorDiv(ylo, 8), cy1 = Math.floorDiv(yhi, 8) + 1, ny = cy1 - cy0 + 1;
            double[] cor = new double[25 * ny];
            for (int i = 0; i < 5; i++) for (int k = 0; k < 5; k++) for (int j = 0; j < ny; j++) cor[(i * 5 + k) * ny + j] = t.density(bx + i * 4, (cy0 + j) * 8, bz + k * 4);
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = ylo; y <= yhi; y++) {
                double d = interpolatedFine(t, cor, ny, cy0, bx, bz, x, y, z);
                int f = d > 0 ? 9 : t.fluidAt(bx + x, y, bz + z);
                DeepBiome b = t.biomeAt(bx + x, y, bz + z);
                h = h * 1000003L + Double.doubleToLongBits(d) * 31 + f * 7 + (b == null ? -1 : b.ordinal());
            }
            sb.append(' ').append(Long.toHexString(h));
        }
        return sb.toString();
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
     * empty, and asking for a noise layer's plan throws.
     */
    @Test
    void noiseLayersPlanNothing() {
        Terrain t = new Terrain(TerrainSettings.defaults(), -64, 4242);
        Shape s = t.shape;
        int lo = s.floorY + 1, hi = s.top(Terrain.WASTELAND);
        Random r = new Random(5);
        double sink = 0;
        for (int k = 0; k < 400; k++) {
            int x = r.nextInt(4000) - 2000, z = r.nextInt(4000) - 2000;
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
        for (int li = Terrain.WASTELAND; li < s.layerCount; li++) {
            final int L = li;
            assertThrows(IllegalArgumentException.class, () -> t.network(L, 0, 0), "a plan of noise layer " + li);
        }
        assertEquals(0, t.network.plansMade.sum(), "plans made for the noise layers");
    }

    /**
     * The join's rows are one density: Terrain routes them to {@link Join#density} (the two fields' smooth minimum and
     * the seal), and at the band's top and bottom rows the combined density has the sign of the lone field there, so no
     * lid or step forms where the band begins. Where beta is from {@link Join#LAVA_BETA} to under {@link Join#HOLE_BETA}
     * no block of the Wasteland's rows under a row over its lava table is open, whatever the interpolation gives.
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
        int bottomFlips = 0, topFlips = 0, sealed = 0, open = 0;
        for (int k = 0; k < 20000; k++) {
            int x = r.nextInt(6000) - 3000, z = r.nextInt(6000) - 3000;
            int y = j.lo + r.nextInt(j.hi - j.lo + 1);
            double want = Math.max(Network.smin(t.wasteland.density(x, y, z), t.galleries.density(x, y, z), Join.K), j.seal(x, y, z));
            assertEquals(want, t.rawDensity(x, y, z), "the join's density at " + x + " " + y + " " + z);
            if ((t.rawDensity(x, j.lo, z) > 0) != (t.galleries.density(x, j.lo, z) > 0)) bottomFlips++;
            if ((t.rawDensity(x, j.hi, z) > 0) != (t.wasteland.density(x, j.hi, z) > 0)) topFlips++;
            double beta = t.breakthrough.beta(x, z);
            if (beta >= Join.LAVA_BETA && beta < Join.HOLE_BETA) {
                sealed++;
                int yy = j.wBot + r.nextInt(j.W - j.wBot + 1);
                if (t.fine(x, yy, z, r.nextDouble() * 2 - 1) <= 0) open++;
            }
        }
        assertEquals(0, bottomFlips, "columns whose band's bottom row differs from the Galleries' alone");
        assertEquals(0, topFlips, "columns whose band's top row differs from the Wasteland's alone");
        assertTrue(sealed > 1000, "too few sealed columns to check: " + sealed);
        assertEquals(0, open, "open blocks under the Wasteland's lava table where the seal holds");
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
