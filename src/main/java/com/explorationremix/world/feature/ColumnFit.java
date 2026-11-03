package com.explorationremix.world.feature;

/**
 * How long a column of a cluster may be ({@link ColumnClusterFeature}): a pure rule on block counts, so it can be
 * checked without a level.
 */
public final class ColumnFit {
    private ColumnFit() {}

    /**
     * The length a hanging column gets: the length drawn ({@code h}), cut so that at least {@code clearance} open blocks
     * stay between its lowest block and the floor under it ({@code openBelow} open blocks run straight down from the
     * ceiling block it hangs from). 0 when no block fits; {@code h} itself when the clearance is 0.
     */
    public static int hangingLength(int h, int openBelow, int clearance) {
        if (clearance <= 0) return h;
        return Math.max(0, Math.min(h, openBelow - clearance));
    }
}
