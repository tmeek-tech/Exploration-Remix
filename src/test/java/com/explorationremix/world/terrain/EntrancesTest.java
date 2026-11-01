package com.explorationremix.world.terrain;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks of the entrances' promises against a made-up world: each kind of entrance only under its own ground and never
 * under water; Root Vault chambers a few blocks under the ground, of mixed sizes and close together; every site open to
 * the surface, every chamber of a site reachable from the others, every chamber with two ways out; descents that end
 * in the pack's caves; water that stays in its basins; the biomes never near the surface; the same answers on every
 * thread.
 */
class EntrancesTest {
    private static final long[] SEEDS = {8675309L, 12345L};
    private static final int PACK_FLOOR = -64;
    /** The checks walk cells -N .. N-1 on both axes. */
    private static final int N = 6;

    /**
     * Rolling ground around y 90 in strips 160 blocks wide along x: oak forest, snowy spruce forest, plains, jungle,
     * desert, snowy plains, badlands. A river 40 blocks wide every 700 blocks along z. The pack's caves are tubes along
     * x every 48 blocks of z, about 40 under the ground, and along z every 56 of x, about 58 under.
     */
    static final class World implements Overhead {
        static int strip(int x) { return Math.floorMod(Math.floorDiv(x, 160), 7); }

        @Override
        public Surface at(int x, int z) {
            int y = ground(x, z);
            boolean wet = Math.floorMod(z, 700) < 40;
            int s = strip(x);
            RootKind k = RootKind.NONE;
            Cover c = Cover.OTHER;
            boolean cold = false;
            if (!wet) switch (s) {
                case 0 -> { k = RootKind.OAK; c = Cover.FOREST; }
                case 1 -> { k = RootKind.SPRUCE; c = Cover.FOREST; cold = true; }
                case 2 -> c = Cover.PLAINS;
                case 3 -> { k = RootKind.JUNGLE; c = Cover.FOREST; }
                case 4 -> c = Cover.DESERT;
                case 5 -> { c = Cover.PLAINS; cold = true; }
                default -> c = Cover.BADLANDS;
            }
            return new Surface(y, k, wet, !wet && s != 3 && y >= 67, c, cold);
        }

        static int ground(int x, int z) {
            return 90 + (int) Math.round(6 * Math.sin(x / 47.0) + 5 * Math.cos(z / 39.0) + 2 * Math.sin((x + z) / 13.0));
        }

        @Override
        public double packDensity(int x, int y, int z) {
            int g = ground(x, z);
            if (y > g) return -1;
            double zc = Math.round((z - 20) / 48.0) * 48.0 + 20;
            double ya = g - 40 + 6 * Math.sin(x / 37.0);
            double best = Math.hypot(z - zc, y - ya) - 3.5;
            double xc = Math.round((x - 30) / 56.0) * 56.0 + 30;
            double yb = g - 58 + 5 * Math.cos(z / 29.0);
            best = Math.min(best, Math.hypot(x - xc, y - yb) - 4.0);
            return Math.max(-1, Math.min(1, best / 6.0));
        }
    }

    private static final World WORLD = new World();

    private static List<Entrances.Site> sites(Entrances e) { return sites(e, N); }

    private static List<Entrances.Site> sites(Entrances e, int n) {
        List<Entrances.Site> out = new ArrayList<>();
        for (int i = -n; i < n; i++) for (int j = -n; j < n; j++) {
            Entrances.Site s = e.site(i, j);
            if (s != null) out.add(s);
        }
        return out;
    }

