package com.explorationremix.world.terrain;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 0.5.1 looks of the two bottom layers (Palette.shell): natural stone only, no stripe at the high-water mark, the
 * Wasteland's and the Galleries' looks blended over ragged rows at the join, magma only by the lava, crying obsidian
 * sparingly, columns that read one by one, no magma shore where the Wasteland keeps its lava off an opening; and the
 * rock of layers 0 and 1 and of the upper caves exactly as 0.5.0 made it (but where the Blue Reaches' ways moved).
 */
class PaletteLooksTest {
    private static final long SEED = 8675309L;
    private static Terrain terrain;

    private static synchronized Terrain terrain() {
        if (terrain == null) terrain = new Terrain(TerrainSettings.defaults(), -64, SEED);
        return terrain;
    }

    /** Stand-in look queries: 3x3 columns, clusters, tube walls, cones and kept lava in fixed patterns. */
    static final class Stand implements LookQueries {
        boolean kept;
        @Override public long columnCell(int x, int z) { return Hashing.mix(77, Math.floorDiv(x, 3), 0, Math.floorDiv(z, 3)) | 1; }
        @Override public double columnEdge(int x, int z) { return Math.min(Math.min(Math.floorMod(x, 3), 2 - Math.floorMod(x, 3)), Math.min(Math.floorMod(z, 3), 2 - Math.floorMod(z, 3))) + 0.5; }
        @Override public double hang(int x, int y, int z) { return ((x ^ z) & 8) == 0 ? 0.5 : 0; }
        @Override public double tube(int x, int y, int z) { return Math.floorMod(x * 7 + z * 13, 5) - 0.5; }
        @Override public double tubeShellDepth(int x, int y, int z) { return Math.max(0, tube(x, y, z)); }
        @Override public boolean tubeRib(int x, int y, int z) { return (x & 7) == 0; }
        @Override public double cone(int x, int z) { return Math.floorMod(x, 40) / 20.0; }
        @Override public boolean lavaKept(int x, int y, int z) { return kept; }
    }

    private static final DeepBiome[] GALLERIES = {DeepBiome.BASALT_GALLERIES, DeepBiome.CALDERA, DeepBiome.CAUSEWAY,
            DeepBiome.SCORIA_CANYONS, DeepBiome.OBSIDIAN_TUBES};

    @Test
    void layersZeroAndOneAndTheUpperCavesKeepTheirRock() {
        // 0.5.0's answers (IdentityHash over a2c3d73's classes, seed 8675309; the same for seed 12345 against its own),
        // but for the public path's: there the shell follows the plan's biomes, and in 0.5.1 the Blue Reaches' ways and
        // deep dark doors kept off the rift's void and the caverns' air, which changes the rock at 22 of layer 0's 60,000
        // samples (layer 1's and the bands' are 0.5.0's)
        Terrain t = terrain();
        Palette p = t.palette;
        Shape s = t.shape;
        Random r = new Random(SEED);
        long hBiome = 17, hInterior = 17, hPublic = 17, hUpper = 17;
        DeepBiome[][] byLayer = {{DeepBiome.DEEP_BLUE_RIFT, DeepBiome.DEEP_DARK, DeepBiome.ASURINE_GEODES},
                {DeepBiome.LANTERN_LAKE, DeepBiome.EMBER_GARDENS, DeepBiome.RUST_SPRINGS}};
        for (int li = 0; li < 2; li++) {
            for (int k = 0; k < 400000; k++) {
                int x = r.nextInt(4000) - 2000, z = r.nextInt(4000) - 2000;
                int y = s.bottom(li) + r.nextInt(s.height(li));
                int above = 1 + r.nextInt(8), below = 1 + r.nextInt(8);
                boolean wet = r.nextInt(4) == 0;
                for (DeepBiome b : byLayer[li]) hBiome = hBiome * 31 + p.shell(b, li, x, y, z, above, below, wet).name().hashCode();
                hInterior = hInterior * 31 + p.interior(li, x, y, z).name().hashCode();
            }
            for (int k = 0; k < 60000; k++) {
                int x = r.nextInt(1200) - 600, z = r.nextInt(600) - 300;
                int y = s.bottom(li) + r.nextInt(s.height(li));
                hPublic = hPublic * 31 + p.shell(x, y, z, 1 + r.nextInt(8), 1 + r.nextInt(8), r.nextInt(4) == 0).name().hashCode();
            }
        }
        for (int b = 1; b <= 3; b++) for (int k = 0; k < 2000; k++) {
            int y = s.bandBottom(b) + r.nextInt(Math.max(1, s.bandTop(b) - s.bandBottom(b) + 1));
            hPublic = hPublic * 31 + p.shell(r.nextInt(2000), y, r.nextInt(2000), 3, 3, false).name().hashCode();
        }
        DeepBiome[] upper = {DeepBiome.SILVER_FERN_RIFT, DeepBiome.IVORY_ROOT_CAVERN};
        for (int k = 0; k < 400000; k++) {
            int x = r.nextInt(4000) - 2000, z = r.nextInt(4000) - 2000, y = -64 + r.nextInt(120);
            int above = 1 + r.nextInt(8), below = 1 + r.nextInt(8);
            boolean wet = r.nextInt(4) == 0;
            double shell = r.nextDouble() * 8;
            for (DeepBiome b : upper) hUpper = hUpper * 31 + nz(p.upperShell(b, x, y, z, above, below, wet));
            hUpper = hUpper * 31 + nz(p.rootvaults(r.nextInt(3), r.nextBoolean(), x, y, z, above, below, wet, shell));
            hUpper = hUpper * 31 + nz(p.frozenVaults(r.nextInt(3), r.nextDouble(), x, y, z, above, below, wet, shell));
            hUpper = hUpper * 31 + nz(p.sinkhole(x, y, z, above, below, wet, shell, r.nextBoolean()));
            hUpper = hUpper * 31 + nz(p.oasis(x, y, z, above, below, wet, shell, r.nextBoolean()));
            hUpper = hUpper * 31 + nz(p.frostGrotto(x, y, z, above, below, wet));
        }
        assertEquals("b9eaef6541e0fcd6", String.format("%016x", hBiome), "layers 0 and 1, by biome");
        assertEquals("2604e01a1b4b2ee3", String.format("%016x", hInterior), "layers 0 and 1, interior");
        assertEquals("4913fabc7a673e9d", String.format("%016x", hPublic), "layers 0 and 1 through the public path, and the bands");
        assertEquals("f1e881cc466dc49d", String.format("%016x", hUpper), "the upper caves");
    }

    private static int nz(Rock q) { return q == null ? 0 : q.name().hashCode(); }

    @Test
    void naturalStoneOnly() {
        Set<Rock> allowed = EnumSet.of(Rock.BASALT, Rock.SMOOTH_BASALT, Rock.BLACKSTONE, Rock.GILDED_BLACKSTONE, Rock.MAGMA,
                Rock.OBSIDIAN, Rock.CRYING_OBSIDIAN, Rock.TUFF, Rock.CALCITE, Rock.DRIPSTONE, Rock.SCORIA, Rock.SCORCHIA,
                Rock.LAYERED_SCORIA, Rock.LAYERED_SCORCHIA, Rock.EMBER_CRUST, Rock.DEEPSLATE_GOLD_ORE);
        Terrain t = terrain();
        Shape s = t.shape;
        Random r = new Random(5);
        // no look queries, stand-ins that put columns, tubes and cones everywhere, and the noise fields' own (t.palette)
        for (Palette p : new Palette[]{new Palette(t, SEED, LookQueries.NONE), new Palette(t, SEED, new Stand()), t.palette}) {
            for (int k = 0; k < 200000; k++) {
                boolean gal = r.nextBoolean();
                int li = gal ? Terrain.GALLERIES : Terrain.WASTELAND;
                DeepBiome b = gal ? GALLERIES[r.nextInt(GALLERIES.length)] : r.nextBoolean() ? DeepBiome.WASTELAND : DeepBiome.ASH_HOLLOWS;
                int x = r.nextInt(4000) - 2000, z = r.nextInt(4000) - 2000, y = s.bottom(li) + r.nextInt(s.height(li));
                Rock q = p.shell(b, li, x, y, z, 1 + r.nextInt(8), 1 + r.nextInt(8), r.nextInt(4) == 0);
                assertTrue(allowed.contains(q), q + " in " + b + " at " + x + " " + y + " " + z);
            }
        }
    }

    @Test
    void noStripeAtTheHighWaterMark() {
        Terrain t = terrain();
        Palette p = new Palette(t, SEED, LookQueries.NONE);
        int W = t.shape.waterY(Terrain.GALLERIES), high = W + Terrain.surge(Terrain.GALLERIES);
        int rows = 16, n = 20000;
        double[] dark = new double[rows];
        for (int i = 0; i < rows; i++) {
            int y = high - 12 + i, magma = 0, black = 0;
            for (int k = 0; k < n; k++) {
                Rock q = p.shell(DeepBiome.BASALT_GALLERIES, Terrain.GALLERIES, k * 7 - 70000, y, (k * 13) % 4001 - 2000, 12, 12, false);
                if (q == Rock.MAGMA) magma++;
                if (q == Rock.BLACKSTONE) black++;
            }
            assertTrue(magma <= n / 100, "magma on " + magma + " of " + n + " walls at W+" + (y - W));
            dark[i] = black / (double) n;
        }
        double fall = dark[0] - dark[rows - 1];
        assertTrue(fall > 0.1, "the scorched walls under the mark are darker: " + fall);
        for (int i = 0; i + 1 < rows; i++)
            assertTrue(Math.abs(dark[i + 1] - dark[i]) < 0.5 * fall, "a step of " + (dark[i] - dark[i + 1]) + " of " + fall + " at W+" + (high - 12 + i - W));
    }

    @Test
    void theJoinBlendsOverRaggedRows() {
        Terrain t = terrain();
        Shape s = t.shape;
        int gal = Terrain.GALLERIES, was = Terrain.WASTELAND, join = s.top(gal);
        assertEquals(join + 1, s.bottom(was), "the Wasteland stands right on the Galleries (no band between them)");
        Palette p = t.palette;
        double last = -1;
        for (int d = -Palette.JOIN_BLEND - 2; d <= Palette.JOIN_BLEND + 2; d++) {
            int y = join + d, li = d > 0 ? was : gal, up = 0, n = 20000;
            for (int k = 0; k < n; k++) if (p.lookLayer(li, k * 11 - 110000, y, (k * 17) % 3001 - 1500) == was) up++;
            double share = up / (double) n;
            if (d <= -Palette.JOIN_BLEND) assertEquals(0, share, 1e-9, "Wasteland look at join" + d);
            if (d > Palette.JOIN_BLEND) assertEquals(1, share, 1e-9, "Galleries look at join+" + d);
            if (d == 0 || d == 1) assertTrue(share > 0.1 && share < 0.9, "both looks at join+" + d + ": " + share);
            assertTrue(share >= last - 0.02, "the Wasteland's share grows upward: " + share + " after " + last);
            last = share;
        }
    }

    @Test
    void magmaOnlyByTheLava() {
        Terrain t = terrain();
        Shape s = t.shape;
        Random r = new Random(9);
        for (Palette p : new Palette[]{new Palette(t, SEED, LookQueries.NONE), new Palette(t, SEED, new Stand())}) {
            for (int k = 0; k < 200000; k++) {
                DeepBiome b = new DeepBiome[]{DeepBiome.BASALT_GALLERIES, DeepBiome.CAUSEWAY, DeepBiome.SCORIA_CANYONS}[r.nextInt(3)];
                int W = s.waterY(Terrain.GALLERIES), y = W + 7 + r.nextInt(s.top(Terrain.GALLERIES) - W - 7);
                Rock q = p.shell(b, Terrain.GALLERIES, r.nextInt(4000) - 2000, y, r.nextInt(4000) - 2000, 1 + r.nextInt(8), 1 + r.nextInt(8), false);
                assertTrue(q != Rock.MAGMA, "magma in " + b + " at W+" + (y - W));
            }
        }
    }

    @Test
    void noMagmaShoreWhereTheWastelandKeepsItsLavaOff() {
        Terrain t = terrain();
        Stand looks = new Stand();
        looks.kept = true;
        Palette p = new Palette(t, SEED, looks);
        int W = t.shape.waterY(Terrain.WASTELAND), magma = 0;
        for (int k = 0; k < 40000; k++) {
            int y = W - 1 + k % 4;
            if (p.shell(DeepBiome.WASTELAND, Terrain.WASTELAND, k * 7 - 140000, y, (k * 29) % 3001 - 1500, 1, 8, false) == Rock.MAGMA) magma++;
        }
        assertEquals(0, magma, "magma on dry shore floors by an opening");
    }

    @Test
    void cryingObsidianSparingly() {
        Terrain t = terrain();
        Palette p = new Palette(t, SEED, new Stand());
        int W = t.shape.waterY(Terrain.GALLERIES), obsidian = 0, crying = 0;
        Random r = new Random(3);
        for (int k = 0; k < 200000; k++) {
            Rock q = p.shell(DeepBiome.OBSIDIAN_TUBES, Terrain.GALLERIES, r.nextInt(4000) - 2000, W + 45 + r.nextInt(60), r.nextInt(4000) - 2000,
                    1 + r.nextInt(8), 1 + r.nextInt(8), false);
            if (q == Rock.OBSIDIAN) obsidian++;
            if (q == Rock.CRYING_OBSIDIAN) crying++;
        }
        assertTrue(obsidian > 10000, "the tubes are lined with obsidian: " + obsidian);
        assertTrue(crying < 0.05 * (obsidian + crying), "crying obsidian " + crying + " of " + (obsidian + crying));
    }

    @Test
    void columnsReadOneByOne() {
        Terrain t = terrain();
        Palette p = new Palette(t, SEED, new Stand());
        int W = t.shape.waterY(Terrain.GALLERIES), same = 0, all = 0;
        Set<Rock> specks = EnumSet.of(Rock.GILDED_BLACKSTONE, Rock.DEEPSLATE_GOLD_ORE, Rock.EMBER_CRUST);
        for (int c = 0; c < 2000; c++) {
            int x0 = Math.floorDiv(c * 3 * 37 - 100000, 3) * 3, z0 = (c * 3 * 53) % 6000 - 3000;   // the corner of a 3x3 column
            Map<Rock, Integer> count = new EnumMap<>(Rock.class);
            for (int y = W + 55; y < W + 70; y++) for (int dx = 0; dx < 3; dx++) for (int dz = 0; dz < 3; dz++) {
                Rock q = p.shell(DeepBiome.BASALT_GALLERIES, Terrain.GALLERIES, x0 + dx, y, z0 + dz, 12, 12, false);
                if (!specks.contains(q)) count.merge(q, 1, Integer::sum);
            }
            int most = count.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            same += most;
            all += count.values().stream().mapToInt(Integer::intValue).sum();
        }
        assertTrue(same > 0.95 * all, "blocks of a column in its column's stone: " + same + " of " + all);
    }
}
