package com.explorationremix.world.terrain;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The looks of the two bottom layers (Palette.shell): natural stone only, and no made block anywhere in them (palette,
 * features); no stripe at the high-water mark, and a tide that only darkens a column, once; every face of a column its
 * column's stone, top to underside; the Wasteland's and the Galleries' looks blended over ragged rows at the join; in the
 * Galleries magma only on rock the lava touches, checked on the blocks the real fields build; the Caldera's cinders on its
 * cones, not on the walls and vault over them; crying obsidian sparingly;
 * columns that read one by one; no magma shore where the Wasteland keeps its lava off an opening; and the rock of layers
 * 0 and 1 and of the upper caves exactly as 0.5.0 made it (but where the Blue Reaches' ways moved, and where main's den
 * gates brought their landings and moved the rift for them).
 */
class PaletteLooksTest {
    private static final long SEED = 8675309L;
    private static Terrain terrain;

    private static synchronized Terrain terrain() {
        if (terrain == null) terrain = new Terrain(TerrainSettings.defaults(), -64, SEED);
        return terrain;
    }

    /**
     * Stand-in look queries: 3x3 columns, clusters, tube walls, cones (their ground over every place) and kept lava in
     * fixed patterns; {@code hang} and {@code cone}, when set, put every place in (or out of) a cluster or on (or off) a
     * cone.
     */
    static final class Stand implements LookQueries {
        boolean kept;
        Double hang, cone;
        @Override public long columnCell(int x, int z) { return Hashing.mix(77, Math.floorDiv(x, 3), 0, Math.floorDiv(z, 3)) | 1; }
        @Override public double columnEdge(int x, int z) { return Math.min(Math.min(Math.floorMod(x, 3), 2 - Math.floorMod(x, 3)), Math.min(Math.floorMod(z, 3), 2 - Math.floorMod(z, 3))) + 0.5; }
        @Override public double hang(int x, int y, int z) { return hang != null ? hang : ((x ^ z) & 8) == 0 ? 0.5 : 0; }
        @Override public double tube(int x, int y, int z) { return Math.floorMod(x * 7 + z * 13, 5) - 0.5; }
        @Override public double tubeShellDepth(int x, int y, int z) { return Math.max(0, tube(x, y, z)); }
        @Override public boolean tubeRib(int x, int y, int z) { return (x & 7) == 0; }
        @Override public double cone(int x, int z) { return cone != null ? cone : Math.floorMod(x, 40) / 20.0; }
        @Override public double coneTop(int x, int z) { return 1e9; }
        @Override public boolean lavaKept(int x, int y, int z) { return kept; }
    }

    private static final DeepBiome[] GALLERIES = {DeepBiome.BASALT_GALLERIES, DeepBiome.CALDERA, DeepBiome.CAUSEWAY,
            DeepBiome.SCORIA_CANYONS, DeepBiome.OBSIDIAN_TUBES};

    @Test
    void layersZeroAndOneAndTheUpperCavesKeepTheirRock() {
        // 0.5.0's answers (IdentityHash over a2c3d73's classes, seed 8675309; the same for seed 12345 against its own),
        // but for the public path's: there the shell follows the plan's biomes, and in 0.5.1 the Blue Reaches' ways and
        // deep dark doors kept off the rift's void and the caverns' air, which changes the rock at 22 of layer 0's 60,000
        // samples (layer 1's and the bands' are 0.5.0's). Pinned again with main's den gates (0.8.0): each cell plans the
        // lower landings of its den gates past the first, with their courts and ways, and the rift keeps clear of them;
        // the geodes move with the rift, which changes the rock by biome at 1,475 of layer 0's 400,000 samples, all in
        // the Asurine Geodes (the answer is 0.6.1's), and through the public path at 608 of layer 0's 60,000 samples.
        // Against 0.6.1 the public path differs at 44 samples, all where the ways keep off the void. The interior,
        // layer 1, the bands and the upper caves answer as before. With the den gates past the first switched off all
        // four answers are the last pin's; with the void rules switched off they are 0.6.1's (the pillar drop changes
        // none of them). They held when this merge landed on the reshape's fixes (0.8.0): a49a88c answers 94a4dea's
        // four (the last pin's), 1dd7375 and the merge these. Split over the same draws into one running hash per layer
        // (by biome, interior, public path), per band and for the upper caves, every part of the merge's answers is
        // 1dd7375's; they differ from a49a88c's only in layer 0's by-biome and public parts, and every other part, band
        // 3's (the band over the noise layers) among them, is the same on all three. None of the four reaches the
        // Wasteland or the Galleries
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
        assertEquals("d4480c4b98b9e541", String.format("%016x", hBiome), "layers 0 and 1, by biome");
        assertEquals("2604e01a1b4b2ee3", String.format("%016x", hInterior), "layers 0 and 1, interior");
        assertEquals("625c6dfe796ad0fa", String.format("%016x", hPublic), "layers 0 and 1 through the public path, and the bands");
        assertEquals("f1e881cc466dc49d", String.format("%016x", hUpper), "the upper caves");
    }

