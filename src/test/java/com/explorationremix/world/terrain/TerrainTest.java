package com.explorationremix.world.terrain;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Checks of the deep terrain's promises that don't need Minecraft: layer heights, open gates and ways, flat water. */
class TerrainTest {
    private static final long[] SEEDS = {8675309L, 12345L, -42L};
    private static final int[][] CELLS = {{0, 0}, {-1, 0}, {3, -2}};

    private static Terrain terrain(long seed) { return new Terrain(TerrainSettings.defaults(), -64, seed); }

    /** The plans that had thrown when the test began ({@link NetPlanner#FAULTS}). */
    private final java.util.Set<String> faultsBefore = new java.util.TreeSet<>();

    @BeforeEach
    void countFaults() { faultsBefore.addAll(NetPlanner.FAULTS); }

    /** No plan the test made needed the planner's safety net ({@link NetPlanner#plan}). */
    @AfterEach
    void noPlanThrew() { NetworkTest.noNewFaults(faultsBefore); }

    @Test
    void layersSitWhereTheSpecSays() {
        Shape s = terrain(1).shape;
        assertEquals(-832, s.floorY);
        // Blue Reaches, Deep Gardens, Wasteland, Basalt Galleries (Taran swapped the two middle layers on 2026-10-28
        // and split the bottom one into the Wasteland and the Galleries on 2026-11-01, keeping bedrock at -832). In
        // 0.5.1 the Wasteland opens into the Galleries with no band between them: the band's 16 rows are the Wasteland's.
        int[][] expected = {{-81, -272}, {-289, -528}, {-545, -688}, {-689, -832}};
        assertEquals(4, s.layerCount);
        assertEquals("blue_reaches", s.id(0));
        assertEquals("deep_gardens", s.id(1));
        assertEquals("wasteland", s.id(2));
        assertEquals("basalt_galleries", s.id(3));
        assertEquals(-678, s.waterY(Terrain.WASTELAND), "the Wasteland's lava table (10 over its bottom)");
        assertEquals(-822, s.waterY(Terrain.GALLERIES), "the Galleries' lava table (its top block at -823, as before 0.5)");
        for (int i = 0; i < 4; i++) {
            assertEquals(expected[i][0], s.top(i), "top of layer " + i);
            assertEquals(expected[i][1], s.bottom(i), "bottom of layer " + i);
            // band i + 1 lies over layer i: none over the Galleries
            int thick = i == Terrain.GALLERIES ? 0 : 16;
            assertEquals(thick, s.bandThickness(i + 1), "band " + (i + 1) + " thickness");
            assertEquals(thick, s.bandTop(i + 1) - s.bandBottom(i + 1) + 1, "band " + (i + 1) + " rows");
        }
        assertEquals(-65, s.bandTop(1));
        assertEquals(0, Math.floorMod(s.floorY, 16));
    }

