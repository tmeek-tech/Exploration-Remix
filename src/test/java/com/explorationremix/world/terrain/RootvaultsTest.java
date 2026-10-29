package com.explorationremix.world.terrain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks of the Rootvaults' promises against a made-up world: grottos only under trees and never under water, their
 * open space 10 to 40 blocks under the ground, cave sized, open to the sky only as ravines where the sky may open, the
 * biome never near the surface, water that stays in its basins, and the same answers on every thread.
 */
class RootvaultsTest {
    private static final long[] SEEDS = {8675309L, 12345L, -42L};
    private static final int PACK_FLOOR = -64;
    /** The checks walk cells -N .. N-1 on both axes. */
    private static final int N = 8;

    /**
     * Rolling ground around y 90. Strips 300 blocks wide along x: oak forest, spruce taiga, plains (no trees) and jungle
     * (no ravines, as if it grew temples). A river 40 blocks wide every 700 blocks along z.
     */
    static final class World implements Overhead {
        @Override
        public Surface at(int x, int z) {
            int y = ground(x, z);
            boolean wet = Math.floorMod(z, 700) < 40;
            int strip = Math.floorMod(Math.floorDiv(x, 300), 4);
            RootKind k = wet ? RootKind.NONE : switch (strip) {
                case 0 -> RootKind.OAK;
                case 1 -> RootKind.SPRUCE;
                case 2 -> RootKind.NONE;
                default -> RootKind.JUNGLE;
            };
            return new Surface(y, k, wet, !wet && strip != 3 && y >= 67);
        }

        static int ground(int x, int z) {
            return 90 + (int) Math.round(6 * Math.sin(x / 47.0) + 5 * Math.cos(z / 39.0) + 2 * Math.sin((x + z) / 13.0));
        }
    }

    private static final World WORLD = new World();

    private static int strip(int x) { return Math.floorMod(Math.floorDiv(x, 300), 4); }

    private static List<Rootvaults.Grotto> grottos(Rootvaults r) {
        List<Rootvaults.Grotto> out = new ArrayList<>();
        for (int i = -N; i < N; i++) for (int j = -N; j < N; j++) {
            Rootvaults.Grotto g = r.grotto(i, j);
            if (g != null) out.add(g);
        }
        return out;
    }

    /** The columns over a grotto's footprint (every other block). */
    private static List<int[]> footprint(Rootvaults r, Rootvaults.Grotto g) {
        List<int[]> out = new ArrayList<>();
        double reach = Rootvaults.reach(g.look, g.a, g.b);
        for (int x = (int) Math.floor(g.cx - reach); x <= g.cx + reach; x += 2)
            for (int z = (int) Math.floor(g.cz - reach); z <= g.cz + reach; z += 2)
                if (r.grottoAt(x, z) == g) out.add(new int[]{x, z});
        return out;
    }

    @Test
    void grottosLieUnderTreesAndNeverUnderWater() {
        for (long seed : SEEDS) {
            Rootvaults r = new Rootvaults(seed, PACK_FLOOR, WORLD);
            List<Rootvaults.Grotto> gs = grottos(r);
            assertTrue(gs.size() >= 15, "only " + gs.size() + " grottos in " + (4 * N * N) + " cells for seed " + seed);
            int[] looks = new int[3];
            for (Rootvaults.Grotto g : gs) {
                looks[g.look]++;
                Overhead.Surface s0 = WORLD.at((int) Math.floor(g.cx), (int) Math.floor(g.cz));
                assertTrue(s0.trees().trees(), "a grotto under " + s0.trees() + " at " + g.cx + " " + g.cz);
                assertEquals(s0.trees(), g.kind);
                int treeless = 0, cols = 0;
                for (int[] c : footprint(r, g)) {
                    Overhead.Surface s = WORLD.at(c[0], c[1]);
                    assertFalse(s.wet(), "a grotto under water at " + c[0] + " " + c[1]);
                    cols++;
                    if (!s.trees().trees()) treeless++;
                }
                assertTrue(treeless <= cols * 0.35, treeless + " of " + cols + " columns without trees over the grotto at " + g.cx + " " + g.cz);
            }
            // No grotto under the plains strip, away from its edges.
            for (int x = -N * 64; x < N * 64; x += 3) for (int z = -N * 64; z < N * 64; z += 3) {
                if (strip(x) != 2 || strip(x - 34) != 2 || strip(x + 34) != 2) continue;
                assertNull(r.grottoAt(x, z), "a grotto under the plains at " + x + " " + z);
            }
            assertTrue(looks[Rootvaults.HALL] > 0 && looks[Rootvaults.TEAL] > 0, "looks " + java.util.Arrays.toString(looks));
        }
    }

