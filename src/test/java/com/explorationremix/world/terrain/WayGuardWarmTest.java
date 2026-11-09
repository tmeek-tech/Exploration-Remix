package com.explorationremix.world.terrain;

import com.explorationremix.world.eruption.BombFlight;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The eruptions' warm-up of the blasts' way guard (0.9.0, review G4): the first question whether a way down is near a
 * place ({@link GalleriesField#wayNear}) makes the field find the holes' openings for the lattice cells about it, a tenth
 * of a second's work, which a bomb's burst would pay in the server's tick. {@code Eruptions} asks it on a worker thread
 * as an eruption starts, at the four corners of the square its bombs come down in ({@link BombFlight#REACH} across of
 * the vent). The field shares those openings between threads, so a blast anywhere in that square, asked on another
 * thread, finds every lattice cell it needs already found.
 */
class WayGuardWarmTest {
    /** The rows asked: a vent's floor and the way guard's four rows either side (inside the Galleries). */
    static final int Y = -775, ROWS = 4, MARGIN = 3;

    @Test
    void aWorkerAskingAtTheCornersLeavesNothingForTheBlasts() throws Exception {
        Terrain t = new Terrain(TerrainSettings.defaults(), -64, 8675309);
        GalleriesField g = t.galleries;
        // three vents of the Caldera patch (-2, 1), where the pack's eruption checks stand
        List<Net.Vent> vents = t.ventSitesNear(-375, 375, 120);
        assertTrue(vents.size() >= 3, "vents " + vents.size());
        int d = (int) Math.ceil(BombFlight.REACH);
        for (Net.Vent v : vents.subList(0, 3)) {
            int x = (int) Math.floor(v.x), z = (int) Math.floor(v.z);
            Thread worker = new Thread(() -> {
                for (int sx = -1; sx <= 1; sx += 2) {
                    for (int sz = -1; sz <= 1; sz += 2) g.wayNear(x + sx * d, z + sz * d, Y - ROWS, Y + ROWS, MARGIN, MARGIN);
                }
            });
            worker.start();
            worker.join();
            long found = g.rawCells.size();
            assertTrue(found > 0, "the worker found no lattice cell");
            for (int dx = -d; dx <= d; dx += 3) {
                for (int dz = -d; dz <= d; dz += 3) g.wayNear(x + dx, z + dz, Y - ROWS, Y + ROWS, MARGIN, MARGIN);
            }
            assertEquals(found, g.rawCells.size(), "a blast within reach of the vent at " + x + " " + z + " needed a cell the warm-up did not find");
        }
    }
}
