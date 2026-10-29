package com.explorationremix.world.feature;

import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.Network;
import com.explorationremix.world.terrain.Plans;
import com.explorationremix.world.terrain.Route;
import com.explorationremix.world.terrain.Terrain;

/**
 * Keeps the deep layers' ways walkable. The open way pass clears every way's walking corridor, but only inside its own
 * chunk: a feature decorating a neighbouring chunk later can still reach across into it. So every feature that builds
 * something solid asks here first and leaves the corridors alone: no column, spike, root, pool rim, machine part or
 * waterfall stands on a way or hangs into its headroom. The ways are the network's (section 12.2): every tunnel, inner
 * walk, ledge passage and stub of every cell, the rift's ledge path, and the gates' passages and landings.
 */
public final class Walkway {
    /** How far from a walking line its corridor reaches sideways, at least. */
    public static final double HALF_WIDTH = 2.6;

    private Walkway() {}

    /** True when (x, y, z) is inside a way's walking corridor: on the way's line and up to four blocks above its floor. */
    public static boolean blocks(int x, int y, int z) {
        return near(x, z, y, y, 0);
    }

    /**
     * True when a structure in column (x, z) spanning y0 to y1 would come within {@code margin} blocks of a way's
     * walking corridor (sideways), at the corridor's height: within 0.6 of the way's half-width (at least 2.6) plus the
     * margin of its line, between its feet level and three blocks over it.
     */
    public static boolean near(int x, int z, int y0, int y1, double margin) {
        Terrain t = DeepWorld.terrain();
        if (t == null || !DeepWorld.installed()) return false;
        int lo = Math.min(y0, y1), hi = Math.max(y0, y1);
        if (nearGate(t, x, z, lo, hi, margin)) return true;
        if (lo >= t.shape.packFloor) return false;
        int li0 = t.shape.layerAt(Math.min(hi, t.shape.packFloor - 1)), li1 = t.shape.layerAt(lo);
        for (int li = 0; li < t.shape.layerCount; li++) {
            if (li != li0 && li != li1) continue;
            Network.WayLines w = t.wayLinesAt(li, x, z, 8 + margin);
            for (int k = 0; k < w.n; k++) {
                if (w.d[k] > Math.max(0.6 * w.half[k], HALF_WIDTH) + margin) continue;
                int feet = (int) Math.ceil(w.floor[k] - 1e-9);
                if (hi >= feet && lo <= feet + 3) return true;
            }
        }
        return false;
    }

    /** The gate passages through the bands, their landings, and the first gate's ramp up into the pack's caves. */
    private static boolean nearGate(Terrain t, int x, int z, int lo, int hi, double margin) {
        if (lo > t.shape.packFloor + 48) return false;
        Plans plans = t.plans;
        int cx = plans.cellOf(x), cz = plans.cellOf(z);
        for (int band = 1; band <= t.shape.layerCount; band++) {
            Plans.Gate g = plans.gate(band, cx, cz);
            if (!g.overlaps(x, z, x, z, margin + 6)) continue;
            double r = g.landR + margin;
            if (Math.hypot(x - g.ux, z - g.uz) < r && hi >= g.uFloor && lo <= g.uFloor + 6) return true;
            if (Math.hypot(x - g.lx, z - g.lz) < r && hi >= g.lFloor && lo <= g.lFloor + 6) return true;
            for (int y = lo; y <= hi; y += 2) {
                if (g.passageXZ(x + 0.5, y, z + 0.5) < g.radius * 1.4 + margin) return true;
            }
            if (band == 1) {
                Route.Link ramp = plans.ramp(g);
                for (int y = lo; y <= hi; y += 2) if (ramp.dist(x + 0.5, y + 0.5, z + 0.5) < margin + 1.5) return true;
            }
        }
        return false;
    }
}
