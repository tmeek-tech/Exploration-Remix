package com.explorationremix.world.terrain;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the Deep Gardens' two kinds of gate land: the way in from the Blue Reaches (band 2's lower landing, on the top
 * storey) and the way down to the Wasteland (band 3's upper landing, on the bottom storey). The fields pull their floors
 * to these landings and keep their vents away from them.
 */
interface GardensGates {
    /** A landing: its centre across, its floor as a row of the layer (0 = the layer's bottom row), and which way it leads. */
    record Gate(double x, double z, double floor, boolean entry, Plans.Gate source) {}

    /** Every landing within {@code r} of (x, z), in a fixed order. */
    List<Gate> near(double x, double z, double r);

    /** The game's gates, as {@link Plans} places them: one of each kind per cell. */
    static GardensGates of(Plans plans, Shape shape) {
        int bottom = shape.bottom(Terrain.GARDENS);
        return (x, z, r) -> {
            List<Gate> out = new ArrayList<>(2);
            int cx = plans.cellOf(x), cz = plans.cellOf(z);
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    Plans.Gate in = plans.gate(2, cx + dx, cz + dz);
                    if (Math.hypot(in.lx - x, in.lz - z) <= r) out.add(new Gate(in.lx, in.lz, in.lFloor - bottom, true, in));
                    Plans.Gate down = plans.gate(3, cx + dx, cz + dz);
                    if (Math.hypot(down.ux - x, down.uz - z) <= r) out.add(new Gate(down.ux, down.uz, down.uFloor - bottom, false, down));
                }
            return out;
        };
    }

    /** Fixed landings (the offline preview's: round 1's spots, with the game's floors). */
    static GardensGates fixed(List<Gate> gates) {
        return (x, z, r) -> {
            List<Gate> out = new ArrayList<>(2);
            for (Gate g : gates) if (Math.hypot(g.x() - x, g.z() - z) <= r) out.add(g);
            return out;
        };
    }
}
