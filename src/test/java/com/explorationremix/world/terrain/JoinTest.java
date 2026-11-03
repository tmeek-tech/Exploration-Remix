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
 * every skylight goes through with no lid, the Galleries' openings are the Wasteland's own dips, and the two layers are
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
        int sealChecked, sealHoles, lavaGroundChecked, lavaGroundHoles, galleriesTopChecked, galleriesTopHoles;
        int openings, openingsThrough;
        int lidColumns, biggestLid;
        int dipColumns, partialDips, craterColumns;
        String holeAt = "", lidAt = "", shutAt = "";

        @Override public String toString() {
            return String.format(java.util.Locale.ROOT, "seed %d: seal %d holes in %d columns (lava ground %d in %d, Galleries' top %d in %d); skylights %d, through %d%s;"
                            + " lid columns (open at the Galleries' top row under rock) %d, largest flat lid %d columns%s; dip columns %d (partly guarded %d); crater columns %d%s",
                    seed, sealHoles, sealChecked, lavaGroundHoles, lavaGroundChecked, galleriesTopHoles, galleriesTopChecked, openings, openingsThrough, shutAt,
                    lidColumns, biggestLid, lidAt, dipColumns, partialDips, craterColumns, holeAt);
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
            if (c.holeDip > 0) { b.dipColumns++; if (c.under < 1) b.partialDips++; }
            // the seal's three parts, block by block; none in the craters round the holes with ramps (Join#seal)
            if (c.crater > 0) {
                b.craterColumns++;
                lid[i * SIZE + k] = !v.rock(x, j.gTop, z) && v.rock(x, j.wBot, z);
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
        // skylights (the craters' holes are measured as ways down and up, layersJoinBothWays): 4 columns or more of hole
        // columns outside the craters; each must reach down into the Galleries' open space (no lid)
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
     * In blocks made as the game makes them, over three boxes: outside the craters the seal has no hole (no open block
     * from the Wasteland's bottom to a row over its lava table where beta is under {@link Join#HOLE_BETA} but for the lava
     * pools' dips, none in the 4 rows over its bottom under lava ground, none in the Galleries' top 4 rows under
     * {@link Join#CLOSED_BETA}); every skylight (4 columns or more outside the craters open from 2 under the Wasteland's
     * bottom to 8 over it) runs on down into the Galleries' space, to 6 rows under their top at least, so no lid closes
     * it; no flat lid lies at the join line itself: where the Galleries' top row is open under the Wasteland's bottom row
     * of rock, it is in patches of a few columns (the edges of openings, a crater's vault), never a plane; and the guard
     * cuts few dips short: at most 1 % of the columns where the Wasteland's floor falls through have the Galleries only
     * partly open under them (W-ENG-2).
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
            assertTrue(b.partialDips <= 0.01 * b.dipColumns + 2, "dips cut short by the guard: " + b);
            openings += b.openings;
        }
        assertTrue(openings >= 3, "too few skylights to check:\n" + all);
    }

    // ================================================================================================ one opening, two halves

    /**
     * The Galleries find the Wasteland's openings from {@link Breakthrough#openingSite}, and the Wasteland's dip is that
     * same weight times its guard (spec-galleries-field-v2 7.5, W-ENG-2): over three seeds, at every column within 24
     * blocks of an opening the Galleries find (opened or not) and at 20 000 columns anywhere (20 areas of 512 x 512 in
     * 8000 x 8000), the Wasteland's dip weight is exactly openingSite x under, bit for bit, so the two halves of an opening
     * can never drift apart.
     */
    @Test
    void openingSiteIsThePreGuardDip() {
        int checked = 0, sites = 0, dips = 0;
        for (long seed : new long[]{1, 2, 8004}) {
            Terrain t = new Terrain(TerrainSettings.defaults(), -64, seed);
            List<int[]> cols = new ArrayList<>();
            for (int i = -6; i < 6; i++) for (int k = -6; k < 6; k++)
                for (GalleriesField.Opening o : t.galleries.rawCell(i, k).ops)
                    for (int dx = -24; dx <= 24; dx += 2) for (int dz = -24; dz <= 24; dz += 2) cols.add(new int[]{(int) Math.floor(o.x) + dx, (int) Math.floor(o.z) + dz});
            Spread spread = new Spread(new Random(seed), 8000, 512, 1000);
            for (int q = 0; q < 20_000; q++) cols.add(spread.next());
            int wrong = 0;
            String first = null;
            for (int[] p : cols) {
                WastelandField.Col c = t.wasteland.column(p[0], p[1]);
                double site = t.breakthrough.openingSite(p[0], p[1]);
                if (site > 0) sites++;
                if (c.holeDip > 0) dips++;
                if (c.holeDip != site * c.under) { wrong++; if (first == null) first = p[0] + " " + p[1] + ": " + c.holeDip + " against " + site + " x " + c.under; }
                checked++;
            }
            assertEquals(0, wrong, "seed " + seed + ": dips unlike the opening site times the guard, first at " + first);
        }
        System.out.printf(java.util.Locale.ROOT, "JoinTest opening sites: %d columns, %d with a site, %d dipping%n", checked, sites, dips);
        assertTrue(sites > 5000 && dips > 100, "too few sites or dips to check: " + sites + ", " + dips);
    }

    /**
     * The Galleries open over their top row only under a skylight (spec-galleries-field-v2 7.3-7.4): over 4 x 4 lattice
     * cells round openings of three seeds, every column whose Galleries are open at a row over T (their own per-block
     * density) lies in the disc of an opened opening without a ramp (within its radius plus LIP and a cell's reach) and
     * within 6 columns of the Wasteland's dip, and never in the disc of an opening with a ramp (those open only up to
     * their crater's floor); and the Galleries' rows T - 6..T are rock at every column whose cell's site lies in no disc,
     * under no ramp's head room and in no tube mouth. (The discs are wider than the dips: about half of the columns open
     * over T are a few blocks past the Wasteland's dip, the hole's undercut rim.)
     */
    @Test
    void galleriesOpenOverTheTopOnlyUnderSkylights() {
        long[][] areas = {{1, 2, 2}, {2, -15, 21}, {8004, 31, -24}};
        int over = 0, overNoDip = 0, checked = 0;
        for (long[] ar : areas) {
            Terrain t = new Terrain(TerrainSettings.defaults(), -64, ar[0]);
            GalleriesField g = t.galleries;
            int T = g.T, x0 = (int) ar[1] * 64, z0 = (int) ar[2] * 64;
            List<GalleriesField.Opening> ops = new ArrayList<>();
            for (int i = (int) ar[1] - 4; i < ar[1] + 8; i++) for (int k = (int) ar[2] - 4; k < ar[2] + 8; k++) ops.addAll(Arrays.asList(g.openings(i, k)));
            GalleriesField.Climate cl = new GalleriesField.Climate();
            GalleriesField.Shape0 sh = new GalleriesField.Shape0();
            int bad = 0;
            String first = null;
            for (int x = x0; x < x0 + 256; x++) for (int z = z0; z < z0 + 256; z++) {
                boolean open = false;
                for (int y = T + 1; y <= T + 9 && !open; y++) open = g.fineBlocks(x, y, z) <= 0;
                if (open) {
                    over++;
                    double sky = Double.MAX_VALUE, ramped = Double.MAX_VALUE;
                    for (GalleriesField.Opening o : ops) {
                        double d = Math.hypot(x + 0.5 - o.x, z + 0.5 - o.z) - o.r;
                        if (o.ramp == null) sky = Math.min(sky, d);
                        else ramped = Math.min(ramped, d);
                    }
                    boolean dip = false;
                    for (int dx = -6; dx <= 6 && !dip; dx++) for (int dz = -6; dz <= 6 && !dip; dz++) dip = t.wasteland.column(x + dx, z + dz).holeDip > 0;
                    if (!(t.wasteland.column(x, z).holeDip > 0)) overNoDip++;
                    if (sky > GalleriesField.LIP + 3 || ramped < GalleriesField.LIP || !dip) {
                        bad++;
                        if (first == null) first = String.format(java.util.Locale.ROOT, "%d %d (skylight's disc edge %.1f, ramped %.1f, dip near %b)", x, z, sky, ramped, dip);
                    }
                }
                GalleriesField.Cell c = g.column(x, z);
                if (c.closed()) continue;
                g.climate(c.x, c.z, cl);
                g.shape(c.x, c.z, cl, sh, c.h);
                if (sh.rh.jt > 0 || sh.rh.rb == sh.rh.rb || sh.mouth > 0) continue;
                checked++;
                for (int y = T - 6; y <= T; y++) if (g.fineBlocks(x, y, z) <= 0) {
                    bad++;
                    if (first == null) first = x + " " + y + " " + z + " (open in the top rows with no disc, ramp or mouth)";
                    break;
                }
            }
            assertEquals(0, bad, "seed " + ar[0] + ": the Galleries open over their top where they should not, first at " + first);
        }
        System.out.printf(java.util.Locale.ROOT, "JoinTest over the top: %d columns open over T (%d past the dip itself); top rows checked at %d columns%n", over, overNoDip, checked);
        assertTrue(over > 50 && checked > 100_000, "too little to check: " + over + ", " + checked);
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

    /** What one box round gate 3's landing showed of the ways between the layers. */
    static final class Ways {
        long seed;
        int wPlaces, wMain, gPlaces, gMain;
        int ramped, skylights, measured, down, down3, up, both;
        double km2;
        int landingWalk = -1;
        int upPlaces, upWithin400, upNever;
        String failed = "";

        double perKm2(int n) { return n / km2; }

        @Override public String toString() {
            return String.format(java.util.Locale.ROOT, "seed %d: main component holds %.1f %% of %d Wasteland places and %.1f %% of %d Galleries dry places;"
                            + " %d ramped holes (%.0f per km2), %d skylights; measured %d: way down %d (every drop <= 3: %d), way up %d, both %d (%.0f per km2);"
                            + " Galleries dry places reaching the Wasteland within 400 moves %.1f %% (none %d); walk from gate 3's landing to the nearest rim %d%s",
                    seed, 100.0 * wMain / wPlaces, wPlaces, 100.0 * gMain / gPlaces, gPlaces, ramped, perKm2(ramped), skylights, measured, down, down3, up, both,
                    perKm2(ramped) * both / Math.max(1, measured), 100.0 * upWithin400 / upPlaces, upNever, landingWalk, failed);
        }
    }

    /**
     * One voxel build of all the rows from the Galleries' bottom to the Wasteland's top round gate 3's landing of cell
     * (0, 0), 512 x 512 columns: the walk graph's main component, each ramped hole's way down (from the Wasteland's
     * tunnels 12 to 30 blocks from its edge to its ramp's foot on the Galleries' floor, inside its own reach) and way up
     * (back), the walk from the Galleries' dry ground up into the Wasteland, and the walk from the landing to the nearest
     * crater rim (far-from-entry).
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
        List<GalleriesField.Opening> ops = new ArrayList<>();
        for (int i = Math.floorDiv(x0, 64); i <= Math.floorDiv(x0 + size, 64); i++)
            for (int k = Math.floorDiv(z0, 64); k <= Math.floorDiv(z0 + size, 64); k++) ops.addAll(Arrays.asList(g.openings(i, k)));
        boolean[] rim = new boolean[v.places];
        StringBuilder failed = new StringBuilder();
        for (GalleriesField.Opening o : ops) {
            boolean inBox = o.x >= x0 && o.x < x0 + size && o.z >= z0 && o.z < z0 + size;
            if (o.ramp == null) { if (inBox) w.skylights++; continue; }
            if (inBox) w.ramped++;
            for (int p = 0; p < v.places; p++) {
                double d = Math.hypot(v.px(p) + 0.5 - o.x, v.pz(p) + 0.5 - o.z);
                if (d <= o.r + WastelandField.CR_FLAT + 2 && v.py(p) >= T - 6 && v.py(p) <= T + 2) rim[p] = true;
            }
            GalleriesField.Ramp rp = o.ramp;
            double ex = rp.px[rp.n - 1], ez = rp.pz[rp.n - 1];
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
        // the Galleries' dry ground up into the Wasteland
        boolean[] wGoal = new boolean[v.places];
        for (int p = 0; p < v.places; p++) wGoal[p] = v.py(p) >= wBot + 4;
        int[] toW = v.walkTo(wGoal);
        for (int p = 0; p < v.places; p++) {
            int y = v.py(p);
            if (!v.inMain(p) || y > T - 12 || y <= Wg + 2) continue;
            w.upPlaces++;
            if (toW[p] < 0) w.upNever++;
            else if (toW[p] <= 400) w.upWithin400++;
        }
        // gate 3's landing to the nearest crater rim
        int[] toRim = v.walkTo(rim);
        int best = Integer.MAX_VALUE;
        for (int p = 0; p < v.places; p++) {
            double d = Math.hypot(v.px(p) + 0.5 - land.lx, v.pz(p) + 0.5 - land.lz);
            if (d < land.landR - 2 && v.py(p) >= land.lFloor - 3 && v.py(p) <= land.lFloor + 4 && toRim[p] >= 0) best = Math.min(best, toRim[p]);
        }
        w.landingWalk = best == Integer.MAX_VALUE ? -1 : best;
        return w;
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

    /**
     * The layers join both ways (W-ENG-3, ways-down; Taran: "No band, opens into them", "many ways to climb down"): over
     * three seeds, round gate 3's landing, the walk graph's main component holds most of both layers' places; 90 % or more
     * of the ramped holes measured have a way down (drops of 15 at most, three quarters of them 3 at most) and a way back
     * up through their crater, and there are 20 or more such holes per km^2 in every box; and 90 % or more of the
     * Galleries' dry places in the main component walk up into the Wasteland within 400 moves. The walk from gate 3's
     * landing to the nearest crater rim is printed (far-from-entry asks a median of about 200 moves) and must exist.
     */
    @Test
    void layersJoinBothWays() {
        List<Integer> landings = new ArrayList<>();
        StringBuilder all = new StringBuilder();
        for (long seed : new long[]{1, 2, 8004}) {
            Ways w = ways(seed);
            System.out.println("JoinTest ways " + w);
            all.append(w).append('\n');
            assertTrue(w.wPlaces > 100_000 && w.gPlaces > 100_000, "too few places to measure: " + w);
            assertTrue(w.wMain >= 0.85 * w.wPlaces && w.gMain >= 0.85 * w.gPlaces, "the layers are not one walk both ways: " + w);
            assertTrue(w.measured >= 3, "too few ramped holes to measure: " + w);
            assertTrue(w.both >= 0.9 * w.measured, "holes without a way down and back up: " + w);
            assertTrue(w.down3 >= 0.75 * w.measured, "ways down that take long drops: " + w);
            assertTrue(w.perKm2(w.ramped) * w.both / w.measured >= 20, "too few ways down and up per km2: " + w);
            assertTrue(w.upWithin400 >= 0.9 * w.upPlaces, "the Galleries' ground is far from a way up: " + w);
            assertTrue(w.landingWalk >= 0, "no walk from gate 3's landing to a crater's rim: " + w);
            landings.add(w.landingWalk);
        }
        Collections.sort(landings);
        System.out.println("JoinTest walks from gate 3's landings to the nearest rim: " + landings);
    }
}
