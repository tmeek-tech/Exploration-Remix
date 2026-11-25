package com.explorationremix.world.terrain;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ember crust (0.22.9, Taran 2026-11-25: "re-add ember crust to light up the lower Galleries but give it a texture
 * that blends in better with the basalt"): where the palette lays it, and how much of the high ground its light
 * reaches on the blocks the game makes ({@link NoiseVox}).
 */
class EmberCrustTest {
    private static final long SEED = 8675309L;
    /** GalleriesFieldTest's two boxes' middles: seed, x, z. */
    static final long[][] BOXES = {{8675309, -512, -512}, {-42, -5000, 6000}};
    static final int SIZE = 256, MARGIN = 16, YLO = -784, YHI = -630;
    /** ERBlocks.EMBER_LIGHT (the registry needs the game to load). */
    static final int LIGHT = 13;

    /** How lit a box's ground is: the standing places of the main walk component in the inner square. */
    static final class Lit {
        long seed;
        int crust, exposed, tops, crustTops, crustWalls, crustCeils, places, high;
        /** Places by the crust's light at their feet, and by the crust's and the lava's. */
        final int[] byLevel = new int[16], withLava = new int[16];

        double share(int[] h, int least) {
            int k = 0;
            for (int l = least; l < 16; l++) k += h[l];
            return places == 0 ? 0 : k / (double) places;
        }

        double topShare() { return crustTops / (double) Math.max(1, tops); }

        @Override
        public String toString() {
            return String.format("seed %d: crust %d of %d open-faced rock blocks (%d on the tops, %.3f of them; %d in walls,"
                            + " %d in vaults); %d places, %d of them 12 or more over the table; lit 6+ %.2f, 8+ %.2f, 1+ %.2f;"
                            + " with the lava's light 6+ %.2f",
                    seed, crust, exposed, crustTops, topShare(), crustWalls, crustCeils, places, high,
                    share(byLevel, 6), share(byLevel, 8), share(byLevel, 1), share(withLava, 6));
        }
    }

    private static final int[][] D = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /**
     * The box round (cx, cz): every rock block with open space beside it asks the palette for its stone, with its stone
     * depths and wetness read from its column as the surface rules read them; the crust's light and the lava's spread
     * through every open block, one less a block (taxicab, as the game's block light); then the walk graph.
     */
    static Lit measure(long seed, int cx, int cz) {
        Terrain t = new Terrain(TerrainSettings.defaults(), -64, seed);
        int W = t.shape.waterY(Terrain.GALLERIES);
        int x0 = cx - SIZE / 2, z0 = cz - SIZE / 2, ny = YHI - YLO + 1;
        NoiseVox v = NoiseVox.build(t, x0, z0, SIZE, SIZE, YLO, YHI);
        byte[] crustLight = new byte[SIZE * SIZE * ny], allLight = new byte[SIZE * SIZE * ny];
        ArrayDeque<int[]> q = new ArrayDeque<>(), qa = new ArrayDeque<>();
        Lit out = new Lit();
        out.seed = seed;
        for (int i = 0; i < SIZE; i++) for (int k = 0; k < SIZE; k++) {
            int x = x0 + i, z = z0 + k;
            for (int y = YLO + 1; y < YHI; y++) {
                int m = v.mat(x, y, z);
                if (m == NoiseVox.LAVA) qa.add(new int[]{x, y, z, 15});
                if (m != NoiseVox.ROCK) continue;
                boolean faced = false;
                for (int[] d : D) if (v.mat(x + d[0], y + d[1], z + d[2]) != NoiseVox.ROCK) { faced = true; break; }
                if (!faced) continue;
                out.exposed++;
                int top = y, bot = y;
                while (top + 1 <= YHI && v.mat(x, top + 1, z) == NoiseVox.ROCK) top++;
                while (bot - 1 >= YLO && v.mat(x, bot - 1, z) == NoiseVox.ROCK) bot--;
                int over = v.mat(x, top + 1, z);
                boolean wet = over == NoiseVox.LAVA || over == NoiseVox.WATER;
                Rock r = t.palette.shell(x, y, z, top - y + 1, y - bot + 1, wet);
                boolean onTop = top == y && over == NoiseVox.AIR, under = bot == y && !onTop;
                if (onTop) out.tops++;
                if (r == Rock.EMBER_CRUST || r == Rock.SMOOTH_EMBER_CRUST) {
                    out.crust++;
                    if (onTop) out.crustTops++;
                    else if (under) out.crustCeils++;
                    else out.crustWalls++;
                    q.add(new int[]{x, y, z, LIGHT});
                    qa.add(new int[]{x, y, z, LIGHT});
                }
            }
        }
        spread(v, crustLight, q, x0, z0, ny);
        spread(v, allLight, qa, x0, z0, ny);
        v.graph();
        int[] count = new int[v.compSize.length];
        int xa = x0 + MARGIN, za = z0 + MARGIN, xb = x0 + SIZE - MARGIN, zb = z0 + SIZE - MARGIN;
        for (int p = 0; p < v.places; p++) if (v.inArea(p, xa, za, xb, zb, W, YHI)) count[v.comp[p]]++;
        int main = 0;
        for (int c = 0; c < count.length; c++) if (count[c] > count[main]) main = c;
        for (int p = 0; p < v.places; p++) {
            if (v.comp[p] != main || !v.inArea(p, xa, za, xb, zb, W, YHI)) continue;
            int x = v.px(p), y = v.py(p), z = v.pz(p);
            int idx = ((x - x0) * SIZE + (z - z0)) * ny + (y - YLO);
            out.places++;
            if (y >= W + 12) out.high++;
            out.byLevel[crustLight[idx]]++;
            out.withLava[allLight[idx]]++;
        }
        return out;
    }

    /** Block light from the queued sources through every open block (air, water, lava), one less a block. */
    private static void spread(NoiseVox v, byte[] light, ArrayDeque<int[]> q, int x0, int z0, int ny) {
        while (!q.isEmpty()) {
            int[] s = q.poll();
            for (int[] d : D) {
                int x = s[0] + d[0], y = s[1] + d[1], z = s[2] + d[2], l = s[3] - 1;
                if (l <= 0 || !v.in(x, y, z) || v.mat(x, y, z) == NoiseVox.ROCK) continue;
                int idx = ((x - x0) * SIZE + (z - z0)) * ny + (y - YLO);
                if (light[idx] >= l) continue;
                light[idx] = (byte) l;
                q.add(new int[]{x, y, z, l});
            }
        }
    }

    /**
     * Card "Pick how much of the lower Galleries the crust lights", option "Most of it": about two thirds of the high
     * ground a player walks on is lit to 6 or more by the crust alone, with dark stretches between, and few of the tops'
     * blocks are crust. Measured on GalleriesFieldTest's two boxes (256 x 256 round their middles; the main walk
     * component, all of it on the high ground, 12 or more rows over the lava's table): 0.69 and 0.67 lit to 6 or more,
     * 0.47 to 8, with 5.3 % of the tops' blocks crust; the lava's light adds nothing up there. The walls' joints and the
     * vaults' cracks hold a crust block in 50 to 60 of the open-faced rock.
     */
    @Test
    void lightsAboutTwoThirdsOfTheHighGround() {
        List<String> report = new ArrayList<>();
        for (long[] b : BOXES) {
            Lit l = measure(b[0], (int) b[1], (int) b[2]);
            report.add(l.toString());
            assertTrue(l.places > 20000, "a big main walk component: " + report);
            assertTrue(l.high > 0.9 * l.places, "the main component is the high ground: " + report);
            double lit = l.share(l.byLevel, 6);
            assertTrue(lit > 0.6 && lit < 0.78, "about two thirds of the high ground lit: " + report);
            assertTrue(l.share(l.byLevel, 1) < 0.99, "dark stretches remain: " + report);
            assertTrue(l.topShare() < 0.07, "few of the tops' blocks are crust: " + report);
            assertTrue(l.crustWalls > 1000 && l.crustCeils > 200, "joints down the walls and cracks over the vaults: " + report);
        }
        System.out.println("ember crust light: " + report);
    }

    private static final DeepBiome[] GALLERIES = {DeepBiome.BASALT_GALLERIES, DeepBiome.CALDERA, DeepBiome.CAUSEWAY,
            DeepBiome.SCORIA_CANYONS, DeepBiome.OBSIDIAN_TUBES};

    /**
     * The crust is always the stone round it with a crack in it: over the Galleries' rows under the join's blend, in
     * every biome but the Obsidian Tubes, never in a wet floor, a wall under the flood's tide mark (the lava lights
     * those; the Caldera's cones keep their cracks) or a hanging cluster, and
     * as rare as the light needs (a few hundredths of the shell's blocks at random depths).
     */
    @Test
    void layInTheHallsCalderaCausewayAndRifts() {
        Terrain t = new Terrain(TerrainSettings.defaults(), -64, SEED);
        Shape s = t.shape;
        int lo = s.bottom(Terrain.GALLERIES), hi = s.top(Terrain.GALLERIES) - Palette.JOIN_BLEND;
        int W = s.waterY(Terrain.GALLERIES), mark = W + Terrain.surge(Terrain.GALLERIES) - 9;   // under every tide mark
        Random r = new Random(29);
        Map<DeepBiome, int[]> count = new EnumMap<>(DeepBiome.class);
        Spread spread = new Spread(r, 4000, 512, 5000);
        int wetCrust = 0, wet = 0, tideCrust = 0, tide = 0, hungCrust = 0, hung = 0;
        for (int k = 0; k < 300000; k++) {
            DeepBiome b = GALLERIES[r.nextInt(GALLERIES.length)];
            int[] at = spread.next();
            int x = at[0], z = at[1], y = lo + r.nextInt(hi - lo + 1);
            int above = 1 + r.nextInt(12), below = 1 + r.nextInt(12);
            boolean isWet = r.nextInt(4) == 0;
            Rock q = t.palette.shell(b, Terrain.GALLERIES, x, y, z, above, below, isWet);
            boolean crust = q == Rock.EMBER_CRUST || q == Rock.SMOOTH_EMBER_CRUST;
            int[] c = count.computeIfAbsent(b, key -> new int[2]);
            c[0]++;
            if (crust) c[1]++;
            if (isWet && above <= 3) { wet++; if (crust) wetCrust++; }
            if (above > 3 && b != DeepBiome.OBSIDIAN_TUBES && t.looks.hang(x, y, z) > 0.05) { hung++; if (crust) hungCrust++; }
            // (the Caldera's cones keep their own look under it, cracks and all)
            if (above > 3 && y >= W && y < mark && b != DeepBiome.OBSIDIAN_TUBES && b != DeepBiome.CALDERA) { tide++; if (crust) tideCrust++; }
        }
        StringBuilder shares = new StringBuilder();
        for (Map.Entry<DeepBiome, int[]> e : count.entrySet())
            shares.append(String.format(" %s %.4f", e.getKey(), e.getValue()[1] / (double) e.getValue()[0]));
        for (DeepBiome b : GALLERIES) {
            double share = count.get(b)[1] / (double) count.get(b)[0];
            if (b == DeepBiome.OBSIDIAN_TUBES) assertEquals(0, count.get(b)[1], "no crust in the tubes:" + shares);
            else assertTrue(share > 0.003 && share < 0.06, "crust in " + b + ":" + shares);
        }
        assertEquals(0, wetCrust, "no crust in a floor the lava or water rests on (" + wet + " of them)");
        assertTrue(tide > 1000, "walls under the tide mark sampled: " + tide);
        assertEquals(0, tideCrust, "no crust under the flood's tide mark (" + tide + " walls)");
        assertTrue(hung > 1000, "hanging clusters' walls and undersides sampled: " + hung);
        assertEquals(0, hungCrust, "no crust on a hanging cluster (" + hung + " of its blocks)");
    }

    /** {@link Palette#ember}: the crust of basalt is the ember crust, of smooth basalt the smooth one, and nothing else. */
    @Test
    void theCrustIsItsStone() {
        assertEquals(Rock.EMBER_CRUST, Palette.ember(Rock.BASALT));
        assertEquals(Rock.SMOOTH_EMBER_CRUST, Palette.ember(Rock.SMOOTH_BASALT));
        for (Rock r : Rock.values())
            if (r != Rock.BASALT && r != Rock.SMOOTH_BASALT) assertEquals(r, Palette.ember(r), "no crust in " + r);
    }
}
