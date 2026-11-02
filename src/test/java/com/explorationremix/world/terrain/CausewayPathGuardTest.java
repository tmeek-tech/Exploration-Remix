package com.explorationremix.world.terrain;

import com.explorationremix.world.terrain.Net.Chamber;
import com.explorationremix.world.terrain.Net.Kind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Causeway's column path is a walking route with no way laid on it, so the walking lines the decoration keeps off
 * ({@link Network#wayLinesAt}, read by {@code Walkway}) must list it: every column top of the path's two rows must lie
 * in a line's corridor, the top block itself (a lava pool or a fall's sump there) and the place a player stands on it,
 * or the lava pools, falls and column clusters land on the path. Nothing may carve it, so the lines the open way pass
 * reads ({@link Network#carvedLinesAt}) must not list it.
 */
class CausewayPathGuardTest {
    /** Cells whose Galleries plans hold Causeways (seed, cell x, cell z). */
    static final long[][] CELLS = {{8675309L, 0, -3}, {2000023L, -1, 1}, {777L, 0, 0}, {424242L, 1, -1}};

    @Test
    void theColumnPathIsAWalkingLineForTheDecorationOnly() {
        List<String> fails = new ArrayList<>();
        int causeways = 0, tops = 0;
        for (long[] cell : CELLS) {
            Terrain t = NetworkTest.terrain(cell[0]);
            Network nw = t.network;
            Net n = nw.net(Terrain.GALLERIES, (int) cell[1], (int) cell[2]);
            for (Chamber c : n.ch) {
                if (c.kind != Kind.CAUSEWAY || !c.keep) continue;
                causeways++;
                int r = (int) Math.ceil(c.R());
                for (int x = (int) c.x - r; x <= (int) c.x + r; x++) {
                    for (int z = (int) c.z - r; z <= (int) c.z + r; z++) {
                        double[] uv = nw.hallUV(c, x, z);
                        // the path's two rows: their hexagons reach 1.01 hexS either side of the path's line, the rows
                        // beside them begin at 0.72 hexS
                        if (Math.abs(uv[1] * c.side - c.pathV) > 0.7 * c.hexS) continue;
                        int top = nw.hexTop(c, x, z);
                        if (top == Network.NO_COLUMN || top == Network.TO_VAULT) continue;
                        tops++;
                        for (int y = top; y <= top + 1; y++) {
                            if (!inCorridor(nw.wayLinesAt(Terrain.GALLERIES, x, z, 8), y))
                                fails.add("seed " + cell[0] + " Causeway #" + c.id + ": no walking line keeps y " + y + " at " + x + " " + z + " (top " + top + ")");
                        }
                        // away from the end ramps, whose walks step onto the path's last columns
                        if (Math.abs(uv[0]) < c.rx - c.endW - 6 && inCorridor(nw.carvedLinesAt(Terrain.GALLERIES, x, z, 8), top))
                            fails.add("seed " + cell[0] + " Causeway #" + c.id + ": a carved line runs through the column top at " + x + " " + top + " " + z);
                    }
                }
            }
        }
        assertTrue(causeways >= 2 && tops > 500, "too few Causeways to test: " + causeways + " with " + tops + " path tops");
        assertTrue(fails.isEmpty(), fails.size() + " of " + tops + " path tops unguarded:\n" + String.join("\n", fails.subList(0, Math.min(30, fails.size()))));
    }

    /** As {@code Walkway.near(x, z, y, y, 0)} reads the lines: within 0.6 of a line's half-width (at least 2.6), feet to feet + 3. */
    private static boolean inCorridor(Network.WayLines w, int y) {
        for (int k = 0; k < w.n; k++) {
            if (w.d[k] > Math.max(0.6 * w.half[k], 2.6)) continue;
            int feet = (int) Math.ceil(w.floor[k] - 1e-9);
            if (y >= feet && y <= feet + 3) return true;
        }
        return false;
    }
}
