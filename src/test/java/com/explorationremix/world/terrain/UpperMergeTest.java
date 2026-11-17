package com.explorationremix.world.terrain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks of the Upper Caves merging with the pack's own caves ({@link UpperShapes#SEAM}, 0.18.5) in the made-up world of
 * {@link EntrancesTest}, whose pack has round tunnels crossing the warrens and the halls, with two more families here
 * through the rifts and under the halls' floors. The world's terrain is built as the deep world builds it: open where a
 * shape is ({@link UpperShapes#carve}, the merge included) or where the pack's cave is and nothing of ours seals it.
 * <ul>
 *     <li>no rock 1 to {@link UpperShapes#SEAM} blocks thick is left between a shape and the pack's cave, one over the
 *     other;</li>
 *     <li>still water keeps rock beside it and under it, and the pack's caves stay 2 blocks clear of it across and
 *     {@link UpperShapes#SEAM} + 1 under it;</li>
 *     <li>roots, icicles and stalactites hang from thick rock, stalagmites stand on it;</li>
 *     <li>the same answers on every thread.</li>
 * </ul>
 */
class UpperMergeTest {
    private static final long[] SEEDS = {8675309L, 12345L};
    private static final int PACK_FLOOR = -64, SEAM = UpperShapes.SEAM;
    private static final EntrancesTest.World WORLD = new EntrancesTest.World();

    /** The made-up world's caves, and two more tunnel families: one through the rifts, one under the halls' floors. */
    static double pack(int x, int y, int z) {
        double d = WORLD.packDensity(x, y, z);
        if (y > EntrancesTest.World.ground(x, z)) return d;
        double zc = Math.round((z - 7) / 41.0) * 41.0 + 7;
        double t = Math.hypot(z - zc, y - (-38 + 7 * Math.sin(x / 31.0))) - 4.0;
        double xc = Math.round((x + 11) / 53.0) * 53.0 - 11;
        t = Math.min(t, Math.hypot(x - xc, y - (6 + 5 * Math.cos(z / 27.0))) - 3.5);
        return Math.min(d, Math.max(-1, Math.min(1, t / 6.0)));
    }

    private static UpperShapes merged(long seed) { return new UpperShapes(seed, PACK_FLOOR, WORLD, null, UpperMergeTest::pack); }

    private static boolean sealed(UpperShapes s, int x, int y, int z) { return s.formation(x, y, z) != 0 || s.rim(x, y, z); }

    /** Open in the world as the deep world builds it. */
    private static boolean open(UpperShapes s, int x, int y, int z) {
        return s.carve(x, y, z) < 0 || pack(x, y, z) < 0 && !sealed(s, x, y, z);
    }

    /**
     * Over 256 x 256 columns: every run of rock, straight up and down, with a shape's own open space at one end and the
     * pack's cave at the other (not the open air near the ground) is more than {@link UpperShapes#SEAM} thick. Before
     * the merge the same world has plenty of thinner ones.
     */
    @Test
    void noThinRockBetweenOursAndThePacks() {
        for (long seed : SEEDS) {
            UpperShapes s = merged(seed), plain = new UpperShapes(seed, PACK_FLOOR, WORLD);
            int thinBefore = 0, thinAfter = 0, opened = 0;
            String first = null;
            for (int x = -128; x < 128; x++) for (int z = -128; z < 128; z++) {
                UpperShapes.Col c = s.column(x, z);
                if (c.lo > c.hi) continue;
                int lo = Math.max(PACK_FLOOR, (int) c.lo - 6), hi = (int) Math.ceil(c.hi) + 6;
                int n = hi - lo + 1;
                int[] end = new int[n];
                boolean[] after = new boolean[n], before = new boolean[n];
                double low = c.low;
                for (int i = 0; i < n; i++) {
                    int y = lo + i;
                    boolean ours = s.openKind(x, y, z) >= 0;
                    boolean packOpen = pack(x, y, z) < 0 && !ours && y <= low - 6 && !sealed(s, x, y, z);
                    end[i] = ours ? 1 : packOpen ? 2 : 0;
                    after[i] = open(s, x, y, z);
                    before[i] = plain.carve(x, y, z) < 0 || pack(x, y, z) < 0 && !(plain.formation(x, y, z) != 0 || plain.rim(x, y, z));
                    if (after[i] && !ours && !(pack(x, y, z) < 0)) opened++;
                }
                thinBefore += thinRuns(before, end, null, x, lo, z);
                String[] at = new String[1];
                int t = thinRuns(after, end, at, x, lo, z);
                thinAfter += t;
                if (t > 0 && first == null) first = at[0] + "; " + s.describe(x, z);
            }
            System.out.printf("seed %d: thin rock between ours and the pack's, one over the other, in 256 x 256 columns: %d runs before the merge, %d after; %d blocks fell away (first left: %s)%n",
                    seed, thinBefore, thinAfter, opened, first);
            assertTrue(thinBefore > 0, "the made-up pack's caves never come close to ours: nothing to test");
            assertTrue(opened > 0, "nothing fell away");
            assertEquals(0, thinAfter, "thin rock left between ours and the pack's caves; first " + first);
        }
    }

    /** Runs of 1 to SEAM solid blocks with ours at one end and the pack's cave at the other. */
    private static int thinRuns(boolean[] open, int[] end, String[] first, int x, int lo, int z) {
        int runs = 0;
        int i = 1;
        while (i < open.length - 1) {
            if (open[i] || !open[i - 1]) {
                i++;
                continue;
            }
            int j = i;
            while (j < open.length && !open[j]) j++;
            if (j >= open.length) break;
            int len = j - i, under = end[i - 1], over = end[j];
            if (len <= SEAM && (under == 1 && over == 2 || under == 2 && over == 1)) {
                runs++;
                if (first != null && first[0] == null) first[0] = len + " thick at " + x + " " + (lo + i) + " " + z + (over == 1 ? " (ours over it)" : " (the pack's over it)");
            }
            i = j;
        }
        return runs;
    }

    /**
     * Still water stays put in the merged world: beside and under every block of it lies water or rock, and the pack's
     * caves keep 2 blocks clear of it across, from its height down to {@link UpperShapes#SEAM} + 1 under it.
     */
    @Test
    void stillWaterSitsOnSolidRock() {
        int[][] around = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, -1, 0}};
        for (long seed : SEEDS) {
            UpperShapes s = merged(seed);
            int still = 0, spill = 0, near = 0;
            String first = null;
            for (int x = -192; x < 192; x++) for (int z = -192; z < 192; z++) {
                UpperShapes.Col c = s.column(x, z);
                if (c.lo > c.hi) continue;
                for (int y = (int) c.lo; y <= c.hi; y++) {
                    if (s.fluid(x, y, z) != UpperShapes.WATER) continue;
                    still++;
                    for (int[] d : around) {
                        int nx = x + d[0], ny = y + d[1], nz = z + d[2];
                        if (s.fluid(nx, ny, nz) == UpperShapes.WATER || !open(s, nx, ny, nz)) continue;
                        spill++;
                        if (first == null) first = "open " + nx + " " + ny + " " + nz + " beside water at " + x + " " + y + " " + z;
                    }
                    search:
                    for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int k = 0; k <= SEAM + 1; k++) {
                        if (pack(x + dx, y - k, z + dz) < 0) {
                            near++;
                            if (first == null) first = "the pack's cave at " + (x + dx) + " " + (y - k) + " " + (z + dz) + " near water at " + x + " " + y + " " + z;
                            break search;
                        }
                    }
                }
            }
            System.out.printf("seed %d: %d still water blocks in 384 x 384; %d beside open space, %d near the pack's caves (first: %s)%n", seed, still, spill, near, first);
            assertTrue(still > 0, "no still water");
            assertEquals(0, spill, "water beside open space; first " + first);
            assertEquals(0, near, "water near the pack's caves; first " + first);
        }
    }

    /**
     * Roots, icicles and stalactites hang from rock more than {@link UpperShapes#SEAM} thick (or reaching the ground) and
     * stalagmites stand on it, so no seam cut them loose and none hangs from a crust.
     */
    @Test
    void formationsKeepThickRock() {
        for (long seed : SEEDS) {
            UpperShapes s = merged(seed);
            int hanging = 0, standing = 0, loose = 0;
            String first = null;
            for (int x = -192; x < 192; x++) for (int z = -192; z < 192; z++) {
                UpperShapes.Col c = s.column(x, z);
                if (c.lo > c.hi) continue;
                int ground = EntrancesTest.World.ground(x, z);
                for (int y = (int) c.lo; y <= c.hi; y++) {
                    int f = s.formation(x, y, z);
                    if (f == 0 || open(s, x, y, z)) continue;
                    // the bottom (or top) block of a formation that has open space under it (or over it)
                    boolean hangs = f != UpperShapes.STALAGMITE && !(f == UpperShapes.DRIP && s.formation(x, y - 1, z) == UpperShapes.STALAGMITE);
                    int dir = hangs ? 1 : -1;
                    if (!open(s, x, y - dir, z)) continue;
                    if (hangs) hanging++;
                    else standing++;
                    // the rock past the cave's ceiling (or floor): the solid blocks from the formation's end on to the next
                    // open space, less those of the formation inside the cave
                    int h = y, rock = 0;
                    while (!open(s, x, h, z) && h < ground && rock <= SEAM + 8) {
                        if (s.kindAt(x, h, z) < 0) rock++;
                        h += dir;
                    }
                    boolean packs = h < ground && s.openKind(x, h, z) < 0;
                    if (rock <= SEAM && packs) {
                        loose++;
                        if (first == null) first = (hangs ? "hangs from " : "stands on ") + rock + " blocks at " + x + " " + y + " " + z + "; " + s.describe(x, z);
                    }
                }
            }
            System.out.printf("seed %d: %d hanging and %d standing formations in 384 x 384; %d on rock %d or less thick (first: %s)%n", seed, hanging, standing, loose, SEAM, first);
            assertTrue(hanging > 0, "no formations");
            assertEquals(0, loose, "a formation on thin rock; first " + first);
        }
    }

    /** Four threads asking at once, each in its own order, get what one thread gets, merge included. */
    @Test
    void sameOnEveryThread() throws Exception {
        Random r = new Random(2);
        List<int[]> pts = new ArrayList<>();
        for (int i = 0; i < 6000; i++) pts.add(new int[]{r.nextInt(800) - 400, r.nextInt(150) - 60, r.nextInt(800) - 400});
        UpperShapes one = merged(8675309L);
        String[] want = new String[pts.size()];
        for (int i = 0; i < pts.size(); i++) want[i] = answer(one, pts.get(i));
        UpperShapes shared = merged(8675309L);
        ExecutorService ex = Executors.newFixedThreadPool(4);
        try {
            List<Future<String[]>> fs = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                final int seed = t;
                fs.add(ex.submit(() -> {
                    List<Integer> order = new ArrayList<>();
                    for (int i = 0; i < pts.size(); i++) order.add(i);
                    Collections.shuffle(order, new Random(seed));
                    String[] got = new String[pts.size()];
                    for (int i : order) got[i] = answer(shared, pts.get(i));
                    return got;
                }));
            }
            for (Future<String[]> f : fs) {
                String[] got = f.get();
                for (int i = 0; i < want.length; i++) assertEquals(want[i], got[i], "at " + pts.get(i)[0] + " " + pts.get(i)[1] + " " + pts.get(i)[2]);
            }
        } finally {
            ex.shutdownNow();
        }
    }

    private static String answer(UpperShapes s, int[] p) {
        int x = p[0], y = p[1], z = p[2];
        return s.carve(x, y, z) + " " + s.fluid(x, y, z) + " " + s.formation(x, y, z) + " " + s.seamKind(x, y, z) + " " + s.besideOpen(x, y, z)
                + " " + s.nearWater(x, y, z);
    }
}
