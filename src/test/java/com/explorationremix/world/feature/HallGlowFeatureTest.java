package com.explorationremix.world.feature;

import com.explorationremix.world.terrain.DeepBiome;
import com.explorationremix.world.terrain.GalleriesField;
import com.explorationremix.world.terrain.Perlin;
import com.explorationremix.world.terrain.Terrain;
import com.explorationremix.world.terrain.TerrainSettings;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The Galleries' lava glow: how bright it is at each height over the flood band. */
class HallGlowFeatureTest {
    // only HallGlow and the config record: HallGlowFeature itself is a Feature, which needs the game's registries
    private static final HallGlowFeature.Config C = new HallGlowFeature.Config(4, 4, 15, 11, 60, 2, 0, 0, 9);

    @Test
    void brightestOverTheLavaFadingUpward() {
        assertEquals(15, HallGlow.levelAt(C, 0, null, 0, 0, 0), "just over the flood band");
        assertEquals(13, HallGlow.levelAt(C, 30, null, 0, 0, 0));
        assertEquals(11, HallGlow.levelAt(C, 60, null, 0, 0, 0), "under the vaults");
        assertEquals(11, HallGlow.levelAt(C, 200, null, 0, 0, 0), "no dimmer above the fade");
        int prev = 16;
        for (int over = 0; over <= 100; over++) {
            int lv = HallGlow.levelAt(C, over, null, 0, 0, 0);
            assertTrue(lv <= prev, "never brighter higher up, at " + over);
            prev = lv;
        }
    }

    @Test
    void patchesMoveItAtMostTwoEitherWayAndStayALight() {
        Perlin n = new Perlin(42);
        int lo = 16, hi = 0;
        for (int x = 0; x < 400; x += 3) for (int y = -780; y < -690; y += 5) {
            int over = y + 782;
            int plain = HallGlow.levelAt(C, over, null, x, y, 7);
            int lv = HallGlow.levelAt(C, over, n, x, y, 7);
            assertTrue(Math.abs(lv - plain) <= 2, "patch moved " + plain + " to " + lv);
            assertTrue(lv >= 1 && lv <= 15);
            lo = Math.min(lo, lv - plain);
            hi = Math.max(hi, lv - plain);
        }
        assertTrue(lo < 0 && hi > 0, "the patches both dim and brighten (" + lo + " to " + hi + ")");
    }

    @Test
    void theBandsGlowIsFullBright() {
        assertEquals(15, HallGlow.BAND_LEVEL);
    }

    @Test
    void sidesAreFloorFirst() {
        assertEquals(HallGlow.FLOOR, HallGlow.side(true, true, true), "a spot with a floor in reach is a floor's");
        assertEquals(HallGlow.WALL, HallGlow.side(false, true, true));
        assertEquals(HallGlow.VAULT, HallGlow.side(false, false, true));
        assertEquals(-1, HallGlow.side(false, false, false), "no rock in reach: no glow");
    }

    @Test
    void wallsAndVaultsTakeTheirShareLess() {
        HallGlowFeature.Config c = new HallGlowFeature.Config(4, 4, 15, 13, 70, 0, 1, 2, 9);
        for (int over = 0; over <= 120; over += 5) {
            int floor = HallGlow.levelAt(c, over, HallGlow.FLOOR, null, 0, 0, 0);
            assertEquals(floor - 1, HallGlow.levelAt(c, over, HallGlow.WALL, null, 0, 0, 0), "a wall's spot at " + over);
            assertEquals(floor - 2, HallGlow.levelAt(c, over, HallGlow.VAULT, null, 0, 0, 0), "a vault's spot at " + over);
            assertEquals(floor, HallGlow.levelAt(c, over, null, 0, 0, 0), "the old call is a floor's spot");
        }
    }

