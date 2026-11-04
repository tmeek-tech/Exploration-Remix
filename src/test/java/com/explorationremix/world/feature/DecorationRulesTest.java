package com.explorationremix.world.feature;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The decorations' pure rules on their own blocks: a hanging column's clearance ({@link ColumnFit}), whether a lava
 * fall's sump may take a standing place ({@link Detour}), and whether a lava pool or fall keeps the walks round it
 * ({@link WalkWindow}).
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

    /**
     * Blocks of a cave for {@link WalkWindow}: each column open from its floor up to its ceiling (solid under and over),
     * by default a floor at row 0 and a ceiling at row 6 (feet at row 0); single blocks may be set over that.
     */
    static class Cave implements Detour.Blocks {
        final java.util.Map<Long, Integer> set = new java.util.HashMap<>();

        int floor(int x, int z) { return 0; }

        int ceil(int x, int z) { return 6; }

        Cave set(int x, int y, int z, int k) {
            set.put(key(x, y, z), k);
            return this;
        }

        static long key(int x, int y, int z) { return ((long) x & 0xFFFFF) << 40 | ((long) y & 0xFFFFF) << 20 | ((long) z & 0xFFFFF); }

        @Override public int at(int x, int y, int z) {
            Integer k = set.get(key(x, y, z));
            if (k != null) return k;
            return y >= floor(x, z) && y < ceil(x, z) ? Detour.OPEN : Detour.SOLID;
        }
    }

    /** A lava pool's cells (rows -1, the floor, with the bed under them) over the columns x0..x1, z0..z1. */
    static WalkWindow.Change pool(int x0, int x1, int z0, int z1) {
        WalkWindow.Change c = new WalkWindow.Change();
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) c.add(x, -1, z, Detour.OTHER).add(x, -2, z, Detour.SOLID);
        return c;
    }

    /** A lava fall from a source in the wall at (x + 1, top, z) down column (x, z) to its sump at row -1. */
    static WalkWindow.Change fall(int x, int top, int z) {
        WalkWindow.Change c = new WalkWindow.Change().add(x + 1, top, z, Detour.OTHER);
        for (int y = top; y >= 0; y--) c.add(x, y, z, Detour.OTHER);
        return c.add(x, -1, z, Detour.OTHER);
    }

    @Test
    void aPoolOrAFallKeepsTheWalksRoundIt() {
        // an open floor: a pool or a fall in it takes the places it stands on, and nothing else
        assertTrue(WalkWindow.keeps(new Cave(), pool(0, 1, 0, 1)), "a pool in an open floor");
        Cave wall = new Cave() {
            @Override int ceil(int x, int z) { return x >= 1 ? 0 : 6; }
        };
        assertTrue(WalkWindow.keeps(wall, fall(0, 4, 0)), "a fall down a wall onto an open floor");
        // a passage three wide: lava across it cuts it; one cell in its middle leaves a lane either side
        Cave passage = new Cave() {
            @Override int ceil(int x, int z) { return Math.abs(z) > 1 ? 0 : 6; }
        };
        assertFalse(WalkWindow.keeps(passage, pool(0, 0, -1, 1)), "lava across a passage");
        assertTrue(WalkWindow.keeps(passage, pool(0, 0, 0, 0)), "lava in the middle of a passage three wide");
        // lava that closes the gap in a line of the cave's own lava cuts the floor in two (seed 8675309 at 0,0)
        Cave line = new Cave() {
            @Override public int at(int x, int y, int z) { return x == 0 && y == -1 && Math.abs(z) >= 2 ? Detour.OTHER : super.at(x, y, z); }
        };
        assertFalse(WalkWindow.keeps(line, pool(0, 0, -1, 1)), "lava closing a gap in the cave's own lava");
        Cave shortLine = new Cave() {
            @Override public int at(int x, int y, int z) { return x == 0 && y == -1 && Math.abs(z) >= 2 && Math.abs(z) <= 4 ? Detour.OTHER : super.at(x, y, z); }
        };
        assertTrue(WalkWindow.keeps(shortLine, pool(0, 0, -1, 1)), "a way round the end of the cave's own lava");
        // a nook whose way out is the floor beside it, and whose way in is a drop of 3 from a ledge: a pool on that
        // floor leaves it a pit (seed -1249636696 at -1925,171); a pool elsewhere does not
        Cave nook = new Cave() {
            @Override int floor(int x, int z) {
                if (x == 2 && z <= -1 && z >= -3) return 4 + z;   // the ledge at (2, -1), feet 3, and its stair
                return 0;
            }

            @Override int ceil(int x, int z) {
                if (x <= 1 || x == 2 && z == 0) return 6;         // the cave, and the nook at (2, 0)
                if (x == 2 && z <= -1 && z >= -3) return 10 + z;
                return 0;
            }
        };
        assertFalse(WalkWindow.keeps(nook, pool(0, 1, 0, 1)), "a pool that leaves a nook a pit");
        assertTrue(WalkWindow.keeps(nook, pool(-2, -1, 2, 3)), "a pool away from the nook");
        // a niche whose only way out is a drop into the fall's column, and whose way in a drop from a ledge: a fall there
        // leaves it a pit (seed -1314707143 at 5560,2806); the same fall further along the wall does not
        Cave niche = new Cave() {
            @Override int floor(int x, int z) {
                if (x == 1 && z == 0) return 3;                       // the niche, feet 3
                if (x == 1 && z <= -1 && z >= -5) return 6 + z;      // the ledge at (1, -1), feet 5, and its stair
                return 0;
            }

            @Override int ceil(int x, int z) {
                if (x <= 0) return 12;
                if (x == 1 && z == 0) return 8;
                if (x == 1 && z <= -1 && z >= -5) return 12 + z;
                return 0;
            }
        };
        assertFalse(WalkWindow.keeps(niche, fall(0, 9, 0)), "a fall that leaves a niche a pit");
        assertTrue(WalkWindow.keeps(niche, fall(0, 9, 4)), "the same fall further along the wall");
    }
}
