package com.explorationremix.world.terrain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lower Galleries' ruins as {@link GalleriesRuins} plans them (0.22.1; Taran's cards: a cluster about every 400
 * blocks, on the high ground, half fallen, a rune and some loot, lava glowing in them): 4 x 4 cells of two seeds, every
 * cluster's blocks checked as the feature would set them. On the first plan 13 and 12 of the 16 cells held a cluster,
 * with 75 and 71 small ruins round them, 5.8 a centre.
 */
class GalleriesRuinsTest {
    static final long[] SEEDS = {8675309L, -42L};
    static final int I0 = -2, J0 = -2, N = 4;
    static final Map<Long, Terrain> TERRAINS = new LinkedHashMap<>();
    static final Map<Long, List<GalleriesRuins.Plan>> PLANS = new LinkedHashMap<>();

    static synchronized Terrain terrain(long seed) {
        return TERRAINS.computeIfAbsent(seed, s -> new Terrain(TerrainSettings.defaults(), -64, s));
    }

    /** The clusters of the seed's cells (planned once for all the tests). */
    static synchronized List<GalleriesRuins.Plan> plans(long seed) {
        return PLANS.computeIfAbsent(seed, s -> {
            GalleriesRuins r = GalleriesRuins.of(terrain(s));
            List<GalleriesRuins.Plan> out = new ArrayList<>();
            for (int i = I0; i < I0 + N; i++)
                for (int j = J0; j < J0 + N; j++) {
                    GalleriesRuins.Plan p = r.plan(i, j);
                    if (p != null) out.add(p);
                }
            return out;
        });
    }

    /**
     * Most cells hold a cluster: HELD (85 %) of them are meant to, and a meant one is missed only where no spot of its
     * cell's tops is flat and roomy enough. Each has 2 or more small ruins round its centre, and on average 4 or more.
     */
    @Test
    void mostCellsHoldAClusterWithSmallRuinsRoundIt() {
        for (long seed : SEEDS) {
            List<GalleriesRuins.Plan> ps = plans(seed);
            assertTrue(ps.size() >= 0.6 * N * N, "seed " + seed + ": only " + ps.size() + " of " + N * N + " cells hold a cluster");
            int smalls = 0;
            for (GalleriesRuins.Plan p : ps) {
                smalls += p.smalls.size();
                assertTrue(p.smalls.size() >= 2, "seed " + seed + ": " + where(p) + " has only " + p.smalls + " round it");
            }
            assertTrue(smalls >= 4.0 * ps.size(), "seed " + seed + ": " + smalls + " small ruins round " + ps.size() + " clusters");
        }
    }

    /** A cluster is the field's alone: a planner of its own plans the same blocks. */
    @Test
    void plansAreTheSameFromAnyPlanner() {
        long seed = SEEDS[0];
        Terrain t = terrain(seed);
        GalleriesRuins fresh = new GalleriesRuins(t.galleries, seed);
        for (GalleriesRuins.Plan p : plans(seed)) {
            GalleriesRuins.Plan q = fresh.plan(p.i, p.j);
            assertNotNull(q, where(p) + ": a fresh planner found none");
            assertEquals(p.cx, q.cx, where(p));
            assertEquals(p.cz, q.cz, where(p));
            assertEquals(p.gy, q.gy, where(p));
            assertTrue(Arrays.equals(p.b, q.b), where(p) + ": its blocks differ");
        }
    }

    /** Each cluster holds the rune's chest, and up to three loot chests; each chest stands on a block, open over it. */
    @Test
    void eachClusterHoldsTheRunesChestAndSomeLoot() {
        int loot = 0, clusters = 0;
        for (long seed : SEEDS)
            for (GalleriesRuins.Plan p : plans(seed)) {
                clusters++;
                int rune = 0, others = 0;
                for (int[] c : p.chests) {
                    byte code = p.at(c[0], c[1], c[2]);
                    assertTrue(code >= GalleriesRuins.CHEST && code < GalleriesRuins.RUNE_CHEST + 4, where(p) + ": a chest's block is " + code);
                    assertEquals(c[3] == 1, code >= GalleriesRuins.RUNE_CHEST, where(p) + ": a chest's kind");
                    byte under = p.at(c[0], c[1] - 1, c[2]);
                    assertTrue(GalleriesRuins.full(under), where(p) + ": a chest on " + under);
                    byte over = p.at(c[0], c[1] + 1, c[2]);
                    assertTrue(over == GalleriesRuins.AIR || over == GalleriesRuins.NONE, where(p) + ": a chest under " + over);
                    if (c[3] == 1) rune++; else others++;
                }
                assertEquals(1, rune, where(p) + ": rune chests");
                assertTrue(others <= 3, where(p) + ": " + others + " loot chests");
                loot += others;
            }
        assertTrue(loot >= clusters, loot + " loot chests in " + clusters + " clusters");
    }