    @Test
    void hallsAndTealGrottosStayTenToFortyUnderTheGround() {
        int lowest = 0, highest = -1000;
        for (long seed : SEEDS) {
            Rootvaults r = new Rootvaults(seed, PACK_FLOOR, WORLD);
            for (Rootvaults.Grotto g : grottos(r)) {
                if (g.look == Rootvaults.RAVINE) continue;
                for (int[] c : footprint(r, g)) {
                    int ground = WORLD.at(c[0], c[1]).y();
                    for (int y = ground - 60; y <= ground + 4; y++) {
                        // Anything open, the tunnels of other grottos included, stays 10 under the ground.
                        if (r.carve(c[0], y, c[1]) < 0)
                            assertTrue(y - ground <= -10, "open " + (ground - y) + " under the ground at " + c[0] + " " + y + " " + c[1]);
                        // The grotto itself reaches no deeper than 40, the bottom of its stream or pool included.
                        if (r.shapeDist(g, c[0], y, c[1]) >= 0) continue;
                        int under = y - ground;
                        lowest = Math.min(lowest, under);
                        highest = Math.max(highest, under);
                        assertTrue(under >= -40, "the grotto is open " + (-under) + " under the ground at " + c[0] + " " + y + " " + c[1]);
                    }
                }
            }
        }
        System.out.println("halls and teal grottos: open from " + (-highest) + " to " + (-lowest) + " blocks under the ground");
    }

    @Test
    void onlyRavinesOpenToTheSkyAndOnlyWhereTheyMay() {
        for (long seed : SEEDS) {
            Rootvaults r = new Rootvaults(seed, PACK_FLOOR, WORLD);
            int ravines = 0;
            for (Rootvaults.Grotto g : grottos(r)) {
                if (g.look != Rootvaults.RAVINE) continue;
                ravines++;
                int open = 0;
                for (int[] c : footprint(r, g)) {
                    int ground = WORLD.at(c[0], c[1]).y();
                    if (r.carve(c[0], ground, c[1]) < 0) {
                        open++;
                        assertTrue(WORLD.at(c[0], c[1]).ravineOk(), "a ravine opens where it may not at " + c[0] + " " + c[1]);
                    }
                }
                assertTrue(open > 20, "a ravine at " + g.cx + " " + g.cz + " opens to the sky over only " + open + " columns");
            }
            // Away from the ravines nothing comes within 9 blocks of the ground.
            for (int x = -N * 64; x < N * 64; x += 2) for (int z = -N * 64; z < N * 64; z += 2) {
                if (!r.anyNear(x, z)) continue;
                Rootvaults.Grotto g = r.grottoAt(x, z);
                if (g != null && g.look == Rootvaults.RAVINE) continue;
                if (nearRavine(r, x, z)) continue;
                int ground = WORLD.at(x, z).y();
                for (int y = ground - 9; y <= ground + 2; y++)
                    assertTrue(r.carve(x, y, z) >= 0, "open " + (ground - y) + " under the ground at " + x + " " + y + " " + z);
            }
            System.out.println("seed " + seed + ": " + ravines + " ravines");
        }
    }

    private static boolean nearRavine(Rootvaults r, int x, int z) {
        for (Rootvaults.Grotto g : r.near(x, z, 1))
            if (g.look == Rootvaults.RAVINE && Math.hypot(g.cx - x, g.cz - z) < Rootvaults.reach(g.look, g.a, g.b) + 4) return true;
        return false;
    }

