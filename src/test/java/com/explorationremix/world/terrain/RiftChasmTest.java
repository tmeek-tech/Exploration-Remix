package com.explorationremix.world.terrain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Deep Blue Rift's great chasm (0.20.8, Terrain#inRiftChasm): the open span of the rift over each column of its
 * centre line is in it, the rock over its ceiling is not, and neither are the grottos of the Blue Reaches well away from
 * it, which share its biome (the "smaller tunnels" whose fireflies Taran wants kept as they are).
 */
class RiftChasmTest {
    private static final Terrain T = new Terrain(TerrainSettings.defaults(), -64, 8675309L);

    @Test
    void theChasmIsTheRiftsOpenSpanAndNotTheCavesOffIt() {
        Network nw = T.network;
        int top = T.shape.top(Terrain.BLUE), bottom = T.shape.bottom(Terrain.BLUE);
        int inside = 0, above = 0, grottos = 0;
        for (int cx = -1; cx <= 0; cx++)
            for (int cz = -1; cz <= 0; cz++) {
                Net n = nw.net(Terrain.BLUE, cx, cz);
                for (Route r : n.canyons) {
                    if (!r.isRift()) continue;
                    for (int i = 0; i < r.n; i += 2) {
                        int x = (int) Math.floor(r.px[i]), z = (int) Math.floor(r.pz[i]);
                        double[] open = nw.riftOpen(r, x, z, 0);
                        if (open == null || open[1] - open[0] < 12) continue;
                        int mid = (int) Math.round((open[0] + open[1]) / 2);
                        if (mid <= bottom || mid >= top) continue;
                        assertTrue(T.inRiftChasm(x, mid, z), "the middle of the rift's span over (" + x + ", " + z + ") at " + mid);
                        inside++;
                        int over = (int) Math.ceil(open[1] + 14);
                        if (over < top) {
                            assertFalse(T.inRiftChasm(x, over, z), "the rock over the rift's ceiling at (" + x + ", " + over + ", " + z + ")");
                            above++;
                        }
                    }
                }
                for (Net.Chamber c : n.ch) {
                    if (c.biome != DeepBiome.DEEP_BLUE_RIFT || c.kind != Net.Kind.GROTTO) continue;
                    boolean far = true;
                    for (Route r : n.canyons) {
                        double[] loc = r.isRift() ? r.locate(c.x, c.z) : null;
                        if (loc != null && Math.abs(loc[2]) < r.riftHalfAt(loc[1]) + c.R() + 16) far = false;
                    }
                    if (!far) continue;
                    int y = (int) Math.ceil(c.f0) + 3;
                    assertFalse(T.inRiftChasm((int) Math.floor(c.x), y, (int) Math.floor(c.z)), "a grotto well away from the rift, at (" + c.x + ", " + y + ", " + c.z + ")");
                    grottos++;
                }
            }
        assertTrue(inside >= 40, inside + " points of the rifts' centre lines");
        assertTrue(above >= 10, above + " points over their ceilings");
        assertTrue(grottos >= 3, grottos + " grottos away from them");
        assertFalse(T.inRiftChasm(112, T.shape.packFloor + 10, 0), "nothing above the deep world is in it");
    }
}