    /**
     * Half fallen, yet nothing floats: every block of a cluster is joined to the terrain's rock through blocks touching
     * face to face. A chest, a slab, a stair or a chain counts as joined where it touches one.
     */
    @Test
    void nothingFloats() {
        for (long seed : SEEDS)
            for (GalleriesRuins.Plan p : plans(seed)) {
                int S = GalleriesRuins.SX, Y = GalleriesRuins.SY;
                boolean[] on = new boolean[p.b.length];
                int[] q = new int[p.b.length];
                int h = 0, n = 0;
                for (int dx = 0; dx < S; dx++)
                    for (int dz = 0; dz < S; dz++) {
                        int top = p.terrain[dx * S + dz];
                        for (int dy = 1; dy < Y; dy++) {
                            int k = (dx * S + dz) * Y + dy;
                            if (!block(p.b[k])) continue;
                            if (p.b[k - 1] == GalleriesRuins.NONE && p.y0 + dy - 1 <= top) { on[k] = true; q[n++] = k; }
                        }
                    }
                while (h < n) {
                    int k = q[h++], dy = k % Y, col = k / Y, dz = col % S, dx = col / S;
                    int[][] nb = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}};
                    for (int[] d : nb) {
                        int x = dx + d[0], y = dy + d[1], z = dz + d[2];
                        if (x < 0 || z < 0 || y < 0 || x >= S || z >= S || y >= Y) continue;
                        int nk = (x * S + z) * Y + y;
                        if (on[nk] || !block(p.b[nk])) continue;
                        on[nk] = true;
                        q[n++] = nk;
                    }
                }
                int loose = 0;
                String first = null;
                for (int k = 0; k < p.b.length; k++)
                    if (block(p.b[k]) && !on[k]) {
                        loose++;
                        if (first == null) {
                            int dy = k % Y, col = k / Y;
                            first = "(" + (p.x0 + col / S) + ", " + (p.y0 + dy) + ", " + (p.z0 + col % S) + ") code " + p.b[k];
                        }
                    }
                assertEquals(0, loose, "seed " + seed + " " + where(p) + ": blocks joined to nothing, first " + first);
            }
    }

    /** Every block of its lava stays put: held under and on its four sides, open over it ({@link GalleriesRuins#held}). */
    @Test
    void theLavaIsHeld() {
        int lava = 0;
        for (long seed : SEEDS) {
            GalleriesRuins r = GalleriesRuins.of(terrain(seed));
            for (GalleriesRuins.Plan p : plans(seed))
                for (int x = p.x0; x < p.x0 + GalleriesRuins.SX; x++)
                    for (int z = p.z0; z < p.z0 + GalleriesRuins.SX; z++)
                        for (int y = p.yMin(); y <= p.yMax(); y++) {
                            if (p.at(x, y, z) != GalleriesRuins.LAVA) continue;
                            lava++;
                            assertTrue(r.held(p, x, y, z), "seed " + seed + " " + where(p) + ": lava loose at (" + x + ", " + y + ", " + z + ")");
                        }
        }
        assertTrue(lava > 0, "no lava glows in any ruin");
    }

    /**
     * The ruins stand on plain ground of the tops, over the flood's reach: every column the plan sets anything in is an
     * open cell with no deck, band, bridge's pier, ramp's plane, cone, pillar or stray, its own top within FLAT_LO under
     * and FLAT_HI over the plan's ground there, nothing within two rows of its ceiling; and none within the ways down's
     * decks and feet ({@link GalleriesField#wayNear}).
     */
    @Test
    void theRuinsStandOnPlainGroundOffTheWays() {
        for (long seed : SEEDS) {
            GalleriesField g = terrain(seed).galleries;
            for (GalleriesRuins.Plan p : plans(seed)) {
                int S = GalleriesRuins.SX;
                for (int dx = 0; dx < S; dx++)
                    for (int dz = 0; dz < S; dz++) {
                        int k = dx * S + dz;
                        if (p.lo[k] > p.hi[k]) continue;
                        int x = p.x0 + dx, z = p.z0 + dz, t = g.floorAt(x, z) - 1, gr = p.ground[k];
                        GalleriesField.Cell c = g.column(x, z);
                        String at = "seed " + seed + " " + where(p) + " at (" + x + ", " + z + ")";
                        assertTrue(GalleriesRuins.plainCell(c), at + ": not plain ground");
                        assertTrue(t >= gr - GalleriesRuins.FLAT_LO && t <= gr + GalleriesRuins.FLAT_HI, at + ": top " + t + " under ground " + gr);
                        assertTrue(t >= g.W + GalleriesRuins.DRY - GalleriesRuins.FLAT_LO, at + ": in the flood's reach, W+" + (t - g.W));
                        int topBlock = Integer.MIN_VALUE;
                        for (int y = p.yMax(); y >= p.yMin() && topBlock == Integer.MIN_VALUE; y--) if (block(p.at(x, y, z))) topBlock = y;
                        assertTrue(topBlock == Integer.MIN_VALUE || topBlock <= c.c - 3, at + ": a block at " + topBlock + " under a ceiling at " + c.c);
                        assertTrue(!g.wayNear(x, z, p.y0 + p.lo[k], p.y0 + p.hi[k], 0, -1000), at + ": on a way down");
                    }
            }
        }
    }

    /** Whether code b is a block (not nothing, air or lava). */
    static boolean block(byte b) {
        return b != GalleriesRuins.NONE && b != GalleriesRuins.AIR && b != GalleriesRuins.LAVA;
    }

    static String where(GalleriesRuins.Plan p) {
        return "cell " + p.i + "," + p.j + " (" + p.kindName() + " at " + p.cx + ", " + p.gy + ", " + p.cz + ")";
    }
}