    @Test
    void theBiomeNeverReachesTheSurface() {
        Random rnd = new Random(5);
        for (long seed : SEEDS) {
            Rootvaults r = new Rootvaults(seed, PACK_FLOOR, WORLD);
            for (Rootvaults.Grotto g : grottos(r)) {
                for (int k = 0; k < 300; k++) {
                    int x = (int) Math.floor(g.cx + (rnd.nextDouble() * 2 - 1) * 30), z = (int) Math.floor(g.cz + (rnd.nextDouble() * 2 - 1) * 30);
                    int ground = WORLD.at(x, z).y();
                    for (int y = ground - 5; y <= ground + 10; y++) assertFalse(r.biomeAt(x, y, z), "Rootvaults biome at " + x + " " + y + " " + z);
                }
                // But it is there in the grotto itself.
                int x = (int) Math.floor(g.cx), z = (int) Math.floor(g.cz);
                boolean any = false;
                for (int y = (int) g.water - 4; y < g.water + 8; y++) any |= r.biomeAt(x, y, z);
                assertTrue(any, "no Rootvaults biome in the grotto at " + x + " " + z);
            }
        }
    }

    @Test
    void cavesAreCaveSized() {
        double[][] range = {{10, 0}, {10, 0}, {10, 0}};  // per look: smallest and largest span across, tallest
        double[] tallest = new double[3];
        for (long seed : SEEDS) {
            Rootvaults r = new Rootvaults(seed, PACK_FLOOR, WORLD);
            for (Rootvaults.Grotto g : grottos(r)) {
                double reach = Rootvaults.reach(g.look, g.a, g.b);
                double uMin = 1e9, uMax = -1e9, vMin = 1e9, vMax = -1e9, tall = 0;
                double[] axis = axis(g);
                for (int x = (int) Math.floor(g.cx - reach); x <= g.cx + reach; x++)
                    for (int z = (int) Math.floor(g.cz - reach); z <= g.cz + reach; z++) {
                        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
                        int top = g.look == Rootvaults.RAVINE ? WORLD.at(x, z).y() : (int) g.roofCap + 1;
                        for (int y = (int) g.water - 6; y <= top; y++) {
                            if (r.shapeDist(g, x, y, z) < 0) { lo = Math.min(lo, y); hi = Math.max(hi, y); }
                        }
                        if (lo > hi) continue;
                        double u = (x - g.cx) * axis[0] + (z - g.cz) * axis[1], v = -(x - g.cx) * axis[1] + (z - g.cz) * axis[0];
                        uMin = Math.min(uMin, u); uMax = Math.max(uMax, u);
                        vMin = Math.min(vMin, v); vMax = Math.max(vMax, v);
                        tall = Math.max(tall, hi - lo + 1);
                    }
                double along = uMax - uMin + 1, across = vMax - vMin + 1;
                range[g.look][0] = Math.min(range[g.look][0], across);
                range[g.look][1] = Math.max(range[g.look][1], along);
                tallest[g.look] = Math.max(tallest[g.look], tall);
                String what = g.lookName() + " at " + (int) g.cx + " " + (int) g.cz;
                if (g.look == Rootvaults.RAVINE) {
                    assertTrue(along >= 26 && along <= 52, what + " is " + along + " long");
                    assertTrue(across >= 8 && across <= 24, what + " is " + across + " wide");
                } else {
                    assertTrue(along >= 14 && along <= 36, what + " is " + along + " long");
                    assertTrue(across >= 10 && across <= 30, what + " is " + across + " wide");
                    assertTrue(tall >= 7 && tall <= 22, what + " is " + tall + " tall");
                }
            }
        }
        for (int l = 0; l < 3; l++)
            System.out.printf("%s: %.0f to %.0f across, up to %.0f tall%n", Rootvaults.lookName(l), range[l][0], range[l][1], tallest[l]);
    }

    /** A grotto's long axis as a unit vector, found from its shape (the angle is private to the plan). */
    private static double[] axis(Rootvaults.Grotto g) {
        double u = g.worldX(1, 0) - g.cx, w = g.worldZ(1, 0) - g.cz;
        return new double[]{u, w};
    }

