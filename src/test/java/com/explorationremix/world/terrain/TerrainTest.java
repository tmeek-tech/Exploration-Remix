package com.explorationremix.world.terrain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Checks of the deep terrain's promises that don't need Minecraft: layer heights, open gates and routes, flat water. */
class TerrainTest {
    private static final long[] SEEDS = {8675309L, 12345L, -42L};
    private static final int[][] CELLS = {{0, 0}, {-1, 0}, {3, -2}};

    private static Terrain terrain(long seed) { return new Terrain(TerrainSettings.defaults(), -64, seed); }

    @Test
    void layersSitWhereTheSpecSays() {
        Shape s = terrain(1).shape;
        assertEquals(-832, s.floorY);
        int[][] expected = {{-81, -208}, {-225, -384}, {-401, -592}, {-609, -832}};
        for (int i = 0; i < 4; i++) {
            assertEquals(expected[i][0], s.top(i), "top of layer " + i);
            assertEquals(expected[i][1], s.bottom(i), "bottom of layer " + i);
            assertEquals(16, s.bandTop(i + 1) - s.bandBottom(i + 1) + 1, "band " + (i + 1) + " thickness");
        }
        assertEquals(-65, s.bandTop(1));
        assertEquals(0, Math.floorMod(s.floorY, 16));
    }

    @Test
    void gatePassagesAreOpenFromLandingToLanding() {
        for (long seed : SEEDS) {
            Terrain t = terrain(seed);
            for (int[] c : CELLS) for (int b = 1; b <= 4; b++) {
                Plans.Gate g = t.plans.gate(b, c[0], c[1]);
                for (int i = 0; i <= 200; i++) {
                    double tt = i / 200.0;
                    int x = (int) Math.floor(g.px(tt)), y = (int) Math.floor(g.py(tt)), z = (int) Math.floor(g.pz(tt));
                    double d = y >= t.shape.packFloor ? Math.min(t.rawDensity(x, y, z), t.gateOneCarve(x, y, z)) : t.rawDensity(x, y, z);
                    assertTrue(d < 0, "gate " + b + " blocked at t=" + tt + " (" + x + " " + y + " " + z + ") seed " + seed + " d=" + d);
                    if (y < t.shape.packFloor) assertEquals(Terrain.AIR, t.fluidAt(x, y, z), "gate " + b + " flooded at " + x + " " + y + " " + z);
                }
            }
        }
    }

    @Test
    void bandsAreSolidAwayFromTheirPassage() {
        Terrain t = terrain(SEEDS[0]);
        java.util.Random r = new java.util.Random(7);
        int open = 0;
        for (int k = 0; k < 20000; k++) {
            int b = 1 + r.nextInt(4);
            int x = r.nextInt(1800) - 900, z = r.nextInt(1800) - 900;
            int y = t.shape.bandBottom(b) + r.nextInt(t.shape.bandThickness);
            Plans.Gate g = t.plans.gate(b, t.plans.cellOf(x), t.plans.cellOf(z));
            if (g.passageXZ(x, y, z) < g.radius * 2 + 6) continue;
            if (t.rawDensity(x, y, z) <= 0) open++;
        }
        assertEquals(0, open, "open blocks inside bands away from the passages");
    }

    @Test
    void everyRouteHasAnOpenWalkingPathWithRockUnderIt() {
        for (long seed : SEEDS) {
            Terrain t = terrain(seed);
            for (int[] c : CELLS) for (int li = 0; li < 4; li++) {
                Route r = t.plans.route(li, c[0], c[1]);
                int noFloor = 0, samples = 0;
                for (double s = 0; s <= r.length; s += 2) {
                    double[] p = r.pathAt(s);
                    int x = (int) Math.floor(p[0]), z = (int) Math.floor(p[1]), fy = (int) Math.floor(p[2]);
                    for (int y = fy + 1; y <= fy + 2; y++) {
                        assertTrue(t.rawDensity(x, y, z) < 0, "route " + li + " blocked at s=" + s + " (" + x + " " + y + " " + z + ") seed " + seed);
                        assertEquals(Terrain.AIR, t.fluidAt(x, y, z), "route " + li + " under water at s=" + s + " seed " + seed);
                    }
                    samples++;
                    boolean nearEnd = s < 20 || s > r.length - 20;
                    if (!nearEnd && t.rawDensity(x, fy - 1, z) <= 0) noFloor++;
                }
                assertTrue(noFloor <= samples / 50, "route " + li + " seed " + seed + ": " + noFloor + " of " + samples + " path points have no rock under them");
            }
        }
    }

    @Test
    void waterTablesAreFlat() {
        Terrain t = terrain(SEEDS[1]);
        java.util.Random r = new java.util.Random(3);
        for (int k = 0; k < 20000; k++) {
            int li = r.nextInt(4);
            int x = r.nextInt(1200) - 600, z = r.nextInt(1200) - 600;
            int y = t.shape.bottom(li) + r.nextInt(t.shape.height(li));
            int f = t.fluidAt(x, y, z);
            if (y >= t.shape.waterY(li)) assertEquals(Terrain.AIR, f, "fluid above the table at " + x + " " + y + " " + z);
            else if (f == Terrain.AIR) {
                Plans.Gate ex = t.plans.gate(li + 2 <= 4 ? li + 2 : 4, t.plans.cellOf(x), t.plans.cellOf(z));
                assertTrue(li == 3 || ex.passageXZ(x, y, z) < ex.radius * 1.45 + 3.01, "dry pocket below the table at " + x + " " + y + " " + z);
            }
        }
    }

    @Test
    void noSeamsAtCellBorders() {
        Terrain t = terrain(SEEDS[0]);
        java.util.Random r = new java.util.Random(11);
        double worst = 0;
        for (int k = 0; k < 4000; k++) {
            int border = (r.nextInt(6) - 3) * 600;
            int z = r.nextInt(2400) - 1200;
            int li = r.nextInt(4);
            int y = t.shape.bottom(li) + 6 + r.nextInt(t.shape.height(li) - 12);
            double a = t.rawDensity(border - 1, y, z), b = t.rawDensity(border, y, z);
            if (Math.abs(a) > 30 && Math.abs(b) > 30) continue;
            worst = Math.max(worst, Math.abs(a - b));
        }
        assertTrue(worst < 8, "density jumps by " + worst + " blocks across a cell border");
    }

    @Test
    void spacesGrowWithDepth() {
        Terrain t = terrain(SEEDS[0]);
        int[] tallest = new int[4];
        for (int li = 0; li < 4; li++) {
            for (int x = 0; x < 600; x += 6) for (int z = 0; z < 600; z += 6) {
                int run = 0;
                for (int y = t.shape.bottom(li); y <= t.shape.top(li); y++) {
                    if (t.rawDensity(x, y, z) < 0) { run++; tallest[li] = Math.max(tallest[li], run); } else run = 0;
                }
            }
        }
        for (int li = 1; li < 4; li++) assertTrue(tallest[li] >= tallest[li - 1] - 10, "layer " + li + " tallest " + tallest[li] + " vs " + tallest[li - 1]);
        assertTrue(tallest[3] > 120, "the Basalt Galleries' tallest open space is only " + tallest[3] + " blocks");
    }
}
