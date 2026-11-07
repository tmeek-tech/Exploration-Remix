package com.explorationremix.world.terrain;

import com.explorationremix.world.feature.GlowLight;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Galleries' lava decorations as the shipped data places them, on blocks made as the game makes them
 * ({@link GalleriesDecor} on {@link NoiseVox}). 0.8.0 fix round: the pack run (seed 8675309, chunks -27..20 x -23..26)
 * found a lava pool and a fall on the ramp of the way down at (-121.5, -19.9), the way at (21, 126) one way at its foot,
 * and 1557 lava falls in its 2400 chunks, side by side in curtains, 85 to 93 in a 128-block square.
 */
class GalleriesDecorTest {
    static final Path WORLDGEN = Path.of("src/main/resources/data/explorationremix/worldgen");
    static final long SEED = 8675309L;
    /** The least distance between two falls (FallSites keep 24: the falls' blocks keep to their sites' shares). */
    static final double FALL_KEEP = 24;
    /**
     * The least floor lava ({@link Box#floorLavaHeld}) the three ways' box and the pack run's region hold, to tell (fix
     * round 2: 1678 and 7250 blocks, every one held; fix round 3, each open to the air above as well: 1678 and 7251).
     */
    static final int FLOOR_LEAST_BOX = 1_200, FLOOR_LEAST_REGION = 5_000;

    /**
     * The three ways down round (4, 125), (21, 126) and (19, 151), with their whole reach, decorated: no lava on a ramp's
     * deck band or in a hole's throat, every way walking down to its foot and back up through the decorations, the halls'
     * falls few, spaced and 1 to 3 sources wide, and the halls' faces as bright as with the old falls. On the 0.8.0 data
     * the same box held 634 falls (the nearest other fall 5.8 blocks off at the median, 84 in one 128-block square), lava
     * on all three ramps (235 blocks), the way at (21, 126) one way at its foot, and faces at 10.33 on average, 7.7 %
     * under 8; the 0.8.0 fix round's data 50 falls, the nearest 27.7 blocks off at the least, and 10.6, 4.6 %. Fix round
     * 2: 14 falls (7 one source wide, 7 two), the nearest 43.7 apart, at most 4 in a view; the faces at 12.5 on
     * average, 13.1 under 60 rows over the table and 12.3 higher up, none under 8. Fix round 3: 10 falls (9 two sources
     * wide, 1 three; 2 pour from a hanging mass), the nearest 37.3 apart, at most 3 in a view; the faces as in round 2.
     */
    @Test
    void theFallsAreFewSpacedAndWideAndKeepOffTheWaysDown() throws Exception {
        Box b = new Box(-160, -55, 364, 386);
        List<String> bad = new ArrayList<>();
        assertTrue(b.ops.size() >= 3, "the box lost its ways down: " + b.ops.size());
        List<String> where = new ArrayList<>();
        int[] lw = b.d.lavaOnWays(b.ops, where);
        assertEquals(0, lw[0], "lava on " + lw[0] + " of " + b.ops.size() + " ramps (" + lw[1] + " blocks): " + where);
        b.v.graph();
        for (GalleriesField.Opening o : b.ops) {
            String foot = JoinTest.footWalks(b.v, b.t.galleries, o, b.t.join.gTop, b.t.join.wBot);
            if (foot != null) bad.add(foot);
        }
        assertTrue(bad.isEmpty(), "ways down that do not walk down and back up through the decorations: " + bad);
        b.fewSpacedAndWide(40, 200, 4, 5);
        b.asBright(10.33, 0.077);
        b.litFromTheFloorUp(12.2, 0.005);
        b.floorLavaHeld(FLOOR_LEAST_BOX);
    }

    /**
     * Fix round 2 after the 0.8.0 pack runs: the Causeway's falls, spaced evenly through its middle, stood in a colonnade
     * of tall poles (the land run: 18 falls in the 190-block square round the pictures' Causeway, 17 of them 80 blocks
     * tall or more; the data before this round: 22 here). Now its falls pour by its walls and masses (FallSites'
     * anchor), their sites 44 apart: at most 6 in the square (4 then, 3 after fix round 3), and of 400 views from random
     * standing places in a box round it, 95 in 100 show at most 4 falls and none more than 5 (3 and 5 then, 2 and 4 after
     * fix round 3).
     */
    @Test
    void theCausewaysFallsAreFew() throws Exception {
        Box b = new Box(-300, 120, 500, 360);
        int n = 0;
        for (int[] f : b.d.falls) if (f[1] >= -230 && f[1] < -40 && f[3] >= 240 && f[3] < 431) n++;
        System.out.println("GalleriesDecorTest Causeway square: " + n + " falls");
        assertTrue(n <= 6, "too many falls in the Causeway's square: " + n);
        assertTrue(n >= 1, "no falls in the Causeway's square");
        b.fewSpacedAndWide(40, 200, 4, 5);
    }

