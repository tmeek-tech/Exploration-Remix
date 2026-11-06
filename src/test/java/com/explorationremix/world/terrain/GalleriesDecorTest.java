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
     * The three ways down round (4, 125), (21, 126) and (19, 151), with their whole reach, decorated: no lava on a ramp's
     * deck band or in a hole's throat, every way walking down to its foot and back up through the decorations, the halls'
     * falls few, spaced and two or more sources wide, and the halls' faces as bright as with the old falls. On the 0.8.0
     * data the same box held 634 falls (the nearest other fall 5.8 blocks off at the median, 84 in one 128-block square),
     * lava on all three ramps (235 blocks), the way at (21, 126) one way at its foot, and faces at 10.33 on average, 7.7 %
     * under 8; now 50 falls, the nearest 27.7 blocks off at the least, and 10.6, 4.6 %.
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
        b.fewSpacedAndWide(150, 600, 8);
        b.asBright(10.33, 0.077);
    }

    /**
     * The Caldera's falls stand on its lake's rim only (FallSites ring 0.42 to 0.62 of the patch's radius; a fall's
     * blocks lie within a few of its site), and no fall pours inside the rim, over the lake. The box is the Caldera at
     * the pack region's south-west corner, whose 0.8.0 tries (12 a chunk, and the halls' vault and cliff falls besides)
     * poured all over the crater. (A hall fall may stand at the Caldera's outer edge, where the biomes meet.)
     */
    @Test
    void theCalderasFallsRingItsLake() throws Exception {
        Box b = new Box(-432, 352, 160, 80);
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
     * The pack run's whole region, decorated: about a tenth of the falls the 0.8.0 tries placed (2925 falls there, 2606
     * as fallcount.py counts them, the pack 1557; 133 in one 128-block square), every way down clear of lava and walking
     * both ways (the 0.8.0 data: lava on all 20 ramps, the way at (21, 126) one way), and the halls' faces as bright as
     * before (the 0.8.0 data: 10.30 on average, 7.6 % under 8).
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
        b.fewSpacedAndWide(150, 600, 16);
        b.asBright(10.30, 0.076);
    }

    /** A box of the Galleries, decorated, and the ways down whose whole reach lies in it. */
    private static final class Box {
        final Terrain t = new Terrain(TerrainSettings.defaults(), -64, SEED);
        final int x0, z0, nx, nz;
        final NoiseVox v;
        final GalleriesDecor d;
        final List<GalleriesField.Opening> ops = new ArrayList<>();

        Box(int x0, int z0, int nx, int nz) {
            this.x0 = x0;
            this.z0 = z0;
            this.nx = nx;
            this.nz = nz;
            v = NoiseVox.build(t, x0, z0, nx, nz, t.shape.bottom(Terrain.GALLERIES), t.join.wBot + 40);
            d = new GalleriesDecor(t, v, WORLDGEN).run(SEED, x0, z0, x0 + nx, z0 + nz);
            for (GalleriesField.Opening o : JoinTest.openingsIn(t.galleries, x0, z0, x0 + nx, z0 + nz)) {
                double m = o.reach + 10;
                if (o.ramp == null || o.x - m < x0 || o.x + m > x0 + nx || o.z - m < z0 || o.z + m > z0 + nz) continue;
                ops.add(o);
            }
        }

        /**
         * Falls per km2 within [lo, hi], two or more sources each, no two nearer than {@link #FALL_KEEP}, and at most
         * {@code tile} in a 128-block square.
         */
        void fewSpacedAndWide(double lo, double hi, int tile) {
            int n = d.falls.size();
            double km2 = d.area / 1e6, nn = Arrays.stream(d.nearestFalls()).min().orElse(Double.MAX_VALUE);
            Map<Long, Integer> tiles = new HashMap<>();
            int thin = 0, most = 0;
            for (int[] f : d.falls) {
                if (f.length < 7) thin++;
                most = Math.max(most, tiles.merge((long) Math.floorDiv(f[1], 128) << 32 | (Math.floorDiv(f[3], 128) & 0xFFFFFFFFL), 1, Integer::sum));
            }
            String what = String.format(Locale.ROOT, "%d falls in %.3f km2 (%.0f a km2), %d one source wide, the nearest two %.1f apart, %d in one 128-block square; %s",
                    n, km2, n / km2, thin, nn, most, d);
            System.out.println("GalleriesDecorTest: " + what);
            assertTrue(n / km2 >= lo && n / km2 <= hi, "falls per km2 out of [" + lo + ", " + hi + "]: " + what);
            assertEquals(0, thin, "lone threads: " + what);
            assertTrue(nn >= FALL_KEEP, "two falls nearer than " + FALL_KEEP + ": " + what);
            assertTrue(most <= tile, "too many falls in one 128-block square: " + what);
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
