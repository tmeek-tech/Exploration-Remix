package com.explorationremix.world.feature;

import com.explorationremix.world.terrain.DeepBiome;
import com.explorationremix.world.terrain.GalleriesField;
import com.explorationremix.world.terrain.Hashing;
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
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The Galleries' lava glow: how bright it is at each height over the flood band, and in the Obsidian Tubes. */
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
        HallGlowFeature.Config shipped = shipped();
        assertEquals(HallGlow.BAND_LEVEL, shipped.low(), "the band's glow and the glow just over it meet without a step");
        // 0.8.0: the Obsidian Tubes as bright as the high halls (Taran's second picture, the tubes lit to their far ends);
        // at 9 their faces measured a mean of 6 (theTubesFacesAreLit)
        assertEquals(13, shipped.tubes(), "the tubes' glow");
        assertTrue(shipped.tubes() >= shipped.high(), "the tubes no darker than the high halls");
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

    /** The glow as tools/worldgen.py ships it. */
    static HallGlowFeature.Config shipped() throws Exception {
        try (Reader r = Files.newBufferedReader(Path.of(
                "src/main/resources/data/explorationremix/worldgen/configured_feature/basalt_galleries/glow.json"))) {
            JsonElement cfg = JsonParser.parseReader(r).getAsJsonObject().get("config");
            return HallGlowFeature.Config.CODEC.parse(JsonOps.INSTANCE, cfg).result().orElseThrow();
        }
    }

    /**
     * A later pass raises a glow over the band, never lowers it (the critique's glow-level-depends-on-decoration-order):
     * a neighbour decorated before this chunk lit its spot with only a vault in reach, before this chunk's column stood
     * under it; the second pass weighs it again and it ends at the floor's level, the level it gets when the column stood
     * first. Both orders end alike at every height.
     */
    @Test
    void aGlowEndsAtTheSameLevelInEitherOrder() throws Exception {
        assertEquals(9, HallGlow.tryLevel(0, 9, 10, 0), "air takes its level");
        assertEquals(0, HallGlow.tryLevel(-1, 9, 10, 0), "any other block is left");
        assertEquals(0, HallGlow.tryLevel(0, 0, 10, 0), "no level, no glow");
        assertEquals(14, HallGlow.tryLevel(13, 14, 10, 0), "a glow over the band goes up");
        assertEquals(0, HallGlow.tryLevel(14, 13, 10, 0), "and never down");
        assertEquals(0, HallGlow.tryLevel(14, 14, 10, 0));
        assertEquals(0, HallGlow.tryLevel(15, 15, -3, 0), "the band's glow is the flood's");
        HallGlowFeature.Config c = shipped();
        int raisedAt = 0;
        for (int over = 0; over <= 150; over += 2) {
            int vault = HallGlow.levelAt(c, over, HallGlow.VAULT, null, 0, 0, 0), floor = HallGlow.levelAt(c, over, HallGlow.FLOOR, null, 0, 0, 0);
            int neighbourFirst = pass(pass(0, vault, over), floor, over);
            int columnFirst = pass(pass(0, floor, over), floor, over);
            assertEquals(columnFirst, neighbourFirst, "the two orders at " + over + " over the band");
            assertEquals(floor, neighbourFirst);
            if (vault < floor) raisedAt++;
        }
        assertTrue(raisedAt > 10, "the vault's spot is dimmer than the floor's at only " + raisedAt + " heights");
    }

    /** What a spot holds after a pass that would give it lv (lightChunk's use of tryLevel). */
    private static int pass(int held, int lv, int over) {
        int set = HallGlow.tryLevel(held, lv, over, 0);
        return set > 0 ? set : held;
    }

    /**
     * Taran's second picture: the tubes lit to their far ends. HallGlowFeature's spots over the real tube field with the
     * shipped glow (one try a 4-block cube, air with rock within reach along an axis, a fluid stopping the look, the level
     * HallGlow.spotLevel gives, the patch noise), the light falling by one a block through air and water; each face of
     * rock beside a tube's air reads the light of the air in front of it. Glow only (no ember crust or crying obsidian in
     * the lining), so the game's light is at least this. In boxes round the tube spots of standing countries, faces
     * counted 19 blocks in from the box's sides, beyond the reach of any spot the box misjudges. With the first tubes'
     * glow of 9 the faces measured a mean of about 6, four in five under 8 and a third under 6.
     */
    @Test
    void theTubesFacesAreLit() throws Exception {
        HallGlowFeature.Config c = shipped();
        long[] tube = new long[16], hall = new long[16];
        int boxes = 0;
        for (long seed : new long[]{8675309L, 42L}) {
            Terrain t = new Terrain(TerrainSettings.defaults(), -64, seed);
            int found = 0;
            for (int ci = -3; ci < 3 && found < 2; ci++) for (int cj = -3; cj < 3 && found < 2; cj++) {
                int[] s = t.galleries.tubeSpot(ci, cj);
                if (s == null) continue;
                faceLight(t, c, s[0], s[2], tube, hall);
                found++;
                boxes++;
            }
        }
        String tubes = hist(tube), halls = hist(hall);
        System.out.println("HallGlowFeatureTest face light (" + boxes + " boxes): tube faces " + tubes + "; hall faces 40+ over the lava " + halls);
        long n = Arrays.stream(tube).sum();
        assertTrue(boxes >= 3, "too few tube spots: " + boxes);
        assertTrue(n > 20_000, "too few tube faces: " + tubes);
        assertTrue(mean(tube) >= 9, "the tube faces too dark on average: " + tubes);
        assertTrue(median(tube) >= 9, "the tube faces too dark: " + tubes);
        assertTrue(under(tube, 8) <= 0.15 * n, "too many tube faces under 8: " + tubes);
        assertTrue(under(tube, 6) <= 0.02 * n, "too many tube faces under 6: " + tubes);
    }

    private static final byte ROCK = 0, AIR = 1, WATER = 2;

    /**
     * The face light in a box round (cx, cz): 128 blocks on a side, from 26 to 117 rows over the lava table (the tubes
     * stand 46 to 98 over it, under the join); tube faces into {@code tube}, the cavern's own faces from 40 over the table
     * into {@code hall}.
     */
    static void faceLight(Terrain t, HallGlowFeature.Config c, int cx, int cz, long[] tube, long[] hall) {
        GalleriesField g = t.galleries;
        int W = t.shape.waterY(Terrain.GALLERIES), y0 = W + Terrain.surge(Terrain.GALLERIES), top = t.shape.top(Terrain.GALLERIES);
        int N = 128, H = 92, M = 19, x0 = cx - N / 2, z0 = cz - N / 2, yb = W + 26;
        byte[] mat = new byte[N * N * H], light = new byte[N * N * H];
        boolean[] opened = new boolean[N * N * H];   // open where the cavern itself is rock: the tube's
        for (int i = 0; i < N; i++) for (int k = 0; k < N; k++) for (int j = 0; j < H; j++) {
            int x = x0 + i, y = yb + j, z = z0 + k, id = (i * N + k) * H + j;
            if (t.fine(x, y, z, 0) > 0) continue;
            mat[id] = t.fluidAt(x, y, z) == Terrain.AIR ? AIR : WATER;
            opened[id] = g.density(x, y, z) > 0;
        }
        // the spots, as lightChunk tries them
        Perlin noise = new Perlin(Hashing.mix(t.seed, 9311, 0, 0));
        int n = c.spacing();
        int[][] q = new int[16][];
        int[] qn = new int[16];
        for (int l = 0; l < 16; l++) q[l] = new int[256];
        for (int gx = Math.floorDiv(x0, n); gx <= Math.floorDiv(x0 + N - 1, n); gx++)
            for (int gz = Math.floorDiv(z0, n); gz <= Math.floorDiv(z0 + N - 1, n); gz++)
                for (int gy = Math.floorDiv(Math.max(W, yb), n); gy <= Math.floorDiv(Math.min(top, yb + H - 1), n); gy++) {
                    long h = HallGlow.spotHash(t.seed, gx, gy, gz);
                    int i = gx * n + (int) Long.remainderUnsigned(h, n) - x0;
                    int j = gy * n + (int) Long.remainderUnsigned(h >>> 16, n) - yb;
                    int k = gz * n + (int) Long.remainderUnsigned(h >>> 32, n) - z0;
                    if (i < 0 || i >= N || j < 0 || j >= H || k < 0 || k >= N || yb + j < W || yb + j > top) continue;
                    int id = (i * N + k) * H + j;
                    if (mat[id] != AIR) continue;
                    boolean under = rockAlong(mat, N, H, i, j, k, 0, -1, 0, c.reach());
                    boolean beside = !under && (rockAlong(mat, N, H, i, j, k, 1, 0, 0, c.reach()) || rockAlong(mat, N, H, i, j, k, -1, 0, 0, c.reach())
                            || rockAlong(mat, N, H, i, j, k, 0, 0, 1, c.reach()) || rockAlong(mat, N, H, i, j, k, 0, 0, -1, c.reach()));
                    boolean over = !under && !beside && rockAlong(mat, N, H, i, j, k, 0, 1, 0, c.reach());
                    int side = HallGlow.side(under, beside, over);
                    if (side < 0) continue;
                    int lv = HallGlow.tryLevel(0, HallGlow.spotLevel(t, c, y0, side, noise, x0 + i, yb + j, z0 + k), yb + j, y0);
                    if (lv <= 0) continue;
                    light[id] = (byte) lv;
                    if (qn[lv] == q[lv].length) q[lv] = Arrays.copyOf(q[lv], qn[lv] * 2);
                    q[lv][qn[lv]++] = id;
                }
        // the light, one less a block through air and water; rock is opaque
        int[][] steps = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int l = 15; l >= 2; l--)
            for (int e = 0; e < qn[l]; e++) {
                int id = q[l][e];
                if (light[id] != l) continue;
                int j = id % H, i = id / H / N, k = id / H % N;
                for (int[] d : steps) {
                    int ii = i + d[0], jj = j + d[1], kk = k + d[2];
                    if (ii < 0 || ii >= N || jj < 0 || jj >= H || kk < 0 || kk >= N) continue;
                    int nid = (ii * N + kk) * H + jj;
                    if (mat[nid] == ROCK || light[nid] >= l - 1) continue;
                    light[nid] = (byte) (l - 1);
                    if (qn[l - 1] == q[l - 1].length) q[l - 1] = Arrays.copyOf(q[l - 1], qn[l - 1] * 2);
                    q[l - 1][qn[l - 1]++] = nid;
                }
            }
        // the faces: rock beside air, read by the air's light
        for (int i = M; i < N - M; i++) for (int k = M; k < N - M; k++) for (int j = 1; j < H - 1; j++) {
            int id = (i * N + k) * H + j;
            if (mat[id] != ROCK) continue;
            for (int[] d : steps) {
                int nid = ((i + d[0]) * N + k + d[2]) * H + j + d[1];
                if (mat[nid] != AIR) continue;
                if (opened[nid]) tube[light[nid]]++;
                else if (yb + j + d[1] >= W + 40) hall[light[nid]]++;
            }
        }
    }

    /** Rock within reach of (i, j, k) along d: air goes on, a fluid stops the look, the box's outside is none. */
    private static boolean rockAlong(byte[] mat, int N, int H, int i, int j, int k, int dx, int dy, int dz, int reach) {
        for (int s = 1; s <= reach; s++) {
            int ii = i + dx * s, jj = j + dy * s, kk = k + dz * s;
            if (ii < 0 || ii >= N || jj < 0 || jj >= H || kk < 0 || kk >= N) return false;
            byte m = mat[(ii * N + kk) * H + jj];
            if (m == AIR) continue;
            return m == ROCK;
        }
        return false;
    }

    private static double mean(long[] h) {
        double sum = 0;
        long n = 0;
        for (int l = 0; l < 16; l++) { sum += l * (double) h[l]; n += h[l]; }
        return n == 0 ? 0 : sum / n;
    }

    private static int median(long[] h) {
        long n = Arrays.stream(h).sum(), acc = 0;
        for (int l = 0; l < 16; l++) if ((acc += h[l]) * 2 >= n) return l;
        return 15;
    }

    private static long under(long[] h, int lv) {
        long u = 0;
        for (int l = 0; l < lv; l++) u += h[l];
        return u;
    }

    private static String hist(long[] h) {
        long n = Arrays.stream(h).sum();
        return String.format(Locale.ROOT, "%d, mean %.2f, median %d, under 8 %.1f %%, under 6 %.1f %%", n, mean(h), median(h),
                100.0 * under(h, 8) / Math.max(1, n), 100.0 * under(h, 6) / Math.max(1, n));
    }

    /**
     * Spec-tubes 12: a spot in a tube's air takes {@code tubes}, whatever its side; a spot of hall air at a tube's mouth,
     * within the old 4-block shell round the tube's air, takes the hall's level (the flood band's under the band's top).
     * The tube's air is flooded from the tube spots of standing countries (/remix tp's), and the hall air met within 4
     * blocks of it, through hall air, is the mouths'.
     */
    @Test
    void tubeAirTakesTheTubesLevelAndHallAirAtAMouthTheHalls() throws Exception {
        HallGlowFeature.Config c = shipped();
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
                        int lv = HallGlow.spotLevel(t, c, y0, side, null, b[0], b[1], b[2]);
                        if (inTube) assertEquals(c.tubes(), lv, "a spot in a tube's air at " + b[0] + " " + b[1] + " " + b[2]);
                        else {
                            int hall = b[1] < y0 ? HallGlow.BAND_LEVEL : HallGlow.levelAt(c, b[1] - y0, side, null, b[0], b[1], b[2]);
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
