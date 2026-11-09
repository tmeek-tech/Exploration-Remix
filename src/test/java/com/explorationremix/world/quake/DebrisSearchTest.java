package com.explorationremix.world.quake;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static com.explorationremix.world.quake.DebrisSearch.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The search for ceiling rock that can fall (0.9.0, spec 4.4.2) on voxel columns: a column with the eight round it,
 * rock everywhere unless set, in a layer whose top row is -689 (over it, the Wasteland's open space).
 */
class DebrisSearchTest {
    private static final int TOP = -689, BOTTOM = -900, FEET = -850;

    // cells as the reader gives them
    private static final int ROCK = DEBRIS, PLAIN = 0, AIR = OPEN | EMPTY, GLOW = OPEN | EMPTY, TORCH = EMPTY, SLAB = LOW | DEBRIS,
            LAVA_SOURCE = EMPTY | FLUID | SOURCE | LAVA | LIQUID, LAVA_FLOWING = EMPTY | FLUID | LAVA | LIQUID,
            WATER_SOURCE = EMPTY | FLUID | SOURCE | LIQUID;

    /** A column and the eight round it: rock in the layer unless set, open space over its top. */
    private static final class Column implements Cells {
        final Map<Long, Integer> cells = new HashMap<>();

        @Override
        public int at(int dx, int y, int dz) {
            Integer f = cells.get(key(dx, y, dz));
            if (f != null) return f;
            return y > TOP ? AIR : ROCK;
        }

        Column set(int dx, int y, int dz, int f) {
            cells.put(key(dx, y, dz), f);
            return this;
        }

        /** Rows {@code from} to {@code to} of the middle column. */
        Column fill(int from, int to, int f) {
            for (int y = from; y <= to; y++) set(0, y, 0, f);
            return this;
        }

        private static long key(int dx, int y, int dz) { return ((long) (dx + 8) << 40) | ((long) (dz + 8) << 32) | (y & 0xffffffffL); }
    }

    /** A cave over a player standing at FEET: open from the floor up to the ceiling {@code h} rows up. */
    private static Column cave(int h) { return new Column().fill(FEET, FEET + h - 1, AIR); }

    @Test
    void ceilingsAreFoundHighAndLow() {
        for (int h : new int[]{10, 64, 131}) {
            Found f = search(cave(h), FEET + 2, TOP, BOTTOM);
            assertNotNull(f, "a ceiling " + h + " rows up");
            assertEquals(FEET + h, f.y());
            assertEquals(FEET, f.landY(), "it lands on the floor");
            assertEquals(h, f.height());
            assertEquals(Integer.MIN_VALUE, f.lavaY());
        }
    }

    @Test
    void aHoleIntoTheLayerOverItGivesNone() {
        Column c = new Column().fill(FEET, TOP, AIR);
        assertNull(search(c, FEET + 2, TOP, BOTTOM), "open at -689: a hole into the Wasteland");
    }

    @Test
    void theGlowIsOpen() {
        Column c = cave(20).set(0, FEET + 2, 0, GLOW).set(0, FEET + 9, 0, GLOW).set(0, FEET + 19, 0, GLOW);
        Found f = search(c, FEET + 2, TOP, BOTTOM);
        assertNotNull(f);
        assertEquals(FEET + 20, f.y());
    }

    @Test
    void aWallOrALowRoofIsNoColumn() {
        // the column must be open for 4 rows from the start
        assertNull(search(cave(5), FEET + 2, TOP, BOTTOM), "only 3 rows open from the start");
        assertNotNull(search(cave(6), FEET + 2, TOP, BOTTOM));
        assertNull(search(new Column(), FEET + 2, TOP, BOTTOM), "rock");
        assertNull(search(cave(20).set(0, FEET + 4, 0, TORCH), FEET + 2, TOP, BOTTOM), "a torch on a wall is not open");
    }

    @Test
    void thinCeilingsAndOpenSpaceOverThemAreRefused() {
        for (int rows = 1; rows <= 3; rows++) {
            // a rock band or a bridge `rows` thick with open space over it
            Column c = cave(30).fill(FEET + 30 + rows, FEET + 60, AIR);
            assertNull(search(c, FEET + 2, TOP, BOTTOM), rows + " rows to open space over it");
        }
        for (int rows : new int[]{4, 10, 16}) {
            Column c = cave(30).fill(FEET + 30 + rows, FEET + 60, AIR);
            assertNull(search(c, FEET + 2, TOP, BOTTOM), "open space " + rows + " rows over it");
        }
        Column deep = cave(30).fill(FEET + 47, FEET + 60, AIR);
        assertNotNull(search(deep, FEET + 2, TOP, BOTTOM), "open space 17 rows over it is past the check");
        // the layer's top: 4 rows of rock up to it are enough, 3 are not (the rows over it are read whatever layer they are in)
        Column four = new Column().fill(FEET, TOP - 4, AIR);
        Found f = search(four, FEET + 2, TOP, BOTTOM);
        assertNotNull(f, "4 rows to the layer's top");
        assertEquals(TOP - 3, f.y());
        Column three = new Column().fill(FEET, TOP - 3, AIR);
        assertNull(search(three, FEET + 2, TOP, BOTTOM), "3 rows to the layer's top");
    }

    @Test
    void theCeilingBlockMustBeNaturalDebrisRockWithNoFluidRoundIt() {
        int c = FEET + 20;
        assertNull(search(cave(20).set(0, c, 0, ROCK | HELD), FEET + 2, TOP, BOTTOM), "a block someone set");
        assertNull(search(cave(20).set(0, c, 0, PLAIN), FEET + 2, TOP, BOTTOM), "not in #quake_debris");
        assertNull(search(cave(20).set(0, c, 0, ROCK | WATERLOGGABLE), FEET + 2, TOP, BOTTOM), "a block that could hold water");
        assertNull(search(cave(20).set(1, c, 0, LAVA_SOURCE), FEET + 2, TOP, BOTTOM), "lava beside it");
        assertNull(search(cave(20).set(0, c, -1, LAVA_FLOWING), FEET + 2, TOP, BOTTOM), "a fall beside it");
        assertNull(search(cave(20).set(0, c + 1, 0, WATER_SOURCE), FEET + 2, TOP, BOTTOM), "water over it");
        assertNull(search(cave(20).set(-1, c, 0, UNKNOWN), FEET + 2, TOP, BOTTOM), "a neighbour not loaded");
        assertNotNull(search(cave(20).set(1, c - 1, 0, LAVA_SOURCE), FEET + 2, TOP, BOTTOM), "lava beside the cell under it is not next to it");
    }

    @Test
    void itLandsThroughAirGlowAndLava() {
        // a pool three deep on the floor, the glow over it: the block sinks to the pool's bed and lands in its lowest source
        Column c = cave(30).fill(FEET, FEET + 2, LAVA_SOURCE).set(0, FEET + 5, 0, GLOW);
        Found f = search(c, FEET + 6, TOP, BOTTOM);
        assertNotNull(f);
        assertEquals(FEET, f.landY());
        assertEquals(FEET + 2, f.lavaY(), "the lava's top row");
        // the sea's lava over the floor: the same
        Column sea = cave(30).fill(FEET, FEET + 9, LAVA_SOURCE);
        Found g = search(sea, FEET + 12, TOP, BOTTOM);
        assertNotNull(g);
        assertEquals(FEET, g.landY());
        assertEquals(FEET + 9, g.lavaY());
        // in a water source
        assertNotNull(search(cave(30).set(0, FEET, 0, WATER_SOURCE), FEET + 2, TOP, BOTTOM));
    }

    @Test
    void aRestCellThatWouldLoseTheBlockIsRefused() {
        assertNull(search(cave(20).set(0, FEET, 0, TORCH), FEET + 2, TOP, BOTTOM), "a torch where it would rest");
        assertNull(search(cave(20).set(0, FEET, 0, SLAB), FEET + 2, TOP, BOTTOM), "a bottom slab: it would rest in the slab's cell");
        // over a full block on the floor it rests on that block
        Found f = search(cave(20).set(0, FEET, 0, PLAIN), FEET + 2, TOP, BOTTOM);
        assertNotNull(f);
        assertEquals(FEET + 1, f.landY());
    }

    @Test
    void flowingLavaAtOrBesideTheRestCellIsRefused() {
        assertNull(search(cave(20).set(0, FEET, 0, LAVA_FLOWING), FEET + 2, TOP, BOTTOM), "flowing lava at the rest cell");
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            assertNull(search(cave(20).set(d[0], FEET, d[1], LAVA_FLOWING), FEET + 2, TOP, BOTTOM), "flowing lava beside the rest cell");
            assertNotNull(search(cave(20).set(d[0], FEET, d[1], LAVA_SOURCE), FEET + 2, TOP, BOTTOM), "a source beside it");
        }
        assertNotNull(search(cave(20).set(0, FEET, 0, LAVA_SOURCE), FEET + 2, TOP, BOTTOM), "a source at it");
    }

    @Test
    void theCheckAtTheDrop() {
        int c = FEET + 20;
        assertNotNull(at(cave(20), c, TOP, BOTTOM));
        assertNull(at(cave(20).set(0, c - 1, 0, PLAIN), c, TOP, BOTTOM), "no longer open under it");
        assertNull(at(cave(20).set(0, c, 0, ROCK | HELD), c, TOP, BOTTOM), "changed since");
        assertNull(at(cave(20).set(0, FEET, 0, TORCH), c, TOP, BOTTOM), "a torch put where it would rest");
        assertNull(at(cave(20), TOP + 1, TOP, BOTTOM), "over the layer");
    }

    @Test
    void fallTimes() {
        assertEquals(24, Fall.ticks(10));
        assertEquals(60, Fall.ticks(50));
        assertEquals(92, Fall.ticks(100));
        assertEquals(109, Fall.ticks(130));
        for (int h : new int[]{1, 10, 50, 100, 130}) {
            int t = Fall.ticks(h);
            assertTrue(Fall.y(t) >= h && Fall.y(t - 1) < h, "h " + h);
        }
        assertEquals(0, Fall.ticks(0));
    }

    @Test
    void theSearchIsPure() throws Exception {
        for (String name : new String[]{"DebrisSearch", "Fall"}) {
            String src = Files.readString(Path.of("src/main/java/com/explorationremix/world/quake/" + name + ".java"));
            assertFalse(src.contains("import net.minecraft") || src.contains("import com.mojang") || src.contains("import net.minecraftforge"),
                    name + " must stay free of game types: it is tested offline");
        }
    }
}
