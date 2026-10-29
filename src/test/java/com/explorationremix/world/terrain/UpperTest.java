package com.explorationremix.world.terrain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The Silver Fern Rift and the Ivory Root Cavern: set-piece sized (40 to 60 blocks across), and never under water. */
class UpperTest {
    private static final long[] SEEDS = {8675309L, 12345L, -42L};

    private static Terrain terrain(long seed, Upper.SurfaceProbe probe) {
        Terrain t = new Terrain(TerrainSettings.defaults(), -64, seed);
        t.surfaceProbe = probe;
        return t;
    }

    @Test
    void setPiecesAreSetPieceSized() {
        int[] count = new int[2];
        double[][] range = {{1e9, 0}, {1e9, 0}};
        for (long seed : SEEDS) {
            Terrain t = terrain(seed, (x, z) -> false);
            for (int cx = -3; cx <= 3; cx++) for (int cz = -3; cz <= 3; cz++) {
                Upper.Piece p = t.plans.upper(cx, cz);
                if (p == null) continue;
                List<int[]> open = new ArrayList<>();
                for (int x = (int) Math.floor(p.minX); x <= p.maxX; x += 2)
                    for (int z = (int) Math.floor(p.minZ); z <= p.maxZ; z += 2)
                        for (int y = (int) Math.floor(p.waterY) - 6; y <= p.topY; y++)
                            if (t.pieceDist(x, y, z) < 0) {
                                open.add(new int[]{x, z});
                                break;
                            }
                double across = diameter(open);
                int k = p instanceof Upper.Rift ? 0 : 1;
                count[k]++;
                range[k][0] = Math.min(range[k][0], across);
                range[k][1] = Math.max(range[k][1], across);
                String what = p.biome() + " at " + (int) p.centerX() + " " + (int) p.centerZ();
                assertTrue(across >= 40 && across <= 60, what + " is " + across + " blocks across");
                if (p instanceof Upper.Rift r) {
                    // The centre line runs 46 to 58 blocks from end to end; its bends make it at most 30 % longer.
                    double ends = Math.hypot(r.pointX(r.points() - 1) - r.pointX(0), r.pointZ(r.points() - 1) - r.pointZ(0));
                    assertTrue(ends >= 45.9 && ends <= 58.1, what + " runs " + ends + " blocks from end to end");
                    assertTrue(r.length <= ends * 1.3, what + " is " + r.length + " long along its bends");
                    assertTrue(r.halfTop >= 8 && r.halfTop <= 11, what + " is " + 2 * r.halfTop + " wide at the top");
                } else if (p instanceof Upper.Cavern c) {
                    assertTrue(c.rxz >= 21 && c.rxz <= 28, what + " has radius " + c.rxz);
                }
            }
        }
        assertTrue(count[0] > 3 && count[1] > 3, "rifts " + count[0] + ", caverns " + count[1]);
        System.out.printf("rifts: %d, %.0f to %.0f across; caverns: %d, %.0f to %.0f across%n",
                count[0], range[0][0], range[0][1], count[1], range[1][0], range[1][1]);
    }

    private static double diameter(List<int[]> pts) {
        double best = 0;
        for (int i = 0; i < pts.size(); i++) for (int j = i + 1; j < pts.size(); j++) {
            int[] a = pts.get(i), b = pts.get(j);
            best = Math.max(best, Math.hypot(a[0] - b[0], a[1] - b[1]));
        }
        return best + 2;
    }

    @Test
    void neverUnderWater() {
        for (long seed : SEEDS) {
            Terrain wet = terrain(seed, (x, z) -> true);
            Terrain half = terrain(seed, (x, z) -> x < 0);
            int kept = 0;
            for (int cx = -3; cx <= 3; cx++) for (int cz = -3; cz <= 3; cz++) {
                assertNull(wet.plans.upper(cx, cz), "a set piece under the sea in cell " + cx + " " + cz);
                Upper.Piece p = half.plans.upper(cx, cz);
                if (p == null) continue;
                kept++;
                assertTrue(p.minX > -1, "a set piece reaches under the water to x " + p.minX);
            }
            assertTrue(kept > 0, "no set piece left on dry land");
        }
    }
}
