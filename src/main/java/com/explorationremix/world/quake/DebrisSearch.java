package com.explorationremix.world.quake;

import javax.annotation.Nullable;

/**
 * Where a block of ceiling rock can come loose in a big quake (0.9.0, spec 4.4.2): a search up one column from near a
 * player's feet to the rock over them, and down again to where the block would come to rest. It is pure over a column
 * reader ({@link Cells}), which gives each cell as a set of flags, so it is tested on voxel columns; {@link Debris} reads
 * the world through it.
 *
 * <ol>
 *     <li>The column must be open (air or the glow) for {@link #CLEAR} rows from the start row: otherwise it is a wall.</li>
 *     <li>Up through open cells to the first one that is not open, C. A column still open at the layer's top row is a
 *     hole into the layer over it: no debris there.</li>
 *     <li>C must be natural (the change log holds nothing there), in {@code #explorationremix:quake_debris}, with no
 *     waterlogged property and no fluid, with no fluid in any of its six neighbours, and in the layer.</li>
 *     <li>Rock over C: the {@link #SOLID_OVER} rows over it are not open (whatever layer they are in), and no row up to
 *     {@link #NO_OPEN_OVER} over it, or to the layer's top, is open. Thin ceilings, rock bands and their decks, bridges,
 *     arches and spire tops are refused; the vault and what hangs from it are kept.</li>
 *     <li>The landing, the way the falling block comes to rest: down from C - 1 through cells with no collision to the
 *     first cell S with some. It rests in S itself when S's shape ends below the cell's top (a bottom slab), else in the
 *     cell over it, R. R must be open or a source liquid (the block lands in it), and neither R nor its four neighbours
 *     may hold a fluid that is not a source (the foot of a fall: a landing there would make it flow again).</li>
 * </ol>
 */
final class DebrisSearch {
    private DebrisSearch() {}

    // ------------------------------------------------------------------------------------------------ cell flags
    /** Air or the glow. */
    static final int OPEN = 1;
    /** No collision for the empty context: air, the glow, fire, fluids, torches, vines. */
    static final int EMPTY = 1 << 1;
    /** A collision shape that ends below the cell's top (a bottom slab): a block lands in the cell itself. */
    static final int LOW = 1 << 2;
    /** Its fluid state is not empty. */
    static final int FLUID = 1 << 3;
    /** Its fluid is a source. */
    static final int SOURCE = 1 << 4;
    /** Its fluid is lava. */
    static final int LAVA = 1 << 5;
    /** The block is a liquid block (a falling block lands in it, replacing it). */
    static final int LIQUID = 1 << 6;
    /** The change log holds the position: someone or something changed it, so it is not natural rock. */
    static final int HELD = 1 << 7;
    /** In {@code #explorationremix:quake_debris}. */
    static final int DEBRIS = 1 << 8;
    /** The block has a waterlogged property. */
    static final int WATERLOGGABLE = 1 << 9;
    /** Its chunk is not loaded: never open, always counted as a fluid (the careful answer). */
    static final int UNKNOWN = 1 << 10;

    /** How many rows from the start row must be open. */
    static final int CLEAR = 4;
    /** How many rows over the ceiling block must not be open, whatever layer they are in. */
    static final int SOLID_OVER = 3;
    /** How far over the ceiling block no row of the layer may be open. */
    static final int NO_OPEN_OVER = 16;

    /** The flags of the cells of one column and those round it. */
    interface Cells {
        /** The flags of the cell {@code dx}, {@code dz} from the column, at row {@code y}. */
        int at(int dx, int y, int dz);
    }

    /**
     * A ceiling block that can fall: the row it hangs at, the row it comes to rest in, and the top row of lava it passes
     * on the way ({@code Integer.MIN_VALUE} for none).
     */
    record Found(int y, int landY, int lavaY) {
        /** How many blocks it falls. */
        int height() { return y - landY; }
    }

    /**
     * Searches the column from row {@code y0} (a player's feet row + 2) up to its ceiling and down to where a block of it
     * would land, in a layer whose rows are {@code bottom} to {@code top}. Returns null when nothing there can fall.
     */
    @Nullable
    static Found search(Cells c, int y0, int top, int bottom) {
        for (int y = y0; y < y0 + CLEAR; y++) {
            if (!open(c.at(0, y, 0))) return null;
        }
        int y = y0 + CLEAR;
        while (open(c.at(0, y, 0))) {
            if (y >= top) return null;
            y++;
        }
        return at(c, y, top, bottom);
    }

    /**
     * Steps 3 to 5 for a block at row {@code y} with open space under it: whether it can fall, and where it lands. This is
     * also the check at the drop, and the test a neighbour of a block that came loose must pass.
     */
    @Nullable
    static Found at(Cells c, int y, int top, int bottom) {
        if (y < bottom || y > top || !open(c.at(0, y - 1, 0)) || !ceiling(c, y, top)) return null;
        // the landing: down through cells with no collision to the first with some
        int lavaY = Integer.MIN_VALUE;
        int s = y - 1;
        for (; ; s--) {
            if (s < bottom) return null;
            int f = c.at(0, s, 0);
            if ((f & UNKNOWN) != 0) return null;
            if ((f & EMPTY) == 0) break;
            if ((f & LAVA) != 0 && lavaY == Integer.MIN_VALUE) lavaY = s;
        }
        int r = (c.at(0, s, 0) & LOW) != 0 ? s : s + 1;
        int rf = c.at(0, r, 0);
        if (!open(rf) && !((rf & LIQUID) != 0 && (rf & SOURCE) != 0)) return null;
        if (flowing(rf) || flowing(c.at(1, r, 0)) || flowing(c.at(-1, r, 0)) || flowing(c.at(0, r, 1)) || flowing(c.at(0, r, -1))) return null;
        return new Found(y, r, lavaY);
    }

    /** Steps 3 and 4: the block at row {@code y} is natural ceiling rock with rock over it. */
    static boolean ceiling(Cells c, int y, int top) {
        int f = c.at(0, y, 0);
        if ((f & (HELD | WATERLOGGABLE | FLUID | UNKNOWN)) != 0 || (f & DEBRIS) == 0) return false;
        if (wet(c.at(1, y, 0)) || wet(c.at(-1, y, 0)) || wet(c.at(0, y, 1)) || wet(c.at(0, y, -1))
                || wet(c.at(0, y + 1, 0)) || wet(c.at(0, y - 1, 0))) return false;
        for (int k = 1; k <= SOLID_OVER; k++) {
            int o = c.at(0, y + k, 0);
            if (open(o) || (o & UNKNOWN) != 0) return false;
        }
        for (int yy = y + SOLID_OVER + 1; yy <= Math.min(y + NO_OPEN_OVER, top); yy++) {
            int o = c.at(0, yy, 0);
            if (open(o) || (o & UNKNOWN) != 0) return false;
        }
        return true;
    }

    private static boolean open(int f) { return (f & OPEN) != 0 && (f & UNKNOWN) == 0; }

    /** A fluid there, or a cell not loaded. */
    private static boolean wet(int f) { return (f & (FLUID | UNKNOWN)) != 0; }

    /** A fluid that is not a source there, or a cell not loaded. */
    private static boolean flowing(int f) { return (f & UNKNOWN) != 0 || (f & FLUID) != 0 && (f & SOURCE) == 0; }
}
