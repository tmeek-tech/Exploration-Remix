package com.explorationremix.world.feature;

import com.explorationremix.world.terrain.Terrain;
import com.explorationremix.world.terrain.TerrainSettings;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Galleries' lava fall sites ({@link FallSites}) as the shipped data sets them (tools/worldgen.py, gal_falls). */
class FallSitesTest {
    static final FallSites.Spec GAL = shipped();
    /** The shipped sites without their anchor: every candidate outside the Caldera counts (the packing alone). */
    static final FallSites.Spec ALL = new FallSites.Spec(GAL.salt(), GAL.grid(), GAL.spacing(), GAL.chance(), GAL.keep(), GAL.ringMin(), GAL.ringMax(), 0);
    static final Terrain T = new Terrain(TerrainSettings.defaults(), -64, 8675309L);

    /** The halls' falls' sites as tools/worldgen.py ships them (configured_feature/basalt_galleries/vault_falls.json). */
    static FallSites.Spec shipped() {
        try (Reader r = Files.newBufferedReader(Path.of("src/main/resources/data/explorationremix/worldgen/configured_feature/basalt_galleries/vault_falls.json"))) {
            JsonElement sites = JsonParser.parseReader(r).getAsJsonObject().getAsJsonObject("config").get("sites");
            return FallSites.Spec.CODEC.parse(JsonOps.INSTANCE, sites).result().orElseThrow();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The sites of the chunks [cx0, cx1] x [cz0, cz1], chunk by chunk in the given order. */
    private static List<int[]> sites(List<int[]> chunks) { return sites(GAL, chunks); }

    private static List<int[]> sites(FallSites.Spec spec, List<int[]> chunks) {
        List<int[]> out = new ArrayList<>();
        for (int[] c : chunks) FallSites.inChunk(T, spec, c[0] * 16, c[1] * 16, 0, 1, out);
        return out;
    }

    private static List<int[]> chunks(int cx0, int cz0, int cx1, int cz1) {
        List<int[]> l = new ArrayList<>();
        for (int cx = cx0; cx <= cx1; cx++) for (int cz = cz0; cz <= cz1; cz++) l.add(new int[]{cx, cz});
        return l;
    }

    /**
     * No two sites nearer than the spacing; without the anchor about as many as random sequential packing gives (0.55
     * of the plane), and with it no two nearer either.
     */
    @Test
    void sitesKeepTheirSpacingAndPackClose() {
        List<int[]> s = sites(ALL, chunks(-40, -40, 39, 39));   // 1280 x 1280 blocks
        assertTrue(least(s) >= GAL.spacing(), "two sites " + least(s) + " apart");
        // Every candidate outside the Caldera's middle counted, the packing covers 0.4 to 0.6 of the plane with disks of
        // the spacing's diameter (random sequential adsorption jams at 0.547).
        double cover = s.size() * Math.PI * GAL.spacing() * GAL.spacing() / 4 / (1280.0 * 1280.0);
        assertTrue(cover > 0.4 && cover < 0.6, "the sites cover " + cover + " of the plane (" + s.size() + " sites)");
        List<int[]> a = sites(chunks(-40, -40, 39, 39));
        assertTrue(least(a) >= GAL.spacing(), "two anchored sites " + least(a) + " apart");
    }

    /**
     * Fix round 2 after the 0.8.0 pack runs (the falls stood in rows down the halls' middles, colonnades in the Causeway):
     * outside the Caldera every site stands by a wall or under a hanging mass (GalleriesField.fallAnchor within the
     * anchor), every site is a kept candidate (the packing's spacing holds), and the anchor leaves out a good share of
     * the kept candidates in the halls' open middles: at most three in four of those in open columns hold a fall.
     */
    @Test
    void sitesStandByWallsAndMasses() {
        assertTrue(GAL.anchor() > 0, "the shipped sites have no anchor");
        int n = 0;
        for (int[] p : sites(chunks(-40, -40, 39, 39))) {
            if (T.galleries.calderaDn(p[0] + 0.5, p[1] + 0.5) <= 8) continue;
            assertTrue(T.galleries.fallAnchor(p[0], p[1], GAL.anchor()), "a site in a hall's open middle: " + p[0] + "," + p[1]);
            n++;
        }
        assertTrue(n >= 30, "too few anchored sites to tell: " + n);
        int open = 0, sited = 0;
        for (int i = -200; i < 200; i++) for (int j = -200; j < 200; j++) {
            boolean site = FallSites.site(T, GAL, i, j);
            if (!FallSites.kept(T, GAL, i, j)) {
                assertTrue(!site, "a site not kept: " + i + "," + j);
                continue;
            }
            int x = FallSites.x(T.seed, GAL, i, j), z = FallSites.z(T.seed, GAL, i, j);
            if (T.galleries.column(x, z).closed() || T.galleries.calderaDn(x + 0.5, z + 0.5) <= 8) continue;
            open++;
            if (site) sited++;
        }
        assertTrue(open > 300, "too few kept candidates in open columns to tell: " + open);
        assertTrue(sited <= 0.75 * open, "the anchor keeps " + sited + " of " + open + " kept candidates in open columns");
        assertTrue(sited >= 0.1 * open, "the anchor keeps only " + sited + " of " + open + " kept candidates in open columns");
    }

    private static double least(List<int[]> s) {
        Map<Long, List<int[]>> grid = new HashMap<>();
        for (int[] p : s) grid.computeIfAbsent(key(p[0] >> 6, p[1] >> 6), k -> new ArrayList<>()).add(p);
        double least = Double.MAX_VALUE;
        for (int[] p : s)
            for (int di = -1; di <= 1; di++) for (int dj = -1; dj <= 1; dj++)
                for (int[] q : grid.getOrDefault(key((p[0] >> 6) + di, (p[1] >> 6) + dj), List.of()))
                    if (q != p) least = Math.min(least, Math.hypot(p[0] - q[0], p[1] - q[1]));
        return least;
    }

    /**
     * A chunk finds the same sites whatever was asked before it, in any order (the cache holds no answer of its own): the
     * packing's (the unanchored set, enough sites to tell) and the shipped set's, anchored, over 32 x 32 chunks (24 x 24
     * held 48 of the unanchored sites once they stood 44 apart).
     */
    @Test
    void sitesAreTheSameInAnyOrder() {
        List<int[]> order = chunks(-16, -16, 15, 15);
        List<int[]> shuffled = new ArrayList<>(order);
        java.util.Collections.shuffle(shuffled, new Random(5));
        List<int[]> mixed = new ArrayList<>();
        for (int[] c : shuffled) {
            mixed.add(c);
            mixed.add(new int[]{c[0] + 700, c[1] - 900});   // far chunks between, to turn the cache over
        }
        for (FallSites.Spec spec : new FallSites.Spec[]{ALL, GAL}) {
            Set<String> a = set(sites(spec, order));
            Set<String> b = new HashSet<>();
            for (int[] p : sites(spec, mixed)) if (p[0] >= -256 && p[0] < 256 && p[1] >= -256 && p[1] < 256) b.add(p[0] + "," + p[1]);
            assertEquals(a, b);
            assertTrue(a.size() > (spec == ALL ? 50 : 10), "too few sites to tell: " + a.size());
        }
    }

    /**
     * Two falls of different sites never come within the keep of each other: a column clear for one site and a column
     * clear for another lie on either side of the hyperbola between them. Tried on columns round every pair of
     * neighbouring sites of the unanchored set (fix round 2: the sites stand 44 apart, so the columns tried reach out
     * to half the spacing and more round each site, where the hyperbola runs, not 8 as when they stood 28 apart).
     */
    @Test
    void columnsClearForTwoSitesKeepApart() {
        List<int[]> s = sites(ALL, chunks(-12, -12, 11, 11));
        Random r = new Random(11);
        int pairs = 0, tried = 0, reach = (int) Math.ceil(ALL.spacing() / 2) + 4;
        for (int[] a : s) for (int[] b : s) {
            if (a == b || Math.hypot(a[0] - b[0], a[1] - b[1]) > 1.5 * ALL.spacing()) continue;
            pairs++;
            List<int[]> ca = new ArrayList<>(), cb = new ArrayList<>();
            for (int k = 0; k < 400; k++) {
                int[] p = {a[0] + r.nextInt(2 * reach + 1) - reach, a[1] + r.nextInt(2 * reach + 1) - reach};
                int[] q = {b[0] + r.nextInt(2 * reach + 1) - reach, b[1] + r.nextInt(2 * reach + 1) - reach};
                if (FallSites.clear(T, ALL, a[0], a[1], p[0], p[1])) ca.add(p);
                if (FallSites.clear(T, ALL, b[0], b[1], q[0], q[1])) cb.add(q);
            }
            for (int[] p : ca) for (int[] q : cb) {
                tried++;
                assertTrue(Math.hypot(p[0] - q[0], p[1] - q[1]) >= ALL.keep() - 1e-9,
                        "columns " + p[0] + "," + p[1] + " (site " + a[0] + "," + a[1] + ") and " + q[0] + "," + q[1] + " (site " + b[0] + "," + b[1] + ")");
            }
        }
        assertTrue(pairs > 20 && tried > 10_000, "too few pairs to tell: " + pairs + " pairs, " + tried + " column pairs");
    }

    /** In the Caldera only its lake's rim holds sites (0.42 to 0.62 of the patch's radius from its middle). */
    @Test
    void theCalderaHoldsSitesOnItsRimOnly() {
        int rim = 0;
        for (int[] p : sites(chunks(-40, -40, 39, 39))) {
            double dn = T.galleries.calderaDn(p[0] + 0.5, p[1] + 0.5);
            if (dn > 8) continue;
            assertTrue(dn >= GAL.ringMin() && dn <= GAL.ringMax(), "a site in the Caldera off its rim: " + p[0] + "," + p[1] + " dn " + dn);
            rim++;
        }
        assertTrue(rim >= 3, "too few sites on a Caldera rim to tell: " + rim);
    }

    private static long key(int i, int j) { return (long) i << 32 | (j & 0xFFFFFFFFL); }

    private static Set<String> set(List<int[]> l) {
        Set<String> s = new HashSet<>();
        for (int[] p : l) s.add(p[0] + "," + p[1]);
        return s;
    }
}
