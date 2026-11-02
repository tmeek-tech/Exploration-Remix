package com.explorationremix.world.terrain;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Where the Wasteland meets the Basalt Galleries (0.5.1, spec-wasteland-field 6 and 11.3): over the join's rows the two
 * fields are one density at the corners and in the per-block pass, the seal leaves no hole, and every opening goes
 * through with no lid. Blocks are made as the game makes them ({@link NoiseVox}). The wiring itself is
 * {@link NoiseLayersTest#joinIsOneDensity}.
 */
class JoinTest {
    /** Seeds and centres of the boxes (the Wasteland's measured boxes), 384 x 384 columns, rows -720..-660. */
    static final long[][] BOXES = {{1, 300, 300}, {2, -900, 1400}, {8004, 2000, -1500}};
    static final int SIZE = 384, YLO = -720, YHI = -660;

    /**
     * One density over 10^5 columns: at a random row of the band the corner density is exactly the smooth minimum of the
     * two fields' densities and the seal, and at the band's bottom and top rows it has the sign of the lone field there
     * (the Galleries' at -704, the Wasteland's at -673), so nothing changes where the band begins.
     */
    @Test
    void joinIsOneDensity() {
        Terrain t = new Terrain(TerrainSettings.defaults(), -64, 2);
        Join j = t.join;
        assertTrue(j.on);
        Random r = new Random(41);
        int bottomFlips = 0, topFlips = 0, wrong = 0;
        String first = null;
        for (int k = 0; k < 100_000; k++) {
            int x = r.nextInt(16000) - 8000, z = r.nextInt(16000) - 8000;
            int y = j.lo + r.nextInt(j.hi - j.lo + 1);
            double want = Math.max(Network.smin(t.wasteland.density(x, y, z), t.galleries.density(x, y, z), Join.K), j.seal(x, y, z));
            if (want != t.rawDensity(x, y, z)) { wrong++; if (first == null) first = x + " " + y + " " + z; }
            if ((t.rawDensity(x, j.lo, z) > 0) != (t.galleries.density(x, j.lo, z) > 0)) bottomFlips++;
            if ((t.rawDensity(x, j.hi, z) > 0) != (t.wasteland.density(x, j.hi, z) > 0)) topFlips++;
        }
        assertEquals(0, wrong, "corners off the combined density, first at " + first);
        assertEquals(0, bottomFlips, "columns whose band's bottom row differs from the Galleries' alone");
        assertEquals(0, topFlips, "columns whose band's top row differs from the Wasteland's alone");
    }

    /**
     * The per-block pass at the band's edges is each field's own: at the top row the Wasteland's, at the bottom row the
     * Galleries' (the other field's cores are empty there and the seal does not reach), whatever the corners give; and
     * just outside the band Terrain asks the field alone. So the blocks run on across both edges with no seam.
     */
    @Test
    void perBlockPassMeetsEachFieldsOwnAtTheEdges() {
        Terrain t = new Terrain(TerrainSettings.defaults(), -64, 8004);
        Join j = t.join;
        Random r = new Random(43);
        int differ = 0;
        String first = null;
        for (int k = 0; k < 100_000; k++) {
            int x = r.nextInt(16000) - 8000, z = r.nextInt(16000) - 8000;
            double coarse = r.nextDouble() * 2 - 1;
            double[][] pairs = {
                    {t.fine(x, j.hi, z, coarse), t.wasteland.fine(x, j.hi, z, coarse)},
                    {t.fine(x, j.hi + 1, z, coarse), t.wasteland.fine(x, j.hi + 1, z, coarse)},
                    {t.fine(x, j.lo, z, coarse), t.galleries.fine(x, j.lo, z, coarse)},
                    {t.fine(x, j.lo - 1, z, coarse), t.galleries.fine(x, j.lo - 1, z, coarse)}};
            for (int p = 0; p < pairs.length; p++) if (pairs[p][0] != pairs[p][1]) {
                differ++;
                if (first == null) first = "case " + p + " at " + x + " " + z + ": " + pairs[p][0] + " against " + pairs[p][1];
            }
        }
        assertEquals(0, differ, "per-block values at the band's edges unlike the lone field's; first " + first);
    }

    /** What a box of the join's rows shows. */
    static final class Box {
        long seed;
        int sealChecked, sealHoles, lavaGroundChecked, lavaGroundHoles, galleriesTopChecked, galleriesTopHoles;
        int openings, openingsThrough;
        int lidColumns, biggestLid;
        String holeAt = "", lidAt = "", shutAt = "";

        @Override public String toString() {
            return String.format(java.util.Locale.ROOT, "seed %d: seal %d holes in %d columns (lava ground %d in %d, Galleries' top %d in %d); openings %d, through %d%s;"
                            + " lid columns (open at the Galleries' top row under rock) %d, largest flat lid %d columns%s%s",
                    seed, sealHoles, sealChecked, lavaGroundHoles, lavaGroundChecked, galleriesTopHoles, galleriesTopChecked, openings, openingsThrough, shutAt,
                    lidColumns, biggestLid, lidAt, holeAt);
        }
    }

    static Box measure(long seed, int cx, int cz) {
        Terrain t = new Terrain(TerrainSettings.defaults(), -64, seed);
        Join j = t.join;
        int x0 = cx - SIZE / 2, z0 = cz - SIZE / 2;
        NoiseVox v = NoiseVox.build(t, x0, z0, SIZE, SIZE, YLO, YHI);
        Box b = new Box();
        b.seed = seed;
        assertEquals(0, v.nans, "NaN densities");
        StringBuilder holeAt = new StringBuilder();
        boolean[] lid = new boolean[SIZE * SIZE];
        boolean[] hole = new boolean[SIZE * SIZE];
        for (int i = 0; i < SIZE; i++) for (int k = 0; k < SIZE; k++) {
            int x = x0 + i, z = z0 + k;
            WastelandField.Col c = t.wasteland.column(x, z);
            // the seal's three parts, block by block
            if (c.beta < Join.HOLE_BETA && c.lavaDip <= 0) {
                b.sealChecked++;
                for (int y = j.wBot; y <= j.W + 1; y++) if (!v.rock(x, y, z)) {
                    b.sealHoles++;
                    if (b.sealHoles <= 3) holeAt.append(String.format(" (seal %d,%d,%d)", x, y, z));
                    break;
                }
            }
            if (c.beta < Join.LAVA_BETA) {
                b.lavaGroundChecked++;
                for (int y = j.wBot; y <= j.wBot + 3; y++) if (!v.rock(x, y, z)) {
                    b.lavaGroundHoles++;
                    if (b.lavaGroundHoles <= 3) holeAt.append(String.format(" (lava ground %d,%d,%d)", x, y, z));
                    break;
                }
            }
            if (c.beta < Join.CLOSED_BETA) {
                b.galleriesTopChecked++;
                for (int y = j.gTop - 3; y <= j.gTop; y++) if (!v.rock(x, y, z)) {
                    b.galleriesTopHoles++;
                    if (b.galleriesTopHoles <= 3) holeAt.append(String.format(" (Galleries' top %d,%d,%d)", x, y, z));
                    break;
                }
            }
            lid[i * SIZE + k] = !v.rock(x, j.gTop, z) && v.rock(x, j.wBot, z);
            if (lid[i * SIZE + k]) b.lidColumns++;
            hole[i * SIZE + k] = WastelandFieldTest.holeColumn(v, j.wBot, x, z);
        }
        b.holeAt = holeAt.toString();
        // flat lids: connected columns open at the Galleries' top row under rock at the Wasteland's bottom row
        b.biggestLid = 0;
        int[] lab = new int[SIZE * SIZE];
        Arrays.fill(lab, -1);
        for (int p0 = 0; p0 < SIZE * SIZE; p0++) {
            if (!lid[p0] || lab[p0] >= 0) continue;
            int n = flood(lid, lab, p0);
            if (n > b.biggestLid) { b.biggestLid = n; b.lidAt = String.format(" at (%d,%d)", x0 + p0 / SIZE, z0 + p0 % SIZE); }
        }
        // openings: 4 columns or more of hole columns; each must reach down into the Galleries' open space (no lid)
        Arrays.fill(lab, -1);
        StringBuilder shut = new StringBuilder();
        for (int p0 = 0; p0 < SIZE * SIZE; p0++) {
            if (!hole[p0] || lab[p0] >= 0) continue;
            int n = flood(hole, lab, p0);
            if (n < 4) continue;
            b.openings++;
            boolean through = false;
            for (int p = 0; p < SIZE * SIZE && !through; p++) {
                if (lab[p] != p0) continue;
                int x = x0 + p / SIZE, z = z0 + p % SIZE, y = j.wBot + 8;
                while (y > YLO && !v.rock(x, y - 1, z)) y--;
                if (y <= j.gTop - 6) through = true;
            }
            if (through) b.openingsThrough++;
            else if (shut.length() < 80) shut.append(String.format(" (shut %d,%d)", x0 + p0 / SIZE, z0 + p0 % SIZE));
        }
        b.shutAt = shut.toString();
        return b;
    }

    /** Labels the 4-connected set of {@code on} columns holding p0 with p0; returns its size. */
    static int flood(boolean[] on, int[] lab, int p0) {
        ArrayDeque<Integer> q = new ArrayDeque<>();
        q.add(p0);
        lab[p0] = p0;
        int n = 0;
        while (!q.isEmpty()) {
            int p = q.poll(), i = p / SIZE, k = p % SIZE;
            n++;
            for (int d = 0; d < 4; d++) {
                int a = i + NoiseVox.DX[d], c = k + NoiseVox.DZ[d];
                if (a < 0 || c < 0 || a >= SIZE || c >= SIZE) continue;
                int pp = a * SIZE + c;
                if (on[pp] && lab[pp] < 0) { lab[pp] = p0; q.add(pp); }
            }
        }
        return n;
    }

    /**
     * In blocks made as the game makes them, over three boxes: the seal has no hole (no open block from the Wasteland's
     * bottom to a row over its lava table where beta is under {@link Join#HOLE_BETA} but for the lava pools' dips, none in
     * the 4 rows over its bottom under lava ground, none in the Galleries' top 4 rows under {@link Join#CLOSED_BETA});
     * every opening (4 columns or more open from 2 under the Wasteland's bottom to 8 over it) runs on down into the
     * Galleries' space, to 6 rows under their top at least, so no lid closes it; and no flat lid lies at the join line
     * itself: where the Galleries' top row is open under the Wasteland's bottom row of rock, it is in patches of a few
     * columns (the edges of openings), never a plane.
     */
    @Test
    void sealedOrOpenNeverLidded() {
        int openings = 0;
        StringBuilder all = new StringBuilder();
        for (long[] bx : BOXES) {
            Box b = measure(bx[0], (int) bx[1], (int) bx[2]);
            System.out.println("JoinTest box " + b);
            all.append(b).append('\n');
            assertTrue(b.sealChecked > 50_000, "too few sealed columns: " + b);
            assertEquals(0, b.sealHoles, "holes in the seal: " + b);
            assertEquals(0, b.lavaGroundHoles, "holes under lava ground: " + b);
            assertEquals(0, b.galleriesTopHoles, "holes in the Galleries' top rows: " + b);
            assertEquals(b.openings, b.openingsThrough, "openings with a lid under them: " + b);
            assertTrue(b.biggestLid <= 64, "a flat lid at the join line: " + b);
            openings += b.openings;
        }
        assertTrue(openings >= 10, "too few openings to check:\n" + all);
    }
}
