package com.explorationremix.world.eruption;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static com.explorationremix.world.eruption.Crater.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * What a lava bomb's blast does (0.9.0, spec 5.4.4 to 5.4.6), on a voxel box: with {@code breaks} the natural blocks and
 * the held Galleries rock go to the crater, players' other blocks to the game's blast, nothing outside the layer, no ore,
 * nothing next to a fluid; the magma rule; the ejecta only from debris rock, nearest the surface, from the cell one out
 * along the face; without {@code breaks} (the blast's {@code NONE}: the way guard, the reset off) nothing at all; and the
 * strike's numbers are the game's, scaled by the config's shares.
 */
class CraterTest {
    /** The box: 16 x 16 x 16, the layer's top at row 11 (rows 12 and up are outside the Galleries). */
    static final int N = 16, LAYER_TOP = 11;

    /** A box of cells: natural Galleries rock (debris rock) at rows 0 to 7, open over it; changes set by the cases. */
    static final class Box implements Cells {
        final int[][][] c = new int[N][N][N];

        Box() {
            for (int x = 0; x < N; x++) {
                for (int y = 0; y < N; y++) {
                    for (int z = 0; z < N; z++) c[x][y][z] = (y <= LAYER_TOP ? IN_LAYER : 0) | (y <= 7 ? ROCK | DEBRIS | SOLID : AIR);
                }
            }
        }

        void put(int x, int y, int z, int bits) { c[x][y][z] = (y <= LAYER_TOP ? IN_LAYER : 0) | bits; }

        @Override
        public int at(int x, int y, int z) {
            if (x < 0 || y < 0 || z < 0 || x >= N || y >= N || z >= N) return SOLID;
            return c[x][y][z];
        }
    }

    /**
     * The cells the blast's rays reach round (cx, cy, cz): every cell whose middle is within {@code r} (a stand-in for the
     * rays), less those holding a fluid, whose resistance (100) keeps them out of the game's list.
     */
    static List<int[]> reach(Box b, double cx, double cy, double cz, double r) {
        List<int[]> out = new ArrayList<>();
        for (int x = 0; x < N; x++) {
            for (int y = 0; y < N; y++) {
                for (int z = 0; z < N; z++) {
                    double dx = x + 0.5 - cx, dy = y + 0.5 - cy, dz = z + 0.5 - cz;
                    if (dx * dx + dy * dy + dz * dz <= r * r && (b.at(x, y, z) & FLUID) == 0) out.add(new int[]{x, y, z});
                }
            }
        }
        return out;
    }

    /** The partition of {@code reach} with {@code breaks}: [crater, vanilla, not touched]. */
    static List<List<int[]>> partition(Box b, List<int[]> reach, boolean breaks) {
        List<List<int[]>> out = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        for (int[] p : reach) out.get(part(b.at(p[0], p[1], p[2]), breaks)).add(p);
        return out;
    }

    static boolean has(List<int[]> l, int x, int y, int z) {
        for (int[] p : l) if (p[0] == x && p[1] == y && p[2] == z) return true;
        return false;
    }

    @Test
    void withBreaksTheNaturalRockGoesToTheCraterAndPlayersBlocksToTheGame() {
        Box b = new Box();
        b.put(9, 8, 8, HELD | SOLID);                        // a player's plank on the floor
        b.put(7, 8, 8, HELD | ROCK | DEBRIS | SOLID);        // a player's basalt (or landed debris)
        b.put(8, 6, 10, ROCK | SOLID | KEEP);                // an ore in the rock
        b.put(8, 8, 9, GLOW);                                // the glow over the floor (natural)
        b.put(10, 7, 8, HELD | AIR);                         // a hole a player dug
        List<int[]> reach = reach(b, 8.5, 8.1, 8.5, 3.2);
        List<List<int[]>> p = partition(b, reach, true);
        List<int[]> crater = p.get(CRATER), vanilla = p.get(VANILLA), not = p.get(NOT);
        assertTrue(has(crater, 8, 7, 8) && has(crater, 8, 6, 8) && has(crater, 7, 7, 7), "the natural rock");
        assertTrue(has(crater, 7, 8, 8), "a held block of the Galleries' rock");
        assertTrue(has(crater, 8, 8, 9), "the glow");
        assertTrue(has(vanilla, 9, 8, 8), "a player's other block is the game's");
        assertTrue(has(vanilla, 10, 7, 8) && has(vanilla, 8, 9, 8), "air is left to the game, which skips it");
        assertTrue(has(not, 8, 6, 10), "an ore is never touched");
        for (int[] q : crater) assertTrue((b.at(q[0], q[1], q[2]) & AIR) == 0 && q[1] <= LAYER_TOP);
    }

    @Test
    void nothingOutsideTheLayer() {
        Box b = new Box();
        // a blast just under the layer's top, against a ceiling that runs up out of it
        for (int x = 0; x < N; x++) for (int z = 0; z < N; z++) for (int y = 10; y < N; y++) b.put(x, y, z, ROCK | DEBRIS | SOLID);
        List<List<int[]>> p = partition(b, reach(b, 8.5, 9.9, 8.5, 3.5), true);
        assertFalse(p.get(CRATER).isEmpty());
        for (int[] q : p.get(CRATER)) assertTrue(q[1] <= LAYER_TOP, "a crater block out of the layer at " + q[1]);
        for (int[] q : p.get(NOT)) assertTrue(q[1] > LAYER_TOP);
        assertFalse(p.get(NOT).isEmpty(), "the rows over the layer are taken out of the list");
    }

    @Test
    void noBlockNextToAFluidIsRemoved() {
        Box b = new Box();
        // a lava pool cell beside the crater's middle, and the lake's lava under one corner
        b.put(9, 7, 8, FLUID);
        b.put(7, 5, 8, FLUID);
        List<int[]> crater = partition(b, reach(b, 8.5, 8.1, 8.5, 3.2), true).get(CRATER);
        List<int[]> removed = removed(crater, b);
        assertFalse(removed.isEmpty());
        assertTrue(removed.size() < crater.size());
        for (int[] q : removed) {
            for (int[] d : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
                assertEquals(0, b.at(q[0] + d[0], q[1] + d[1], q[2] + d[2]) & FLUID, "removed next to a fluid at " + q[0] + " " + q[1] + " " + q[2]);
            }
        }
        assertFalse(has(removed, 8, 7, 8), "the wall holding the pool stays");
        assertFalse(has(removed, 7, 6, 8), "the roof of the lake stays");
        assertTrue(has(removed, 7, 7, 8));
    }

    @Test
    void magmaAtTheBottomOfTheMiddleColumnOnSolidRockOnly() {
        Box b = new Box();
        List<int[]> removed = removed(partition(b, reach(b, 8.5, 8.1, 8.5, 3.2), true).get(CRATER), b);
        int m = magma(removed, 8, 8, b);
        assertTrue(m >= 0);
        int[] q = removed.get(m);
        assertEquals(8, q[0]);
        assertEquals(8, q[2]);
        for (int[] o : removed) if (o[0] == 8 && o[2] == 8) assertTrue(o[1] >= q[1], "the lowest of the column");
        assertTrue((b.at(8, q[1] - 1, 8) & SOLID) != 0);
        // over a cave (open under the crater's bottom), or lava under it: none
        Box hollow = new Box();
        hollow.put(8, 4, 8, AIR);
        hollow.put(8, 3, 8, AIR);
        List<int[]> r2 = removed(partition(hollow, reach(hollow, 8.5, 8.1, 8.5, 3.2), true).get(CRATER), hollow);
        int lowest = Integer.MAX_VALUE;
        for (int[] o : r2) if (o[0] == 8 && o[2] == 8) lowest = Math.min(lowest, o[1]);
        if (lowest == 5) assertEquals(-1, magma(r2, 8, 8, hollow), "no magma over a hollow");
        Box wet = new Box();
        wet.put(8, 4, 8, FLUID | SOLID);
        List<int[]> r3 = removed(partition(wet, reach(wet, 8.5, 8.1, 8.5, 3.2), true).get(CRATER), wet);
        for (int[] o : r3) assertFalse(o[0] == 8 && o[1] == 5 && o[2] == 8, "next to lava: not removed");
        // no removed block in the middle column (a wall crater's middle is in the open): none
        assertEquals(-1, magma(removed, 0, 0, b));
    }

    @Test
    void ejectaAreDebrisRockNearestTheSurface() {
        Box b = new Box();
        b.put(8, 8, 9, GLOW);
        b.put(7, 7, 9, ROCK | SOLID);                    // magma or a slab: Galleries rock that is no debris
        b.put(9, 7, 9, ROCK | SOLID | FLUID);            // waterlogged
        List<int[]> removed = removed(partition(b, reach(b, 8.5, 8.1, 8.5, 3.2), true).get(CRATER), b);
        int m = magma(removed, 8, 8, b);
        int[] ej = ejecta(removed, b, m, 8.5, 8.1, 8.5, 0, 1, 0, 3);
        assertEquals(3, ej.length);
        int topRow = Integer.MIN_VALUE;
        for (int[] q : removed) if ((b.at(q[0], q[1], q[2]) & DEBRIS) != 0) topRow = Math.max(topRow, q[1]);
        for (int i : ej) {
            int[] q = removed.get(i);
            assertNotEquals(m, i, "never the magma");
            assertTrue((b.at(q[0], q[1], q[2]) & DEBRIS) != 0, "only debris rock");
            assertEquals(0, b.at(q[0], q[1], q[2]) & (GLOW | FLUID));
            assertEquals(topRow, q[1], "nearest the surface");
        }
        // the first is the one under the centre
        assertArrayEquals(new int[]{8, 7, 8}, removed.get(ej[0]));
        // a wall struck facing +x: the shallowest along the normal first
        Box wall = new Box();
        for (int x = 0; x < N; x++) for (int y = 0; y < N; y++) for (int z = 0; z < N; z++) wall.put(x, y, z, x <= 7 && y <= LAYER_TOP ? ROCK | DEBRIS | SOLID : AIR);
        List<int[]> wr = removed(partition(wall, reach(wall, 8.1, 9.5, 8.5, 3.2), true).get(CRATER), wall);
        int[] wj = ejecta(wr, wall, -1, 8.1, 9.5, 8.5, 1, 0, 0, 3);
        for (int i : wj) assertEquals(7, wr.get(i)[0], "the wall's face");
        // none asked: none
        assertEquals(0, ejecta(removed, b, m, 8.5, 8.1, 8.5, 0, 1, 0, 0).length);
    }

    @Test
    void ejectaGoOutAndUp() {
        Random r = new Random(4);
        double[][] normals = {{0, 1, 0}, {1, 0, 0}, {0, 0, -1}, {0, -1, 0}};
        for (double[] n : normals) {
            for (int i = 0; i < 500; i++) {
                double[] v = ejectaVelocity(n[0], n[1], n[2], r);
                assertTrue(v[1] >= 0.25, "at least 0.25 up");
                double along = v[0] * n[0] + v[1] * n[1] + v[2] * n[2];
                if (n[1] >= 0) assertTrue(along >= 0.3 - 1e-9 && along <= 0.5 + 1e-9, "along the normal " + along);
                double sx = v[0] - along * n[0], sy = v[1] - along * n[1], sz = v[2] - along * n[2];
                double side = Math.sqrt(sx * sx + sy * sy + sz * sz);
                if (n[1] == 0) assertTrue(side >= 0.2 - 1e-9, "across: " + side);
                if (n[1] == 1) assertTrue(side >= 0.2 - 1e-9 && side <= 0.4 + 1e-9, "across: " + side);
            }
        }
        // the cell one step out along the face struck
        assertArrayEquals(new int[]{8, 8, 8}, ejectaCell(8, 7, 8, 0, 1, 0));
        assertArrayEquals(new int[]{9, 9, 8}, ejectaCell(8, 9, 8, 1, 0, 0));
    }

    @Test
    void withoutBreaksNothingAtAll() {
        // the blast's NONE (the reset off, the lava cycle off, or a way down near): the handler leaves the list to the
        // game, which clears it; nothing to the crater, so nothing removed, no magma and no ejecta
        Box b = new Box();
        b.put(7, 8, 8, HELD | ROCK | DEBRIS | SOLID);
        b.put(8, 8, 9, GLOW);
        List<List<int[]>> p = partition(b, reach(b, 8.5, 8.1, 8.5, 3.2), false);
        assertTrue(p.get(CRATER).isEmpty(), "NONE gives no crater");
        assertTrue(p.get(NOT).isEmpty());
        List<int[]> removed = removed(p.get(CRATER), b);
        assertTrue(removed.isEmpty());
        assertEquals(-1, magma(removed, 8, 8, b));
        assertEquals(0, ejecta(removed, b, -1, 8.5, 8.1, 8.5, 0, 1, 0, 3).length);
        // the box is as it was
        Box fresh = new Box();
        fresh.put(7, 8, 8, HELD | ROCK | DEBRIS | SOLID);
        fresh.put(8, 8, 9, GLOW);
        for (int x = 0; x < N; x++) for (int y = 0; y < N; y++) for (int z = 0; z < N; z++) assertEquals(fresh.at(x, y, z), b.at(x, y, z));
    }

    @Test
    void theStrikeIsTheGamesScaledByTheShares() {
        // power 3 (a creeper's), fully exposed: the game's numbers, and the table of spec 5.4.6 at bombDamage 0.6
        double[] game = {43, 33, 24, 16, 10, 5, 1};
        double[] table = {25.8, 19.8, 14.4, 9.6, 6.0, 3.0, 0.6};
        for (int d = 0; d <= 6; d++) {
            double q = exposure(d, 3, 1);
            assertEquals(game[d], damage(q, 3), "at " + d + " blocks");
            assertEquals(table[d], damage(q, 3) * 0.6, 1e-9, "at " + d + " blocks, bombDamage 0.6");
            // the push at bombKnockback 1, before Blast Protection: (1 - d / 6) x exposure blocks a tick
            assertEquals(1 - d / 6.0, q, 1e-12);
        }
        assertEquals(-1, exposure(6.01, 3, 1), "beyond twice the power: not struck");
        // half hidden: half the exposure
        assertEquals(0.25, exposure(3, 3, 0.5), 1e-12);
        assertEquals(1, damage(0, 3), "within reach but unseen: the game's 1");
        // a weaker blast reaches less far
        assertEquals(-1, exposure(4.5, 2, 1));
        assertEquals(29, damage(exposure(0, 2, 1), 2));
    }
}