    @Test
    void gatePassagesAreOpenFromLandingToLanding() {
        for (long seed : SEEDS) {
            Terrain t = terrain(seed);
            for (int[] c : CELLS) for (int b = 1; b <= t.shape.layerCount; b++) {
                // no band, no gate: the layer above opens into the one below
                if (t.shape.bandThickness(b) == 0) continue;
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

    /**
     * Gate 3 comes down onto the Wasteland's walking floor (fix round 1, landing-plaza): with no landing dome, the
     * passage's lower end is a tunnel of the field at the landing's floor, so within 2 blocks of the landing's centre a
     * player stands with his feet within a row of it.
     */
    @Test
    void gateThreeEndsOnTheWalkingFloor() {
        for (long seed : SEEDS) {
            Terrain t = terrain(seed);
            for (int[] c : CELLS) {
                Plans.Gate g = t.plans.gate(Terrain.WASTELAND + 1, c[0], c[1]);
                int lx = (int) Math.floor(g.lx), lz = (int) Math.floor(g.lz), lf = (int) Math.ceil(g.lFloor);
                NoiseVox v = NoiseVox.build(t, lx - 2, lz - 2, 5, 5, lf - 12, lf + 12);
                boolean stands = false;
                for (int x = lx - 2; x <= lx + 2 && !stands; x++) for (int z = lz - 2; z <= lz + 2 && !stands; z++)
                    for (int y = lf - 1; y <= lf + 1 && !stands; y++) stands = v.stand(x, y, z);
                assertTrue(stands, "no walking floor at gate 3's lower end, seed " + seed + " cell " + c[0] + "," + c[1] + " (" + lx + " " + lf + " " + lz + ")");
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
            if (t.shape.bandThickness(b) == 0) continue;   // no band: the Wasteland opens into the Galleries
            int x = r.nextInt(1800) - 900, z = r.nextInt(1800) - 900;
            int y = t.shape.bandBottom(b) + r.nextInt(t.shape.bandThickness(b));
            Plans.Gate g = t.plans.gate(b, t.plans.cellOf(x), t.plans.cellOf(z));
            if (g.passageXZ(x, y, z) < g.radius * 2 + 6) continue;
            if (t.rawDensity(x, y, z) <= 0) open++;
        }
        assertEquals(0, open, "open blocks inside bands away from the passages");
    }

    /** Over the planned layers (the Wasteland and the Basalt Galleries are noise fields, with no ways). */
    @Test
    void everyWayHasAnOpenWalkingPathWithRockUnderIt() {
        for (long seed : SEEDS) {
            Terrain t = terrain(seed);
            for (int[] c : CELLS) for (int li = 0; li < t.shape.layerCount && Terrain.planned(li); li++) {
                Net n = t.network.net(li, c[0], c[1]);
                Plans.Gate en = t.plans.gate(li + 1, c[0], c[1]);
                Plans.Gate ex = li + 2 <= t.shape.layerCount ? t.plans.gate(li + 2, c[0], c[1]) : null;
                int[] counts = new int[2];
                double cell = t.network.cell, x0 = c[0] * cell, z0 = c[1] * cell;
                // every tunnel, inner walk, ledge passage and stub, every 2 blocks along its line
                for (Net.Way wy : n.ways) {
                    double carry = 0;
                    for (int i = 0; i + 1 < wy.n; i++) {
                        double dx = wy.x[i + 1] - wy.x[i], dz = wy.z[i + 1] - wy.z[i], len = Math.hypot(dx, dz);
                        double s = carry;
                        for (; s < len; s += 2) {
                            double q = s / len, px = wy.x[i] + dx * q, pz = wy.z[i] + dz * q;
                            // a portal's stub runs on past the seam: the neighbour's own plan carves (and checks) that half
                            if (px < x0 || pz < z0 || px >= x0 + cell || pz >= z0 + cell) continue;
                            double f = wy.f[i] + (wy.f[i + 1] - wy.f[i]) * q;
                            // an inner walk across a chamber whose floor dips under the table: a flooded bay, swum across
                            if (wy.inner && f < t.shape.waterY(li)) continue;
                            // a climb's ledge or tunnel sets out across the mouth of the stage it leaves, and a walk
                            // from a stage that opens in a chamber's floor starts there: the hole is the way up
                            if (overStage(n, px, f, pz)) continue;
                            wayPoint(t, li, en, ex, px, f, pz, "way #" + wy.id + " (" + wy.kind + ")", seed, counts);
                        }
                        carry = s - len;
                    }
                }
                // the Deep Blue Rift's ledge path, checked as in 0.2.0
                for (Route r : n.canyons) {
                    for (double s = 0; s <= r.length; s += 2) {
                        double[] p = r.pathAt(s);
                        int x = (int) Math.floor(p[0]), z = (int) Math.floor(p[1]);
                        // The path is carved open from half a block above its floor line, so this is where the feet go.
                        // On a slope a block's own floor can sit a block above the line at the sample point: a step up.
                        int feet = (int) Math.floor(p[2] + 0.5) + 1;
                        if (t.rawDensity(x, feet, z) >= 0) feet++;
                        for (int y = feet; y <= feet + 1; y++) {
                            assertTrue(t.rawDensity(x, y, z) < 0, "the rift's path blocked at s=" + s + " (" + x + " " + y + " " + z + ") seed " + seed);
                            assertEquals(Terrain.AIR, t.fluidAt(x, y, z), "the rift's path under water at s=" + s + " seed " + seed);
                        }
                        counts[0]++;
                        boolean nearEnd = s < 20 || s > r.length - 20;
                        // Rock right under the feet, or a block lower (a step down).
                        if (!nearEnd && t.rawDensity(x, feet - 1, z) < 0 && t.rawDensity(x, feet - 2, z) < 0) counts[1]++;
                    }
                }
                assertTrue(counts[1] <= counts[0] / 50, "layer " + li + " cell " + c[0] + "," + c[1] + " seed " + seed + ": " + counts[1] + " of " + counts[0] + " path points have no rock under them");
            }
        }
    }

    /** True when the block nearest a walking line's point lies over the hole of a climb's stage, at its mouth. */
    private static boolean overStage(Net n, double px, double floor, double pz) {
        int x = (int) Math.round(px), z = (int) Math.round(pz);
        for (Net.Shaft s : n.shafts)
            if (Math.hypot(x - s.x, z - s.z) < s.r + 0.5 && floor > s.y0 && floor < s.y1 + 1) return true;
        return false;
    }

    /**
     * One point of a walking line: the feet (at the first block over the floor line, or one higher on a step up) and the
     * head are open and dry; counts the point, and whether there is rock under the feet or one block lower.
     */
    private static void wayPoint(Terrain t, int li, Plans.Gate en, Plans.Gate ex, double px, double floor, double pz, String what, long seed, int[] counts) {
        // the block whose column is nearest the line
        int x = (int) Math.round(px), z = (int) Math.round(pz);
        int feet = (int) Math.ceil(floor - 1e-9);
        if (en.passageXZ(x, feet, z) < en.radius * 1.45 + 3 || (ex != null && ex.passageXZ(x, feet, z) < ex.radius * 1.45 + 3)) return;
        if (t.rawDensity(x, feet, z) >= 0) feet++;
        for (int y = feet; y <= feet + 1; y++) {
            assertTrue(t.rawDensity(x, y, z) < 0, what + " in layer " + li + " blocked at " + x + " " + y + " " + z + " seed " + seed);
            assertEquals(Terrain.AIR, t.fluidAt(x, y, z), what + " in layer " + li + " under water at " + x + " " + y + " " + z + " seed " + seed);
        }
        counts[0]++;
        if (t.rawDensity(x, feet - 1, z) < 0 && t.rawDensity(x, feet - 2, z) < 0) counts[1]++;
    }

    /**
     * Each layer's fluid lies flat at its table. Above it only perched water: the Lantern Lake's perched lakes, each flat
     * at its own level, and the pools of the Basalt Galleries' Obsidian Tubes. Under it the table's fluid but in a gate's
     * passage, and in the Wasteland, where the Galleries open into it, air ({@link Join#AIR_BETA}): its lava pools only
     * where beta is under that.
     */
    @Test
    void waterTablesAreFlat() {
        Terrain t = terrain(SEEDS[1]);
        java.util.Random r = new java.util.Random(3);
        int wasteAir = 0, wasteLava = 0;
        for (int k = 0; k < 20000; k++) {
            int li = r.nextInt(t.shape.layerCount);
            int x = r.nextInt(1200) - 600, z = r.nextInt(1200) - 600;
            int y = t.shape.bottom(li) + r.nextInt(t.shape.height(li));
            int f = t.fluidAt(x, y, z);
            if (y >= t.shape.waterY(li)) {
                if (f != Terrain.AIR) assertTrue(f == Terrain.WATER && (li == Terrain.GALLERIES || inPerchedLake(t, li, x, y, z)), "fluid above the table at " + x + " " + y + " " + z);
            } else if (li == Terrain.WASTELAND) {
                boolean air = t.breakthrough != null && t.breakthrough.beta(x, z) >= Join.AIR_BETA;
                if (air) wasteAir++; else wasteLava++;
                assertEquals(air ? Terrain.AIR : Terrain.LAVA, f, "the Wasteland under its table at " + x + " " + y + " " + z);
            } else if (f == Terrain.AIR) {
                boolean last = li == t.shape.layerCount - 1;
                Plans.Gate ex = t.plans.gate(last ? li + 1 : li + 2, t.plans.cellOf(x), t.plans.cellOf(z));
                assertTrue(last || ex.passageXZ(x, y, z) < ex.radius * 1.45 + 3.01, "dry pocket below the table at " + x + " " + y + " " + z);
            }
        }
        assertTrue(wasteAir > 0 && wasteLava > 0, "the Wasteland under its table: " + wasteAir + " air, " + wasteLava + " lava");
    }

    private static boolean inPerchedLake(Terrain t, int li, int x, int y, int z) {
        Network w = t.network;
        for (Net.Chamber c : w.net(li, w.cellOf(x), w.cellOf(z)).ch)
            if (c.keep && c.kind == Net.Kind.LAKE && y < c.lakeY && w.e(c, x, z) < c.lakeE + 0.1) return true;
        return false;
    }

    /** No perched lake drains: every water block in its top layers is walled by rock or more water (planned layers). */
    @Test
    void perchedLakesHoldTheirWater() {
        int lakes = 0;
        for (long seed : SEEDS) {
            Terrain t = terrain(seed);
            for (int[] c : CELLS) for (int li = 0; li < t.shape.layerCount && Terrain.planned(li); li++) {
                for (Net.Chamber hl : t.network.net(li, c[0], c[1]).ch) {
                    // the perched lakes and the pools at the wells' feet
                    if (!hl.keep || !Network.pool(hl) || hl.lakeY <= t.shape.waterY(li) + 0.5) continue;
                    lakes++;
                    int top = (int) Math.ceil(hl.lakeY) - 1, reach = (int) Math.ceil(hl.R()) + 1;
                    int cx = (int) Math.floor(hl.x), cz = (int) Math.floor(hl.z);
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
        // the seeds run side by side
        int[][] counts = java.util.Arrays.stream(SEEDS).parallel().mapToObj(TerrainTest::seamSteps).toArray(int[][]::new);
        int border = 0, inside = 0;
        for (int[] c : counts) { border += c[0]; inside += c[1]; }
        assertTrue(border <= inside * 1.5 + 10, border + " sharp steps across cell borders against " + inside + " across lines inside the cells");
    }

    /** One seed's sharp steps {across the cell borders, across lines through the middle of the cells}. */
    private static int[] seamSteps(long seed) {
        Terrain t = terrain(seed);
        java.util.Random r = new java.util.Random(11 + seed);
        java.util.List<int[]> samples = new java.util.ArrayList<>();
        for (int k = 0; k < 3000; k++) {
            int line = (r.nextInt(6) - 3) * 600, along = r.nextInt(2400) - 1200;
            int li = r.nextInt(t.shape.layerCount);
            int y = t.shape.bottom(li) + 6 + r.nextInt(t.shape.height(li) - 12);
            boolean xLine = r.nextBoolean();
            samples.add(new int[]{li, xLine ? 1 : 0, line, Math.floorDiv(along, 600), along, y});
        }
        // the rows where the Wasteland and the Galleries meet with no band between them (the join's), which the samples
        // above mostly miss
        if (t.join != null && t.join.on) for (int k = 0; k < 600; k++) {
            int line = (r.nextInt(6) - 3) * 600, along = r.nextInt(2400) - 1200;
            int y = t.join.lo + r.nextInt(t.join.hi - t.join.lo + 1);
            boolean xLine = r.nextBoolean();
            samples.add(new int[]{t.shape.layerAt(y), xLine ? 1 : 0, line, Math.floorDiv(along, 600), along, y});
        }
        // Taken grouped by layer, line and cell along the line, so each cell-layer is planned once: in random order
        // the samples visit about 150 cell-layers and thrashed the plan cache.
        samples.sort(java.util.Comparator.<int[]>comparingInt(s -> s[0]).thenComparingInt(s -> s[1]).thenComparingInt(s -> s[2]).thenComparingInt(s -> s[3]));
        int border = 0, inside = 0;
        for (int[] s : samples) {
            boolean xLine = s[1] == 1;
            if (sharpStep(t, xLine, s[2], s[5], s[4])) border++;
            if (sharpStep(t, xLine, s[2] + 300, s[5], s[4])) inside++;
        }
        return new int[]{border, inside};
    }

    /** True where the density flips between open and solid by more than 8 blocks from one block to the next. */
    private static boolean sharpStep(Terrain t, boolean xLine, int line, int y, int along) {
        double a = xLine ? t.rawDensity(line - 1, y, along) : t.rawDensity(along, y, line - 1);
        double b = xLine ? t.rawDensity(line, y, along) : t.rawDensity(along, y, line);
        return (a < 0) != (b < 0) && Math.abs(a - b) > 8;
    }

    /**
     * Spaces are sized to each biome's picture, not grown with depth (Taran, 2026-10-28): the Deep Blue Rift keeps its
     * full depth inside the shorter Blue Reaches, and the Basalt Galleries keep their tall caverns. The noise layers'
     * open blocks take their own biomes: the Wasteland's one of its two, the Galleries' one of their five.
     */
    @Test
    void spacesFollowTheirBiomes() {
        Terrain t = terrain(SEEDS[0]);
        int[] tallest = new int[t.shape.layerCount];
        java.util.Map<Integer, java.util.Set<DeepBiome>> own = java.util.Map.of(
                Terrain.WASTELAND, java.util.EnumSet.of(DeepBiome.WASTELAND, DeepBiome.ASH_HOLLOWS),
                Terrain.GALLERIES, java.util.EnumSet.of(DeepBiome.BASALT_GALLERIES, DeepBiome.CALDERA, DeepBiome.CAUSEWAY,
                        DeepBiome.SCORIA_CANYONS, DeepBiome.OBSIDIAN_TUBES));
        java.util.List<String> strays = new java.util.ArrayList<>();
        for (int li = 0; li < t.shape.layerCount; li++) {
            java.util.Set<DeepBiome> mine = own.get(li);
            for (int x = 0; x < 600; x += 6) for (int z = 0; z < 600; z += 6) {
                int run = 0;
                for (int y = t.shape.bottom(li); y <= t.shape.top(li); y++) {
                    if (t.rawDensity(x, y, z) < 0) {
                        run++;
                        tallest[li] = Math.max(tallest[li], run);
                        if (mine != null && !mine.contains(t.biomeAt(x, y, z)) && strays.size() < 10) strays.add(x + " " + y + " " + z + ": " + t.biomeAt(x, y, z));
                    } else run = 0;
                }
            }
        }
        assertTrue(strays.isEmpty(), "open blocks of the noise layers in another biome: " + strays);
        assertTrue(tallest[Terrain.BLUE] > 140, "the Deep Blue Rift is only " + tallest[Terrain.BLUE] + " blocks deep");
        assertTrue(tallest[Terrain.GARDENS] > 60, "the Deep Gardens' tallest hall is only " + tallest[Terrain.GARDENS] + " blocks");
        // the Basalt Galleries' caverns: tall from the lava up
        assertTrue(tallest[Terrain.GALLERIES] >= 60, "the Basalt Galleries' tallest open space is only " + tallest[Terrain.GALLERIES] + " blocks");
    }
}