    @Test
    void theShippedGlowIsBrightAndLitFromTheFloorUp() throws Exception {
        // tools/worldgen.py (0.5.1): {"low": 15, "high": 13, "fade": 70, "patch": 1, "vault": 1}. A face gets about 3 to
        // 4 levels under the spot that lights it. On the look prototype's stand-in cavern (reshape/proc-looks) this
        // lit floor tops at 11.1, 10.1 and 9.0 on average 0-30, 30-60 and 60+ over the band, walls 10.9, 10.1, 9.3 and
        // undersides 10.8, 9.9, 9.2 (0.5.0's flat glow: 11.2 to 9.9, 11.1 to 10.3, 11.2 to 10.4). The first fade, to
        // 11, left the vaults at 8 on average with a third of the faces under 8: no spot may go below 11.
        HallGlowFeature.Config shipped;
        try (Reader r = Files.newBufferedReader(Path.of(
                "src/main/resources/data/explorationremix/worldgen/configured_feature/basalt_galleries/glow.json"))) {
            JsonElement cfg = JsonParser.parseReader(r).getAsJsonObject().get("config");
            shipped = HallGlowFeature.Config.CODEC.parse(JsonOps.INSTANCE, cfg).result().orElseThrow();
        }
        assertEquals(HallGlow.BAND_LEVEL, shipped.low(), "the band's glow and the glow just over it meet without a step");
        assertEquals(15, HallGlow.levelAt(shipped, 0, HallGlow.FLOOR, null, 0, 0, 0));
        int floorHigh = HallGlow.levelAt(shipped, 200, HallGlow.FLOOR, null, 0, 0, 0);
        int vaultHigh = HallGlow.levelAt(shipped, 200, HallGlow.VAULT, null, 0, 0, 0);
        assertTrue(floorHigh >= 13, "high ground stays bright: " + floorHigh);
        assertTrue(vaultHigh >= 12 && vaultHigh < floorHigh, "the vaults a little dimmer: " + vaultHigh);
        assertTrue(15 - vaultHigh >= 2, "not flat: band top to vault " + (15 - vaultHigh));
        Perlin n = new Perlin(42);
        for (int side : new int[]{HallGlow.FLOOR, HallGlow.WALL, HallGlow.VAULT})
            for (int x = 0; x < 300; x += 7) for (int over = 0; over < 160; over += 3) {
                int lv = HallGlow.levelAt(shipped, over, side, n, x, over, 3);
                assertTrue(lv >= 11 && lv <= 15, "level " + lv + " at " + over + " over the band, side " + side);
            }
    }

