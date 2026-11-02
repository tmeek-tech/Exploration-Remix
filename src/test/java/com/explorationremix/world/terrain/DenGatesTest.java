package com.explorationremix.world.terrain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The den gates' plan: the first gate stays first and unmoved, the rest keep their spacing, margins and clearances. */
class DenGatesTest {
    private static final long[] SEEDS = {8675309L, 12345L, -42L};

    private static Terrain terrain(long seed) { return new Terrain(TerrainSettings.defaults(), -64, seed); }

    @Test
    void denGatesKeepTheirPromises() {
        int cells = 0, gates = 0, minCount = Integer.MAX_VALUE, maxCount = 0;
        for (long seed : SEEDS) {
            Terrain t = terrain(seed);
            Plans p = t.plans;
            for (int cx = -3; cx <= 3; cx++)
                for (int cz = -3; cz <= 3; cz++) {
                    List<Plans.Gate> l = p.denGates(cx, cz);
                    assertSame(p.gate(1, cx, cz), l.get(0), "the first gate leads the list");
                    assertSame(l, p.denGates(cx, cz), "cached");
                    cells++;
                    gates += l.size();
                    minCount = Math.min(minCount, l.size());
                    maxCount = Math.max(maxCount, l.size());
                    double lo = cx * (double) p.cellSize, hi = lo + p.cellSize, loZ = cz * (double) p.cellSize, hiZ = loZ + p.cellSize;
                    for (int i = 0; i < l.size(); i++) {
                        Plans.Gate g = l.get(i);
                        assertEquals(i, g.den, "index");
                        assertEquals(1, g.band);
                        assertTrue(g.ux >= lo + Plans.MARGIN && g.ux <= hi - Plans.MARGIN && g.uz >= loZ + Plans.MARGIN && g.uz <= hiZ - Plans.MARGIN, "upper landing inside the cell's margin");
                        assertTrue(g.minX() >= lo + Plans.MARGIN - 6 && g.maxX() <= hi - Plans.MARGIN + 6, "passage inside the cell's margin (x)");
                        assertTrue(g.minZ() >= loZ + Plans.MARGIN - 6 && g.maxZ() <= hiZ - Plans.MARGIN + 6, "passage inside the cell's margin (z)");
                        Route.Link r = p.ramp(g);
                        assertTrue(r.x2() >= lo + Plans.MARGIN - 1e-6 && r.x2() <= hi - Plans.MARGIN + 1e-6, "ramp inside the cell's margin");
                        for (int j = 0; j < i; j++) {
                            Plans.Gate o = l.get(j);
                            assertTrue(Math.hypot(g.ux - o.ux, g.uz - o.uz) >= Plans.DEN_SPACING - 1e-6, "den gates keep apart");
                        }
                        if (i > 0) assertSame(r, p.ramp(g), "den ramps are cached");
                        for (int j = 0; j < i; j++)
                            assertTrue(Math.hypot(g.lx - l.get(j).lx, g.lz - l.get(j).lz) >= Plans.DEN_LOWER_SPACING - 1e-6, "lower landings keep apart");
                    }
                }
        }
        double perCell = gates / (double) cells;
        double spacing = 600 / Math.sqrt(perCell);
        System.out.printf("den gates: %d cells, %.2f per cell (min %d, max %d), mean spacing about %.0f blocks%n", cells, perCell, minCount, maxCount, spacing);
        assertTrue(perCell >= 4.3, "about one den gate every 250-290 blocks, got " + perCell + " per cell");
        assertTrue(minCount >= 3, "every cell has a few den gates");
    }
}