    @Test
    void waterStaysInItsBasins() {
        int water = 0;
        for (long seed : SEEDS) {
            Rootvaults r = new Rootvaults(seed, PACK_FLOOR, WORLD);
            for (Rootvaults.Grotto g : grottos(r)) {
                double reach = Rootvaults.reach(g.look, g.a, g.b) + 6;
                for (int x = (int) Math.floor(g.cx - reach); x <= g.cx + reach; x++)
                    for (int z = (int) Math.floor(g.cz - reach); z <= g.cz + reach; z++) {
                        if (!r.anyNear(x, z)) continue;
                        for (int y = (int) g.water - 8; y <= g.water + 2; y++) {
                            if (r.carve(x, y, z) >= 0) continue;
                            int f = r.fluid(x, y, z);
                            assertNotEquals(-1, f, "open but no fluid decided at " + x + " " + y + " " + z);
                            if (f != Rootvaults.WATER) continue;
                            water++;
                            int[][] next = {{x + 1, y, z}, {x - 1, y, z}, {x, y, z + 1}, {x, y, z - 1}, {x, y - 1, z}};
                            for (int[] n : next) {
                                if (r.carve(n[0], n[1], n[2]) >= 0) continue;
                                assertEquals(Rootvaults.WATER, r.fluid(n[0], n[1], n[2]),
                                        "water at " + x + " " + y + " " + z + " runs out into the open block " + n[0] + " " + n[1] + " " + n[2]);
                            }
                        }
                    }
            }
        }
        assertTrue(water > 1000, "only " + water + " water blocks");
    }

    @Test
    void thePacksCavesCannotDrainTheWater() {
        int checked = 0;
        for (long seed : SEEDS) {
            Rootvaults r = new Rootvaults(seed, PACK_FLOOR, WORLD);
            for (Rootvaults.Grotto g : grottos(r)) {
                double reach = Rootvaults.reach(g.look, g.a, g.b) + 6;
                for (int x = (int) Math.floor(g.cx - reach); x <= g.cx + reach; x++)
                    for (int z = (int) Math.floor(g.cz - reach); z <= g.cz + reach; z++) {
                        if (!r.anyNear(x, z)) continue;
                        for (int y = (int) g.water - 8; y <= g.water + 2; y++) {
                            if (r.carve(x, y, z) >= 0 || r.fluid(x, y, z) != Rootvaults.WATER) continue;
                            // The rock the water rests against, and two blocks under it, stays shut to the pack's caves.
                            int[][] next = {{x + 1, y, z}, {x - 1, y, z}, {x, y, z + 1}, {x, y, z - 1}, {x, y - 1, z}, {x, y - 2, z}};
                            for (int[] n : next) {
                                if (r.carve(n[0], n[1], n[2]) < 0) continue;
                                checked++;
                                assertTrue(r.seals(n[0], n[1], n[2]), "the pack's caves could open " + n[0] + " " + n[1] + " " + n[2]
                                        + " beside the water at " + x + " " + y + " " + z + " in a " + g.lookName() + " a " + g.a + " b " + g.b
                                        + " u " + g.along(n[0], n[2]) + " v " + g.across(n[0], n[2]) + " water " + g.water);
                            }
                        }
                    }
            }
        }
        assertTrue(checked > 1000, "only " + checked + " rock blocks beside water");
    }

    @Test
    void sameAnswersOnEveryThread() throws Exception {
        long seed = 8675309L;
        Rootvaults a = new Rootvaults(seed, PACK_FLOOR, WORLD), b = new Rootvaults(seed, PACK_FLOOR, WORLD);
        Random rnd = new Random(11);
        int n = 20000;
        int[][] pts = new int[n][];
        for (int i = 0; i < n; i++) {
            int x = rnd.nextInt(N * 128) - N * 64, z = rnd.nextInt(N * 128) - N * 64;
            pts[i] = new int[]{x, WORLD.at(x, z).y() - 10 - rnd.nextInt(40), z};
        }
        double[] want = new double[n];
        int[] wantFluid = new int[n];
        boolean[] wantBiome = new boolean[n];
        for (int i = 0; i < n; i++) {
            want[i] = b.carve(pts[i][0], pts[i][1], pts[i][2]);
            wantFluid[i] = b.fluid(pts[i][0], pts[i][1], pts[i][2]);
            wantBiome[i] = b.biomeAt(pts[i][0], pts[i][1], pts[i][2]);
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                int off = t;
                fs.add(pool.submit(() -> {
                    for (int k = 0; k < n; k++) {
                        int i = (k * 7 + off * 2503) % n;
                        assertEquals(want[i], a.carve(pts[i][0], pts[i][1], pts[i][2]), 0.0);
                        assertEquals(wantFluid[i], a.fluid(pts[i][0], pts[i][1], pts[i][2]));
                        assertEquals(wantBiome[i], a.biomeAt(pts[i][0], pts[i][1], pts[i][2]));
                    }
                    return null;
                }));
            }
            for (Future<?> f : fs) f.get();
        } finally {
            pool.shutdownNow();
        }
    }
}
