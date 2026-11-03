package com.explorationremix.world.feature;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The decorations' pure rules on their own blocks: a hanging column's clearance ({@link ColumnFit}) and whether a lava
 * fall's sump may take a standing place ({@link Detour}).
 */
class DecorationRulesTest {
    /** A hanging column leaves at least its clearance open under it, is never longer than drawn, and is cut only when it must be. */
    @Test
    void hangingColumnsLeaveTheirClearance() {
        for (int h = 1; h <= 12; h++) for (int open = 0; open <= 30; open++) for (int c = 1; c <= 8; c++) {
            int len = ColumnFit.hangingLength(h, open, c);
            String what = "height " + h + ", open " + open + ", clearance " + c + ": " + len;
            assertTrue(len >= 0 && len <= h, what);
            if (len > 0) assertTrue(open - len >= c, "too little room under the column, " + what);
            if (open - h >= c) assertEquals(h, len, "cut although it fits, " + what);
        }
        assertEquals(7, ColumnFit.hangingLength(7, 3, 0), "no clearance: the column as drawn");
    }

    /** A box of blocks for {@link Detour}: x and z from -3 to 3, y from 0 to 11; open where not set solid. */
    static final class Box implements Detour.Blocks {
        final int[][][] b = new int[7][12][7];

        Box() {
            for (int x = 0; x < 7; x++) for (int z = 0; z < 7; z++) for (int y = 0; y < 12; y++) b[x][y][z] = y == 0 ? Detour.SOLID : Detour.OPEN;
        }

        /** Column (x, z) solid from row 0 up to row y (a floor there; y 11 walls it). */
        Box floor(int x, int z, int y) {
            for (int yy = 0; yy <= y; yy++) b[x + 3][yy][z + 3] = Detour.SOLID;
            return this;
        }

        Box set(int x, int y, int z, int m) {
            b[x + 3][y][z + 3] = m;
            return this;
        }

        /** Every column with |z| over the half-width is a wall. */
        Box corridor(int half) {
            for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) if (Math.abs(z) > half) floor(x, z, 11);
            return this;
        }

        @Override public int at(int x, int y, int z) {
            if (x < -3 || x > 3 || z < -3 || z > 3 || y < 0) return Detour.SOLID;
            if (y > 11) return Detour.OPEN;
            return b[x + 3][y][z + 3];
        }
    }

    @Test
    void aFallNeverClosesAPassage() {
        // a passage one block wide: taking its floor cuts it in two
        assertFalse(Detour.around(new Box().corridor(0), 0, 1, 0), "a one-wide passage");
        // open floor all round, a passage three wide with its middle taken, the end of a stub: a way round, or nothing to keep
        assertTrue(Detour.around(new Box(), 0, 1, 0), "open floor");
        assertTrue(Detour.around(new Box().corridor(1), 0, 1, 0), "the middle of a passage three wide");
        Box stub = new Box().corridor(0);
        for (int x = 1; x <= 3; x++) stub.floor(x, 0, 11);
        assertTrue(Detour.around(stub, 0, 1, 0), "the end of a stub");
        // a passage two wide whose second lane is a block up: the way round climbs it where there is head room
        Box steps = new Box().corridor(1);
        for (int x = -3; x <= 3; x++) { steps.floor(x, -1, 11); steps.floor(x, 1, 1); }
        assertTrue(Detour.around(steps, 0, 1, 0), "a lane a block up with head room");
        for (int x = -3; x <= 3; x++) steps.set(x, 3, 0, Detour.SOLID);
        assertFalse(Detour.around(steps, 0, 1, 0), "a lane a block up without head room");
        // lava or a plant is no floor and no way: a passage whose side lane is lava has no way round
        Box lava = new Box().corridor(1);
        for (int x = -3; x <= 3; x++) { lava.floor(x, -1, 11); lava.set(x, 1, 1, Detour.OTHER); }
        assertFalse(Detour.around(lava, 0, 1, 0), "a lane of lava beside the passage");
        // no standing place there at all: nothing to take
        assertTrue(Detour.around(new Box().corridor(0), 0, 4, 0), "no standing place");
    }
}