    /**
     * The Caldera's falls stand on its lake's rim only (FallSites ring 0.42 to 0.62 of the patch's radius; a fall's
     * blocks lie within a few of its site), and no fall pours inside the rim, over the lake. The box is the Caldera at
     * the pack region's south-west corner, whose 0.8.0 tries (12 a chunk, and the halls' vault and cliff falls besides)
     * poured all over the crater. (A hall fall may stand at the Caldera's outer edge, where the biomes meet.) Fix round
     * 3: the rim's sites stand 72 apart (40 before), and the box reaches over the whole rim, 48 rows further south than
     * the 80 it took, so that it holds the rim's 3 falls (2 of them in the old box; 4 of the 40 apart there).
     */
    @Test
    void theCalderasFallsRingItsLake() throws Exception {
        Box b = new Box(-432, 336, 160, 128);
        double[] s = b.d.feats[GalleriesDecor.CALDERA].fallSites;
        int n = 0;
        List<String> bad = new ArrayList<>();
        for (int[] f : b.d.falls) {
            if (f[0] == GalleriesDecor.CALDERA) n++;
            for (int i = 1; i < f.length; i += 3) {
                double dn = b.t.galleries.calderaDn(f[i] + 0.5, f[i + 2] + 0.5);
                boolean off = dn < s[5] - 0.08 || (f[0] == GalleriesDecor.CALDERA && dn > s[6] + 0.08);
                if (off) bad.add(String.format(Locale.ROOT, "%s off the rim at %d %d %d: dn %.3f", f[0] == GalleriesDecor.CALDERA ? "a Caldera fall" : "a hall fall",
                        f[i], f[i + 1], f[i + 2], dn));
            }
        }
        assertTrue(n >= 3, "too few falls on the Caldera's rim to tell: " + n);
        assertTrue(bad.isEmpty(), bad.toString());
    }

    /**
     * Fix round 3 (judge3: the views along the Caldera's rim showed five and six falls at once, 39 vista_new1 six, 40
     * vista_new2 five): the Caldera's own sites stand 72 apart, so of 400 views from random standing places in the
     * Caldera, on a box round both of the pack region's Calderas and the halls round them, none shows more than 3 of
     * the Caldera's own falls, 99 in 100 at most 4 falls of any kind and none more than 5. Measured: here at most 2 of
     * its own and 4 in all (the 5th most 4). Over the whole decorated box round the pack region (1.06 km2, the probe
     * FallLook's 400 views from the Calderas) at most 2 of its own; of all falls 5 in 1 view, which looks east across
     * the second Caldera's lake from its west rim at one rim fall and four of the halls' 110 to 182 blocks off, and 4 in
     * the next 6; with the rim's sites 56 apart 6 in 1 view and 5 in 5, 64 apart 6 in 1 and 5 in 4.
     */
    @Test
    void theCalderasViewsShowFewFalls() throws Exception {
        Box b = new Box(-640, 150, 640, 640);
        int[] own = b.d.fallsPerView(400, 29, java.util.EnumSet.of(DeepBiome.CALDERA), GalleriesDecor.CALDERA);
        int[] all = b.d.fallsPerCalderaView(400, 29);
        String what = String.format(Locale.ROOT, "%d views: its own falls most %d; all falls most %d, the 5th most %d; %s", all.length, own.length > 0 ? own[0] : -1,
                all.length > 0 ? all[0] : -1, all.length > 4 ? all[4] : -1, b.d);
        System.out.println("GalleriesDecorTest Caldera views: " + what);
        assertTrue(all.length >= 300 && own.length == all.length, "too few views from the Caldera to tell: " + what);
        assertTrue(own[0] <= 3, "more than 3 of the Caldera's own falls in a view: " + what);
        assertTrue(all[all.length / 100] <= 4, "more than 4 falls in 1 view of 100 from the Caldera: " + what);
        assertTrue(all[0] <= 5, "more than 5 falls in a view from the Caldera: " + what);
    }