    private static int nz(Rock q) { return q == null ? 0 : q.name().hashCode(); }

    @Test
    void naturalStoneOnly() {
        Set<Rock> allowed = EnumSet.of(Rock.BASALT, Rock.SMOOTH_BASALT, Rock.BLACKSTONE, Rock.GILDED_BLACKSTONE, Rock.MAGMA,
                Rock.OBSIDIAN, Rock.CRYING_OBSIDIAN, Rock.TUFF, Rock.CALCITE, Rock.DRIPSTONE, Rock.SCORIA, Rock.SCORCHIA,
                Rock.EMBER_CRUST, Rock.DEEPSLATE_GOLD_ORE);
        Terrain t = terrain();
        Shape s = t.shape;
        Random r = new Random(5);
        // no look queries, stand-ins that put columns, tubes and cones everywhere, and the noise fields' own (t.palette)
        for (Palette p : new Palette[]{new Palette(t, SEED, LookQueries.NONE), new Palette(t, SEED, new Stand()), t.palette}) {
            // columns in 40 clusters anywhere in the square (Spread): the noise fields resolve the Galleries' openings
            // round an area once, where columns at random over it would make every query pay for that again
            Spread spread = new Spread(r, 4000, 512, 5000);
            for (int k = 0; k < 200000; k++) {
                boolean gal = r.nextBoolean();
                int li = gal ? Terrain.GALLERIES : Terrain.WASTELAND;
                DeepBiome b = gal ? GALLERIES[r.nextInt(GALLERIES.length)] : r.nextBoolean() ? DeepBiome.WASTELAND : DeepBiome.ASH_HOLLOWS;
                int[] at = spread.next();
                int x = at[0], z = at[1], y = s.bottom(li) + r.nextInt(s.height(li));
                Rock q = p.shell(b, li, x, y, z, 1 + r.nextInt(8), 1 + r.nextInt(8), r.nextInt(4) == 0);
                assertTrue(allowed.contains(q), q + " in " + b + " at " + x + " " + y + " " + z);
            }
        }
    }