    /** Over 24 x 24 cells (all seven strips three or four times): each kind of site only under its own ground. */
    @Test
    void eachKindOnlyUnderItsGround() {
        int vaults = 0, sinkholes = 0, oases = 0;
        for (long seed : SEEDS) {
            List<Entrances.Site> sites = sites(new Entrances(seed, PACK_FLOOR, WORLD), 12);
            for (Entrances.Site s : sites) {
                switch (s.kind) {
                    case VAULTS -> vaults++;
                    case SINKHOLE -> sinkholes++;
                    case OASIS -> oases++;
                }
                // Near a strip's edge the chambers may straddle it: the ground over one of them must match.
                boolean match = false;
                for (Entrances.Chamber c : s.chambers) {
                    Overhead.Surface su = WORLD.at((int) Math.floor(c.cx), (int) Math.floor(c.cz));
                    if (su.wet()) continue;
                    boolean kind = switch (s.kind) {
                        case VAULTS -> su.cover() == Overhead.Cover.FOREST;
                        case SINKHOLE -> su.cover() == Overhead.Cover.PLAINS;
                        case OASIS -> su.cover() == (s.red ? Overhead.Cover.BADLANDS : Overhead.Cover.DESERT);
                    };
                    match |= kind && su.cold() == s.cold;
                }
                assertTrue(match, s.describe() + " at " + (int) s.cx + " " + (int) s.cz + " is not under its own ground");
            }
        }
        // About 85 % of the forest cells grow Root Vaults and a fifth of the plains', deserts' and badlands' cells a
        // sinkhole or an oasis (2 x 210, 2 x 126 and 2 x 168 cells here).
        assertTrue(vaults >= 250 && sinkholes >= 20 && oases >= 30, "vaults " + vaults + ", sinkholes " + sinkholes + ", oases " + oases);
    }

    /** Root Vault clusters: 2 to 5 chambers, mixed sizes, neighbours close; roofs a few blocks under the ground. */
    @Test
    void rootVaultsAreShallowVariedAndClose() {
        int[] sizes = new int[3];
        double gaps = 0;
        int pairs = 0, roofLo = 99, roofHi = -99;
        for (long seed : SEEDS) {
            Entrances e = new Entrances(seed, PACK_FLOOR, WORLD);
            for (Entrances.Site s : sites(e)) {
                if (s.kind != Entrances.Kind.VAULTS) continue;
                assertTrue(s.chambers.size() >= 2 && s.chambers.size() <= 5, s.describe());
                for (Entrances.Chamber c : s.chambers) {
                    sizes[c.size]++;
                    double near = 1e9;
                    for (Entrances.Chamber o : s.chambers) if (o != c) near = Math.min(near, Math.hypot(o.cx - c.cx, o.cz - c.cz));
                    gaps += near;
                    pairs++;
                    if (c.look == Entrances.RAVINE) continue;
                    double reach = Entrances.reach(c.look, c.a, c.b);
                    double top = -1e9;
                    for (int x = (int) Math.floor(c.cx - reach); x <= c.cx + reach; x += 3)
                        for (int z = (int) Math.floor(c.cz - reach); z <= c.cz + reach; z += 3) {
                            int g = World.ground(x, z);
                            for (int y = g - 40; y <= g + 2; y++) {
                                if (e.shapeDist(c, x, y, z) >= 0) continue;
                                top = Math.max(top, y);
                                assertTrue(y <= g - Entrances.COLUMN_ROOF, s.describe() + " " + c.lookName() + " open " + (g - y) + " under the ground");
                            }
                        }
                    int under = (int) (c.groundMin - top);
                    roofLo = Math.min(roofLo, under);
                    roofHi = Math.max(roofHi, under);
                }
            }
        }
        assertTrue(sizes[Entrances.SMALL] > 0 && sizes[Entrances.MEDIUM] > 0 && sizes[Entrances.LARGE] > 0,
                "sizes " + sizes[0] + " " + sizes[1] + " " + sizes[2]);
        assertTrue(gaps / pairs < 32, "mean distance to the nearest chamber " + gaps / pairs);
        assertTrue(roofLo >= Entrances.COLUMN_ROOF && roofHi <= 16, "roofs " + roofLo + " to " + roofHi + " under the lowest ground");
    }

