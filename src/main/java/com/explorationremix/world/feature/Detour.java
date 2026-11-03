package com.explorationremix.world.feature;

/**
 * Whether a standing place can be taken without closing a way: the places a player steps to from it, and back, must stay
 * joined to each other through the eight columns round it. A pure rule over a block query, so the tests ask it on their
 * own blocks; the decorations ask it on the level ({@link FluidFallFeature}: a lava fall's sump takes the place at its
 * foot).
 *
 * <p>The moves are the walk tests': a player stands on two open blocks over a solid one, and steps to a neighbouring
 * column on the level, or one block up or down where the lower column is open a block over the head (both ways the
 * same). A detour through more than the eight columns is not looked for, so the rule may refuse where a longer way round
 * exists, never the other way about.
 */
public final class Detour {
    /** What a block is to a walker. */
    public static final int SOLID = 0, OPEN = 1, OTHER = 2;

    /** The block at (x, y, z) as {@link #SOLID}, {@link #OPEN} or {@link #OTHER} (fluids, plants, anything else). */
    @FunctionalInterface
    public interface Blocks {
        int at(int x, int y, int z);
    }

    private static final int NONE = Integer.MIN_VALUE;
    /** The ring of eight columns, going round; edge-adjacent to the middle at the odd places. */
    private static final int[] RX = {-1, 0, 1, 1, 1, 0, -1, -1}, RZ = {-1, -1, -1, 0, 1, 1, 1, 0};

    private Detour() {}

    /**
     * Whether taking the standing place with its feet at (x, y, z) keeps every place joined to it both ways (in the four
     * columns beside it, feet within a block of its own) joined to the others round it. True where it has at most one such
     * neighbour (the end of a stub: nothing to keep joined) or where it is no standing place at all.
     */
    public static boolean around(Blocks b, int x, int y, int z) {
        if (!stand(b, x, y, z)) return true;
        int[] feet = new int[8];
        for (int r = 0; r < 8; r++) feet[r] = standNear(b, x + RX[r], y, z + RZ[r]);
        boolean[] nb = new boolean[8];
        int n = 0;
        for (int r = 1; r < 8; r += 2) {
            if (feet[r] == NONE || !twoWay(b, x, y, z, x + RX[r], feet[r], z + RZ[r])) continue;
            nb[r] = true;
            n++;
        }
        if (n <= 1) return true;
        // the ring's parts: neighbouring cells of the ring (a corner and an edge cell) joined by a two-way step
        int[] part = new int[8];
        for (int r = 0; r < 8; r++) part[r] = r;
        for (int r = 0; r < 8; r++) {
            int s = (r + 1) & 7;
            if (feet[r] == NONE || feet[s] == NONE) continue;
            if (!twoWay(b, x + RX[r], feet[r], z + RZ[r], x + RX[s], feet[s], z + RZ[s])) continue;
            int pr = find(part, r), ps = find(part, s);
            if (pr != ps) part[pr] = ps;
        }
        int first = -1;
        for (int r = 1; r < 8; r += 2) {
            if (!nb[r]) continue;
            if (first < 0) first = find(part, r);
            else if (find(part, r) != first) return false;
        }
        return true;
    }

    private static int find(int[] part, int r) {
        while (part[r] != r) r = part[r];
        return r;
    }

    /** A standing place: feet and head open, a solid block under the feet. */
    static boolean stand(Blocks b, int x, int y, int z) {
        return b.at(x, y, z) == OPEN && b.at(x, y + 1, z) == OPEN && b.at(x, y - 1, z) == SOLID;
    }

    /** The feet row of column (x, z)'s standing place within a block of row y (there is at most one), or NONE. */
    private static int standNear(Blocks b, int x, int y, int z) {
        for (int f = y - 1; f <= y + 1; f++) if (stand(b, x, f, z)) return f;
        return NONE;
    }

    /** Whether two standing places in neighbouring columns are joined both ways (level, or one up with head room). */
    private static boolean twoWay(Blocks b, int ax, int ay, int az, int bx, int by, int bz) {
        if (ay == by) return true;
        if (Math.abs(ay - by) > 1) return false;
        return ay < by ? b.at(ax, ay + 2, az) == OPEN : b.at(bx, by + 2, bz) == OPEN;
    }
}