    /**
     * Spec-tubes 12: a spot in a tube's air takes {@code tubes}, whatever its side; a spot of hall air at a tube's mouth,
     * within the old 4-block shell round the tube's air, takes the hall's level (the flood band's under the band's top).
     * The tube's air is flooded from the tube spots of standing countries (/remix tp's), and the hall air met within 4
     * blocks of it, through hall air, is the mouths'.
     */
    @Test
    void tubeAirTakesTheTubesLevelAndHallAirAtAMouthTheHalls() {
        Terrain t = new Terrain(TerrainSettings.defaults(), -64, 8675309);
        GalleriesField g = t.galleries;
        int y0 = t.shape.waterY(Terrain.GALLERIES) + Terrain.surge(Terrain.GALLERIES);
        int spots = 0, tubeAir = 0, hallAir = 0;
        Set<Long> seen = new HashSet<>();
        for (int ci = -3; ci < 3; ci++) for (int cj = -3; cj < 3; cj++) {
            int[] s = g.tubeSpot(ci, cj);
            if (s == null || !tubeOpen(g, s[0], s[1], s[2]) || !seen.add(key(s[0], s[1], s[2]))) continue;
            spots++;
            // flood the tube's open blocks from the spot (a pool's water too), and from each hall block met, 4 more
            // blocks through hall air
            ArrayDeque<int[]> q = new ArrayDeque<>();
            q.add(new int[]{s[0], s[1], s[2], 0});
            int flooded = 0;
            while (!q.isEmpty() && flooded < 150_000) {
                int[] b = q.poll();
                flooded++;
                boolean inTube = b[3] == 0;
                if (g.fluid(b[0], b[1], b[2]) == Terrain.AIR) {
                    for (int side : new int[]{HallGlow.FLOOR, HallGlow.WALL, HallGlow.VAULT}) {
                        int lv = HallGlow.spotLevel(t, C, y0, side, null, b[0], b[1], b[2]);
                        if (inTube) assertEquals(C.tubes(), lv, "a spot in a tube's air at " + b[0] + " " + b[1] + " " + b[2]);
                        else {
                            int hall = b[1] < y0 ? HallGlow.BAND_LEVEL : HallGlow.levelAt(C, b[1] - y0, side, null, b[0], b[1], b[2]);
                            assertEquals(hall, lv, "a spot of hall air " + b[3] + " from a mouth at " + b[0] + " " + b[1] + " " + b[2]);
                            assertNotEquals(DeepBiome.OBSIDIAN_TUBES, t.biomeAt(b[0], b[1], b[2]), "hall air in the tubes' biome at " + b[0] + " " + b[1] + " " + b[2]);
                        }
                    }
                    if (inTube) tubeAir++;
                    else hallAir++;
                }
                for (int[] d : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
                    int x = b[0] + d[0], y = b[1] + d[1], z = b[2] + d[2];
                    if (g.density(x, y, z) > 0) {
                        if (inTube && tubeOpen(g, x, y, z) && seen.add(key(x, y, z))) q.add(new int[]{x, y, z, 0});
                    } else if (b[3] < 4 && g.fluid(x, y, z) == Terrain.AIR && seen.add(key(x, y, z))) q.add(new int[]{x, y, z, b[3] + 1});
                }
            }
        }
        System.out.println("HallGlowFeatureTest: " + spots + " tube spots, " + tubeAir + " spots of tube air, " + hallAir + " of hall air at mouths");
        assertTrue(spots >= 2, "too few tube spots: " + spots);
        assertTrue(tubeAir > 20_000, "too few spots of tube air: " + tubeAir);
        assertTrue(hallAir > 1000, "too few spots of hall air at the mouths: " + hallAir);
    }

    /** A block the tube opens in the cavern's rock (air or a pool's water). */
    private static boolean tubeOpen(GalleriesField g, int x, int y, int z) {
        return g.density(x, y, z) > 0 && g.tube(x, y, z) < 0;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x + (1 << 22)) << 34) | ((long) (y + 4096) << 22) | (z + (1 << 21));
    }

    @Test
    void spotsOfNeighbouringCubesAreIndependent() {
        // Hashed once with Hashing.mix, cubes next to each other along z kept the same x offset 38% of the time and
        // never shared a spot, which lined the glow up in rows along the walls.
        int n = 4;
        int[][] steps = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
        for (int[] st : steps) {
            long pairs = 0, sameX = 0, sameY = 0, sameZ = 0, sameSpot = 0;
            for (long seed : new long[]{1, 8675309, 2000023})
                for (int cx = -30; cx < 30; cx++) for (int cy = -206; cy < -172; cy++) for (int cz = -30; cz < 30; cz++) {
                    long a = HallGlow.spotHash(seed, cx, cy, cz), b = HallGlow.spotHash(seed, cx + st[0], cy + st[1], cz + st[2]);
                    boolean x = Long.remainderUnsigned(a, n) == Long.remainderUnsigned(b, n);
                    boolean y = Long.remainderUnsigned(a >>> 16, n) == Long.remainderUnsigned(b >>> 16, n);
                    boolean z = Long.remainderUnsigned(a >>> 32, n) == Long.remainderUnsigned(b >>> 32, n);
                    pairs++;
                    if (x) sameX++;
                    if (y) sameY++;
                    if (z) sameZ++;
                    if (x && y && z) sameSpot++;
                }
            String along = st[0] == 1 ? "x" : st[1] == 1 ? "y" : "z";
            for (long same : new long[]{sameX, sameY, sameZ}) {
                double f = same / (double) pairs;
                assertTrue(f > 0.24 && f < 0.26, "neighbours along " + along + " share an offset " + f + " of the time");
            }
            double spot = sameSpot / (double) pairs;
            assertTrue(spot > 0.0135 && spot < 0.0178, "neighbours along " + along + " share the spot " + spot + " of the time");
        }
    }
}
