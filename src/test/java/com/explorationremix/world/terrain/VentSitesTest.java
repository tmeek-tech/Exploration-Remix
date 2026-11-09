package com.explorationremix.world.terrain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The eruptions' cheap vent query (0.9.0, spec 5.1): {@link Terrain#ventSitesNear} lists every vent
 * {@link Terrain#ventsNear} lists, with the same centre, crater radius and chamber, and its stack's foot radius, height and
 * crater depth as the cone lattice draws them; the only vents it adds are stacks whose crater cell is closed (which
 * ventsNear leaves out). It is a query only: asking it leaves the terrain as it was (the digest of
 * {@link GalleriesFieldTest#digestPinned} on a terrain that answered it first).
 */
class VentSitesTest {
    /** How far round each box's middle the vents are compared, in blocks. */
    static final double R = 192;

    @Test
    void ventSitesHoldEveryVentAndOnlyClosedStacksMore() {
        // seed 8675309 at its Caldera patch (-2, 1), where the pack's eruption checks stand (the vent at -347.1, 372.7),
        // and the first Caldera found on a lattice round the origin for seeds 1 and 31337
        int compared = 0;
        for (long seed : new long[]{8675309, 1, 31337}) {
            Terrain t = new Terrain(TerrainSettings.defaults(), -64, seed);
            int[] at = seed == 8675309 ? new int[]{-375, 375} : firstCaldera(t);
            assertNotNull(at, "no Caldera vent within 3000 blocks of the origin for seed " + seed);
            long t0 = System.nanoTime();
            List<Net.Vent> sites = t.ventSitesNear(at[0], at[1], R);
            double ms = (System.nanoTime() - t0) / 1e6;
            List<Net.Vent> vents = t.ventsNear(at[0], at[1], R);
            assertFalse(vents.isEmpty(), "seed " + seed + " has no vent round " + at[0] + " " + at[1]);
            List<Net.Vent> left = new ArrayList<>(sites);
            for (Net.Vent v : vents) {
                Net.Vent s = null;
                for (Net.Vent c : left) if (c.x == v.x && c.z == v.z) s = c;
                assertNotNull(s, "seed " + seed + ": ventSitesNear misses the vent at " + v.x + " " + v.z);
                left.remove(s);
                assertEquals(v.r, s.r, "crater radius");
                assertEquals(v.chamber, s.chamber, "chamber");
                assertTrue(s.crest && Double.isNaN(s.y), "a site's y is read from the world");
                assertTrue(s.stackR >= 5 && s.stackR <= 9 && s.height >= 47 && s.height <= 62 && s.depth >= 3 && s.depth <= 6,
                        "stack " + s.stackR + " " + s.height + " " + s.depth);
                assertEquals(v.stackR, s.stackR);
                assertEquals(v.height, s.height);
                assertEquals(v.depth, s.depth);
                compared++;
            }
            for (Net.Vent s : left) {
                assertTrue(t.galleries.column((int) Math.floor(s.x), (int) Math.floor(s.z)).closed(),
                        "seed " + seed + ": a site at " + s.x + " " + s.z + " that ventsNear leaves out stands on an open cell");
            }
            System.out.printf("VentSitesTest: seed %d at %d %d: %d vents, %d sites (%.2f ms cold)%n", seed, at[0], at[1], vents.size(), sites.size(), ms);
        }
        assertTrue(compared >= 20, "only " + compared + " vents compared");
    }

    /** The asking changes nothing: the digest of a terrain that answered vent queries first is the pinned one. */
    @Test
    void askingLeavesTheTerrainAsItWas() {
        Terrain t = new Terrain(TerrainSettings.defaults(), -64, 8675309);
        for (int x = -1024; x <= 1024; x += 256) for (int z = -1024; z <= 1024; z += 256) t.ventSitesNear(x, z, R);
        assertEquals("99d103d778b8355c", GalleriesFieldTest.digest(t, 8675309), "seed 8675309");
    }

    /** The middle of the first lattice point (every 200 blocks, outward from the origin) with a vent site within 100. */
    static int[] firstCaldera(Terrain t) {
        for (int ring = 0; ring <= 15; ring++) {
            for (int i = -ring; i <= ring; i++) for (int j = -ring; j <= ring; j++) {
                if (Math.max(Math.abs(i), Math.abs(j)) != ring) continue;
                List<Net.Vent> s = t.ventSitesNear(i * 200, j * 200, 100);
                if (!s.isEmpty()) return new int[]{(int) Math.floor(s.get(0).x), (int) Math.floor(s.get(0).z)};
            }
        }
        return null;
    }
}
