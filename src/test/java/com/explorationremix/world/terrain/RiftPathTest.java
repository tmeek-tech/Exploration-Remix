package com.explorationremix.world.terrain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Finding a creature on the Deep Blue Rift's ledge path (the Skein's journeys go along it): the rifts round a spot, and
 * how far along a rift's path a creature standing on it is.
 */
class RiftPathTest {
    private static final long[] SEEDS = {8675309L, 12345L};

    @Test
    void aCreatureOnTheLedgePathIsFoundWhereItStands() {
        int rifts = 0, points = 0, wrong = 0;
        for (long seed : SEEDS) {
            Terrain t = new Terrain(TerrainSettings.defaults(), -64, seed);
            double cell = t.network.cell;
            for (int cx = -1; cx <= 1; cx++)
                for (int cz = -1; cz <= 1; cz++)
                    for (Route r : t.network.network(Terrain.BLUE, cx, cz).canyons) {
                        rifts++;
                        for (double s = 10; s < r.walkLength - 10; s += 7) {
                            double[] p = r.pathAt(s);
                            assertTrue(t.network.riftsNear(p[0], p[1], 16).contains(r), "its rift is among those round it");
                            double got = r.walkArcNear(p[0], Route.feetAt(p[2]), p[1], 16, 12);
                            points++;
                            // where the path comes back by itself a stretch of it may pass as near
                            if (Double.isNaN(got) || Math.abs(got - s) > 1.5) wrong++;
                        }
                        // 40 blocks over it, it is on this stretch no longer (only on one that passes that high)
                        double[] m = r.pathAt(r.walkLength / 2);
                        int over = Route.feetAt(m[2]) + 40;
                        double up = r.walkArcNear(m[0], over, m[1], 16, 12);
                        if (!Double.isNaN(up)) assertTrue(Math.abs(Route.feetAt(r.pathAt(up)[2]) - over) <= 12.5, "40 blocks over the path");
                    }
            assertTrue(cell >= 320);
        }
        assertTrue(rifts > 0, "there are rifts to walk");
        assertTrue(wrong <= points / 50, wrong + " of " + points + " points along the paths were not found where they are");
    }
}
