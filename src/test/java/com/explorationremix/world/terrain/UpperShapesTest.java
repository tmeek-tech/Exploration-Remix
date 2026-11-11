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
 * and deserts, rifts open to the sky); nothing opens under a river; nothing opens down by the den gates; pools and
 * lakes keep their water; the open space hangs together; every shape turns up, about as often as planned.
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
     * Within 3 blocks under the ground nothing opens except at a way in: a mouth under a forest, a pit under plains or a
     * desert, or a rift where open dry land lies round it (a mouth or rift never under a river's own column).
     */
    @Test
    void groundStaysShutExceptTheWaysIn() {
        for (long seed : SEEDS) {
            UpperShapes s = new UpperShapes(seed, PACK_FLOOR, WORLD);
            int open = 0, mouths = 0, pits = 0, skies = 0;
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
                    assertTrue(c.rOn, "open under the ground" + at);
                    boolean dry = false;
                    for (int dx = -4; dx <= 4; dx += 4) for (int dz = -4; dz <= 4; dz += 4) {
                        Overhead.Surface n = WORLD.at(x + dx, z + dz);
                        dry |= n.ravineOk() && !n.wet();
                    }
                    assertTrue(dry, "a rift open to the sky away from open dry land" + at);
                    skies++;
                }
            }
            System.out.printf("seed %d: %d columns open at the ground (every 3rd block): %d mouth, %d pit, %d sky%n", seed, open, mouths, pits, skies);
            assertTrue(mouths > 0 && pits > 0 && skies > 0, "every way in turns up: " + mouths + " " + pits + " " + skies);
        }
    }

    /** Under a river (the made-up world's wet strips) nothing but a rift's sky slot opens within 8 blocks of its bed. */
    @Test
    void riversStayShut() {
        for (long seed : SEEDS) {
            UpperShapes s = new UpperShapes(seed, PACK_FLOOR, WORLD);
            int sky = 0;
            for (int x = -1200; x < 1200; x += 2) for (int zr = -700; zr < 700; zr += 700) for (int z = zr; z < zr + 40; z += 2) {
                int g = WORLD.at(x, z).y();
                assertTrue(WORLD.at(x, z).wet());
                for (int y = g - 8; y <= g; y++) {
                    if (s.carve(x, y, z) >= 0) continue;
                    UpperShapes.Col c = s.column(x, z);
                    assertTrue(c.rOn && s.kindAt(x, y, z) == UpperShapes.RIFTS, "open under a river at " + x + " " + y + " " + z + ": " + s.describe(x, z));
                    sky++;
                    break;
                }
            }
            System.out.printf("seed %d: %d river columns over a rift's sky slot (every 2nd block)%n", seed, sky);
        }
    }

    /** Nothing opens below the shapes' floor, where the den gates' landings and ramps lie. */
    @Test
    void clearOfTheDenGates() {
        for (long seed : SEEDS) {
            UpperShapes s = new UpperShapes(seed, PACK_FLOOR, WORLD);
            int top = PACK_FLOOR + UpperShapes.FLOOR_CLEARANCE - 2;
            for (int x = -1200; x < 1200; x += 4) for (int z = -1200; z < 1200; z += 4) {
                for (int y = PACK_FLOOR; y < top; y++) {
                    assertTrue(s.carve(x, y, z) >= 0, "open at " + x + " " + y + " " + z);
                    assertEquals(-1, s.fluid(x, y, z), "water or air claimed at " + x + " " + y + " " + z);
                }
            }
        }
    }

    /**
     * Still water stays put: next to a pool or a sinkhole's lake lies water or rock, not open air, nearly everywhere (a
     * pool beside a rift may spill into it as a small fall). The rifts' streams and springs flow on purpose.
     */
    @Test
    void poolsAndLakesHoldTheirWater() {
        for (long seed : SEEDS) {
            UpperShapes s = new UpperShapes(seed, PACK_FLOOR, WORLD);
            int still = 0, spill = 0, pools = 0, lakes = 0;
            String first = null;
            for (int x = -256; x < 256; x++) for (int z = -256; z < 256; z++) {
                UpperShapes.Col c = s.column(x, z);
                if (c.lo > c.hi) continue;
                for (int y = (int) c.lo; y <= (int) c.hi; y++) {
                    boolean pool = s.pool(s.column(x, z), y);
                    c = s.column(x, z);
                    boolean lake = c.sOn && y <= c.sLake && s.fluid(x, y, z) == UpperShapes.WATER;
                    if (!pool && !lake) continue;
                    if (s.carve(x, y, z) >= 0) continue;
                    // a rift's stream or spring in the same column flows anyway
                    if (c.rOn && s.kindAt(x, y, z) == UpperShapes.RIFTS) continue;
                    still++;
                    if (pool) pools++;
                    else lakes++;
                    int[][] around = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, -1, 0}};
                    for (int[] d : around) {
                        int nx = x + d[0], ny = y + d[1], nz = z + d[2];
                        if (s.carve(nx, ny, nz) < 0 && s.fluid(nx, ny, nz) != UpperShapes.WATER) {
                            spill++;
                            if (first == null) first = nx + " " + ny + " " + nz + " beside " + (pool ? "a pool" : "a lake") + " at " + x + " " + y + " " + z;
                            break;
                        }
                    }
                    c = s.column(x, z);
                }
            }
            System.out.printf("seed %d: %d still water blocks (%d pool, %d lake) in 512 x 512, %d beside open air (first: %s)%n", seed, still, pools, lakes,
                    spill, first);
            assertTrue(still > 0, "no pools or lakes");
            assertTrue(spill <= 0.03 * still, spill + " of " + still + " still water blocks spill; first " + first);
        }
    }

    /**
     * The open space hangs together: in a 192-block box, nearly all of it belongs to pieces that reach the box's sides
     * or the sky. A closed pocket would be a dead end the pack's caves may or may not open.
     */
    @Test
    void openSpaceHangsTogether() {
        for (long seed : SEEDS) {
            UpperShapes s = new UpperShapes(seed, PACK_FLOOR, WORLD);
            int n = 192, y0 = -10, h = 125, x0 = 40, z0 = -320;
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
            int n = 0, warren = 0, rift = 0, sky = 0, hall = 0, pit = 0, hollow = 0, mouthCols = 0, springs = 0, roots = 0, icicles = 0, stal = 0;
            int r = 512, sr = 192;
            for (int x = -r; x < r; x += 2) for (int z = -r; z < r; z += 2) {
                UpperShapes.Col c = s.column(x, z);
                n++;
                if (c.wOn[0] && c.wLine[0] < c.wHw[0] || c.wOn[1] && c.wLine[1] < c.wHw[1]) warren++;
                if (c.rOn && c.rLine < 4) {
                    rift++;
                    if (c.rSky) sky++;
                }
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
            System.out.printf("seed %d over %.2f km2 (columns every 2 blocks, shares of columns): warren tunnels %.1f%%, rift %.1f%% (%.2f%% open to the sky), "
                            + "hall %.1f%%, sinkhole pits %.2f%%, desert hollows %.2f%%, mouths %.2f%%; roots %.2f%%, icicles %.2f%%, stalactites %.2f%% "
                            + "(of the halls %.1f%%); %d springs in the middle %.2f km2 (%.0f per km2)%n",
                    seed, km2, 100.0 * warren / n, 100.0 * rift / n, 100.0 * sky / n, 100.0 * hall / n, 100.0 * pit / n, 100.0 * hollow / n,
                    100.0 * mouthCols / n, 100.0 * roots / n, 100.0 * icicles / n, 100.0 * stal / n, hall == 0 ? 0 : 100.0 * stal / hall, springs,
                    springKm2, springs / springKm2);
            assertTrue(warren > 0.10 * n, "warrens " + warren);
            assertTrue(rift > 0.01 * n, "rifts " + rift);
            assertTrue(hall > 0.01 * n, "halls " + hall);
            assertTrue(pit > 0 && hollow > 0 && mouthCols > 0 && sky > 0, "pits " + pit + ", hollows " + hollow + ", mouths " + mouthCols + ", sky " + sky);
            assertTrue(roots > 0 && icicles > 0 && stal > 0 && springs > 0, "roots " + roots + ", icicles " + icicles + ", stalactites " + stal
                    + ", springs " + springs);
        }
    }
}
