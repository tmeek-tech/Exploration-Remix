package com.explorationremix.world.terrain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The plant caves: nearly every way down crosses one, about half of all the caves grow plants, none near the ground. */
class PlantCavesTest {
    @Test
    void coverage() {
        for (long seed : new long[]{8675309L, 12345L, -42L}) {
            PlantCaves p = new PlantCaves(seed);
            int cols = 0, colsHit = 0, cells = 0, cellsHit = 0, band = 0, bandHit = 0;
            for (int x = -2000; x < 2000; x += 32) for (int z = -2000; z < 2000; z += 32) {
                cols++;
                boolean hit = false;
                for (int d = 0; d <= 120; d += 4) {
                    boolean g = p.growth(x, 0, z, d) > 0;
                    if (d < PlantCaves.MIN_DEPTH) { assertFalse(g, "plants " + d + " under the ground"); continue; }
                    cells++;
                    if (g) cellsHit++;
                    if (d >= 25 && d <= 65) { band++; if (g) { bandHit++; hit = true; } }
                }
                if (hit) colsHit++;
            }
            double c = colsHit / (double) cols, v = cellsHit / (double) cells, b = bandHit / (double) band;
            assertTrue(c >= 0.95, "columns crossing a plant cave " + c);
            assertTrue(b >= 0.8, "share of the band " + b);
            assertTrue(v >= 0.4 && v <= 0.62, "share of all caves " + v);
        }
    }

    /**
     * A plant cell's top always lies MIN_DEPTH or more under the ground in each of its column's sixteen block columns,
     * however far off the column's ground estimate is (as on Tectonic's peaks, up to 25 blocks) and however steep the
     * column (corners up to 40 blocks apart), when the lowest corner bounds it.
     */
    @Test
    void neverAtTheGround() {
        PlantCaves p = new PlantCaves(8675309L);
        java.util.Random r = new java.util.Random(11);
        int grown = 0;
        for (int i = 0; i < 20_000; i++) {
            int x = (r.nextInt(8000) - 4000) & ~3, z = (r.nextInt(8000) - 4000) & ~3;
            int[] g = new int[4];
            g[0] = 40 + r.nextInt(240);
            for (int k = 1; k < 4; k++) g[k] = g[0] + r.nextInt(81) - 40;
            int low = Math.min(Math.min(g[0], g[1]), Math.min(g[2], g[3]));
            int ground = (g[0] + g[1] + g[2] + g[3]) / 4 + r.nextInt(51) - 25;
            for (int y = -50; y < 330; y += 4) {
                if (!p.grows(x + 2, y, z + 2, ground, low)) continue;
                grown++;
                for (int a = 0; a < 4; a++) for (int c = 0; c < 4; c++) {
                    double fx = a / 4.0, fz = c / 4.0;
                    double inside = g[0] * (1 - fx) * (1 - fz) + g[1] * fx * (1 - fz) + g[2] * (1 - fx) * fz + g[3] * fx * fz;
                    assertTrue(inside - (y + 1) >= PlantCaves.MIN_DEPTH, "plants " + (inside - y - 1) + " under the ground");
                }
            }
        }
        assertTrue(grown > 10_000, "plant cells grown " + grown);
    }

    /** The top solid block between two grid heights matches the straight-line density the world interpolates. */
    @Test
    void topBetweenMatchesTheInterpolatedColumn() {
        java.util.Random r = new java.util.Random(3);
        for (int i = 0; i < 100_000; i++) {
            double d = 1e-6 + r.nextDouble(), up = -r.nextDouble();
            int h = 8, want = 0;
            for (int t = 0; t < h; t++) if (d + (up - d) * t / h > 0) want = t;
            assertEquals(want, Overhead.topBetween(0, h, d, up));
        }
    }
}
