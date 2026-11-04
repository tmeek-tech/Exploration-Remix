package com.explorationremix.world.terrain;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Where the Wasteland meets the Basalt Galleries (0.5.1, spec-wasteland-field 6 and 11.3, spec-galleries-field-v2 7):
 * over the join's rows the two fields are one density at the corners and in the per-block pass, the seal leaves no hole,
 * every opening goes through with no lid, the Galleries' openings are the Wasteland's own dips, and the two layers are
 * one walk both ways: down and back up through the craters and ramps of the holes the Galleries open with a ramp. Blocks
 * are made as the game makes them ({@link NoiseVox}). The wiring itself is {@link NoiseLayersTest#joinIsOneDensity}.
 */
class JoinTest {
    /** Seeds and centres of the boxes (the Wasteland's measured boxes), 384 x 384 columns, rows -720..-660. */
    static final long[][] BOXES = {{1, 300, 300}, {2, -900, 1400}, {8004, 2000, -1500}};
    static final int SIZE = 384, YLO = -720, YHI = -660;

    /**
     * One density over 10^5 columns (50 areas of 512 x 512 anywhere in 16 000 x 16 000, {@link Spread}): at a random row
     * of the band the corner density is exactly the smooth minimum of the two fields' densities and the seal, and at the
     * band's bottom and top rows it has the sign of the lone field there (the Galleries' at -704, the Wasteland's at
     * -673), so nothing changes where the band begins.
     */
    @Test
    void joinIsOneDensity() {
        Terrain t = new Terrain(TerrainSettings.defaults(), -64, 2);
        Join j = t.join;
        assertTrue(j.on);
        Random r = new Random(41);
        Spread spread = new Spread(r, 16000, 512, 2000);
        int bottomFlips = 0, topFlips = 0, wrong = 0;
        String first = null;
        for (int k = 0; k < 100_000; k++) {
            int[] at = spread.next();
            int x = at[0], z = at[1];
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
     * just outside the band Terrain asks the field alone. So the blocks run on across both edges with no seam (10^5
     * columns, 50 areas of 512 x 512 anywhere in 16 000 x 16 000).
     */
    @Test
    void perBlockPassMeetsEachFieldsOwnAtTheEdges() {
        Terrain t = new Terrain(TerrainSettings.defaults(), -64, 8004);
        Join j = t.join;
        Random r = new Random(43);
        Spread spread = new Spread(r, 16000, 512, 2000);
        int differ = 0;
        String first = null;
        for (int k = 0; k < 100_000; k++) {
            int[] at = spread.next();
            int x = at[0], z = at[1];
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
        String variant = "";
        int sealChecked, sealHoles, lavaGroundChecked, lavaGroundHoles, galleriesTopChecked, galleriesTopHoles;
        /** Areas of hole columns: those holding a throat (openings) and going through; those outside the craters; those with no throat (a crater's bowl cut off from its hole). */
        int openings, openingsThrough, outsideCraters, pockets;
        /** The holes the Galleries open with middles 16 blocks or more inside the box, and those whose hole area goes through; with middles 48 or more inside, those walked and those with a way down to their ramp's deck and back up. */
        int gHoles, gHolesThrough, gWalked, gWays;
        int lidColumns, biggestLid;
        /** Dip columns (the Wasteland's floor falls through), those the guard cuts short, the opening sites' columns with the guard open at all, and the deepest pit of a dip that does not go through. */
        int dipColumns, partialDips, guardOpen, deepestPit, craterColumns;
        String holeAt = "", lidAt = "", shutAt = "", noWayAt = "";

        @Override public String toString() {
            return String.format(java.util.Locale.ROOT, "seed %d%s: seal %d holes in %d columns (lava ground %d in %d, Galleries' top %d in %d); openings %d (outside the craters %d), through %d%s, bowl pockets %d;"
                            + " the Galleries' holes %d, through %d, walked %d, with a way down and up %d%s;"
                            + " lid columns (open at the Galleries' top row under rock) %d, largest flat lid %d columns%s; dip columns %d (partly guarded %d, deepest pit %d), guard open at %d site columns; crater columns %d%s",
                    seed, variant, sealHoles, sealChecked, lavaGroundHoles, lavaGroundChecked, galleriesTopHoles, galleriesTopChecked, openings, outsideCraters, openingsThrough, shutAt, pockets,
                    gHoles, gHolesThrough, gWalked, gWays, noWayAt,
                    lidColumns, biggestLid, lidAt, dipColumns, partialDips, deepestPit, guardOpen, craterColumns, holeAt);
        }
    }

    static Box measure(long seed, int cx, int cz) {
        return measure(new Terrain(TerrainSettings.defaults(), -64, seed), seed, cx, cz, false);
    }

    /** {@link #sealedOrOpenNeverLidded}'s box of terrain t round (cx, cz); with {@code walk}, the ways round the Galleries' holes too. */
    static Box measure(Terrain t, long seed, int cx, int cz, boolean walk) {
        Join j = t.join;
        GalleriesField g = t.galleries;
        int T = j.gTop, x0 = cx - SIZE / 2, z0 = cz - SIZE / 2;
        NoiseVox v = NoiseVox.build(t, x0, z0, SIZE, SIZE, YLO, YHI);
        if (walk) v.graph();
        Box b = new Box();
        b.seed = seed;
        assertEquals(0, v.nans, "NaN densities");
        StringBuilder holeAt = new StringBuilder();
        boolean[] lid = new boolean[SIZE * SIZE];
        boolean[] hole = new boolean[SIZE * SIZE], throat = new boolean[SIZE * SIZE], crater = new boolean[SIZE * SIZE], dip = new boolean[SIZE * SIZE];
        int[] floor = new int[SIZE * SIZE];
        for (int i = 0; i < SIZE; i++) for (int k = 0; k < SIZE; k++) {
            int x = x0 + i, z = z0 + k, q = i * SIZE + k;
            WastelandField.Col c = t.wasteland.column(x, z);
            // the floor a fall from 8 rows over the Wasteland's bottom ends on (its first open row over rock)
            int y0 = j.wBot + 8;
            while (y0 > YLO && !v.rock(x, y0 - 1, z)) y0--;
            floor[q] = y0;
            // a dip the guard cuts short where the floor falls through (its weight before the guard 0.1 or more: the
            // fringe under that lowers the floor by less than a row), and the guard open at all over an opening site
            double site = t.breakthrough.openingSite(x, z);
            if (site >= 0.1 && c.under > 0) b.guardOpen++;
            if (c.holeDip > 0) { b.dipColumns++; dip[q] = true; if (c.under < 1 && site >= 0.1) b.partialDips++; }
            hole[q] = WastelandFieldTest.holeColumn(v, j.wBot, x, z);
            throat[q] = hole[q] && WastelandFieldTest.throatColumn(v, j.wBot, x, z);
            // the seal's three parts, block by block; none in the craters round the holes with ramps (Join#seal)
            if (c.crater > 0) {
                b.craterColumns++;
                crater[q] = true;
                lid[q] = !v.rock(x, j.gTop, z) && v.rock(x, j.wBot, z);
                continue;
            }
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
            lid[q] = !v.rock(x, j.gTop, z) && v.rock(x, j.wBot, z);
            if (lid[q]) b.lidColumns++;
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
        // openings: areas of 4 columns or more of hole columns that hold a throat (a crater's bowl down to its floor at
        // T - 5 and the hole in its middle, also measured as ways down and up in waysDownAndUp; every hole the Galleries
        // open has a ramp and a crater, so none lies outside the craters); each must reach down into the Galleries' open
        // space (no lid). An area with no throat is a pocket of a crater's bowl (its floor 2-5 rows under the
        // Wasteland's bottom, on rock, cut off from its hole by the Wasteland's own rock): counted, never an opening.
        Arrays.fill(lab, -1);
        StringBuilder shut = new StringBuilder();
        for (int p0 = 0; p0 < SIZE * SIZE; p0++) {
            if (!hole[p0] || lab[p0] >= 0) continue;
            int n = flood(hole, lab, p0);
            if (n < 4) continue;
            boolean through = false, inCrater = false, hasThroat = false;
            for (int p = 0; p < SIZE * SIZE; p++) {
                if (lab[p] != p0) continue;
                inCrater |= crater[p];
                hasThroat |= throat[p];
                through |= floor[p] <= j.gTop - 6;
            }
            if (!inCrater) b.outsideCraters++;
            if (!hasThroat) { b.pockets++; continue; }
            b.openings++;
            if (through) b.openingsThrough++;
            else if (shut.length() < 80) shut.append(String.format(" (shut %d,%d)", x0 + p0 / SIZE, z0 + p0 % SIZE));
        }
        b.shutAt = shut.toString();
        // dips that do not go through: the deepest pit (the lowest floor of the footprint under its edge's lowest floor)
        int[] dlab = new int[SIZE * SIZE];
        Arrays.fill(dlab, -1);
        for (int p0 = 0; p0 < SIZE * SIZE; p0++) {
            if (!dip[p0] || dlab[p0] >= 0) continue;
            flood(dip, dlab, p0);
            int low = Integer.MAX_VALUE, edge = Integer.MAX_VALUE;
            boolean through = false;
            for (int p = 0; p < SIZE * SIZE; p++) {
                if (dlab[p] != p0) continue;
                through |= floor[p] <= j.gTop - 6;
                low = Math.min(low, floor[p]);
                int i = p / SIZE, k = p % SIZE;
                for (int d = 0; d < 4; d++) {
                    int a = i + NoiseVox.DX[d], c = k + NoiseVox.DZ[d];
                    if (a >= 0 && c >= 0 && a < SIZE && c < SIZE && !dip[a * SIZE + c]) edge = Math.min(edge, floor[a * SIZE + c]);
                }
            }
            if (!through && edge < Integer.MAX_VALUE) b.deepestPit = Math.max(b.deepestPit, edge - low);
        }
        // the holes the Galleries open: each one's hole area goes through, and (walk) a way down from the Wasteland's
        // tunnels round it (places 12-30 blocks from its middle) to its ramp's deck 12 rows or more under the top, and back
        List<GalleriesField.Opening> ops = new ArrayList<>();
        for (int i = Math.floorDiv(x0, 64) - 1; i <= Math.floorDiv(x0 + SIZE, 64) + 1; i++)
            for (int k = Math.floorDiv(z0, 64) - 1; k <= Math.floorDiv(z0 + SIZE, 64) + 1; k++) ops.addAll(Arrays.asList(g.openings(i, k)));
        StringBuilder noWay = new StringBuilder();
        for (GalleriesField.Opening o : ops) {
            if (!(o.x - 16 > x0 && o.x + 16 < x0 + SIZE && o.z - 16 > z0 && o.z + 16 < z0 + SIZE)) continue;
            b.gHoles++;
            int pm = ((int) Math.floor(o.x) - x0) * SIZE + ((int) Math.floor(o.z) - z0);
            boolean through = false;
            if (hole[pm]) for (int p = 0; p < SIZE * SIZE && !through; p++) through = lab[p] == lab[pm] && floor[p] <= j.gTop - 6;
            if (through) b.gHolesThrough++;
            else if (noWay.length() < 160) noWay.append(String.format(java.util.Locale.ROOT, " (hole %.0f,%.0f shut)", o.x, o.z));
            if (!walk || !(o.x - 48 > x0 && o.x + 48 < x0 + SIZE && o.z - 48 > z0 && o.z + 48 < z0 + SIZE)) continue;
            b.gWalked++;
            GalleriesField.Ramp rp = o.ramp;
            double t0 = o.r + GalleriesField.LIP;
            boolean[] from = new boolean[v.places], to = new boolean[v.places], region = new boolean[v.places];
            for (int p = 0; p < v.places; p++) {
                double dx = v.px(p) + 0.5 - o.x, dz = v.pz(p) + 0.5 - o.z, d = Math.hypot(dx, dz);
                if (d > o.r + 72) continue;
                region[p] = true;
                int y = v.py(p);
                if (y >= j.wBot + 2 && d >= o.r + 12 && d <= o.r + 30) from[p] = true;
                double tt = dx * rp.ux + dz * rp.uz, l = dx * rp.uz - dz * rp.ux;
                if (y <= T - 12 && tt >= t0 && tt <= rp.tEnd && Math.abs(l - g.bend(rp, t0, tt)) < rp.hw) {
                    int deck = (int) Math.floor(g.headTop - rp.s * Math.max(0, tt - t0) + g.deckTerrace(v.px(p) + 0.5, v.pz(p) + 0.5, tt - t0) + 0.5);
                    if (y >= deck - GalleriesField.EDGE_LOW - 1 && y <= deck + 1) to[p] = true;
                }
            }
            boolean dn = reach(v, from, to, region, NoiseVox.MAX_DROP), up = dn && reach(v, to, from, region, NoiseVox.MAX_DROP);
            if (dn && up) b.gWays++;
            else if (noWay.length() < 160) noWay.append(String.format(java.util.Locale.ROOT, " (hole %.0f,%.0f: down %b, up %b)", o.x, o.z, dn, up));
        }
        b.noWayAt = noWay.toString();
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
     * In blocks made as the game makes them, over three boxes: outside the craters the seal has no hole (no open block
     * from the Wasteland's bottom to a row over its lava table where beta is under {@link Join#HOLE_BETA} but for the lava
     * pools' dips, none in the 4 rows over its bottom under lava ground, none in the Galleries' top 4 rows under
     * {@link Join#CLOSED_BETA}); every opening (4 columns or more open from 2 under the Wasteland's bottom to 8 over it,
     * holding a throat, {@link WastelandFieldTest#throatColumn}: a crater's bowl with the hole in its middle; every hole
     * the Galleries open keeps a ramp and a crater, so none lies outside the craters) runs on down into the Galleries'
     * space, to 6 rows under their top at least, so no lid closes it, and so does every hole the Galleries open (the
     * area round its middle); areas of such columns with no throat are pockets of a crater's bowl, on rock 2-5 rows
     * under the Wasteland's bottom and cut off from the hole by its own rock (seed 1 has one of 5 columns; they lie in
     * the craters too); no flat lid lies at the join line itself: where the Galleries' top row is open under the
     * Wasteland's bottom row of rock, it is in patches of a few columns (the edges of openings, a crater's vault), never
     * a plane; and the guard cuts few dips short: at most 1 % of the columns where the Wasteland's floor falls through
     * (the dip's weight before the guard 0.1 or more) have the Galleries only partly open under them (W-ENG-2).
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
            assertEquals(b.gHoles, b.gHolesThrough, "holes the Galleries open that do not go through: " + b);
            assertEquals(0, b.outsideCraters, "holes outside the craters: " + b);
            assertTrue(b.biggestLid <= 64, "a flat lid at the join line: " + b);
            assertTrue(b.partialDips <= 0.01 * b.dipColumns + 2, "dips cut short by the guard: " + b);
            openings += b.openings;
        }
        assertTrue(openings >= 3, "too few openings to check:\n" + all);
    }

    /**
     * W-ENG-2's one-row check: with the Galleries' ramp decks moved a row up, then a row down, and then their discs' caps
     * moved likewise, against the Wasteland's craters (whose floors stay put), each of the three boxes still has no hole
     * in its seal, every opening goes through with no flat lid and lies in a crater, every hole the Galleries open goes
     * through and (those with middles 48 blocks or more inside the box) keeps a way down from the Wasteland's tunnels
     * round it (places 12-30 blocks from its middle) to its ramp's deck 12 rows or more under the top and back up (drops
     * of 15 at most, climbs of 1), no dip footprint has a pit of 4 rows or more, and at most 1 % of the dip columns are
     * cut short by the guard. (The decks' shift moves the ramps' candidates too, so a box may keep other ramps; the caps'
     * shift leaves every walk as it was.)
     */
    @Test
    void oneRowShiftsKeepTheJoin() {
        int[][] shifts = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        StringBuilder all = new StringBuilder();
        int walked = 0;
        for (int[] sh : shifts) for (long[] bx : BOXES) {
            Terrain t = new Terrain(TerrainSettings.defaults(), -64, bx[0], sh[0], sh[1]);
            Box b = measure(t, bx[0], (int) bx[1], (int) bx[2], true);
            b.variant = String.format(java.util.Locale.ROOT, " (decks %+d, caps %+d)", sh[0], sh[1]);
            System.out.println("JoinTest shifted box " + b);
            all.append(b).append('\n');
            assertEquals(0, b.sealHoles, "holes in the seal: " + b);
            assertEquals(0, b.lavaGroundHoles, "holes under lava ground: " + b);
            assertEquals(0, b.galleriesTopHoles, "holes in the Galleries' top rows: " + b);
            assertEquals(b.openings, b.openingsThrough, "openings with a lid under them: " + b);
            assertEquals(b.gHoles, b.gHolesThrough, "holes the Galleries open that do not go through: " + b);
            assertEquals(b.gWalked, b.gWays, "holes without a way down and back up: " + b);
            assertEquals(0, b.outsideCraters, "holes outside the craters: " + b);
            assertTrue(b.biggestLid <= 64, "a flat lid at the join line: " + b);
            assertTrue(b.deepestPit < 4, "a pit in a dip: " + b);
            assertTrue(b.partialDips <= 0.01 * b.dipColumns + 2, "dips cut short by the guard: " + b);
            walked += b.gWalked;
        }
        assertTrue(walked >= 12, "too few holes walked:\n" + all);
    }

    // ================================================================================================ one opening, two halves

    /**
     * The Galleries find the Wasteland's openings from {@link Breakthrough#openingSite}, and the Wasteland's dip is that
     * same weight times its guard (spec-galleries-field-v2 7.5, W-ENG-2): over three seeds, at every column within 24
     * blocks of an opening the Galleries find (opened or not) and at 20 000 columns anywhere (20 areas of 512 x 512 in
     * 8000 x 8000), the Wasteland's dip weight is exactly openingSite x under, bit for bit, so the two halves of an opening
     * can never drift apart. And the dip never falls (W-ENG-2's obligation (ii), with a row of margin): the Galleries'
     * space stops at their cap, 4 rows under their top (T - SEAL + 1 is its first rock row), and an opened disc's at its
     * crater's floor (T - 5), so their density is rock at gTop - 3 at every column, the guard is shut at every site, and
     * every opening is a crater's ({@link #sealedOrOpenNeverLidded}).
     */
    @Test
    void openingSiteIsThePreGuardDip() {
        int checked = 0, sites = 0, dips = 0, guardOpen = 0;
        for (long seed : new long[]{1, 2, 8004}) {
            Terrain t = new Terrain(TerrainSettings.defaults(), -64, seed);
            List<int[]> cols = new ArrayList<>();
            for (int i = -6; i < 6; i++) for (int k = -6; k < 6; k++)
                for (GalleriesField.Opening o : t.galleries.rawCell(i, k).ops)
                    for (int dx = -24; dx <= 24; dx++) for (int dz = -24; dz <= 24; dz++) cols.add(new int[]{(int) Math.floor(o.x) + dx, (int) Math.floor(o.z) + dz});
            Spread spread = new Spread(new Random(seed), 8000, 512, 1000);
            for (int q = 0; q < 20_000; q++) cols.add(spread.next());
            int wrong = 0;
            String first = null;
            for (int[] p : cols) {
                WastelandField.Col c = t.wasteland.column(p[0], p[1]);
                double site = t.breakthrough.openingSite(p[0], p[1]);
                if (site > 0) { sites++; if (c.under > 0) guardOpen++; }
                if (c.holeDip > 0) dips++;
                if (c.holeDip != site * c.under) { wrong++; if (first == null) first = p[0] + " " + p[1] + ": " + c.holeDip + " against " + site + " x " + c.under; }
                checked++;
            }
            assertEquals(0, wrong, "seed " + seed + ": dips unlike the opening site times the guard, first at " + first);
        }
        System.out.printf(java.util.Locale.ROOT, "JoinTest opening sites: %d columns, %d with a site, %d with the guard open, %d dipping%n", checked, sites, guardOpen, dips);
        assertTrue(sites > 5000, "too few sites to check: " + sites);
        assertEquals(0, guardOpen, "sites with the guard open (the Galleries open at gTop - 3)");
        assertEquals(0, dips, "dips falling through");
    }

    /**
     * The Galleries never open over their top row (spec-galleries-field-v2 7.3-7.4): every hole they open keeps a ramp and
     * opens only up to its crater's floor (T - 5), so over 4 x 4 lattice cells round openings of three seeds no column of
     * theirs is open at a row over T (their own per-block density, rows T + 1..T + 9); and their rows T - 6..T are rock at
     * every column whose cell's site lies in no disc, under no ramp's head room and in no tube mouth.
     */
    @Test
    void galleriesStayUnderTheirTop() {
        long[][] areas = {{1, 2, 2}, {2, -15, 21}, {8004, 31, -24}};
        int over = 0, checked = 0, discs = 0;
        for (long[] ar : areas) {
            Terrain t = new Terrain(TerrainSettings.defaults(), -64, ar[0]);
            GalleriesField g = t.galleries;
            int T = g.T, x0 = (int) ar[1] * 64, z0 = (int) ar[2] * 64;
            GalleriesField.Climate cl = new GalleriesField.Climate();
            GalleriesField.Shape0 sh = new GalleriesField.Shape0();
            int bad = 0;
            String first = null;
            for (int x = x0; x < x0 + 256; x++) for (int z = z0; z < z0 + 256; z++) {
                for (int y = T + 1; y <= T + 9; y++) if (g.fineBlocks(x, y, z) <= 0) {
                    over++;
                    bad++;
                    if (first == null) first = x + " " + y + " " + z + " (open over the top)";
                    break;
                }
                GalleriesField.Cell c = g.column(x, z);
                if (c.closed()) continue;
                g.climate(c.x, c.z, cl);
                g.shape(c.x, c.z, cl, sh, c.h);
                if (sh.rh.jt > 0) discs++;
                if (sh.rh.jt > 0 || sh.rh.rb == sh.rh.rb || sh.mouth > 0) continue;
                checked++;
                for (int y = T - 6; y <= T; y++) if (g.fineBlocks(x, y, z) <= 0) {
                    bad++;
                    if (first == null) first = x + " " + y + " " + z + " (open in the top rows with no disc, ramp or mouth)";
                    break;
                }
            }
            assertEquals(0, bad, "seed " + ar[0] + ": the Galleries open over or in their top where they should not, first at " + first);
        }
        System.out.printf(java.util.Locale.ROOT, "JoinTest the top: %d columns open over T; top rows checked at %d columns; %d columns in discs%n", over, checked, discs);
        assertTrue(checked > 100_000 && discs > 300, "too little to check: " + checked + ", " + discs);
    }

    /**
     * The Wasteland's columns are computed from the fields built before it only (W-ENG-5): under the tests' assertions a
     * column fill that called back into the column cache would fail, and with that guard on, the Wasteland's columns
     * round openings and craters (which ask the Galleries for their craters and their density under the join), the
     * Galleries' density and per-block density over the join's rows, and the combined density all answer.
     */
    @Test
    void columnPassIsNotReentered() {
        assertTrue(WastelandField.class.desiredAssertionStatus(), "the column guard needs the tests' assertions on");
        Terrain t = new Terrain(TerrainSettings.defaults(), -64, 1);
        Join j = t.join;
        Random r = new Random(61);
        int n = 0, craters = 0;
        for (int i = -4; i < 4; i++) for (int k = -4; k < 4; k++) for (GalleriesField.Opening o : t.galleries.openings(i, k)) {
            for (int q = 0; q < 200; q++) {
                int x = (int) Math.floor(o.x) + r.nextInt(97) - 48, z = (int) Math.floor(o.z) + r.nextInt(97) - 48, y = j.lo + r.nextInt(j.hi - j.lo + 1);
                double d = t.galleries.density(x, y, z) + t.galleries.fineBlocks(x, y, z) + t.rawDensity(x, y, z) + t.fine(x, y, z, 0.1);
                assertFalse(Double.isNaN(d), "NaN at " + x + " " + y + " " + z);
                if (t.wasteland.column(x, z).crater > 0) craters++;
                n++;
            }
        }
        assertTrue(n > 2000 && craters > 200, "too few columns round openings and craters: " + n + ", " + craters);
    }

    // ================================================================================================ ways down and up

    /**
     * What one box round gate 3's landing showed of the ways between the layers, the keyhole views down and the walks
     * (one voxel build per seed, shared by the tests below and dropped after them, {@link #WAYS}).
     */
    static final class Ways {
        long seed;
        double km2;
        int wPlaces, wMain, gPlaces, gMain;
        /** The holes the Galleries open (all with a ramp) with middles in the box; those measured (wholly inside it). */
        int opened, measured, down, down3, up, both;
        /** The Galleries' dry places in the main component: to the nearest way up, and up into the Wasteland. */
        int gDry, toWayUp400, toWayUpNever, toWayUpMedian, toWayUp90;
        int intoWasteland400, intoWastelandNever, intoWastelandMedian, intoWasteland90;
        /** The Wasteland's places in the main component (its lowest sheets): to the nearest way down, and down to the Galleries' floor. */
        int wLow, toWayDownMedian, toWayDown90, toWayDownNever, downToFloorMedian, downToFloor90, downToFloorNever;
        int landingWalk = -1;
        /**
         * Gate 3's landing: whether a place of it lies in the main component, its walk down to the Galleries' floor, and
         * the walk back up to it from the nearest floor place and (median) from the Galleries' dry places.
         */
        boolean landingInMain;
        int landingToFloor = -1, floorToLanding = -1, floorToLandingMedian = -1;
        /** Keyholes: openings examined, with a reachable rim, whose best rim sees 10 % or more of its downward rays reach the Galleries. */
        int keyholes, withRim, best10;
        double aimedMedian, bestMedian;
        int floorColsMin = -1, floorColsMedian = -1;
        /** Views of 192 or more: share of the Wasteland's places (every 4th column), and of the rims' places. */
        double wViews192, rimViews192;
        String failed = "", holes = "";

        double perKm2(int n) { return n / km2; }

        @Override public String toString() {
            return String.format(java.util.Locale.ROOT, "seed %d: main component %.1f %% of %d Wasteland places, %.1f %% of %d Galleries dry places;"
                            + " %d holes opened (%.0f per km2), %d measured: way down %d (every drop <= 3: %d), way up %d, both %d;"
                            + " Galleries dry places to the nearest way up within 400 moves %.1f %% (none %d; median %d, 90 %% %d), up into the Wasteland within 400 %.1f %% (none %d; median %d, 90 %% %d);"
                            + " the Wasteland's places to the nearest way down median %d, 90 %% %d (none %d), down to the Galleries' floor median %d, 90 %% %d (none %d of %d);"
                            + " gate 3's landing to the nearest rim %d moves (in the main component %b; down to the Galleries' floor %d moves, back up from the nearest floor place %d, from the dry places median %d);"
                            + " keyholes %d: with a reachable rim %d, best rim 10 %% or more %d (median best %.1f %%), edge-aimed median %.1f %%, floor columns seen min %d median %d;"
                            + " views of 192 or more from %.2f %% of the Wasteland's places, %.2f %% of rim places%s%s",
                    seed, 100.0 * wMain / wPlaces, wPlaces, 100.0 * gMain / gPlaces, gPlaces, opened, perKm2(opened), measured, down, down3, up, both,
                    100.0 * toWayUp400 / gDry, toWayUpNever, toWayUpMedian, toWayUp90, 100.0 * intoWasteland400 / gDry, intoWastelandNever, intoWastelandMedian, intoWasteland90,
                    toWayDownMedian, toWayDown90, toWayDownNever, downToFloorMedian, downToFloor90, downToFloorNever, wLow, landingWalk,
                    landingInMain, landingToFloor, floorToLanding, floorToLandingMedian,
                    keyholes, withRim, best10, 100 * bestMedian, 100 * aimedMedian, floorColsMin, floorColsMedian, 100 * wViews192, 100 * rimViews192, failed, holes);
        }
    }

    /** The boxes measured so far (one per seed), dropped after this class's tests. */
    static final java.util.Map<Long, Ways> WAYS = new java.util.HashMap<>();

    static synchronized Ways waysOf(long seed) {
        return WAYS.computeIfAbsent(seed, JoinTest::ways);
    }

    @org.junit.jupiter.api.AfterAll
    static synchronized void dropWays() { WAYS.clear(); }

    /**
     * One voxel build of all the rows from the Galleries' bottom to the Wasteland's top round gate 3's landing of cell
     * (0, 0), 512 x 512 columns, and what the tests below ask of it:
     * <ul>
     * <li>the walk graph's main component's share of each layer's places;</li>
     * <li>for each hole the Galleries open (all keep a ramp) wholly in the box: a way down (from the Wasteland's places 12
     * to 30 blocks from its middle to its ramp's foot on the Galleries' floor, inside its own reach, every drop 15 at
     * most, and every drop 3 at most) and a way back up;</li>
     * <li>the walks from the Galleries' dry places (in the main component, feet from W + 3 to T - 12) to the nearest way
     * up (a place on a ramp's deck, its walked surface or its ragged edge, or within 6 blocks of its foot) and on up into
     * the Wasteland (feet at its bottom + 4 or higher);</li>
     * <li>the walks from the Wasteland's low places (its lowest sheets: feet from its bottom + 2 to T + 40) to the nearest
     * way down (a crater's rim: feet T - 6..T + 2 within r + CR_FLAT + 2 of a hole's middle) and on down to the
     * Galleries' floor (feet T - 12 or lower);</li>
     * <li>the walk from gate 3's landing to the nearest rim (far-from-entry);</li>
     * <li>the keyhole views (keyhole-openings' corrected fix, on the crater design): a hole's throat columns are open from
     * T - 12 to T - 5 (through the crater's floor, whose rock top is T - 6), within r + 6 of its middle; a rim place is a
     * main-component standing place with feet T - 6..T + 30 within 2 columns (not 0) of a throat column; from each, 288
     * downward rays (36 yaws x pitches -15..-85) run to rock or lava, and those that reach row T - 10 see the Galleries;
     * per hole the best rim's share, the median share of rays aimed within 45 degrees of the middle from rims a column
     * from the throat, and the Galleries' floor columns a fan of 7200 rays from the best rim ends on;</li>
     * <li>views of 192 blocks or more (NoiseVox's rays, every 4th column) from the Wasteland's places and from the rims.</li>
     * </ul>
     */
    static Ways ways(long seed) {
        Terrain t = new Terrain(TerrainSettings.defaults(), -64, seed);
        GalleriesField g = t.galleries;
        Join j = t.join;
        int T = j.gTop, wBot = j.wBot, Wg = t.shape.waterY(Terrain.GALLERIES);
        Plans.Gate land = t.plans.gate(Terrain.WASTELAND + 1, 0, 0);
        int size = 512, x0 = (int) Math.floor(land.lx) - size / 2, z0 = (int) Math.floor(land.lz) - size / 2;
        NoiseVox v = NoiseVox.build(t, x0, z0, size, size, t.shape.bottom(Terrain.GALLERIES), t.shape.top(Terrain.WASTELAND) + 1).graph();
        assertEquals(0, v.nans, "NaN densities");
        Ways w = new Ways();
        w.seed = seed;
        w.km2 = size * (double) size / 1e6;
        for (int p = 0; p < v.places; p++) {
            int y = v.py(p);
            if (y >= wBot + 2) { w.wPlaces++; if (v.inMain(p)) w.wMain++; }
            if (y <= T - 12 && y > Wg + 2) { w.gPlaces++; if (v.inMain(p)) w.gMain++; }
        }
        // the holes round the box (their ramps may reach into it)
        List<GalleriesField.Opening> ops = new ArrayList<>();
        for (int i = Math.floorDiv(x0, 64) - 4; i <= Math.floorDiv(x0 + size, 64) + 4; i++)
            for (int k = Math.floorDiv(z0, 64) - 4; k <= Math.floorDiv(z0 + size, 64) + 4; k++) ops.addAll(Arrays.asList(g.openings(i, k)));
        boolean[] rim = new boolean[v.places], wayUp = new boolean[v.places];
        StringBuilder failed = new StringBuilder();
        int top = (int) g.headTop;
        for (GalleriesField.Opening o : ops) {
            GalleriesField.Ramp rp = o.ramp;
            assertNotNull(rp, "an opened hole without a ramp at " + o.x + " " + o.z);
            if (o.x >= x0 && o.x < x0 + size && o.z >= z0 && o.z < z0 + size) w.opened++;
            double t0 = o.r + GalleriesField.LIP, ex = rp.px[rp.n - 1], ez = rp.pz[rp.n - 1];
            int R = (int) Math.ceil(o.r + WastelandField.CR_FLAT + 2);
            for (int x = (int) Math.floor(o.x) - R; x <= (int) Math.floor(o.x) + R; x++) for (int z = (int) Math.floor(o.z) - R; z <= (int) Math.floor(o.z) + R; z++) {
                if (!v.inColumns(x, z) || Math.hypot(x + 0.5 - o.x, z + 0.5 - o.z) > o.r + WastelandField.CR_FLAT + 2) continue;
                int qc = (x - x0) * size + (z - z0);
                for (int p = v.colStart[qc]; p < v.colStart[qc + 1]; p++) if (v.py(p) >= T - 6 && v.py(p) <= T + 2) rim[p] = true;
            }
            // the way up: the deck (its walked surface, or its ragged edge up to EDGE_LOW + 1 rows lower) and the foot
            for (int x = (int) Math.floor(rp.minX) - 8; x <= (int) Math.ceil(rp.maxX) + 8; x++) for (int z = (int) Math.floor(rp.minZ) - 8; z <= (int) Math.ceil(rp.maxZ) + 8; z++) {
                if (!v.inColumns(x, z)) continue;
                double dx = x + 0.5 - o.x, dz = z + 0.5 - o.z, tt = dx * rp.ux + dz * rp.uz, l = dx * rp.uz - dz * rp.ux;
                boolean onDeck = tt >= t0 && tt <= rp.tEnd && Math.abs(l - g.bend(rp, t0, tt)) < rp.hw;
                boolean atFoot = Math.hypot(x + 0.5 - ex, z + 0.5 - ez) <= 6;
                if (!onDeck && !atFoot) continue;
                int deck = (int) Math.floor(top - rp.s * Math.max(0, tt - t0) + g.deckTerrace(x + 0.5, z + 0.5, tt - t0) + 0.5);
                int qc = (x - x0) * size + (z - z0);
                for (int p = v.colStart[qc]; p < v.colStart[qc + 1]; p++) {
                    int y = v.py(p);
                    if (!v.inMain(p)) continue;
                    if (onDeck && y >= deck - GalleriesField.EDGE_LOW - 1 && y <= deck + 1) wayUp[p] = true;
                    if (atFoot && y <= T - 12) wayUp[p] = true;
                }
            }
            if (!(o.x - 64 > x0 && o.x + 64 < x0 + size && o.z - 64 > z0 && o.z + 64 < z0 + size
                    && ex > x0 + 10 && ex < x0 + size - 10 && ez > z0 + 10 && ez < z0 + size - 10)) continue;
            w.measured++;
            boolean[] from = new boolean[v.places], to = new boolean[v.places], region = new boolean[v.places];
            for (int p = 0; p < v.places; p++) {
                double d = Math.hypot(v.px(p) + 0.5 - o.x, v.pz(p) + 0.5 - o.z);
                if (d > o.reach + 8) continue;
                region[p] = true;
                int y = v.py(p);
                if (y >= wBot + 2 && d >= o.r + 12 && d <= o.r + 30) from[p] = true;
                if (y <= T - 12 && Math.hypot(v.px(p) + 0.5 - ex, v.pz(p) + 0.5 - ez) <= 6) to[p] = true;
            }
            boolean dn = reach(v, from, to, region, NoiseVox.MAX_DROP), up = reach(v, to, from, region, NoiseVox.MAX_DROP);
            if (dn) w.down++;
            if (dn && reach(v, from, to, region, 3)) w.down3++;
            if (up) w.up++;
            if (dn && up) w.both++;
            else if (failed.length() < 300) failed.append(String.format(java.util.Locale.ROOT, " (hole %.0f,%.0f: down %b, up %b)", o.x, o.z, dn, up));
        }
        w.failed = failed.toString();
        // the Galleries' dry ground to the nearest way up, and up into the Wasteland
        boolean[] wGoal = new boolean[v.places], gGoal = new boolean[v.places];
        for (int p = 0; p < v.places; p++) { wGoal[p] = v.py(p) >= wBot + 4; gGoal[p] = v.py(p) <= T - 12; }
        int[] toUp = v.walkTo(wayUp), toW = v.walkTo(wGoal);
        List<Integer> a1 = new ArrayList<>(), a2 = new ArrayList<>();
        for (int p = 0; p < v.places; p++) {
            int y = v.py(p);
            if (!v.inMain(p) || y > T - 12 || y <= Wg + 2) continue;
            w.gDry++;
            if (toUp[p] < 0) w.toWayUpNever++;
            else { a1.add(toUp[p]); if (toUp[p] <= 400) w.toWayUp400++; }
            if (toW[p] < 0) w.intoWastelandNever++;
            else { a2.add(toW[p]); if (toW[p] <= 400) w.intoWasteland400++; }
        }
        Collections.sort(a1);
        Collections.sort(a2);
        w.toWayUpMedian = quantile(a1, 0.5);
        w.toWayUp90 = quantile(a1, 0.9);
        w.intoWastelandMedian = quantile(a2, 0.5);
        w.intoWasteland90 = quantile(a2, 0.9);
        // the Wasteland's low places to the nearest way down (a rim), and down to the Galleries' floor
        int[] toRim = v.walkTo(rim), toG = v.walkTo(gGoal);
        List<Integer> b1 = new ArrayList<>(), b2 = new ArrayList<>();
        for (int p = 0; p < v.places; p++) {
            int y = v.py(p);
            if (!v.inMain(p) || y < wBot + 2 || y > T + 40) continue;
            w.wLow++;
            if (toRim[p] < 0) w.toWayDownNever++;
            else b1.add(toRim[p]);
            if (toG[p] < 0) w.downToFloorNever++;
            else b2.add(toG[p]);
        }
        Collections.sort(b1);
        Collections.sort(b2);
        w.toWayDownMedian = quantile(b1, 0.5);
        w.toWayDown90 = quantile(b1, 0.9);
        w.downToFloorMedian = quantile(b2, 0.5);
        w.downToFloor90 = quantile(b2, 0.9);
        // gate 3's landing to the nearest crater rim
        int best = Integer.MAX_VALUE;
        for (int p = 0; p < v.places; p++) {
            double d = Math.hypot(v.px(p) + 0.5 - land.lx, v.pz(p) + 0.5 - land.lz);
            if (d < land.landR - 2 && v.py(p) >= land.lFloor - 3 && v.py(p) <= land.lFloor + 4 && toRim[p] >= 0) best = Math.min(best, toRim[p]);
        }
        w.landingWalk = best == Integer.MAX_VALUE ? -1 : best;
        landingWalks(v, land, T, Wg, w);
        keyholes(v, w, ops, T);
        // views of 192 or more from the Wasteland's places (every 4th column of the box less 64 at each side)
        double[] wv = v.longestViews(4, x0 + 64, z0 + 64, x0 + size - 64, z0 + size - 64, wBot + 2, t.shape.top(Terrain.WASTELAND), t.shape.bottom(Terrain.GALLERIES),
                t.shape.top(Terrain.WASTELAND) + 1, true, 200);
        int long192 = 0;
        for (double d : wv) if (d >= 192) long192++;
        w.wViews192 = wv.length == 0 ? 0 : long192 / (double) wv.length;
        return w;
    }

    /**
     * Gate 3's landing against the Galleries' floor (W-ENG-3's end-to-end check), into {@code w}: whether a place of the
     * landing (within its radius less 2, feet from its floor - 3 to + 4) lies in the main component; the walk from the
     * landing down to the Galleries' dry floor (feet from Wg + 3 to T - 12); and the walks back up to the landing, from
     * the nearest floor place and (the median) from the Galleries' dry places in the main component.
     */
    static void landingWalks(NoiseVox v, Plans.Gate land, int T, int Wg, Ways w) {
        boolean[] landing = new boolean[v.places], floor = new boolean[v.places];
        for (int p = 0; p < v.places; p++) {
            int y = v.py(p);
            if (Math.hypot(v.px(p) + 0.5 - land.lx, v.pz(p) + 0.5 - land.lz) < land.landR - 2 && y >= land.lFloor - 3 && y <= land.lFloor + 4) {
                landing[p] = true;
                w.landingInMain |= v.inMain(p);
            }
            floor[p] = y <= T - 12 && y > Wg + 2;
        }
        int[] down = v.walkTo(floor), up = v.walkTo(landing);
        int best = Integer.MAX_VALUE, back = Integer.MAX_VALUE;
        List<Integer> backs = new ArrayList<>();
        for (int p = 0; p < v.places; p++) {
            if (landing[p] && down[p] >= 0) best = Math.min(best, down[p]);
            if (floor[p] && up[p] >= 0) back = Math.min(back, up[p]);
            if (floor[p] && v.inMain(p) && up[p] >= 0) backs.add(up[p]);
        }
        Collections.sort(backs);
        w.landingToFloor = best == Integer.MAX_VALUE ? -1 : best;
        w.floorToLanding = back == Integer.MAX_VALUE ? -1 : back;
        w.floorToLandingMedian = quantile(backs, 0.5);
    }

    /** The keyhole views of the holes with middles 40 blocks or more inside the box (see {@link #ways}). */
    static void keyholes(NoiseVox v, Ways w, List<GalleriesField.Opening> ops, int T) {
        int size = v.nx, x0 = v.x0, z0 = v.z0;
        double[] ys = new double[8], cs = new double[8];
        for (int q = 0; q < 8; q++) { double pr = Math.toRadians(-15 - 10 * q); ys[q] = Math.sin(pr); cs[q] = Math.cos(pr); }
        List<Double> bests = new ArrayList<>(), aimed = new ArrayList<>();
        List<Integer> cols = new ArrayList<>();
        int rimPlaces = 0, rim192 = 0;
        StringBuilder holes = new StringBuilder();
        for (GalleriesField.Opening o : ops) {
            if (!(o.x - 40 > x0 && o.x + 40 < x0 + size && o.z - 40 > z0 && o.z + 40 < z0 + size)) continue;
            w.keyholes++;
            int R = (int) Math.ceil(o.r + 6);
            List<int[]> th = new ArrayList<>();
            for (int dx = -R; dx <= R; dx++) for (int dz = -R; dz <= R; dz++) {
                int x = (int) Math.floor(o.x) + dx, z = (int) Math.floor(o.z) + dz;
                if (!v.inColumns(x, z) || Math.hypot(x + 0.5 - o.x, z + 0.5 - o.z) > o.r + 6) continue;
                boolean open = true;
                for (int y = T - 12; y <= T - 5 && open; y++) open = v.mat(x, y, z) == NoiseVox.AIR;
                if (open) th.add(new int[]{x, z});
            }
            double bestShare = -1;
            int bestP = -1, rims = 0;
            List<Double> edge = new ArrayList<>();
            for (int dx = -R - 2; dx <= R + 2; dx++) for (int dz = -R - 2; dz <= R + 2; dz++) {
                int x = (int) Math.floor(o.x) + dx, z = (int) Math.floor(o.z) + dz;
                if (!v.inColumns(x, z)) continue;
                int near = 99;
                for (int[] c : th) near = Math.min(near, Math.max(Math.abs(c[0] - x), Math.abs(c[1] - z)));
                if (near > 2 || near == 0) continue;
                int qc = (x - x0) * size + (z - z0);
                for (int p = v.colStart[qc]; p < v.colStart[qc + 1]; p++) {
                    int y = v.py(p);
                    if (y < T - 6 || y > T + 30 || !v.inMain(p)) continue;
                    rims++;
                    double ox = x + 0.5, oy = y + NoiseVox.EYE, oz = z + 0.5, hx = o.x - ox, hz = o.z - oz, hl = Math.hypot(hx, hz);
                    int hits = 0, aimN = 0, aimHit = 0;
                    for (int pi = 0; pi < 8; pi++) for (int ya = 0; ya < 36; ya++) {
                        double a = Math.toRadians(ya * 10);
                        boolean hit = reachesBelow(v, ox, oy, oz, cs[pi] * Math.cos(a), ys[pi], cs[pi] * Math.sin(a), T - 10);
                        if (hit) hits++;
                        if (near == 1 && hl > 1e-6 && (Math.cos(a) * hx + Math.sin(a) * hz) / hl >= Math.cos(Math.toRadians(45))) { aimN++; if (hit) aimHit++; }
                    }
                    double share = hits / 288.0;
                    if (share > bestShare) { bestShare = share; bestP = p; }
                    if (near == 1 && aimN > 0) edge.add(aimHit / (double) aimN);
                    // the longest view from the rim (NoiseVox's rays)
                    double longest = 0;
                    for (int pitch : NoiseVox.PITCHES) for (int a = 0; a < NoiseVox.YAWS; a++) {
                        double th2 = Math.toRadians(a * (360.0 / NoiseVox.YAWS)), cp = Math.cos(Math.toRadians(pitch));
                        longest = Math.max(longest, v.ray(ox, oy, oz, cp * Math.cos(th2), Math.sin(Math.toRadians(pitch)), cp * Math.sin(th2), 200, v.y0, v.y0 + v.ny, true));
                    }
                    rimPlaces++;
                    if (longest >= 192) rim192++;
                }
            }
            if (rims > 0) w.withRim++;
            int fc = -1;
            if (bestP >= 0) {
                bests.add(bestShare);
                if (bestShare >= 0.10) w.best10++;
                fc = floorColumns(v, bestP, T - 12);
                cols.add(fc);
            }
            Collections.sort(edge);
            if (!edge.isEmpty()) aimed.add(edge.get(edge.size() / 2));
            if (holes.length() < 1600) holes.append(String.format(java.util.Locale.ROOT, "%n  hole (%.0f,%.0f) r %.1f: throat %d columns, rims %d, best %.1f %%, edge-aimed median %.1f %%, floor columns seen %d",
                    o.x, o.z, o.r, th.size(), rims, 100 * bestShare, edge.isEmpty() ? -100.0 : 100 * edge.get(edge.size() / 2), fc));
        }
        Collections.sort(bests);
        Collections.sort(aimed);
        Collections.sort(cols);
        w.bestMedian = bests.isEmpty() ? 0 : bests.get(bests.size() / 2);
        w.aimedMedian = aimed.isEmpty() ? 0 : aimed.get(aimed.size() / 2);
        w.floorColsMin = cols.isEmpty() ? -1 : cols.get(0);
        w.floorColsMedian = cols.isEmpty() ? -1 : cols.get(cols.size() / 2);
        w.rimViews192 = rimPlaces == 0 ? 0 : rim192 / (double) rimPlaces;
        w.holes = holes.toString();
    }

    /** Whether a ray from (ox, oy, oz) reaches row yGoal or lower before rock or lava (out of the box: no). */
    static boolean reachesBelow(NoiseVox v, double ox, double oy, double oz, double dx, double dy, double dz, int yGoal) {
        int x = (int) Math.floor(ox), y = (int) Math.floor(oy), z = (int) Math.floor(oz);
        int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
        double inf = Double.POSITIVE_INFINITY, ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        double tx = ax < 1e-12 ? inf : (dx > 0 ? x + 1 - ox : ox - x) / ax, ty = ay < 1e-12 ? inf : (dy > 0 ? y + 1 - oy : oy - y) / ay;
        double tz = az < 1e-12 ? inf : (dz > 0 ? z + 1 - oz : oz - z) / az;
        double ddx = ax < 1e-12 ? inf : 1 / ax, ddy = ay < 1e-12 ? inf : 1 / ay, ddz = az < 1e-12 ? inf : 1 / az;
        for (int n = 0; n < 2000; n++) {
            if (!v.in(x, y, z)) return false;
            if (y <= yGoal) return true;
            int m = v.mat(x, y, z);
            if (m == NoiseVox.ROCK || m == NoiseVox.LAVA) return false;
            if (tx <= ty && tx <= tz) { x += sx; tx += ddx; } else if (ty <= tz) { y += sy; ty += ddy; } else { z += sz; tz += ddz; }
        }
        return false;
    }

    /** The Galleries' floor columns (rock topped by an open block, at row yMax or lower) a fan of 40 x 180 rays from place p ends on. */
    static int floorColumns(NoiseVox v, int p, int yMax) {
        double ox = v.px(p) + 0.5, oy = v.py(p) + NoiseVox.EYE, oz = v.pz(p) + 0.5;
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        for (int pi = 0; pi < 40; pi++) {
            double pr = Math.toRadians(-12 - 2 * pi), cy = Math.sin(pr), cc = Math.cos(pr);
            for (int ya = 0; ya < 180; ya++) {
                double th = Math.toRadians(ya * 2), dx = cc * Math.cos(th), dz = cc * Math.sin(th);
                int x = (int) Math.floor(ox), y = (int) Math.floor(oy), z = (int) Math.floor(oz);
                int sx = dx > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
                double inf = Double.POSITIVE_INFINITY, ax = Math.abs(dx), ay = Math.abs(cy), az = Math.abs(dz);
                double tx = ax < 1e-12 ? inf : (dx > 0 ? x + 1 - ox : ox - x) / ax, ty = (oy - y) / ay, tz = az < 1e-12 ? inf : (dz > 0 ? z + 1 - oz : oz - z) / az;
                double ddx = ax < 1e-12 ? inf : 1 / ax, ddy = 1 / ay, ddz = az < 1e-12 ? inf : 1 / az;
                for (int n = 0; n < 3000; n++) {
                    if (!v.in(x, y, z)) break;
                    int m = v.mat(x, y, z);
                    if (m == NoiseVox.ROCK || m == NoiseVox.LAVA) {
                        if (y <= yMax && v.open(x, y + 1, z)) seen.add(((long) x << 32) ^ (z & 0xFFFFFFFFL));
                        break;
                    }
                    if (tx <= ty && tx <= tz) { x += sx; tx += ddx; } else if (ty <= tz) { y--; ty += ddy; } else { z += sz; tz += ddz; }
                }
            }
        }
        return seen.size();
    }

    /** The value a share f of the way up a sorted list (-1 for an empty list). */
    static int quantile(List<Integer> sorted, double f) {
        return sorted.isEmpty() ? -1 : sorted.get(Math.min(sorted.size() - 1, (int) (f * sorted.size())));
    }

    /** Whether some place of {@code from} walks to some place of {@code to} inside {@code region}, every move dropping at most maxDrop. */
    static boolean reach(NoiseVox v, boolean[] from, boolean[] to, boolean[] region, int maxDrop) {
        ArrayDeque<Integer> q = new ArrayDeque<>();
        boolean[] seen = new boolean[v.places];
        for (int p = 0; p < v.places; p++) if (from[p]) { seen[p] = true; q.add(p); }
        while (!q.isEmpty()) {
            int p = q.poll();
            if (to[p]) return true;
            for (int d = 0; d < 4; d++) {
                int n = v.next[p * 4 + d];
                if (n < 0 || seen[n] || !region[n] || v.py(p) - v.py(n) > maxDrop) continue;
                seen[n] = true;
                q.add(n);
            }
        }
        return false;
    }

    static final long[] WAY_SEEDS = {1, 2, 8004};

    /**
     * Ways down and back up (ways-down, W-ENG-3; Taran 11:45: "No band, opens into them", "many ways to climb down"): in
     * each of three boxes of 512 x 512 round gate 3's landing, every hole the Galleries open has a ramp, so the ways down
     * are the opened holes: 90 % or more of those measured have a way down from the Wasteland's tunnels round them to the
     * Galleries' floor with drops of 15 at most (75 % with every drop 3 at most) and a way back up, and there are 40 or
     * more ways down and up per km^2 in every box (W-ENG-3 estimated 60 and 30 before the Galleries were built; the boxes
     * measure 53-72 opened, all with ways). 90 % or more of the Galleries' dry places reach the nearest way up within 400
     * moves (W-ENG-3's bar: measured 99.2-100 %). The walks on up into the Wasteland and from the Wasteland's low places
     * to the nearest way down are printed.
     */
    @Test
    void waysDownAndUp() {
        for (long seed : WAY_SEEDS) {
            Ways w = waysOf(seed);
            System.out.println("JoinTest ways " + w);
            assertTrue(w.measured >= 3, "too few holes to measure: " + w);
            assertTrue(w.down >= 0.9 * w.measured && w.up >= 0.9 * w.measured && w.both >= 0.9 * w.measured, "holes without a way down and back up: " + w);
            assertTrue(w.down3 >= 0.75 * w.measured, "ways down that take long drops: " + w);
            assertTrue(w.perKm2(w.opened) * w.both / w.measured >= 40, "too few ways down and up per km2: " + w);
            assertTrue(w.toWayUp400 >= 0.9 * w.gDry && w.toWayUpNever == 0, "the Galleries' ground is far from a way up: " + w);
            assertEquals(0, w.toWayDownNever, "Wasteland places with no way down: " + w);
        }
    }

    /**
     * The layers join both ways (W-ENG-3): over the three boxes the walk graph's main component holds most of both
     * layers' places (85 % of the Wasteland's, 83 % of the Galleries' dry places: the boxes count places out to their
     * edges, where a way round may leave the box), and 60 % or more of the Galleries' dry places walk up into the
     * Wasteland within 400 moves, the climb included (W-ENG-3 asked 90 % of a walk to a way up, which {@link #waysDownAndUp}
     * bars; the whole climb up 130-145 rows on a ramp of 0.55-0.78 rows a block takes 150-250 moves of its own; measured
     * 95.2, 61.7 and 96.0 %: seed 2's box has a tube country in its middle, where no hole opens). The walk from gate 3's
     * landing to the nearest crater rim must exist and is printed (far-from-entry asked a median of about 200 moves;
     * measured 390, 460 and 474: not met, the landings are placed by the Wasteland's gates, not by the openings). End to
     * end (W-ENG-3's check from gate 3's landing): the landing lies in the main component, so it walks down to the
     * Galleries' floor and back up; both walks are printed ({@link #gateThreeLandingsReachTheGalleriesAndBack} runs the
     * check over 36 landings).
     */
    @Test
    void layersJoinBothWays() {
        List<Integer> landings = new ArrayList<>();
        List<String> ends = new ArrayList<>();
        for (long seed : WAY_SEEDS) {
            Ways w = waysOf(seed);
            assertTrue(w.wPlaces > 100_000 && w.gPlaces > 100_000, "too few places to measure: " + w);
            assertTrue(w.wMain >= 0.85 * w.wPlaces && w.gMain >= 0.83 * w.gPlaces, "the layers are not one walk both ways: " + w);
            assertTrue(w.intoWasteland400 >= 0.6 * w.gDry && w.intoWastelandNever == 0, "the Galleries' ground is far from the Wasteland: " + w);
            assertEquals(0, w.downToFloorNever, "Wasteland places with no walk down to the Galleries' floor: " + w);
            assertTrue(w.landingWalk >= 0, "no walk from gate 3's landing to a crater's rim: " + w);
            assertTrue(w.landingInMain && w.landingToFloor >= 0 && w.floorToLanding >= 0, "gate 3's landing is cut off from the Galleries' floor: " + w);
            landings.add(w.landingWalk);
            ends.add(String.format(java.util.Locale.ROOT, "seed %d: down %d, back up %d (from the dry places median %d)", seed, w.landingToFloor, w.floorToLanding, w.floorToLandingMedian));
        }
        Collections.sort(landings);
        System.out.println("JoinTest walks from gate 3's landings to the nearest rim: " + landings + "; to the Galleries' floor and back: " + ends);
    }

    /**
     * W-ENG-3's end-to-end check (slow): from gate 3's lower landing of each of the cells -1..1 x -1..1 of seeds 1-4 (36
     * landings), in a 512 x 512 box of both layers round it (rows -832..-545, blocks made as the game makes them), the
     * landing lies in the box's main component and that component holds the Galleries' dry floor, so a walk (climbs of
     * a row, drops of 15 at most) goes from the landing down to the floor and one comes back. The walks down and back
     * up are printed.
     */
    @Test
    @org.junit.jupiter.api.Tag("slow")
    void gateThreeLandingsReachTheGalleriesAndBack() {
        List<String> bad = new ArrayList<>();
        List<Integer> downs = new ArrayList<>(), ups = new ArrayList<>(), medians = new ArrayList<>();
        for (long seed = 1; seed <= 4; seed++) {
            Terrain t = new Terrain(TerrainSettings.defaults(), -64, seed);
            int T = t.join.gTop, Wg = t.shape.waterY(Terrain.GALLERIES);
            for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) {
                Plans.Gate land = t.plans.gate(Terrain.WASTELAND + 1, cx, cz);
                int size = 512, x0 = (int) Math.floor(land.lx) - size / 2, z0 = (int) Math.floor(land.lz) - size / 2;
                NoiseVox v = NoiseVox.build(t, x0, z0, size, size, t.shape.bottom(Terrain.GALLERIES), t.shape.top(Terrain.WASTELAND) + 1).graph();
                Ways w = new Ways();
                w.seed = seed;
                landingWalks(v, land, T, Wg, w);
                int gMain = 0;
                for (int p = 0; p < v.places; p++) if (v.inMain(p) && v.py(p) <= T - 12 && v.py(p) > Wg + 2) gMain++;
                String at = String.format(java.util.Locale.ROOT, "seed %d cell %d,%d (landing %.0f,%.0f): in the main component %b, its Galleries dry places %d, down %d, back up %d (median %d)",
                        seed, cx, cz, land.lx, land.lz, w.landingInMain, gMain, w.landingToFloor, w.floorToLanding, w.floorToLandingMedian);
                System.out.println("JoinTest landing " + at);
                if (!w.landingInMain || gMain < 50_000 || w.landingToFloor < 0 || w.floorToLanding < 0) bad.add(at);
                else { downs.add(w.landingToFloor); ups.add(w.floorToLanding); medians.add(w.floorToLandingMedian); }
            }
        }
        Collections.sort(downs);
        Collections.sort(ups);
        Collections.sort(medians);
        System.out.println("JoinTest landings to the Galleries' floor: down " + downs + "; back up from the nearest floor place " + ups + "; from the dry places (medians) " + medians);
        assertTrue(bad.isEmpty(), "landings cut off from the Galleries' floor: " + bad);
    }

    /**
     * Keyhole views (keyhole-openings' corrected fix, measured from reachable rims only, see {@link #ways}): over the three
     * boxes, every hole examined has a reachable rim (the fix asked 90 %), at 75 % or more of them the best rim sees the
     * Galleries through 10 % or more of its 288 downward rays, and the median over the holes of the edge-aimed share is
     * 25 % or more. The Galleries' floor columns seen from the best rims and the views of 192 blocks or more (from the
     * Wasteland's places and from the rims) are printed: Taran (21:21) prefers long views, so they have no bar.
     */
    @Test
    void keyholeViews() {
        int holes = 0, rim = 0, best10 = 0;
        List<Double> aimed = new ArrayList<>();
        for (long seed : WAY_SEEDS) {
            Ways w = waysOf(seed);
            System.out.println("JoinTest keyholes seed " + seed + ": " + w.keyholes + " holes, with a rim " + w.withRim + ", best 10 % or more " + w.best10 + w.holes);
            holes += w.keyholes;
            rim += w.withRim;
            best10 += w.best10;
            aimed.add(w.aimedMedian);
        }
        assertTrue(holes >= 15, "too few holes to measure: " + holes);
        assertTrue(rim >= 0.9 * holes, "holes without a reachable rim: " + rim + " of " + holes);
        assertTrue(best10 >= 0.75 * holes, "holes whose best rim sees little of the Galleries: " + best10 + " of " + holes);
        for (double a : aimed) assertTrue(a >= 0.25, "edge-aimed views down: " + aimed);
    }
}
