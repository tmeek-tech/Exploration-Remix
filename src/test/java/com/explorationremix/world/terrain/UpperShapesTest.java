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
            int still = 0, stream = 0, lakes = 0, spill = 0, unsealed = 0, shut = 0;
            String first = null, firstShut = null;
            for (int x = -256; x < 256; x++) for (int z = -256; z < 256; z++) {
                UpperShapes.Col c = s.column(x, z);
                if (c.lo > c.hi) continue;
                int lo = (int) c.lo, hi = (int) c.hi, rFloor = c.rOn ? c.rFloor : Integer.MIN_VALUE, sLake = c.sOn ? c.sLake : Integer.MIN_VALUE;
                for (int y = lo; y <= hi; y++) {
                    if (s.fluid(x, y, z) != UpperShapes.WATER) continue;
                    still++;
                    // the water itself is open: rock there would win over it (until 0.18.6 the middle of every lake did)
                    if (s.carve(x, y, z) >= 0 && shut++ == 0) firstShut = x + " " + y + " " + z;
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
            System.out.printf("seed %d: %d still water blocks (%d stream, %d lake) in 512 x 512; %d beside open air, %d beside unsealed rock (first: %s); "
                            + "%d shut as rock (first: %s)%n", seed, still, stream, lakes, spill, unsealed, first, shut, firstShut);
            assertTrue(still > 0 && stream > 0, "no pools or streams");
            assertEquals(0, spill, "water beside open air; first " + first);
            assertEquals(0, unsealed, "water beside rock the pack's caves may open; first " + first);
            assertEquals(0, shut, "water where the shapes are rock; first " + firstShut);
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

    /**
     * Sinkholes differ from one another (0.18.6, Taran: the same feature in every pit would make them cookie-cutter):
     * over a wide square some pits have shelves and some none, some a bank of fallen rock, about one in six a spring;
     * each spring found pours only into its own pit (rock over and under it, every open block beside it the pit's), and
     * every lake keeps its water with the banks in it.
     */
    @Test
    void sinkholesDiffer() {
        int[][] around = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, -1, 0}};
        int allSprings = 0;
        for (long seed : SEEDS) {
            UpperShapes s = new UpperShapes(seed, PACK_FLOOR, WORLD);
            java.util.Map<Long, int[]> pits = new java.util.LinkedHashMap<>();
            for (int x = -1536; x < 1536; x += 6) for (int z = -1536; z < 1536; z += 6) {
                UpperShapes.Col c = s.column(x, z);
                if (!c.sOn || c.sScore < 0.55) continue;
                pits.putIfAbsent(((long) c.sCx << 32) ^ (c.sCz & 0xffffffffL), new int[]{c.sCx, c.sCz, x, z, c.sLake});
            }
            int shelves = 0, banks = 0, falls = 0, springs = 0, leaks = 0, hollows = 0;
            java.util.Set<String> looks = new java.util.HashSet<>();
            String firstLeak = null;
            for (int[] p : pits.values()) {
                boolean sh = s.pitTrait(p[2], p[3], UpperShapes.TRAIT_SHELVES) >= 0.3;
                boolean bank = s.pitTrait(p[2], p[3], UpperShapes.TRAIT_SLOPE) <= 0.45;
                boolean fall = s.pitTrait(p[2], p[3], UpperShapes.TRAIT_FALL) <= UpperShapes.FALL_SHARE;
                boolean ivy = s.pitTrait(p[2], p[3], UpperShapes.TRAIT_IVY) < 0.5;
                if (sh) shelves++;
                if (bank) banks++;
                if (fall) falls++;
                int[] sp = s.findSpring(p[0], p[1]);
                looks.add(sh + " " + bank + " " + (sp != null) + " " + ivy);
                // nothing pours into a desert's hollow (Taran, 18 Nov)
                if (s.column(p[0], p[1]).desert) {
                    assertNull(sp, "a spring in the hollow at " + p[0] + " " + p[1]);
                    hollows++;
                }
                if (sp != null) {
                    springs++;
                    int x = sp[0], y = sp[1], z = sp[2];
                    assertEquals(UpperShapes.SPRING, s.fluid(x, y, z), "spring at " + x + " " + y + " " + z);
                    assertTrue(s.carve(x, y - 1, z) > 0 && s.carve(x, y + 1, z) > 0, "spring at " + x + " " + y + " " + z + " not shut over and under");
                    int intoPit = 0;
                    for (int k = 0; k < 4; k++) {
                        int nx = x + around[k][0], nz = z + around[k][2];
                        if (s.carve(nx, y, nz) >= 0) continue;
                        assertEquals(UpperShapes.SINKHOLES, s.kindAt(nx, y, nz), "spring at " + x + " " + y + " " + z + " opens onto another shape");
                        intoPit++;
                    }
                    assertTrue(intoPit > 0, "spring at " + x + " " + y + " " + z + " opens onto nothing");
                }
                // the lake round the middle keeps its water, banks and all
                for (int x = p[0] - 28; x <= p[0] + 28; x++) for (int z = p[1] - 28; z <= p[1] + 28; z++)
                    for (int y = p[4] - 2; y <= p[4]; y++) {
                        if (s.fluid(x, y, z) != UpperShapes.WATER) continue;
                        for (int[] d : around) {
                            int nx = x + d[0], ny = y + d[1], nz = z + d[2];
                            if (s.fluid(nx, ny, nz) == UpperShapes.WATER || s.carve(nx, ny, nz) >= 0) continue;
                            leaks++;
                            if (firstLeak == null) firstLeak = nx + " " + ny + " " + nz + " beside water at " + x + " " + y + " " + z;
                        }
                    }
            }
            int n = pits.size();
            System.out.printf("seed %d: %d pits in 3072 x 3072 (%d desert hollows); shelves in %d, banks in %d, the spring trait in %d, "
                    + "springs found in %d; %d different looks (shelves, bank, spring, heavy ivy); %d lake blocks beside air (first: %s)%n",
                    seed, n, hollows, shelves, banks, falls, springs, looks.size(), leaks, firstLeak);
            assertTrue(n >= 12, "only " + n + " pits");
            assertTrue(shelves > 0.4 * n && shelves < 0.95 * n, "shelves in " + shelves + " of " + n);
            assertTrue(banks > 0.2 * n && banks < 0.7 * n, "banks in " + banks + " of " + n);
            assertTrue(falls < 0.45 * n, "the spring trait in " + falls + " of " + n);
            int green = n - hollows;
            assertTrue(springs > 0.08 * green && springs < 0.3 * green, "springs in " + springs + " of " + green + " green pits");
            assertTrue(springs <= falls, springs + " springs but " + falls + " pits with the trait");
            assertTrue(looks.size() >= 6, "only " + looks.size() + " looks");
            assertEquals(0, leaks, "lake water beside open air; first " + firstLeak);
            allSprings += springs;
        }
        assertTrue(allSprings > 0, "no sinkhole springs");
    }

    /**
     * The trees' roots through a forest's warrens (0.18.6): strands over a share of the walls and ceilings, never across
     * the middle of a floor away from a pillar, never loose of the rock (each root's blocks hang together with rock that
     * is no root); and pillars down to the floors of some of the wider rooms, solid from floor to roof down their middle,
     * with the room open round them.
     */
    @Test
    void rootsHoldToTheRock() {
        int[][] nb = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (long seed : SEEDS) {
            UpperShapes s = new UpperShapes(seed, PACK_FLOOR, WORLD);
            // the pillars of a 4 km square
            java.util.List<UpperShapes.Pillar> pillars = new java.util.ArrayList<>();
            int cells = 4096 / UpperShapes.PILLAR_CELL;
            for (int cx = -cells / 2; cx < cells / 2; cx++) for (int cz = -cells / 2; cz < cells / 2; cz++)
                for (int i = 0; i < 2; i++) {
                    UpperShapes.Pillar p = s.pillarOf(cx, cz, i);
                    if (p != null) pillars.add(p);
                }
            int shut = 0, buried = 0;
            String firstShut = null, firstBuried = null;
            for (UpperShapes.Pillar p : pillars) {
                int px = (int) Math.floor(p.x), pz = (int) Math.floor(p.z);
                int fl = (int) Math.floor(p.floor - 0.5) + 1, mid = (int) Math.floor((p.floor + p.ceil) / 2);
                for (int y = fl; y < (int) Math.floor(p.ceil); y++)
                    if (s.carve(px, y, pz) < 0 || s.formation(px, y, pz) != UpperShapes.ROOT) {
                        shut++;
                        if (firstShut == null) firstShut = px + " " + y + " " + pz;
                        break;
                    }
                // a way past it on the side away from its wall: three or more open blocks across, a block over the floor
                // and halfway up
                UpperShapes.Col m = s.column(px, pz);
                double ux = -m.wNx[p.sheet], uz = -m.wNz[p.sheet];
                for (int y : new int[]{fl + 1, mid}) {
                    int run = 0, best = 0;
                    boolean left = false;
                    for (double r = 0; r < 14; r += 0.5) {
                        int bx = (int) Math.floor(p.x + r * ux), bz = (int) Math.floor(p.z + r * uz);
                        boolean open = s.carve(bx, y, bz) < 0;
                        if (!left) {
                            left = open;
                            if (!open) continue;
                        }
                        // the longest open run (gaps between its own strands come first)
                        run = open ? run + 1 : 0;
                        best = Math.max(best, run);
                    }
                    if (best < 6) {
                        buried++;
                        if (firstBuried == null) firstBuried = px + " " + y + " " + pz + " (" + best / 2.0 + " blocks open past it)";
                        break;
                    }
                }
            }
            // the strands, over a square of the forests
            int surface = 0, visible = 0, roots = 0, loose = 0, floorMiddle = 0;
            String firstLoose = null, firstFloor = null;
            java.util.Set<Long> seen = new java.util.HashSet<>();
            for (int x = -384; x < 384; x += 2) for (int z = -384; z < 384; z += 2) {
                UpperShapes.Col c = s.column(x, z);
                if (!c.forest) continue;
                int g = (int) Math.floor(c.gF);
                for (int i = 0; i < 2; i++) {
                    if (!c.wOn[i] || c.wLine[i] > 1.5 * c.wHw[i]) continue;
                    int fl = (int) Math.floor(c.wYc[i] - 0.6 * c.wHh[i] - 0.5) + 1;
                    for (int y = Math.max(fl - 1, g - 30); y <= Math.min((int) Math.ceil(c.wYc[i] + c.wHh[i]) + 1, g - 2); y++) {
                        boolean root = s.formation(x, y, z) == UpperShapes.ROOT;
                        boolean open = s.carve(x, y, z) < 0;
                        boolean bySolid = false, byOpen = false;
                        for (int[] d : nb) {
                            double v = s.carve(x + d[0], y + d[1], z + d[2]);
                            if (v >= 0) bySolid = true;
                            else byOpen = true;
                        }
                        if (open && bySolid) surface++;
                        if (!root) continue;
                        roots++;
                        if (byOpen) visible++;
                        if (y == fl && c.wLine[i] < 0.4 * c.wHw[i] && !nearPillar(s, x, z)) {
                            floorMiddle++;
                            if (firstFloor == null) firstFloor = x + " " + y + " " + z;
                        }
                        // the root's blocks hang together with rock that is no root
                        long key = ((long) x << 40) ^ ((long) (y & 0xFFFF) << 24) ^ (z & 0xFFFFFF);
                        if (seen.contains(key)) continue;
                        if (!anchored(s, x, y, z, seen)) {
                            loose++;
                            if (loose <= 4) {
                                boolean stub = c.fKind == UpperShapes.ROOT && y + 0.5 >= c.fBottom && y + 0.5 <= c.fTop;
                                firstLoose = (firstLoose == null ? "" : firstLoose + "; and ") + x + " " + y + " " + z + " (" + (stub ? "a stub"
                                        : nearPillar(s, x, z) ? "near a pillar" : "a strand") + "; " + s.describe(x, z) + ")";
                            }
                        }
                    }
                }
            }
            double km2 = 4096.0 * 4096.0 / 1e6;
            System.out.printf("seed %d: %d root pillars in %.1f km2 (%.1f per km2), %d not shut down the middle (first %s), %d buried (first %s); "
                            + "strands: %d root blocks over %d blocks of wall (every second column), %d showing (%.1f%%), %d loose (first %s), "
                            + "%d on a floor's middle (first %s)%n",
                    seed, pillars.size(), km2, pillars.size() / km2, shut, firstShut, buried, firstBuried, roots, surface, visible,
                    100.0 * visible / Math.max(1, surface), loose, firstLoose, floorMiddle, firstFloor);
            assertTrue(pillars.size() >= 5, "only " + pillars.size() + " pillars");
            assertTrue(pillars.size() / km2 < 60, pillars.size() + " pillars");
            assertEquals(0, shut, "pillars open down the middle; first at " + firstShut);
            assertEquals(0, buried, "pillars with no way past them; first at " + firstBuried);
            assertTrue(visible > 0.03 * surface && visible < 0.3 * surface, visible + " root blocks showing over " + surface + " of wall");
            assertEquals(0, loose, "roots loose of the rock; first at " + firstLoose);
            assertEquals(0, floorMiddle, "roots across a floor; first at " + firstFloor);
        }
    }

    private static boolean nearPillar(UpperShapes s, int x, int z) {
        int cell = UpperShapes.PILLAR_CELL;
        for (int cx = Math.floorDiv(x - 6, cell); cx <= Math.floorDiv(x + 6, cell); cx++)
            for (int cz = Math.floorDiv(z - 6, cell); cz <= Math.floorDiv(z + 6, cell); cz++)
                for (int i = 0; i < 2; i++) {
                    UpperShapes.Pillar p = s.pillarOf(cx, cz, i);
                    if (p != null && Math.hypot(x + 0.5 - p.x, z + 0.5 - p.z) < 6) return true;
                }
        return false;
    }

    /** Whether the root blocks joined to one reach rock that is no root (a flood through up to 2000 root blocks). */
    private static boolean anchored(UpperShapes s, int x0, int y0, int z0, java.util.Set<Long> seen) {
        int[][] nb = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        ArrayDeque<int[]> q = new ArrayDeque<>();
        java.util.Set<Long> mine = new java.util.HashSet<>();
        q.add(new int[]{x0, y0, z0});
        mine.add(((long) x0 << 40) ^ ((long) (y0 & 0xFFFF) << 24) ^ (z0 & 0xFFFFFF));
        boolean held = false;
        while (!q.isEmpty() && mine.size() < 2000) {
            int[] v = q.poll();
            for (int[] d : nb) {
                int x = v[0] + d[0], y = v[1] + d[1], z = v[2] + d[2];
                if (s.carve(x, y, z) < 0) continue;
                if (s.formation(x, y, z) != UpperShapes.ROOT) {
                    held = true;
                    continue;
                }
                long key = ((long) x << 40) ^ ((long) (y & 0xFFFF) << 24) ^ (z & 0xFFFFFF);
                if (mine.add(key)) q.add(new int[]{x, y, z});
            }
            if (held) break;
        }
        seen.addAll(mine);
        return held || mine.size() >= 2000;
    }

    /**
     * The forests' ways in, mixed by place (Taran, 18 Nov): where the slow field calls for long sunken ways a mouth's
     * tunnel opens to the sky most of the way along it; where it calls for roofed mouths it keeps a roof of soil and opens
     * only here and there, in short mouths. Measured a block over the ground estimate, where the pack's real ground often
     * lies, and by whole openings, so a roofed mouth's few openings are mouths a player can walk into, not skylights.
     */
    @Test
    void mouthsMixByPlace() {
        for (long seed : SEEDS) {
            UpperShapes s = new UpperShapes(seed, PACK_FLOOR, WORLD);
            int r = 1024, n = 2 * r;
            // per column over a mouth's tunnel: 1 a roofed mouth's, 2 the same open to the sky, 3 a long way's, 4 the same open
            byte[] k = new byte[n * n];
            for (int x = -r; x < r; x++) for (int z = -r; z < r; z++) {
                UpperShapes.Col c = s.column(x, z);
                if (!c.forest || c.mouth < 0.15 || !c.wOn[0] || c.wLine[0] > c.wHw[0]) continue;
                double f = s.roofed(x, z);
                if (f > 0.1 && f < 0.9) continue;
                boolean open = s.carve(x, (int) Math.floor(c.gF) + 1, z) < 0;
                k[(x + r) * n + z + r] = (byte) ((f >= 0.9 ? 1 : 3) + (open ? 1 : 0));
            }
            int[] cols = new int[2], open = new int[2];
            List<List<Integer>> sizes = List.of(new ArrayList<>(), new ArrayList<>());
            boolean[] seen = new boolean[n * n];
            ArrayDeque<Integer> todo = new ArrayDeque<>();
            for (int i = 0; i < n * n; i++) {
                if (k[i] == 0) continue;
                int kind = k[i] <= 2 ? 1 : 0;
                cols[kind]++;
                if ((k[i] & 1) == 1 || seen[i]) continue;
                // a whole opening: the open columns of the same kind joined to this one
                int size = 0;
                seen[i] = true;
                todo.add(i);
                while (!todo.isEmpty()) {
                    int j = todo.poll(), jx = j / n, jz = j % n;
                    size++;
                    for (int d = 0; d < 4; d++) {
                        int ax = jx + (d == 0 ? 1 : d == 1 ? -1 : 0), az = jz + (d == 2 ? 1 : d == 3 ? -1 : 0);
                        if (ax < 0 || az < 0 || ax >= n || az >= n) continue;
                        int a = ax * n + az;
                        if (k[a] == k[i] && !seen[a]) {
                            seen[a] = true;
                            todo.add(a);
                        }
                    }
                }
                open[kind] += size;
                sizes.get(kind).add(size);
            }
            double ways = open[0] / (double) Math.max(1, cols[0]), roofs = open[1] / (double) Math.max(1, cols[1]);
            List<Integer> holes = sizes.get(1);
            Collections.sort(holes);
            int median = holes.isEmpty() ? 0 : holes.get(holes.size() / 2);
            long mouths = holes.stream().filter(v -> v >= 20).count();
            System.out.printf("seed %d: mouth columns open to the sky a block over the ground: %d of %d (%.0f%%) where the ways are long and "
                            + "sunken, %d of %d (%.0f%%) where they are roofed, in %d openings (%d of 20+ columns, the middle one %d)%n", seed, open[0],
                    cols[0], 100 * ways, open[1], cols[1], 100 * roofs, holes.size(), mouths, median);
            assertTrue(cols[0] > 1000 && cols[1] > 1000, "mouth columns " + cols[0] + " and " + cols[1]);
            assertTrue(ways > 0.2, "the long ways open in only " + open[0] + " of " + cols[0]);
            assertTrue(roofs > 0.03 && roofs < 0.5 * ways, "the roofed mouths open in " + open[1] + " of " + cols[1]);
            assertTrue(median >= 15 && mouths >= 10, "the roofed mouths' openings: " + holes.size() + ", the middle one " + median + " columns, "
                    + mouths + " of 20+");
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
