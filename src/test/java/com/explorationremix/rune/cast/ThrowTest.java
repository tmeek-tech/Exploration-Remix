package com.explorationremix.rune.cast;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reel's throw to a wall: flown with the game's own fall and drag, it gets there. */
class ThrowTest {
    /** Where a player thrown with {@code r} is after {@code ticks} ticks, moving as the game moves a player in the air. */
    static double[] fly(Throw.Result r, int ticks) {
        double x = 0, y = 0, z = 0, vx = r.vx(), vy = r.vy(), vz = r.vz();
        for (int k = 0; k < ticks; k++) {
            x += vx;
            y += vy;
            z += vz;
            vy = (vy - Throw.FALL) * Throw.DRAG_Y;
            vx *= Throw.DRAG_FLAT;
            vz *= Throw.DRAG_FLAT;
        }
        return new double[]{x, y, z};
    }

    static void arrives(double dx, double dy, double dz) {
        Throw.Result r = Throw.to(dx, dy, dz);
        double[] p = fly(r, r.ticks());
        String where = "to " + dx + ", " + dy + ", " + dz + " in " + r.ticks() + " ticks, got " + p[0] + ", " + p[1] + ", " + p[2];
        assertEquals(dx, p[0], 0.05, where);
        assertEquals(dy, p[1], 0.1, where);
        assertEquals(dz, p[2], 0.05, where);
    }

    @Test
    void itGetsThereNearOrFarUpOrDown() {
        arrives(6, 0, 0);
        arrives(0, 0, -9);
        arrives(5, 4, 3);
        arrives(3, 8, 0);
        arrives(10, 2, 4);
        arrives(4, -3, 4);
    }

    @Test
    void aPointAboveIsReachedAtTheTopOfTheThrow() {
        Throw.Result r = Throw.to(2, 6, 0);
        // the next tick it is no higher: it is not still rising past a ledge
        double[] at = fly(r, r.ticks()), after = fly(r, r.ticks() + 1);
        assertTrue(after[1] <= at[1] + 1.0E-9, "tops out there");
        assertEquals(6, at[1], 0.1);
    }

    @Test
    void straightUpHasNoSidewaysSpeed() {
        Throw.Result r = Throw.to(0, 5, 0);
        assertEquals(0.0, r.vx());
        assertEquals(0.0, r.vz());
        assertEquals(5.0, Throw.topHeight(r.vy()), 0.05);
    }

    @Test
    void neverFasterThanTheCaps() {
        for (double[] d : new double[][]{{60, 0, 0}, {0, 80, 0}, {0, -80, 0}, {30, 30, 30}, {-50, 5, 12}}) {
            Throw.Result r = Throw.to(d[0], d[1], d[2]);
            assertTrue(Math.hypot(r.vx(), r.vz()) <= Throw.MAX_FLAT + 1.0E-9);
            assertTrue(r.vy() <= Throw.MAX_UP + 1.0E-9 && r.vy() >= -Throw.MAX_DOWN - 1.0E-9);
            assertTrue(r.ticks() >= 1);
        }
    }

    @Test
    void swiftGetsThereSooner() {
        Throw.Result slow = Throw.to(12, 0, 0), quick = Throw.to(12, 0, 0, 1.5);
        assertTrue(quick.ticks() < slow.ticks());
        double[] p = fly(quick, quick.ticks());
        assertEquals(12, p[0], 0.05);
    }
}
