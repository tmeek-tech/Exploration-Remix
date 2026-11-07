package com.explorationremix.world.lava;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The falling-block tracker's pure part (0.9.0, spec 4.4.4): the sweep of a block's next tick, which must catch someone
 * on the floor before the block lands in their feet, and vanilla's falling-block damage with its cap.
 */
class FallingTest {
    /** A falling block's half width and height (EntityType.FALLING_BLOCK is 0.98 by 0.98). */
    private static final double HALF = 0.49, HEIGHT = 0.98;

    /**
     * Lets a block go at rest {@code h} blocks over a floor whose top is y 0, in vanilla's order (gravity, the move, the
     * drag), with the tracker's sweep after each tick, as at the level tick's end. Returns {the tick it lands, the first
     * tick after which the sweep met the box, the block's bottom then}; -1 for a box never met.
     */
    private static double[] drop(double h, double[] box) {
        double y = h, v = 0;
        double met = -1, metY = Double.NaN;
        for (int t = 1; t < 1000; t++) {
            v -= Falling.GRAVITY;
            if (y + v <= 0) return new double[]{t, met, metY};
            y += v;
            v *= 0.98;
            double[] s = Falling.sweep(0.5 - HALF, y, 0.5 - HALF, 0.5 + HALF, y + HEIGHT, 0.5 + HALF, 0, v, 0);
            if (met < 0 && Falling.meets(s, box[0], box[1], box[2], box[3], box[4], box[5])) {
                met = t;
                metY = y;
            }
        }
        throw new AssertionError("never landed");
    }

    @Test
    void theSweepMeetsABoxOnTheFloorOneTickBeforeTheLanding() {
        double[] flat = {0.3, 0, 0.3, 0.7, 0.001, 0.7};
        double[] player = {0.2, 0, 0.2, 0.8, 1.8, 0.8};
        for (int h = 1; h <= 131; h++) {
            double[] r = drop(h, flat);
            assertEquals(r[0] - 1, r[1], "a block falling " + h + ": the tick before it lands");
            double[] p = drop(h, player);
            if (h < 2) continue;
            assertTrue(p[1] > 0 && p[1] < p[0], "a player under a block falling " + h + " is met before it lands");
            assertTrue(p[2] >= 1.8 - 1e-9, "and before the block is in them: its bottom at " + p[2]);
        }
    }

    @Test
    void theSweepMissesABoxBesideTheColumn() {
        double[] beside = {1.0, 0, 0.2, 1.6, 1.8, 0.8};
        for (int h = 1; h <= 131; h += 10) assertEquals(-1, drop(h, beside)[1], "h " + h);
        // a block knocked sideways sweeps that way
        double[] s = Falling.sweep(0, 5, 0, 0.98, 5.98, 0.98, 0.3, -0.5, 0);
        assertTrue(Falling.meets(s, 1.1, 4.0, 0.2, 1.2, 4.6, 0.8));
        assertFalse(Falling.meets(s, -0.4, 4.0, 0.2, -0.1, 4.6, 0.8));
    }

    @Test
    void theDamageIsVanillasWithTheCap() {
        for (float fd : new float[]{5, 5.5F, 10, 64, 131}) assertEquals(8, Falling.damage(fd, 8), "fell " + fd);
        for (float fd : new float[]{1.01F, 1.5F, 2}) assertEquals(2, Falling.damage(fd, 8), "fell " + fd);
        assertEquals(4, Falling.damage(3, 8));
        assertEquals(6, Falling.damage(4, 8));
        assertEquals(0, Falling.damage(0.5F, 8));
        assertEquals(40, Falling.damage(40, 40));
        for (float fd : new float[]{1.5F, 5, 131}) assertEquals(0, Falling.damage(fd, 0), "debrisDamage 0");
    }
}
