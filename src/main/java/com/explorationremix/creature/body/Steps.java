package com.explorationremix.creature.body;

/**
 * A step about a block high, as a Mossback's body sees it. The game moves its box over one as a climber does: blocked
 * by the riser, the box climbs straight up it, clinging to it as a little wall, then goes across the top; going down
 * one, it comes off the edge touching nothing (or clinging to the riser) for a moment. Its body walks the step
 * instead, as a lizard does: belly to the floor, the front feet up on the step (or down off it) while the hind feet
 * stay on the floor they came from. A box is at a step while a floor lies within {@link #DEEP} under it and the rock
 * it clings to beside it, if any, rises no more than {@link #HIGH} over that floor. A taller face is a wall, and it
 * climbs that.
 *
 * <p>Plain numbers, no game classes, so the offline clips walk steps the way the game does.
 */
public final class Steps {
    /** The most the rock beside a box may rise over the floor under it and still be a step (blocks). */
    public static final double HIGH = 1.25;
    /** How far under its box a floor still holds a body on its feet (blocks): over a step it never leaves that reach. */
    public static final double DEEP = 1.3;
    /** The side a box clings to: none (it touches nothing), or the rock to its west, east, north or south. */
    public static final int NONE = -1, WEST = 0, EAST = 1, NORTH = 2, SOUTH = 3;
    /** How far out from a box's side rock touches it, and how far in from its edges it reads the rock under and beside it. */
    private static final double TOUCH = 0.1, INSET = 0.06;

    private Steps() {}

    /**
     * Whether the box (x0..x1, y0..y1, z0..z1), off its floor, is at a step: a floor lies within {@link #DEEP} under
     * it, and the rock on the side it clings to ({@link #NONE}: it clings to nothing) rises at most {@link #HIGH} over
     * that floor.
     */
    public static boolean at(Terrain t, double x0, double y0, double z0, double x1, double y1, double z1, int side) {
        double floor = floor(t, x0 + INSET, z0 + INSET, x1 - INSET, z1 - INSET, y0 - DEEP, y0 + 0.05);
        if (floor == Double.NEGATIVE_INFINITY) return false;
        if (side == NONE) return true;
        // rock beside it higher than a step over that floor makes a wall
        double lo = floor + HIGH, hi = Math.max(y1, lo) + 0.5;
        return switch (side) {
            case WEST -> !any(t, x0 - TOUCH, lo, z0 + INSET, x0, hi, z1 - INSET);
            case EAST -> !any(t, x1, lo, z0 + INSET, x1 + TOUCH, hi, z1 - INSET);
            case NORTH -> !any(t, x0 + INSET, lo, z0 - TOUCH, x1 - INSET, hi, z0);
            case SOUTH -> !any(t, x0 + INSET, lo, z1, x1 - INSET, hi, z1 + TOUCH);
            default -> false;
        };
    }

    /** The highest top of rock under the square (x0..x1, z0..z1) lying from {@code lo} to {@code hi}, or -inf. */
    static double floor(Terrain t, double x0, double z0, double x1, double z1, double lo, double hi) {
        double[] best = {Double.NEGATIVE_INFINITY};
        Terrain.BoxSink sink = (bx0, by0, bz0, bx1, by1, bz1) -> {
            if (by1 >= lo && by1 <= hi && by1 > best[0] && bx1 > x0 && bx0 < x1 && bz1 > z0 && bz0 < z1) best[0] = by1;
        };
        for (int x = cell(x0); x <= cell(x1 - 1.0E-9); x++)
            for (int z = cell(z0); z <= cell(z1 - 1.0E-9); z++)
                for (int y = cell(lo); y <= cell(hi); y++) t.boxes(x, y, z, sink);
        return best[0];
    }

    /** Whether any rock lies in the region (x0..x1, y0..y1, z0..z1). */
    static boolean any(Terrain t, double x0, double y0, double z0, double x1, double y1, double z1) {
        boolean[] hit = {false};
        Terrain.BoxSink sink = (bx0, by0, bz0, bx1, by1, bz1) -> {
            if (bx1 > x0 && bx0 < x1 && by1 > y0 && by0 < y1 && bz1 > z0 && bz0 < z1) hit[0] = true;
        };
        for (int x = cell(x0); x <= cell(x1 - 1.0E-9) && !hit[0]; x++)
            for (int z = cell(z0); z <= cell(z1 - 1.0E-9) && !hit[0]; z++)
                for (int y = cell(y0); y <= cell(y1 - 1.0E-9) && !hit[0]; y++) t.boxes(x, y, z, sink);
        return hit[0];
    }

    private static int cell(double v) {
        return (int) Math.floor(v);
    }
}
