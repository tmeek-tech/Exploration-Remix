package com.explorationremix.world.terrain;

import java.util.Random;

/**
 * Columns sampled in clusters, for the tests that ask the Basalt Galleries or the Wasteland about places spread over a
 * wide area. The Galleries resolve their openings and ramps over the lattice cells round a query (spec-galleries-field-v2
 * 7.2), and the Wasteland's lowest sheet reads the craters of the holes with ramps from them: the first query in an area
 * costs about 0.1-0.3 s (the openings' lattice cells round it are found and resolved once, into caches every thread
 * shares), the next ones there microseconds, so queries spread at random over a wide area would each pay the first
 * one's cost. So a test takes a new area every {@code per} columns, anywhere in its span, and its columns at random in
 * a window round that area's middle: as many conditions across the world, at a cost a test can pay.
 */
final class Spread {
    private final Random r;
    private final int span, window, per;
    private int left, cx, cz;

    /** Areas anywhere in [-span/2, span/2)^2, columns within window/2 of an area's middle, per columns an area. */
    Spread(Random r, int span, int window, int per) {
        this.r = r;
        this.span = span;
        this.window = window;
        this.per = per;
    }

    /** The next column: {x, z}. */
    int[] next() {
        if (left == 0) {
            cx = r.nextInt(span) - span / 2;
            cz = r.nextInt(span) - span / 2;
            left = per;
        }
        left--;
        return new int[]{cx + r.nextInt(window) - window / 2, cz + r.nextInt(window) - window / 2};
    }
}