    /**
     * The pack run's whole region, decorated: about a tenth of the falls the 0.8.0 tries placed (2925 falls there, 2606
     * as fallcount.py counts them, the pack 1557; 133 in one 128-block square), every way down clear of lava and walking
     * both ways (the 0.8.0 data: lava on all 20 ramps, the way at (21, 126) one way), and the halls' faces as bright as
     * before (the 0.8.0 data: 10.30 on average, 7.6 % under 8). Fix round 2: 53 falls (86 a km2; 30 one source wide, 20
     * two, 3 three), at most 3 in a 128-block square, the nearest 43.3 apart, at most 5 in a view of 400 and 3 in 95 of
     * 100 (sites 40 apart left 66 falls and 6 in a view); faces at 12.45 on average, none under 8. Fix round 3: 40 falls
     * (65 a km2; 32 two sources wide, 6 three, 2 four), 12 of them from a hanging mass's underside, at most 4 in a
     * 128-block square, the nearest 37.3 apart, at most 5 in a view of 400 and 2 in 95 of 100; faces as before.
     */
    @Test
    @Tag("slow")
    void thePackRegionsFallsAndWays() throws Exception {
        Box b = new Box(-432, -368, 768, 800);
        assertTrue(b.ops.size() >= 15, "the region lost its ways down: " + b.ops.size());
        List<String> where = new ArrayList<>();
        int[] lw = b.d.lavaOnWays(b.ops, where);
        assertEquals(0, lw[0], "lava on " + lw[0] + " of " + b.ops.size() + " ramps (" + lw[1] + " blocks): " + where);
        b.v.graph();
        List<String> bad = new ArrayList<>();
        for (GalleriesField.Opening o : b.ops) {
            String foot = JoinTest.footWalks(b.v, b.t.galleries, o, b.t.join.gTop, b.t.join.wBot);
            if (foot != null) bad.add(foot);
        }
        assertTrue(bad.isEmpty(), "ways down that do not walk down and back up through the decorations: " + bad);
        b.fewSpacedAndWide(40, 200, 5, 5);
        // fix round 3 (judge3: none of the falls poured from a hanging mass): about a third pour from a mass's underside
        int mass = b.d.fromMasses(), n = b.d.falls.size();
        assertTrue(mass >= 0.25 * n, "too few falls pour from a hanging mass: " + mass + " of " + n);
        b.asBright(10.30, 0.076);
        b.floorLavaHeld(FLOOR_LEAST_REGION);
    }

    /**
     * Fix round 3 (review3): a lava fall that poured into a neighbour chunk before that chunk was decorated stood on a slab
     * that the neighbour's own Terrain Slabs pass then laid in its sump and filled with water (8 of the pack region's 50
     * falls, 7 of them in a chunk's border column). Every block a fall sets now lies in the chunk being decorated, which
     * has had its slab pass, and so the falls are the same in whatever order the chunks are decorated: on the pack
     * region's north-west, decorated row by row and in a shuffled order, no fall sets a block outside its chunk (the
     * whole region before: 886 of its falls' 4624 blocks) and the falls are the same, source for source.
     */
    @Test
    void theFallsKeepToTheirChunksInAnyOrder() {
        Box rows = new Box(-432, -368, 512, 512, null), shuffled = new Box(-432, -368, 512, 512, 7L);
        List<String> a = rows.fallList(), b = shuffled.fallList();
        System.out.println("GalleriesDecorTest falls in any order: " + a.size() + " falls; " + rows.d + "; shuffled: " + shuffled.d);
        assertTrue(a.size() >= 15, "too few falls to tell: " + a);
        for (Box x : new Box[]{rows, shuffled})
            assertEquals(0, x.d.outside[GalleriesDecor.VAULT] + x.d.outside[GalleriesDecor.CLIFF] + x.d.outside[GalleriesDecor.CALDERA],
                    "fall blocks outside the chunk being decorated: " + x.d);
        assertEquals(a, b, "the falls change with the chunks' order");
    }

    /** A box of the Galleries, decorated, and the ways down whose whole reach lies in it. */
    private static final class Box {
        final Terrain t = new Terrain(TerrainSettings.defaults(), -64, SEED);
        final int x0, z0, nx, nz;
        final NoiseVox v;
        final GalleriesDecor d;
        final List<GalleriesField.Opening> ops = new ArrayList<>();