    /** Every site opens to the surface, its chambers reach each other, and its descents reach the pack's caves. */
    @Test
    void sitesAreConnected() {
        Entrances e = new Entrances(SEEDS[0], PACK_FLOOR, WORLD);
        int checked = 0;
        for (Entrances.Site s : sites(e)) {
            Set<Long> seen = flood(e, s, s.chambers.get(0));
            boolean out = false;
            for (long k : seen) {
                int[] p = unkey(k);
                if (p[1] > World.ground(p[0], p[2]) + 1) { out = true; break; }
            }
            assertTrue(out, "no way out to the surface from " + s.describe() + " at " + (int) s.cx + " " + (int) s.cz);
            for (Entrances.Chamber c : s.chambers) {
                int x = (int) Math.floor(c.cx), z = (int) Math.floor(c.cz);
                boolean found = false;
                for (int y = (int) c.water - 3; y <= c.water + 6 && !found; y++) found = seen.contains(key(x, y, z));
                assertTrue(found, c.lookName() + " at " + x + " " + z + " cut off in " + s.describe());
            }
            for (Entrances.Tunnel t : e.tunnelsOf(s)) {
                if (t.way != Entrances.Way.DESCENT) continue;
                double[] en = t.end();
                assertTrue(WORLD.packDensity((int) Math.floor(en[0]), (int) Math.floor(en[1]), (int) Math.floor(en[2])) < 0,
                        "a descent of " + s.describe() + " ends in rock");
            }
            if (++checked >= 24) break;
        }
        assertTrue(checked >= 10, "only " + checked + " sites");
    }

    /** Water stays in its basins: beside every water block is water or rock the pack's caves may not open. */
    @Test
    void waterStaysPut() {
        for (long seed : SEEDS) {
            Entrances e = new Entrances(seed, PACK_FLOOR, WORLD);
            for (Entrances.Site s : sites(e)) for (Entrances.Chamber c : s.chambers) {
                double reach = Entrances.reach(c.look, c.a, c.b) + 4;
                for (int x = (int) Math.floor(c.cx - reach); x <= c.cx + reach; x += 2)
                    for (int z = (int) Math.floor(c.cz - reach); z <= c.cz + reach; z += 2) {
                        if (!e.anyNear(x, z)) continue;
                        for (int y = (int) c.water - 6; y <= c.water + 1; y++) {
                            if (e.carve(x, y, z) >= 0 || e.fluid(x, y, z) != Entrances.WATER) continue;
                            int[][] nb = {{x + 1, y, z}, {x - 1, y, z}, {x, y, z + 1}, {x, y, z - 1}, {x, y - 1, z}};
                            for (int[] n : nb) {
                                if (e.carve(n[0], n[1], n[2]) < 0) {
                                    assertEquals(Entrances.WATER, e.fluid(n[0], n[1], n[2]), "water runs out of " + c.lookName() + " at " + x + " " + y + " " + z);
                                } else {
                                    assertTrue(e.seals(n[0], n[1], n[2]), "unsealed rock beside the water of " + c.lookName() + " at " + x + " " + y + " " + z);
                                }
                            }
                        }
                    }
            }
        }
    }

    /** The entrances' biomes stay at least 6 blocks under the ground, so the surface keeps its own. */
    @Test
    void biomesStayUnderground() {
        Entrances e = new Entrances(SEEDS[1], PACK_FLOOR, WORLD);
        Random rnd = new Random(7);
        for (Entrances.Site s : sites(e)) for (int k = 0; k < 200; k++) {
            int x = (int) Math.floor(s.cx + (rnd.nextDouble() * 2 - 1) * 40), z = (int) Math.floor(s.cz + (rnd.nextDouble() * 2 - 1) * 40);
            int g = World.ground(x, z);
            for (int y = g - 5; y <= g + 8; y++) assertNull(e.areaAt(x, y, z), "an entrance biome " + (g - y) + " under the ground at " + x + " " + z);
        }
    }