    @Test
    void noStripeAtTheHighWaterMark() {
        Terrain t = terrain();
        Palette p = new Palette(t, SEED, new Stand());   // columns, as the Galleries' field always has
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

    /** 0 basalt or smooth basalt (grey), 1 blackstone or gilded blackstone (dark), -1 a speck of anything else. */
    private static int shade(Rock q) {
        if (q == Rock.BASALT || q == Rock.SMOOTH_BASALT) return 0;
        if (q == Rock.BLACKSTONE || q == Rock.GILDED_BLACKSTONE) return 1;
        return -1;
    }

    @Test
    void theTideOnlyDarkensWholeColumns() {
        // The flood scorches what it covers, but the columns still read one by one (picture 1): under the mark a column
        // keeps its stone or turns blackstone, once, at its own height, and never turns from dark to grey.
        Terrain t = terrain();
        Stand looks = new Stand();
        Palette p = new Palette(t, SEED, looks);
        int W = t.shape.waterY(Terrain.GALLERIES), n = 20000;
        Object[][] faces = {{DeepBiome.BASALT_GALLERIES, 0.0, 12, "Galleries walls"}, {DeepBiome.BASALT_GALLERIES, 0.6, 1, "cluster undersides"},
                {DeepBiome.CAUSEWAY, 0.0, 12, "Causeway walls"}, {DeepBiome.CALDERA, 0.0, 12, "Caldera walls"}};
        looks.cone = 9.0;
        for (Object[] f : faces) {
            DeepBiome b = (DeepBiome) f[0];
            looks.hang = (Double) f[1];
            int below = (Integer) f[2];
            String what = (String) f[3];
            int changed = 0, both = 0, dark45 = 0, dark12 = 0, twice = 0, lighter = 0;
            for (int k = 0; k < n; k++) {
                int x = k * 7 - 70000, z = Math.floorMod(k * 13, 4001) - 2000;
                int a = shade(p.shell(b, Terrain.GALLERIES, x, W + 45, z, 12, below, false));
                int c = shade(p.shell(b, Terrain.GALLERIES, x, W + 12, z, 12, below, false));
                if (a >= 0 && c >= 0) {
                    both++;
                    if (a != c) changed++;
                    dark45 += a;
                    dark12 += c;
                }
                // the column from W+50 down to W+8, specks skipped
                int last = -1, changes = 0;
                for (int y = W + 50; y >= W + 8; y--) {
                    int q = shade(p.shell(b, Terrain.GALLERIES, x, y, z, 12, below, false));
                    if (q < 0) continue;
                    if (last >= 0 && q != last) {
                        changes++;
                        if (q < last) lighter++;
                    }
                    last = q;
                }
                if (changes > 1) twice++;
            }
            assertEquals(0, lighter, what + ": columns that turn from dark to grey going down");
            assertEquals(0, twice, what + ": columns that change more than once");
            assertTrue(changed < 0.4 * both, what + ": " + changed + " of " + both + " places change between W+45 and W+12");
            if (b != DeepBiome.CALDERA)   // the Caldera's walls are blackstone-led already
                assertTrue(dark12 - dark45 >= 0.25 * both, what + ": blackstone " + dark45 + " at W+45, " + dark12 + " at W+12, of " + both);
        }
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

    /**
     * The surface pass over one column of a box of blocks, as Minecraft's SurfaceSystem walks it top down: how deep each
     * block lies under the top of its run of rock (reset by air only, not by a fluid), how far over the run's bottom,
     * and whether a fluid rests on the run. Rows ylo..yhi; {@code out[y - ylo]} is {above, below, wet}, or null off rock.
     */
    private static int[][] surfacePass(NoiseVox v, int x, int z, int ylo, int yhi) {
        int[][] out = new int[yhi - ylo + 1][];
        int above = 0, runBottom = Integer.MAX_VALUE;
        boolean wet = false;
        for (int y = yhi; y >= ylo; y--) {
            int m = v.mat(x, y, z);
            if (m == NoiseVox.AIR) {
                above = 0;
                wet = false;
                continue;
            }
            if (m != NoiseVox.ROCK) {
                wet = true;
                continue;
            }
            if (runBottom >= y) {
                runBottom = ylo;
                for (int yy = y - 1; yy >= ylo; yy--) if (v.mat(x, yy, z) != NoiseVox.ROCK) { runBottom = yy + 1; break; }
            }
            above++;
            out[y - ylo] = new int[]{above, y - runBottom + 1, wet ? 1 : 0};
        }
        return out;
    }

    @Test
    void magmaOnlyOnRockTheLavaTouches() {
        // Crisp dark banks (picture 1), on the blocks as the game builds them from the real fields: in the Galleries
        // every magma block that shows to the air has lava on one of its faces (the bank's edge, not the dry column tops
        // level with the lava a few blocks back from it, which a player walks across), the Caldera's craters aside; magma
        // on about one in eight of the blocks the lava touches; none over W.
        Terrain t = terrain();
        int W = t.shape.waterY(Terrain.GALLERIES), ylo = W - 14, yhi = W + 14, n = 160;
        int[][] boxes = {{-2240, -2752}, {-1728, 1088}};   // seed 8675309: the Causeway's banks and a hall's river
        int[][] faces = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        int allTouched = 0, allMagma = 0;
        for (int[] box : boxes) {
            NoiseVox v = NoiseVox.build(t, box[0], box[1], n, n, ylo, yhi);
            int shown = 0, touched = 0, touchedMagma = 0, dryTops = 0;
            List<String> bad = new ArrayList<>();
            for (int x = box[0] + 1; x < box[0] + n - 1; x++) for (int z = box[1] + 1; z < box[1] + n - 1; z++) {
                int[][] col = surfacePass(v, x, z, ylo, yhi);
                for (int y = W - 6; y <= W + 6; y++) {
                    int[] c = col[y - ylo];
                    if (c == null) continue;
                    Rock q = t.palette.shell(x, y, z, c[0], c[1], c[2] != 0);
                    boolean air = false, lava = false;
                    for (int[] d : faces) {
                        int m = v.mat(x + d[0], y + d[1], z + d[2]);
                        air |= m == NoiseVox.AIR;
                        lava |= m == NoiseVox.LAVA;
                    }
                    if (y == W - 1 && c[0] == 1 && !lava) dryTops++;
                    if (q == Rock.MAGMA && t.looks.cone(x, z) >= Palette.CRATER) {
                        if (y > W || air && !lava) bad.add(x + " " + y + " " + z + (air ? " (shows)" : ""));
                        if (air) shown++;
                    }
                    if (lava) {
                        touched++;
                        if (q == Rock.MAGMA) touchedMagma++;
                    }
                }
            }
            String where = "box " + box[0] + " " + box[1];
            assertTrue(bad.isEmpty(), where + ": " + bad.size() + " magma blocks off the lava, e.g. " + bad.subList(0, Math.min(5, bad.size())));
            assertTrue(dryTops > 500, where + ": dry column tops level with the lava, to test: " + dryTops);
            assertTrue(shown >= 15, where + ": magma showing at the lava's edge: " + shown);
            // the heat's clumps make one box's share wander a little round the whole's
            assertTrue(touchedMagma >= 0.07 * touched && touchedMagma <= 0.17 * touched,
                    where + ": magma on " + touchedMagma + " of " + touched + " blocks the lava touches");
            allTouched += touched;
            allMagma += touchedMagma;
        }
        assertTrue(allMagma >= 0.10 * allTouched && allMagma <= 0.15 * allTouched, "magma on " + allMagma + " of " + allTouched + " blocks the lava touches");
    }

    @Test
    void theConesStoneStaysOnTheCones() {
        // The Caldera's cones (the field's cone floors, seed 8675309): cinders and the craters' throats on the cone's own
        // ground, but the walls, hanging masses, ledges and vault over a cone's foot are the hall's, never scoria,
        // scorchia or magma (round 1's palette dressed the whole height over a cone's foot as the cone)
        Terrain t = terrain();
        Palette p = t.palette;
        LookQueries q = t.looks;
        int W = t.shape.waterY(Terrain.GALLERIES);
        Random r = new Random(11);
        int cones = 0, flank = 0, cinders = 0, over = 0;
        for (int k = 0; k < 400000 && cones < 300; k++) {
            int x = r.nextInt(6000) - 3000, z = r.nextInt(6000) - 3000;
            double d = q.cone(x, z), h = q.coneTop(x, z);
            if (d >= 1 || h < W) continue;   // a cone's foot over a cone standing out of the lava
            cones++;
            int g = (int) Math.floor(h);
            if (d >= Palette.CRATER && d <= 0.8) {
                // the top block of a flank: cinders, but for its cooling cracks
                Rock a = p.shell(DeepBiome.CALDERA, Terrain.GALLERIES, x, g, z, 1, 12, false);
                flank++;
                if (a == Rock.SCORIA || a == Rock.SCORCHIA) cinders++;
            }
            for (int y = Math.max(W + 1, g + Palette.CONE_OVER + 1); y <= W + 130; y += 3) {
                for (int[] ab : new int[][]{{12, 12}, {12, 1}, {1, 12}}) {   // a wall, an underside, a ledge's top
                    Rock b = p.shell(DeepBiome.CALDERA, Terrain.GALLERIES, x, y, z, ab[0], ab[1], false);
                    over++;
                    assertTrue(b != Rock.SCORIA && b != Rock.SCORCHIA && b != Rock.MAGMA,
                            b + " over a cone's ground at " + x + " " + y + " " + z + " (ground W" + (g - W >= 0 ? "+" : "") + (g - W) + ", d " + d + ")");
                }
            }
        }
        assertTrue(cones >= 300, "cone feet found: " + cones);
        assertTrue(over > 20000, "places over the cones tested: " + over);
        assertTrue(cinders > 0.7 * flank, "cinders on " + cinders + " of " + flank + " flank tops");
    }

    @Test
    void aColumnIsOneStoneFromEndToEnd() {
        // Decision 3 and picture 1: every face of a basalt column, its top and the two blocks under it, its sides and its
        // underside, in a hanging cluster or not, is the column's stone, so it reads as one stone from end to end. Under
        // the tide's mark it may turn blackstone, once, going down, and never back: a top is never darker than the side
        // 4 to 6 blocks under it, an underside never lighter than the side over it. Over the tide's reach, no change.
        Terrain t = terrain();
        Stand looks = new Stand();
        looks.cone = 9.0;
        Palette p = new Palette(t, SEED, looks);
        int W = t.shape.waterY(Terrain.GALLERIES), high = W + Terrain.surge(Terrain.GALLERIES);
        Random r = new Random(11);
        for (DeepBiome b : new DeepBiome[]{DeepBiome.BASALT_GALLERIES, DeepBiome.CALDERA, DeepBiome.CAUSEWAY}) {
            for (double hang : new double[]{0, 0.6}) {
                looks.hang = hang;
                int runs = 0, lighter = 0, twice = 0, dryChanged = 0, darkTop = 0, tide = 0;
                for (int k = 0; k < 6000; k++) {
                    int x = r.nextInt(8000) - 4000, z = r.nextInt(8000) - 4000;
                    // runs over the table: under it the lava hides the rock (and covers every face of it)
                    int top = W + 20 + r.nextInt(80), bottom = top - 7 - r.nextInt(12);
                    int last = -1, changes = 0, topShade = -1, side = -1;
                    for (int y = top; y >= bottom; y--) {
                        int q = shade(p.shell(b, Terrain.GALLERIES, x, y, z, top - y + 1, y - bottom + 1, false));
                        if (q < 0) continue;
                        if (y == top) topShade = q;
                        if (y <= top - 4 && y >= top - 6 && side < 0) side = q;
                        if (last >= 0 && q != last) {
                            changes++;
                            if (q < last) lighter++;
                        }
                        last = q;
                    }
                    runs++;
                    if (changes > 1) twice++;
                    if (changes > 0 && bottom >= high) dryChanged++;
                    if (changes > 0) tide++;
                    if (topShade == 1 && side == 0) darkTop++;
                }
                String what = b + (hang > 0 ? " in a hanging cluster" : "");
                assertEquals(0, lighter, what + ": runs that turn lighter going down, of " + runs);
                assertEquals(0, twice, what + ": runs that change stone more than once, of " + runs);
                assertEquals(0, darkTop, what + ": dark tops over grey sides, of " + runs);
                assertEquals(0, dryChanged, what + ": runs over the tide's reach that change stone, of " + runs);
                if (b != DeepBiome.CALDERA) assertTrue(tide > runs / 20, what + ": the tide darkens some runs: " + tide + " of " + runs);
            }
        }
    }

    /** A block id that names a made thing rather than natural rock: cut, polished, layered or dressed stone, a chain. */
    private static final Pattern MADE = Pattern.compile(
            "polished|bricks?\\b|tiles?\\b|chiseled|cut_|_cut\\b|layered|smooth_stone|_slab|_stairs|_wall\\b|chain|lantern|planks|glass|casing|cogwheel|pipe");

    @Test
    void noMadeBlockInTheBottomLayers() throws IOException {
        // Taran, 2026-11-02: "Polished blackstone doesn't fit. It looks too unnatural." Every block the two bottom layers
        // can show: what the palette lays (as DeepMaterials turns rocks into blocks, Create's stones and their stand-ins
        // both), what their biomes' features place (block tags expanded), and what those features' code names.
        Set<String> found = new TreeSet<>();
        // 1. the palette, over both layers, with every look the noise fields can give
        Terrain t = terrain();
        Shape s = t.shape;
        Set<Rock> rocks = EnumSet.noneOf(Rock.class);
        Random r = new Random(17);
        for (Palette p : new Palette[]{new Palette(t, SEED, LookQueries.NONE), new Palette(t, SEED, new Stand()), t.palette}) {
            for (int k = 0; k < 200000; k++) {
                boolean gal = r.nextBoolean();
                int li = gal ? Terrain.GALLERIES : Terrain.WASTELAND;
                DeepBiome b = gal ? GALLERIES[r.nextInt(GALLERIES.length)] : r.nextBoolean() ? DeepBiome.WASTELAND : DeepBiome.ASH_HOLLOWS;
                int x = r.nextInt(4000) - 2000, z = r.nextInt(4000) - 2000, y = s.bottom(li) + r.nextInt(s.height(li));
                rocks.add(p.shell(b, li, x, y, z, 1 + r.nextInt(12), 1 + r.nextInt(12), r.nextInt(4) == 0));
                rocks.add(p.interior(li, x, y, z));
            }
        }
        Map<String, List<String>> blocks = rockBlocks();
        for (Rock q : rocks) {
            List<String> ids = blocks.get(q.name());
            assertTrue(ids != null && !ids.isEmpty(), "DeepMaterials has no block for " + q);
            found.addAll(ids);
        }
        // 2. the bottom biomes' features: every block id their JSON names, block tags expanded, and their code's blocks
        Path data = Path.of("src/main/resources/data/explorationremix");
        Map<String, String> featureClasses = featureClasses();
        for (String biome : new String[]{"wasteland", "ash_hollows", "basalt_galleries", "obsidian_tubes", "caldera", "causeway", "scoria_canyons"}) {
            JsonObject bj = json(data.resolve("worldgen/biome/" + biome + ".json"));
            for (JsonElement step : bj.getAsJsonArray("features")) for (JsonElement f : step.getAsJsonArray()) {
                String id = f.getAsString();
                assertTrue(id.startsWith("explorationremix:"), biome + " places a feature that is not the mod's: " + id);
                JsonObject placed = json(data.resolve("worldgen/placed_feature/" + id.substring(17) + ".json"));
                List<JsonElement> parts = new ArrayList<>(List.of(placed));
                JsonElement feature = placed.get("feature");
                if (feature.isJsonPrimitive()) parts.add(json(data.resolve("worldgen/configured_feature/" + feature.getAsString().substring(17) + ".json")));
                for (JsonElement part : parts) {
                    Set<String> named = new TreeSet<>();
                    names(part, named);
                    for (String n : named) {
                        if (n.startsWith("#explorationremix:")) found.addAll(tag(data, n.substring(18)));
                        else if (!n.startsWith("#")) found.add(n);
                    }
                    Set<String> types = new TreeSet<>();
                    types(part, types);
                    for (String type : types) {
                        if (!type.startsWith("explorationremix:")) continue;
                        String cls = featureClasses.get(type.substring(17));
                        if (cls != null) found.addAll(codeBlocks(cls));
                    }
                }
            }
        }
        List<String> made = new ArrayList<>();
        for (String b : found) if (MADE.matcher(b.substring(b.indexOf(':') + 1)).find()) made.add(b);
        assertTrue(made.isEmpty(), "made blocks in the two bottom layers: " + made + " (of " + found + ")");
        assertTrue(found.contains("minecraft:basalt") && found.contains("minecraft:magma_block"), "the scan found the layers' rock: " + found);
    }

    private static JsonObject json(Path p) throws IOException { return JsonParser.parseString(Files.readString(p)).getAsJsonObject(); }

    /**
     * Every block id or tag a feature's JSON may place or match: block states' "Name", and "block", "blocks" and "fluid"
     * values. Not "on" or "rock": those name the rock a feature grows on or pours out of, which it does not place.
     */
    private static void names(JsonElement e, Set<String> out) {
        if (e.isJsonObject()) {
            for (Map.Entry<String, JsonElement> en : e.getAsJsonObject().entrySet()) {
                JsonElement v = en.getValue();
                if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() && v.getAsString().contains(":")
                        && List.of("Name", "block", "blocks", "fluid").contains(en.getKey())) out.add(v.getAsString());
                if (v.isJsonArray() && List.of("blocks", "values").contains(en.getKey()))
                    for (JsonElement a : v.getAsJsonArray()) if (a.isJsonPrimitive() && a.getAsString().contains(":")) out.add(a.getAsString());
                names(v, out);
            }
        } else if (e.isJsonArray()) {
            for (JsonElement a : e.getAsJsonArray()) names(a, out);
        }
    }

    /** Every feature type a feature's JSON names (nested features too). */
    private static void types(JsonElement e, Set<String> out) {
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("type") && o.get("type").isJsonPrimitive()) out.add(o.get("type").getAsString());
            for (Map.Entry<String, JsonElement> en : o.entrySet()) types(en.getValue(), out);
        } else if (e.isJsonArray()) {
            for (JsonElement a : e.getAsJsonArray()) types(a, out);
        }
    }

    /** A mod block tag's blocks, nested mod tags expanded (vanilla tags are left out: none of them names a made block). */
    private static Set<String> tag(Path data, String name) throws IOException {
        Set<String> out = new TreeSet<>();
        for (JsonElement v : json(data.resolve("tags/blocks/" + name + ".json")).getAsJsonArray("values")) {
            String id = v.isJsonObject() ? v.getAsJsonObject().get("id").getAsString() : v.getAsString();
            if (id.startsWith("#explorationremix:")) out.addAll(tag(data, id.substring(18)));
            else if (!id.startsWith("#")) out.add(id);
        }
        return out;
    }

    /** Each rock's blocks as DeepMaterials.resolve maps it, read from its source: the block, or Create's and its stand-in. */
    private static Map<String, List<String>> rockBlocks() throws IOException {
        String src = Files.readString(Path.of("src/main/java/com/explorationremix/world/gen/DeepMaterials.java"));
        Map<String, List<String>> out = new HashMap<>();
        Matcher m = Pattern.compile("case ([A-Z_]+) -> ([^;]+);").matcher(src);
        while (m.find()) out.put(m.group(1), codeIds(m.group(2)));
        return out;
    }

    /** The blocks a piece of code names: Blocks.X and ERBlocks.X, create("x", ...) and "namespace:path" strings. */
    private static List<String> codeIds(String code) {
        List<String> ids = new ArrayList<>();
        Matcher b = Pattern.compile("\\b(ER)?Blocks\\.([A-Z_]+)").matcher(code);
        while (b.find()) ids.add((b.group(1) != null ? "explorationremix:" : "minecraft:") + b.group(2).toLowerCase());
        Matcher c = Pattern.compile("create\\(\"([a-z_]+)\"").matcher(code);
        while (c.find()) ids.add("create:" + c.group(1));
        Matcher q = Pattern.compile("\"([a-z0-9_]+:[a-z0-9_]+)\"").matcher(code);
        while (q.find()) ids.add(q.group(1));
        return ids;
    }

    /** The mod's feature types and their classes, from ERWorldgen's registrations. */
    private static Map<String, String> featureClasses() throws IOException {
        String src = Files.readString(Path.of("src/main/java/com/explorationremix/registry/ERWorldgen.java"));
        Map<String, String> out = new HashMap<>();
        Matcher m = Pattern.compile("FEATURES\\.register\\(\"([a-z_]+)\",\\s*\\(\\)\\s*->\\s*new\\s+([A-Za-z]+)\\(").matcher(src);
        while (m.find()) out.put(m.group(1), m.group(2));
        return out;
    }

    /** The blocks a feature's code names (ClimbVinesFeature's vines, TerracePoolsFeature's fluids and so on). */
    private static List<String> codeBlocks(String cls) throws IOException {
        Path p = Path.of("src/main/java/com/explorationremix/world/feature/" + cls + ".java");
        assertTrue(Files.exists(p), "the source of feature class " + cls);
        return codeIds(Files.readString(p));
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
        // over rows either side of the tide's mark: a block is in its column's stone, or blackstone where the tide turned it
        Terrain t = terrain();
        Palette p = new Palette(t, SEED, new Stand());
        int W = t.shape.waterY(Terrain.GALLERIES), same = 0, all = 0;
        Set<Rock> specks = EnumSet.of(Rock.GILDED_BLACKSTONE, Rock.DEEPSLATE_GOLD_ORE, Rock.EMBER_CRUST);
        for (int c = 0; c < 2000; c++) {
            int x0 = Math.floorDiv(c * 3 * 37 - 100000, 3) * 3, z0 = (c * 3 * 53) % 6000 - 3000;   // the corner of a 3x3 column
            Map<Rock, Integer> count = new EnumMap<>(Rock.class);
            for (int y = W + 20; y < W + 70; y++) for (int dx = 0; dx < 3; dx++) for (int dz = 0; dz < 3; dz++) {
                Rock q = p.shell(DeepBiome.BASALT_GALLERIES, Terrain.GALLERIES, x0 + dx, y, z0 + dz, 12, 12, false);
                if (!specks.contains(q)) count.merge(q, 1, Integer::sum);
            }
            int best = 0;
            for (Map.Entry<Rock, Integer> e : count.entrySet())
                best = Math.max(best, e.getValue() + (e.getKey() == Rock.BLACKSTONE ? 0 : count.getOrDefault(Rock.BLACKSTONE, 0)));
            same += best;
            all += count.values().stream().mapToInt(Integer::intValue).sum();
        }
        assertTrue(same > 0.95 * all, "blocks of a column in its column's stone or blackstone: " + same + " of " + all);
    }
}
