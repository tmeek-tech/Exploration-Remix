package com.explorationremix.world.terrain;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks of the noise-made Upper Caves ({@link UpperShapes}) against the made-up world of {@link EntrancesTest}: the
 * same answers on every thread; the ground stays shut except at the ways in (mouths under forests, pits under plains
 * and deserts); nothing opens under a river; the rifts lie deep, down by the old floor, and keep clear of the den gates
 * there, where nothing else opens; pools and lakes keep their water; the open space hangs together; every shape turns
 * up, about as often as planned.
 */
class UpperShapesTest {
    private static final long[] SEEDS = {8675309L, 12345L};
    private static final int PACK_FLOOR = -64;
    private static final EntrancesTest.World WORLD = new EntrancesTest.World();

    private static String answer(UpperShapes s, int x, int y, int z) {
        return s.carve(x, y, z) + " " + s.fluid(x, y, z) + " " + s.formation(x, y, z) + " " + s.piece(x, y, z) + " " + s.areaAt(x, y, z)
                + " " + s.look(x, y, z) + " " + s.kindAt(x, y, z);
    }

    /** Four threads asking at once, each in its own order, get what one thread gets. */
    @Test
    void sameOnEveryThread() throws Exception {
        Random r = new Random(1);
        List<int[]> pts = new ArrayList<>();
        for (int i = 0; i < 6000; i++) pts.add(new int[]{r.nextInt(2400) - 1200, r.nextInt(130) - 15, r.nextInt(2400) - 1200});
        UpperShapes one = new UpperShapes(8675309L, PACK_FLOOR, WORLD);
        String[] want = new String[pts.size()];
        for (int i = 0; i < pts.size(); i++) want[i] = answer(one, pts.get(i)[0], pts.get(i)[1], pts.get(i)[2]);
        UpperShapes shared = new UpperShapes(8675309L, PACK_FLOOR, WORLD);
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
                    for (int i : order) got[i] = answer(shared, pts.get(i)[0], pts.get(i)[1], pts.get(i)[2]);
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

    /**
     * Within 3 blocks under the ground nothing opens except at a way in: a mouth under a forest or a pit under plains or a
     * desert (a mouth never under a river's own column). The rifts lie deep and never open to the sky (since 0.14.1).
     */
    @Test
    void groundStaysShutExceptTheWaysIn() {
        for (long seed : SEEDS) {
            UpperShapes s = new UpperShapes(seed, PACK_FLOOR, WORLD);
            int open = 0, mouths = 0, pits = 0;
            for (int x = -1200; x < 1200; x += 3) for (int z = -1200; z < 1200; z += 3) {
                Overhead.Surface su = WORLD.at(x, z);
                int g = su.y();
                boolean hit = false;
                for (int y = g - 3; y <= g && !hit; y++) hit = s.carve(x, y, z) < 0;
                if (!hit) continue;
                open++;
                UpperShapes.Col c = s.column(x, z);
                String at = " at " + x + " " + z + ": " + s.describe(x, z);
                if (c.sOn) {
                    assertTrue(c.plains || c.desert, "a pit outside plains and deserts" + at);
                    pits++;
                } else if (c.mouth > 0) {
                    assertTrue(c.forest && !su.wet(), "a mouth outside a dry forest" + at);
                    mouths++;
                } else {
                    fail("open under the ground" + at);
                }
            }
            System.out.printf("seed %d: %d columns open at the ground (every 3rd block): %d mouth, %d pit%n", seed, open, mouths, pits);
            assertTrue(mouths > 0 && pits > 0, "every way in turns up: " + mouths + " " + pits);
        }
    }

    /** Under a river (the made-up world's wet strips) nothing opens within 8 blocks of its bed. */
    @Test
    void riversStayShut() {
        for (long seed : SEEDS) {
            UpperShapes s = new UpperShapes(seed, PACK_FLOOR, WORLD);
            for (int x = -1200; x < 1200; x += 2) for (int zr = -700; zr < 700; zr += 700) for (int z = zr; z < zr + 40; z += 2) {
                int g = WORLD.at(x, z).y();
                assertTrue(WORLD.at(x, z).wet());
                for (int y = g - 8; y <= g; y++)
                    assertTrue(s.carve(x, y, z) >= 0, "open under a river at " + x + " " + y + " " + z + ": " + s.describe(x, z));
            }
        }
    }

    /**
     * Down by the old floor, where the den gates' landings, rifts and ramps lie, only the rifts open (below the other
     * shapes' floor), and no block of their open space or water lies within {@link UpperShapes#RIFT_KEEP} across the
     * ground of a den gate's works (the seed's own, {@link Terrain#denClearance}).
     */
    @Test
    void clearOfTheDenGates() {
        for (long seed : SEEDS) {
            Terrain t = new Terrain(TerrainSettings.defaults(), PACK_FLOOR, seed);
            UpperShapes s = new UpperShapes(seed, PACK_FLOOR, WORLD, t::denClearance), free = new UpperShapes(seed, PACK_FLOOR, WORLD);
            int top = PACK_FLOOR + UpperShapes.FLOOR_CLEARANCE - 2, rift = 0, shut = 0;
            double nearest = Double.MAX_VALUE;
            for (int x = -1200; x < 1200; x += 4) for (int z = -1200; z < 1200; z += 4) {
                UpperShapes.Col c = s.column(x, z);
                double clear = t.denClearance(x, z);
                // where the rifts' crack runs but a den gate's works are near, it is shut
                if (!c.rOn && clear < UpperShapes.RIFT_KEEP && free.column(x, z).rOn) shut++;
                boolean any = false;
                for (int y = PACK_FLOOR; y < top; y++) {
                    if (s.carve(x, y, z) >= 0 && s.fluid(x, y, z) == -1) continue;
                    String at = " at " + x + " " + y + " " + z + ": " + s.describe(x, z);
                    assertTrue(c.rOn, "a shape other than a rift open or holding water by the old floor" + at);
                    assertTrue(clear >= UpperShapes.RIFT_KEEP, "a rift " + clear + " across from a den gate's works" + at);
                    any = true;
                }
                if (any) {
                    rift++;
                    nearest = Math.min(nearest, clear);
                }
            }
            System.out.printf("seed %d: %d rift columns by the old floor (every 4th block), the nearest %.1f across from a den gate's works; %d columns shut near one%n",
                    seed, rift, nearest, shut);
            assertTrue(rift > 0 && shut > 0, "rifts " + rift + ", shut near den gates " + shut);
        }
    }

    /**
     * The rifts lie at the bottom of the Upper Caves, close to the Blue Reaches (Taran, 2026-11-12): every rift's floor
     * 4 to 22 blocks over the old floor, and its top under y 0 (64 over the old floor) and at least 8 under the lowest
     * ground round it.
     */
    @Test
    void riftsLieDeep() {
        for (long seed : SEEDS) {
            UpperShapes s = new UpperShapes(seed, PACK_FLOOR, WORLD);
            int n = 0;
            double floors = 0, tops = 0;
            for (int x = -1024; x < 1024; x += 2) for (int z = -1024; z < 1024; z += 2) {
                UpperShapes.Col c = s.column(x, z);
                if (!c.rOn) continue;
                String at = " at " + x + " " + z + ": " + s.describe(x, z);
                assertTrue(c.rFloor >= PACK_FLOOR + UpperShapes.RIFT_FLOOR_MIN && c.rFloor <= PACK_FLOOR + 22, "rift floor" + at);
                assertTrue(c.rTop <= PACK_FLOOR + 64 && c.rTop <= c.low - 8, "rift top" + at);
                n++;
                floors += c.rFloor;
                tops += c.rTop;
            }
            System.out.printf("seed %d: %d rift columns (every 2nd block), floors at y %.1f and tops at y %.1f on average%n", seed, n, floors / n, tops / n);
            assertTrue(n > 0, "no rifts");
        }
    }

    /**
     * Still water stays put: beside every block of a pool, a rift's stream or a sinkhole's lake, and under it, lies water
     * or rock to every shape, never open air, and each such rock block is in the rim the pack's caves may not open. Only
     * the springs flow.
     */
    @Test
    void stillWaterHolds() {
        int[][] around = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, -1, 0}};
        for (long seed : SEEDS) {
            UpperShapes s = new UpperShapes(seed, PACK_FLOOR, WORLD);
            int still = 0, stream = 0, lakes = 0, spill = 0, unsealed = 0;
            String first = null;
            for (int x = -256; x < 256; x++) for (int z = -256; z < 256; z++) {
                UpperShapes.Col c = s.column(x, z);
                if (c.lo > c.hi) continue;
                int lo = (int) c.lo, hi = (int) c.hi, rFloor = c.rOn ? c.rFloor : Integer.MIN_VALUE, sLake = c.sOn ? c.sLake : Integer.MIN_VALUE;
                for (int y = lo; y <= hi; y++) {
                    if (s.fluid(x, y, z) != UpperShapes.WATER) continue;
                    still++;
                    if (y == rFloor - 1) stream++;
                    else if (y >= sLake - 1 && y <= sLake) lakes++;
                    for (int[] d : around) {
                        int nx = x + d[0], ny = y + d[1], nz = z + d[2];
                        if (s.fluid(nx, ny, nz) == UpperShapes.WATER) continue;
                        if (s.carve(nx, ny, nz) < 0) spill++;
                        else if (!s.rim(nx, ny, nz)) unsealed++;
                        else continue;
                        if (first == null) first = nx + " " + ny + " " + nz + " beside water at " + x + " " + y + " " + z;
                    }
                }
            }
            System.out.printf("seed %d: %d still water blocks (%d stream, %d lake) in 512 x 512; %d beside open air, %d beside unsealed rock (first: %s)%n",
                    seed, still, stream, lakes, spill, unsealed, first);
            assertTrue(still > 0 && stream > 0, "no pools or streams");
            assertEquals(0, spill, "water beside open air; first " + first);
            assertEquals(0, unsealed, "water beside rock the pack's caves may open; first " + first);
        }
    }

    /**
     * The open space hangs together: in a 192-block box from the old floor up, nearly all of it belongs to pieces that
     * reach the box's sides or the sky. A closed pocket would be a dead end the pack's caves may or may not open.
     */
    @Test
    void openSpaceHangsTogether() {
        for (long seed : SEEDS) {
            UpperShapes s = new UpperShapes(seed, PACK_FLOOR, WORLD);
            int n = 192, y0 = PACK_FLOOR, h = 179, x0 = 40, z0 = -320;
            boolean[] open = new boolean[n * n * h];
            for (int i = 0; i < n; i++) for (int k = 0; k < n; k++) for (int j = 0; j < h; j++)
                open[(i * n + k) * h + j] = s.carve(x0 + i, y0 + j, z0 + k) < 0;
            boolean[] seen = new boolean[open.length];
            long total = 0, closed = 0;
            int pockets = 0;
            String firstPocket = null;
            ArrayDeque<Integer> q = new ArrayDeque<>();
            List<Integer> comp = new ArrayList<>();
            for (int start = 0; start < open.length; start++) {
                if (!open[start] || seen[start]) continue;
                comp.clear();
                boolean out = false;
                q.add(start);
                seen[start] = true;
                while (!q.isEmpty()) {
                    int v = q.poll();
                    comp.add(v);
                    int j = v % h, k = (v / h) % n, i = v / h / n;
                    if (i == 0 || k == 0 || i == n - 1 || k == n - 1 || j == h - 1 || y0 + j >= WORLD.at(x0 + i, z0 + k).y()) out = true;
                    int[][] nb = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
                    for (int[] d : nb) {
                        int ii = i + d[0], jj = j + d[1], kk = k + d[2];
                        if (ii < 0 || kk < 0 || jj < 0 || ii >= n || kk >= n || jj >= h) continue;
                        int w = (ii * n + kk) * h + jj;
                        if (open[w] && !seen[w]) { seen[w] = true; q.add(w); }
                    }
                }
                total += comp.size();
                if (!out) {
                    closed += comp.size();
                    pockets++;
                    if (firstPocket == null || comp.size() > 50) {
                        int v = comp.get(0);
                        firstPocket = comp.size() + " blocks at " + (x0 + v / h / n) + " " + (y0 + v % h) + " " + (z0 + (v / h) % n);
                    }
                }
            }
            System.out.printf("seed %d: %d open blocks in the box, %d in %d closed pockets (%s)%n", seed, total, closed, pockets, firstPocket);
            assertTrue(total > 10000, "too little open space: " + total);
            assertTrue(closed <= 0.02 * total, closed + " of " + total + " open blocks in closed pockets; " + firstPocket);
        }
    }

    /**
     * Every hall opens onto something: a rift through it, or a chimney up to a warren tunnel. Counted over hall regions
     * (connected hall columns) of 400 square blocks or more that lie wholly inside the area; the tiny bubbles at the
     * halls' thin edges are left out.
     */
    @Test
    void hallsOpenOntoTheOtherShapes() {
        for (long seed : SEEDS) {
            UpperShapes s = new UpperShapes(seed, PACK_FLOOR, WORLD);
            int r = 768, st = 2, n = 2 * r / st;
            boolean[] hall = new boolean[n * n], linked = new boolean[n * n];
            for (int i = 0; i < n; i++) for (int k = 0; k < n; k++) {
                int x = -r + i * st, z = -r + k * st;
                UpperShapes.Col c = s.column(x, z);
                if (!c.hOn) continue;
                hall[i * n + k] = true;
                for (int y = c.hFloor; y <= (int) Math.ceil(c.hCeil) + 1; y++) {
                    int kind = s.kindAt(x, y, z);
                    if (kind >= 0 && kind != UpperShapes.HALLS) {
                        linked[i * n + k] = true;
                        break;
                    }
                }
            }
            boolean[] seen = new boolean[n * n];
            long area = 0, alone = 0;
            int regions = 0;
            String first = null;
            ArrayDeque<Integer> q = new ArrayDeque<>();
            for (int s0 = 0; s0 < n * n; s0++) {
                if (!hall[s0] || seen[s0]) continue;
                boolean link = false, edge = false;
                int size = 0;
                q.add(s0);
                seen[s0] = true;
                while (!q.isEmpty()) {
                    int v = q.poll();
                    size++;
                    link |= linked[v];
                    int i = v / n, k = v % n;
                    if (i == 0 || k == 0 || i == n - 1 || k == n - 1) edge = true;
                    int[][] d = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                    for (int[] dd : d) {
                        int ii = i + dd[0], kk = k + dd[1];
                        if (ii < 0 || kk < 0 || ii >= n || kk >= n) continue;
                        int u = ii * n + kk;
                        if (hall[u] && !seen[u]) {
                            seen[u] = true;
                            q.add(u);
                        }
                    }
                }
                if (edge || size * st * st < 400) continue;
                regions++;
                area += size;
                if (!link) {
                    alone += size;
                    if (first == null) first = size * st * st + " square blocks at " + (-r + s0 / n * st) + " " + (-r + s0 % n * st);
                }
            }
            System.out.printf("seed %d: %d hall regions, %.1f%% of their area in halls open onto nothing (%s)%n", seed, regions, 100.0 * alone / Math.max(1, area),
                    first);
            assertTrue(regions > 10, "halls " + regions);
            assertTrue(alone <= 0.02 * area, alone + " of " + area + " hall columns in halls open onto nothing; first " + first);
        }
    }

    /** Every shape turns up over each kind of ground it belongs to, about as often as planned (printed for tuning). */
    @Test
    void census() {
        for (long seed : SEEDS) {
            UpperShapes s = new UpperShapes(seed, PACK_FLOOR, WORLD);
            int n = 0, warren = 0, rift = 0, hall = 0, pit = 0, hollow = 0, mouthCols = 0, springs = 0, roots = 0, icicles = 0, stal = 0;
            int r = 512, sr = 192;
            for (int x = -r; x < r; x += 2) for (int z = -r; z < r; z += 2) {
                UpperShapes.Col c = s.column(x, z);
                n++;
                if (c.wOn[0] && c.wLine[0] < c.wHw[0] || c.wOn[1] && c.wLine[1] < c.wHw[1]) warren++;
                if (c.rOn && c.rLine < 4) rift++;
                if (c.hOn) hall++;
                if (c.sOn && c.sScore > 0.55) {
                    if (c.desert) hollow++;
                    else pit++;
                }
                if (c.mouth > 0.9) mouthCols++;
                if (c.fKind == UpperShapes.ROOT) roots++;
                if (c.fKind == UpperShapes.ICICLE) icicles++;
                if (!Double.isNaN(c.stalBottom)) stal++;
            }
            for (int x = -sr; x < sr; x++) for (int z = -sr; z < sr; z++) {
                UpperShapes.Col c = s.column(x, z);
                if (c.rOn && s.spring(c) != Integer.MIN_VALUE) springs++;
            }
            double km2 = (2.0 * r) * (2.0 * r) / 1e6, springKm2 = (2.0 * sr) * (2.0 * sr) / 1e6;
            System.out.printf("seed %d over %.2f km2 (columns every 2 blocks, shares of columns): warren tunnels %.1f%%, rift %.1f%%, "
                            + "hall %.1f%%, sinkhole pits %.2f%%, desert hollows %.2f%%, mouths %.2f%%; roots %.2f%%, icicles %.2f%%, stalactites %.2f%% "
                            + "(of the halls %.1f%%); %d springs in the middle %.2f km2 (%.0f per km2)%n",
                    seed, km2, 100.0 * warren / n, 100.0 * rift / n, 100.0 * hall / n, 100.0 * pit / n, 100.0 * hollow / n,
                    100.0 * mouthCols / n, 100.0 * roots / n, 100.0 * icicles / n, 100.0 * stal / n, hall == 0 ? 0 : 100.0 * stal / hall, springs,
                    springKm2, springs / springKm2);
            assertTrue(warren > 0.10 * n, "warrens " + warren);
            assertTrue(rift > 0.01 * n, "rifts " + rift);
            assertTrue(hall > 0.01 * n, "halls " + hall);
            assertTrue(pit > 0 && hollow > 0 && mouthCols > 0, "pits " + pit + ", hollows " + hollow + ", mouths " + mouthCols);
            assertTrue(roots > 0 && icicles > 0 && stal > 0 && springs > 0, "roots " + roots + ", icicles " + icicles + ", stalactites " + stal
                    + ", springs " + springs);
        }
    }
}