    @Test
    void sameAnswersOnEveryThread() throws Exception {
        long seed = SEEDS[0];
        Entrances ref = new Entrances(seed, PACK_FLOOR, WORLD);
        Random rnd = new Random(11);
        int[][] pts = new int[3000][];
        for (int i = 0; i < pts.length; i++) {
            int x = rnd.nextInt(700) - 350, z = rnd.nextInt(700) - 350;
            pts[i] = new int[]{x, World.ground(x, z) - 4 - rnd.nextInt(40), z};
        }
        double[] want = new double[pts.length];
        for (int i = 0; i < pts.length; i++) want[i] = ref.carve(pts[i][0], pts[i][1], pts[i][2]);
        Entrances shared = new Entrances(seed, PACK_FLOOR, WORLD);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<double[]>> fs = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                final int off = t;
                fs.add(pool.submit(() -> {
                    double[] got = new double[pts.length];
                    for (int k = 0; k < pts.length; k++) {
                        int i = (k + off * 777) % pts.length;
                        got[i] = shared.carve(pts[i][0], pts[i][1], pts[i][2]);
                    }
                    return got;
                }));
            }
            for (Future<double[]> f : fs) assertArrayEquals(want, f.get(), 1e-12);
        } finally {
            pool.shutdown();
        }
    }

    static long key(int x, int y, int z) { return ((long) (x + 4096) << 40) | ((long) (y + 1024) << 20) | (z + 4096); }

    static int[] unkey(long k) { return new int[]{(int) (k >>> 40) - 4096, (int) ((k >>> 20) & 0xFFFFF) - 1024, (int) (k & 0xFFFFF) - 4096}; }

    /** Open blocks (the entrance, the pack's caves, the air over the ground) reachable from a chamber, within 100 blocks. */
    static Set<Long> flood(Entrances e, Entrances.Site s, Entrances.Chamber c) {
        Set<Long> seen = new HashSet<>();
        ArrayDeque<int[]> q = new ArrayDeque<>();
        int x0 = (int) Math.floor(c.cx), z0 = (int) Math.floor(c.cz);
        for (int y = (int) c.water - 2; y <= c.water + 8; y++) if (e.carve(x0, y, z0) < 0) { q.add(new int[]{x0, y, z0}); seen.add(key(x0, y, z0)); }
        int[][] d = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!q.isEmpty() && seen.size() < 600000) {
            int[] p = q.poll();
            for (int[] dd : d) {
                int x = p[0] + dd[0], y = p[1] + dd[1], z = p[2] + dd[2];
                if (Math.abs(x - s.cx) > 100 || Math.abs(z - s.cz) > 100) continue;
                long k = key(x, y, z);
                if (seen.contains(k)) continue;
                boolean open = e.carve(x, y, z) < 0 || y > World.ground(x, z) || WORLD.packDensity(x, y, z) < 0;
                if (!open) continue;
                seen.add(k);
                if (y > World.ground(x, z) + 2) continue; // out in the open air: stop spreading
                q.add(new int[]{x, y, z});
            }
        }
        return seen;
    }

    /**
     * A cold Root Vault cluster is frozen throughout, snowed in round its openings and thawed deep inside (Taran's ask
     * for 0.4.1: snowier, with the plants hidden deeper in). Over the open floors of the cold clusters (sampled every
     * other block), a good share lies in the snowy zone and a share deep enough for plants, and most clusters have some.
     */
    @Test
    void coldClustersThawDeepInside() {
        long snowy = 0, band = 0, thawed = 0;
        int clusters = 0, withThaw = 0;
        for (long seed : SEEDS) {
            Entrances e = new Entrances(seed, PACK_FLOOR, WORLD);
            for (Entrances.Site s : sites(e, 12)) {
                if (s.kind != Entrances.Kind.VAULTS || !s.cold) continue;
                clusters++;
                long before = thawed;
                for (Entrances.Chamber g : s.chambers) {
                    int r = (int) Math.ceil(Math.max(g.a, g.b) * 1.3);
                    for (int x = (int) g.cx - r; x <= g.cx + r; x += 2) for (int z = (int) g.cz - r; z <= g.cz + r; z += 2) {
                        if (e.chamberAt(x, z) != g) continue;
                        for (int y = (int) Math.floor(g.water) + 1; y < g.top(); y++) {
                            if (e.carve(x, y, z) >= 0 || e.carve(x, y - 1, z) < 0) continue;
                            double c = e.chill(s, x, y, z);
                            if (c <= 0) snowy++;
                            else if (c < Entrances.THAW_REACH) band++;
                            else thawed++;
                            break;
                        }
                    }
                }
                if (thawed > before) withThaw++;
            }
        }
        long all = snowy + band + thawed;
        System.out.printf("cold clusters %d (%d with thawed floor); floors: snowy %.0f %%, thawing %.0f %%, thawed %.0f %%%n", clusters, withThaw,
                100.0 * snowy / all, 100.0 * band / all, 100.0 * thawed / all);
        assertTrue(clusters >= 6, "cold clusters: " + clusters);
        assertTrue(snowy >= 0.25 * all, "snowy floors: " + snowy + " of " + all);
        assertTrue(thawed >= 0.12 * all, "thawed floors: " + thawed + " of " + all);
        assertTrue(withThaw * 2 >= clusters, withThaw + " of " + clusters + " clusters thaw somewhere");
    }
}
