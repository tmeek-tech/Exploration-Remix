package com.explorationremix.world.feature;

import com.explorationremix.world.terrain.Terrain;
import com.explorationremix.world.terrain.TerrainSettings;
import org.junit.jupiter.api.Test;

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
    static final FallSites.Spec GAL = new FallSites.Spec(1, 8, 28, 1, 24, 0.42, 0.62);
    static final Terrain T = new Terrain(TerrainSettings.defaults(), -64, 8675309L);

    /** The sites of the chunks [cx0, cx1] x [cz0, cz1], chunk by chunk in the given order. */
    private static List<int[]> sites(List<int[]> chunks) {
        List<int[]> out = new ArrayList<>();
        for (int[] c : chunks) FallSites.inChunk(T, GAL, c[0] * 16, c[1] * 16, 0, 1, out);
        return out;
    }

    private static List<int[]> chunks(int cx0, int cz0, int cx1, int cz1) {
        List<int[]> l = new ArrayList<>();
        for (int cx = cx0; cx <= cx1; cx++) for (int cz = cz0; cz <= cz1; cz++) l.add(new int[]{cx, cz});
        return l;
    }

    /** No two sites nearer than the spacing; about as many as random sequential packing gives (0.55 of the plane). */
    @Test
    void sitesKeepTheirSpacingAndPackClose() {
        List<int[]> s = sites(chunks(-40, -40, 39, 39));   // 1280 x 1280 blocks
        Map<Long, List<int[]>> grid = new HashMap<>();
        for (int[] p : s) grid.computeIfAbsent(key(p[0] >> 5, p[1] >> 5), k -> new ArrayList<>()).add(p);
        double least = Double.MAX_VALUE;
        for (int[] p : s)
            for (int di = -1; di <= 1; di++) for (int dj = -1; dj <= 1; dj++)
                for (int[] q : grid.getOrDefault(key((p[0] >> 5) + di, (p[1] >> 5) + dj), List.of()))
                    if (q != p) least = Math.min(least, Math.hypot(p[0] - q[0], p[1] - q[1]));
        assertTrue(least >= GAL.spacing(), "two sites " + least + " apart");
        // Outside the Caldera's middle every candidate counts: the packing covers 0.4 to 0.6 of the plane with disks of
        // the spacing's diameter (random sequential adsorption jams at 0.547).
        double cover = s.size() * Math.PI * GAL.spacing() * GAL.spacing() / 4 / (1280.0 * 1280.0);
        assertTrue(cover > 0.4 && cover < 0.6, "the sites cover " + cover + " of the plane (" + s.size() + " sites)");
    }

    /** A chunk finds the same sites whatever was asked before it, in any order (the cache holds no answer of its own). */
    @Test
    void sitesAreTheSameInAnyOrder() {
        List<int[]> order = chunks(-12, -12, 11, 11);
        Set<String> a = set(sites(order));
        List<int[]> shuffled = new ArrayList<>(order);
        java.util.Collections.shuffle(shuffled, new Random(5));
        List<int[]> mixed = new ArrayList<>();
        for (int[] c : shuffled) {
            mixed.add(c);
            mixed.add(new int[]{c[0] + 700, c[1] - 900});   // far chunks between, to turn the cache over
        }
        Set<String> b = new HashSet<>();
        for (int[] p : sites(mixed)) if (p[0] >= -192 && p[0] < 192 && p[1] >= -192 && p[1] < 192) b.add(p[0] + "," + p[1]);
        assertEquals(a, b);
        assertTrue(a.size() > 50, "too few sites to tell: " + a.size());
    }

    /**
     * Two falls of different sites never come within the keep of each other: a column clear for one site and a column
     * clear for another lie on either side of the hyperbola between them. Tried on columns round every pair of
     * neighbouring sites.
     */
    @Test
    void columnsClearForTwoSitesKeepApart() {
        List<int[]> s = sites(chunks(-8, -8, 7, 7));
        Random r = new Random(11);
        int pairs = 0, tried = 0;
        for (int[] a : s) for (int[] b : s) {
            if (a == b || Math.hypot(a[0] - b[0], a[1] - b[1]) > 45) continue;
            pairs++;
            List<int[]> ca = new ArrayList<>(), cb = new ArrayList<>();
            for (int k = 0; k < 400; k++) {
                int[] p = {a[0] + r.nextInt(17) - 8, a[1] + r.nextInt(17) - 8}, q = {b[0] + r.nextInt(17) - 8, b[1] + r.nextInt(17) - 8};
                if (FallSites.clear(T, GAL, a[0], a[1], p[0], p[1])) ca.add(p);
                if (FallSites.clear(T, GAL, b[0], b[1], q[0], q[1])) cb.add(q);
            }
            for (int[] p : ca) for (int[] q : cb) {
                tried++;
                assertTrue(Math.hypot(p[0] - q[0], p[1] - q[1]) >= GAL.keep() - 1e-9,
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
