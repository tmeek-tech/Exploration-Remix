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
        // Blue Reaches, Deep Gardens, Basalt Galleries (Taran swapped the two middle layers on 2026-10-28).
        int[][] expected = {{-81, -272}, {-289, -528}, {-545, -832}};
        assertEquals(3, s.layerCount);
        assertEquals("blue_reaches", s.id(0));
        assertEquals("deep_gardens", s.id(1));
        assertEquals("basalt_galleries", s.id(2));
        for (int i = 0; i < 3; i++) {
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
            for (int[] c : CELLS) for (int b = 1; b <= t.shape.layerCount; b++) {
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
            int b = 1 + r.nextInt(t.shape.layerCount);
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
            for (int[] c : CELLS) for (int li = 0; li < t.shape.layerCount; li++) {
                Route r = t.plans.route(li, c[0], c[1]);
                int noFloor = 0, samples = 0;
                for (double s = 0; s <= r.length; s += 2) {
                    double[] p = r.pathAt(s);
                    int x = (int) Math.floor(p[0]), z = (int) Math.floor(p[1]);
                    // The path is carved open from half a block above its floor line, so this is where the feet go.
                    // On a slope a block's own floor can sit a block above the line at the sample point: a step up.
                    int feet = (int) Math.floor(p[2] + 0.5) + 1;
                    if (t.rawDensity(x, feet, z) >= 0) feet++;
                    for (int y = feet; y <= feet + 1; y++) {
                        assertTrue(t.rawDensity(x, y, z) < 0, "route " + li + " blocked at s=" + s + " (" + x + " " + y + " " + z + ") seed " + seed);
                        assertEquals(Terrain.AIR, t.fluidAt(x, y, z), "route " + li + " under water at s=" + s + " seed " + seed);
                    }
                    samples++;
                    boolean nearEnd = s < 20 || s > r.length - 20;
                    // Rock right under the feet, or a block lower (a step down).
                    if (!nearEnd && t.rawDensity(x, feet - 1, z) < 0 && t.rawDensity(x, feet - 2, z) < 0) noFloor++;
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
            int li = r.nextInt(t.shape.layerCount);
            int x = r.nextInt(1200) - 600, z = r.nextInt(1200) - 600;
            int y = t.shape.bottom(li) + r.nextInt(t.shape.height(li));
            int f = t.fluidAt(x, y, z);
            if (y >= t.shape.waterY(li)) {
                // Above the table only the Lantern Lake's perched lakes hold water, each flat at its own level.
                if (f != Terrain.AIR) assertTrue(f == Terrain.WATER && inPerchedLake(t, li, x, y, z), "fluid above the table at " + x + " " + y + " " + z);
            } else if (f == Terrain.AIR) {
                boolean last = li == t.shape.layerCount - 1;
                Plans.Gate ex = t.plans.gate(last ? li + 1 : li + 2, t.plans.cellOf(x), t.plans.cellOf(z));
                assertTrue(last || ex.passageXZ(x, y, z) < ex.radius * 1.45 + 3.01, "dry pocket below the table at " + x + " " + y + " " + z);
            }
        }
    }

    private static boolean inPerchedLake(Terrain t, int li, int x, int y, int z) {
        for (Route.Hall hl : t.plans.route(li, t.plans.cellOf(x), t.plans.cellOf(z)).halls)
            if (hl.lake() && y < hl.lakeY() && Math.hypot(x - hl.x(), z - hl.z()) < hl.lakeR()) return true;
        return false;
    }

    /** No perched lake drains: every water block in its top layers is walled by rock or more water. */
    @Test
    void perchedLakesHoldTheirWater() {
        int lakes = 0;
        for (long seed : SEEDS) {
            Terrain t = terrain(seed);
            for (int[] c : CELLS) for (int li = 0; li < t.shape.layerCount; li++) {
                for (Route.Hall hl : t.plans.route(li, c[0], c[1]).halls) {
                    if (!hl.lake() || hl.lakeY() <= t.shape.waterY(li) + 0.5) continue;
                    lakes++;
                    int top = (int) Math.ceil(hl.lakeY()) - 1, reach = (int) Math.ceil(hl.lakeR()) + 1;
                    int cx = (int) Math.floor(hl.x()), cz = (int) Math.floor(hl.z());
                    for (int y = top; y >= top - 3; y--) for (int x = cx - reach; x <= cx + reach; x++) for (int z = cz - reach; z <= cz + reach; z++) {
                        if (t.rawDensity(x, y, z) >= 0 || t.fluidAt(x, y, z) != Terrain.WATER) continue;
                        int[][] sides = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, -1, 0}};
                        for (int[] n : sides) {
                            int nx = x + n[0], ny = y + n[1], nz = z + n[2];
                            assertFalse(t.rawDensity(nx, ny, nz) < 0 && t.fluidAt(nx, ny, nz) == Terrain.AIR,
                                    "lake at " + cx + " " + cz + " drains from " + x + " " + y + " " + z + " into " + nx + " " + ny + " " + nz + " seed " + seed);
                        }
                    }
                }
            }
        }
        assertTrue(lakes > 0, "no perched lakes to check");
    }

    /**
     * Each 600-block cell draws its own plans, so a seam would show as sharp rock walls lined up along the cell
     * borders. Steep walls happen everywhere (pillars, rims, column walls), so compare the borders with lines through
     * the middle of the cells: a border may not have many more.
     */
    @Test
    void noSeamsAtCellBorders() {
        int border = 0, inside = 0;
        for (long seed : SEEDS) {
            Terrain t = terrain(seed);
            java.util.Random r = new java.util.Random(11 + seed);
            for (int k = 0; k < 3000; k++) {
                int line = (r.nextInt(6) - 3) * 600, along = r.nextInt(2400) - 1200;
                int li = r.nextInt(t.shape.layerCount);
                int y = t.shape.bottom(li) + 6 + r.nextInt(t.shape.height(li) - 12);
                boolean xLine = r.nextBoolean();
                if (sharpStep(t, xLine, line, y, along)) border++;
                if (sharpStep(t, xLine, line + 300, y, along)) inside++;
            }
        }
        assertTrue(border <= inside * 1.5 + 10, border + " sharp steps across cell borders against " + inside + " across lines inside the cells");
    }

    /** True where the density flips between open and solid by more than 8 blocks from one block to the next. */
    private static boolean sharpStep(Terrain t, boolean xLine, int line, int y, int along) {
        double a = xLine ? t.rawDensity(line - 1, y, along) : t.rawDensity(along, y, line - 1);
        double b = xLine ? t.rawDensity(line, y, along) : t.rawDensity(along, y, line);
        return (a < 0) != (b < 0) && Math.abs(a - b) > 8;
    }

    /**
     * Spaces are sized to each biome's picture, not grown with depth (Taran, 2026-10-28): the Deep Blue Rift keeps its
     * full depth inside the shorter Blue Reaches, and the Basalt Galleries keep their cathedral halls.
     */
    @Test
    void spacesFollowTheirBiomes() {
        Terrain t = terrain(SEEDS[0]);
        int[] tallest = new int[t.shape.layerCount];
        for (int li = 0; li < t.shape.layerCount; li++) {
            for (int x = 0; x < 600; x += 6) for (int z = 0; z < 600; z += 6) {
                int run = 0;
                for (int y = t.shape.bottom(li); y <= t.shape.top(li); y++) {
                    if (t.rawDensity(x, y, z) < 0) { run++; tallest[li] = Math.max(tallest[li], run); } else run = 0;
                }
            }
        }
        assertTrue(tallest[Terrain.BLUE] > 140, "the Deep Blue Rift is only " + tallest[Terrain.BLUE] + " blocks deep");
        assertTrue(tallest[Terrain.GARDENS] > 60, "the Deep Gardens' tallest hall is only " + tallest[Terrain.GARDENS] + " blocks");
        assertTrue(tallest[Terrain.BASALT] > 120, "the Basalt Galleries' tallest open space is only " + tallest[Terrain.BASALT] + " blocks");
    }
}