        Box(int x0, int z0, int nx, int nz) { this(x0, z0, nx, nz, null); }

        /** Decorated chunk by chunk in rows, or in the order a random drawn from {@code order} shuffles them into. */
        Box(int x0, int z0, int nx, int nz, Long order) {
            this.x0 = x0;
            this.z0 = z0;
            this.nx = nx;
            this.nz = nz;
            v = NoiseVox.build(t, x0, z0, nx, nz, t.shape.bottom(Terrain.GALLERIES), t.join.wBot + 40);
            GalleriesDecor g = new GalleriesDecor(t, v, WORLDGEN);
            d = order == null ? g.run(SEED, x0, z0, x0 + nx, z0 + nz) : g.run(SEED, x0, z0, x0 + nx, z0 + nz, order);
            for (GalleriesField.Opening o : JoinTest.openingsIn(t.galleries, x0, z0, x0 + nx, z0 + nz)) {
                double m = o.reach + 10;
                if (o.ramp == null || o.x - m < x0 || o.x + m > x0 + nx || o.z - m < z0 || o.z + m > z0 + nz) continue;
                ops.add(o);
            }
        }

        /** The falls placed, each its kind and its sources, sorted. */
        List<String> fallList() {
            List<String> out = new ArrayList<>();
            for (int[] f : d.falls) out.add(Arrays.toString(f));
            java.util.Collections.sort(out);
            return out;
        }

        /**
         * Falls per km2 within [lo, hi], 2 to 5 sources wide, three in four or more of them 2 or 3 and at most a fifth
         * wider (fix round 3, judge3: 37 of the pack region's 50 falls were one source wide, every fall a thin straight
         * line; so a fall is one curtain of two sources or more, never a lone thread, of the 3 to 5 it tries; fix round 2
         * had 1 to 3, a quarter or more of them 2 or 3; before it two or more each and up to 5), no two nearer than
         * {@link #FALL_KEEP}, at most {@code tile} in a 128-block square, and few in a view: of 400 views from random
         * standing places in the halls ({@link GalleriesDecor#fallsPerView}), 95 in 100 show at most 4 falls and none
         * more than {@code view}.
         */
        void fewSpacedAndWide(double lo, double hi, int tile, int view) {
            int n = d.falls.size();
            double km2 = d.area / 1e6, nn = Arrays.stream(d.nearestFalls()).min().orElse(Double.MAX_VALUE);
            Map<Long, Integer> tiles = new HashMap<>();
            int[] widths = new int[8];
            int most = 0;
            for (int[] f : d.falls) {
                widths[Math.min(7, (f.length - 1) / 3)]++;
                most = Math.max(most, tiles.merge((long) Math.floorDiv(f[1], 128) << 32 | (Math.floorDiv(f[3], 128) & 0xFFFFFFFFL), 1, Integer::sum));
            }
            int[] views = d.fallsPerView(400, 23);
            String what = String.format(Locale.ROOT, "%d falls in %.3f km2 (%.0f a km2), widths 1..5 %d %d %d %d %d, %d from a hanging mass, the nearest two %.1f apart,"
                            + " %d in one 128-block square; %d views: most seen %d, the 20th most %d; %s",
                    n, km2, n / km2, widths[1], widths[2], widths[3], widths[4], widths[5], d.fromMasses(), nn, most, views.length, views.length > 0 ? views[0] : -1,
                    views.length > 19 ? views[19] : -1, d);
            System.out.println("GalleriesDecorTest: " + what);
            assertTrue(n / km2 >= lo && n / km2 <= hi, "falls per km2 out of [" + lo + ", " + hi + "]: " + what);
            assertEquals(0, widths[0] + widths[1] + widths[6] + widths[7], "falls not 2 to 5 sources wide: " + what);
            assertTrue(widths[2] + widths[3] >= 0.75 * n, "too few falls 2 or 3 wide: " + what);
            assertTrue(widths[4] + widths[5] <= 0.2 * n, "too many falls over 3 wide: " + what);
            assertTrue(nn >= FALL_KEEP, "two falls nearer than " + FALL_KEEP + ": " + what);
            assertTrue(most <= tile, "too many falls in one 128-block square: " + what);
            assertTrue(views.length >= 300, "too few views to tell: " + what);
            assertTrue(views[views.length / 20] <= 4, "more than 4 falls in 1 view of 20: " + what);
            assertTrue(views[0] <= view, "more than " + view + " falls in a view: " + what);
            assertEquals(0, d.outside[GalleriesDecor.VAULT] + d.outside[GalleriesDecor.CLIFF] + d.outside[GalleriesDecor.CALDERA],
                    "fall blocks outside the chunk being decorated: " + what);
        }

