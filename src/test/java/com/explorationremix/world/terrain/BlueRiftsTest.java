package com.explorationremix.world.terrain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Blue Reaches' noise rifts (0.21.7; Taran's cards: "More rifts, some very long" and "A mix that changes along a
 * rift"): over 2 x 2 Blue cells they open under a share of the layer's columns, from steep ravines to wide stepped
 * chasms; and they keep clear of the den gates' landings, whose passages are carved whatever the plan, and of the
 * city's reserve.
 */
class BlueRiftsTest {
    @Test
    void riftsRunThroughTheLayerFromRavineToChasm() {
        Terrain t = NetworkTest.terrain(NetworkTest.SEEDS[0]);
        Network w = t.network;
        int cell = (int) w.cell;
        long cols = 0, rift = 0, ravine = 0, chasm = 0;
        double widest = 0;
        for (int x = 0; x < 2 * cell; x += 2) for (int z = -3 * cell; z < -cell; z += 2) {
            Network.LCol lc = w.scratch(x, z, Terrain.BLUE);
            cols++;
            if (!lc.nOn) continue;
            rift++;
            double form = lc.nSh[BlueRifts.FORM];
            if (form < 0.2) ravine++;
            if (form > 0.8) chasm++;
            widest = Math.max(widest, lc.nSh[BlueRifts.HALF]);
        }
        double share = rift / (double) cols;
        System.out.printf(java.util.Locale.ROOT, "BlueRiftsTest: noise rifts under %.1f%% of the columns; %.0f%% of them ravine, %.0f%% chasm; widest half width %.1f%n",
                100 * share, 100.0 * ravine / rift, 100.0 * chasm / rift, widest);
        // measured 5.2 to 7.8% over the three seeds' 2 x 2 cells here (5.4 to 6.5% over 4 x 4 cells) when this was
        // written, a tenth of them ravine and a third chasm
        assertTrue(share > 0.04 && share < 0.12, "noise rifts under " + share + " of the columns");
        assertTrue(ravine * 30 > rift && chasm * 30 > rift, ravine + " ravine and " + chasm + " chasm columns of " + rift);
        assertTrue(widest > 35, "the widest half width is " + widest);
    }

    @Test
    void riftsKeepClearOfLandingsAndTheCity() {
        List<String> fails = new ArrayList<>();
        int landings = 0, cities = 0;
        for (long seed : NetworkTest.SEEDS) {
            Terrain t = NetworkTest.terrain(seed);
            Network w = t.network;
            int cell = (int) w.cell;
            for (int cx = -1; cx <= 0; cx++) for (int cz = -1; cz <= 0; cz++) {
                for (Plans.Gate g : w.base.plans.denGates(cx, cz)) {
                    landings++;
                    double reach = g.landR + NetRifts.LANDING_SHUT - 1;
                    for (double r = 0; r <= reach; r += 2) for (int k = 0; k < 24; k++) {
                        double a = k * Math.PI / 12;
                        int x = (int) Math.floor(g.lx + r * Math.cos(a)), z = (int) Math.floor(g.lz + r * Math.sin(a));
                        if (w.inNoiseRift(x, z, 0)) {
                            fails.add(NetworkTest.tag(seed, Terrain.BLUE, cx, cz) + ": a noise rift at " + x + " " + z + ", " + (int) r + " from a den landing at "
                                    + (int) g.lx + " " + (int) g.lz);
                            break;
                        }
                    }
                }
                Net.Chamber city = w.net(Terrain.BLUE, cx, cz).city;
                if (city == null) continue;
                cities++;
                for (double r = 0; r <= Network.CITY_RESERVE - 2; r += 4) for (int k = 0; k < 48; k++) {
                    double a = k * Math.PI / 24;
                    int x = (int) Math.floor(city.x + r * Math.cos(a)), z = (int) Math.floor(city.z + r * Math.sin(a));
                    if (w.inNoiseRift(x, z, 0)) fails.add(NetworkTest.tag(seed, Terrain.BLUE, cx, cz) + ": a noise rift at " + x + " " + z + " in the city's reserve");
                }
            }
        }
        System.out.printf("BlueRiftsTest: %d den landings and %d cities kept clear of%n", landings, cities);
        assertTrue(landings >= 12, landings + " den landings");
        assertTrue(fails.isEmpty(), fails.size() + " failures:\n" + String.join("\n", fails.subList(0, Math.min(30, fails.size()))));
    }
}
