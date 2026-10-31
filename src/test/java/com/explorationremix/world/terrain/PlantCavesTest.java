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
}