        /**
         * Fix round 2 after the 0.8.0 pack runs (the halls read dim, rock 36.6 on average at the pack's cameras against 44.0 in
         * the first run): the halls' faces (GlowLight: every lava block and glow spot) at least
         * {@code mean} on average and at most {@code under8} of them under 8, and still lit from the floor up: the faces
         * under 60 rows over the table brighter on average than those higher up by half a level or more. With the glow
         * before this round the box measured 10.6 on average (GalleriesDecorTest light).
         */
        void litFromTheFloorUp(double mean, double under8) throws Exception {
            GlowLight gl = GlowLight.measure(t, GlowLight.shipped(WORLDGEN), (x, y, z) -> v.mat(x, y, z), x0, z0, nx, nz, 16);
            long[] all = new long[16], low = new long[16];
            for (int band = 0; band < 3; band++) for (int l = 0; l < 16; l++) {
                all[l] += gl.hall[band][l];
                if (band < 2) low[l] += gl.hall[band][l];
            }
            long faces = Arrays.stream(all).sum();
            System.out.println("GalleriesDecorTest lit from the floor up: " + gl);
            assertTrue(GlowLight.mean(all) >= mean, "the halls darker than " + mean + " on average: " + gl);
            assertTrue(GlowLight.under(all, 8) <= under8 * faces, "more hall faces under 8 than " + under8 + ": " + gl);
            assertTrue(GlowLight.mean(low) >= GlowLight.mean(gl.hall[2]) + 0.5, "not lit from the floor up: " + gl);
        }

        /**
         * The floor's lava (fix round 2, FloorLavaFeature: GalleriesField.floorLavaRow) as it stands once every chunk is
         * decorated: at least {@code least} blocks of it, and every one held, with rock under it and rock or a lava source
         * on its four sides, whatever the decorations laid round it before or after it ({@link GalleriesDecor#floorLavaHeld}),
         * so none flows when a tick comes; and every one open to the air above it (fix round 3: in the pack run 2200 of
         * its 7491 blocks lay under a block, one a basalt block left resting on the lava alone), so nothing rests on it.
         * (On the ways down none lies: {@link GalleriesDecor#lavaOnWays} counts it.)
         */
        void floorLavaHeld(int least) {
            List<String> loose = new ArrayList<>();
            int[] held = d.floorLavaHeld(loose);
            System.out.println(String.format(Locale.ROOT, "GalleriesDecorTest floor lava: %d blocks (%.0f a km2), %d could flow, %d under a block %s", held[0],
                    held[0] / (d.area / 1e6), held[1], held[2], loose));
            assertTrue(held[0] >= least, "too little floor lava to tell: " + held[0]);
            assertEquals(0, held[1], "floor lava that could flow: " + loose);
            assertEquals(0, held[2], "floor lava under a block: " + loose);
        }

        /** The halls' faces (GlowLight: every lava block and glow spot) at least {@code mean} on average, at most {@code under8} of them under 8. */
        void asBright(double mean, double under8) throws Exception {
            GlowLight gl = GlowLight.measure(t, GlowLight.shipped(WORLDGEN), (x, y, z) -> v.mat(x, y, z), x0, z0, nx, nz, 16);
            long[] all = new long[16];
            for (long[] band : gl.hall) for (int l = 0; l < 16; l++) all[l] += band[l];
            long faces = Arrays.stream(all).sum();
            System.out.println("GalleriesDecorTest light: " + gl);
            assertTrue(faces > 100_000, "too few hall faces: " + gl);
            assertTrue(GlowLight.mean(all) >= mean, "the halls darker than with the 0.8.0 falls (" + mean + "): " + gl);
            assertTrue(GlowLight.under(all, 8) <= under8 * faces, "more hall faces under 8 than with the 0.8.0 falls (" + under8 + "): " + gl);
        }
    }
}
