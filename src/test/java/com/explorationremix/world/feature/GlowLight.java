package com.explorationremix.world.feature;

import com.explorationremix.world.terrain.Hashing;
import com.explorationremix.world.terrain.Perlin;
import com.explorationremix.world.terrain.Terrain;

import java.util.Arrays;
import java.util.Locale;

/**
 * The block light on the Galleries' rock in a box of blocks (for tests outside this package): the glow's spots as
 * HallGlowFeature.lightChunk tries them (a spot in every cube of the shipped spacing, air with rock within reach, its
 * level HallGlow.spotLevel gives) and every lava block (the lava sea, pools and falls: level 15), the light falling by
 * one a block through air, water and lava; each face of rock beside air reads the light of the air in front of it.
 * Faces of the halls (not the tubes' air) are counted by height over the lava table: under 30, 30 to 60, 60 and over.
 */
public final class GlowLight {
    /** Materials, as the caller's blocks give them. */
    public static final int ROCK = 0, AIR = 1, WATER = 2, LAVA = 3;

    public interface Blocks { int mat(int x, int y, int z); }

    /** Face counts by light level: [band][level], bands 0 (under 30 over the table), 1 (30 to 60), 2 (60 and over). */
    public final long[][] hall = new long[3][16];
    /** Faces in the Obsidian Tubes' air. */
    public final long[] tube = new long[16];
    /** Glow spots set, lava blocks lighting. */
    public long spots, lavaLights;

    private GlowLight() {}

    /** The glow as the data in {@code worldgen} ships it. */
    public static HallGlowFeature.Config shipped(java.nio.file.Path worldgen) throws java.io.IOException {
        try (java.io.Reader r = java.nio.file.Files.newBufferedReader(worldgen.resolve("configured_feature/basalt_galleries/glow.json"))) {
            com.google.gson.JsonElement cfg = com.google.gson.JsonParser.parseReader(r).getAsJsonObject().get("config");
            return HallGlowFeature.Config.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, cfg).result().orElseThrow();
        }
    }

    /**
     * The light over columns [x0, x0 + nx) x [z0, z0 + nz), rows from the lava table to the Galleries' top; faces counted
     * {@code margin} blocks in from the box's sides.
     */
    public static GlowLight measure(Terrain t, HallGlowFeature.Config c, Blocks b, int x0, int z0, int nx, int nz, int margin) {
        GlowLight out = new GlowLight();
        int g = Terrain.GALLERIES, table = t.shape.waterY(g), y0 = table + Terrain.surge(g), top = t.shape.top(g);
        int yb = table - 1, H = top - yb + 1;
        long n = (long) nx * nz * H;
        if (n > Integer.MAX_VALUE - 8) throw new IllegalArgumentException("box too big");
        byte[] mat = new byte[(int) n], light = new byte[(int) n];
        for (int i = 0; i < nx; i++) for (int k = 0; k < nz; k++) for (int j = 0; j < H; j++) mat[(i * nz + k) * H + j] = (byte) b.mat(x0 + i, yb + j, z0 + k);
        int[][] q = new int[16][];
        int[] qn = new int[16];
        for (int l = 0; l < 16; l++) q[l] = new int[1024];
        // the lava
        for (int id = 0; id < mat.length; id++) {
            if (mat[id] != LAVA) continue;
            light[id] = 15;
            q[15] = push(q[15], qn[15]++, id);
            out.lavaLights++;
        }
        // the glow's spots
        Perlin noise = c.patch() > 0 ? new Perlin(Hashing.mix(t.seed, 9311, 0, 0)) : null;
        int sp = c.spacing();
        for (int cx = Math.floorDiv(x0, sp); cx <= Math.floorDiv(x0 + nx - 1, sp); cx++)
            for (int cz = Math.floorDiv(z0, sp); cz <= Math.floorDiv(z0 + nz - 1, sp); cz++)
                for (int cy = Math.floorDiv(table, sp); cy <= Math.floorDiv(top, sp); cy++) {
                    long h = HallGlow.spotHash(t.seed, cx, cy, cz);
                    int x = cx * sp + (int) Long.remainderUnsigned(h, sp);
                    int y = cy * sp + (int) Long.remainderUnsigned(h >>> 16, sp);
                    int z = cz * sp + (int) Long.remainderUnsigned(h >>> 32, sp);
                    int i = x - x0, j = y - yb, k = z - z0;
                    if (i < 0 || i >= nx || k < 0 || k >= nz || y < table || y > top) continue;
                    int id = (i * nz + k) * H + j;
                    if (mat[id] != AIR) continue;
                    boolean under = along(mat, nx, nz, H, i, j, k, 0, -1, 0, c.reach());
                    boolean beside = !under && (along(mat, nx, nz, H, i, j, k, 1, 0, 0, c.reach()) || along(mat, nx, nz, H, i, j, k, -1, 0, 0, c.reach())
                            || along(mat, nx, nz, H, i, j, k, 0, 0, 1, c.reach()) || along(mat, nx, nz, H, i, j, k, 0, 0, -1, c.reach()));
                    boolean over = !under && !beside && along(mat, nx, nz, H, i, j, k, 0, 1, 0, c.reach());
                    int side = HallGlow.side(under, beside, over);
                    if (side < 0) continue;
                    int lv = HallGlow.tryLevel(0, HallGlow.spotLevel(t, c, y0, side, noise, x, y, z), y, y0);
                    if (lv <= light[id]) continue;
                    light[id] = (byte) lv;
                    q[lv] = push(q[lv], qn[lv]++, id);
                    out.spots++;
                }
        // the light, one less a block through anything but rock
        int[] di = {1, -1, 0, 0, 0, 0}, dj = {0, 0, 1, -1, 0, 0}, dk = {0, 0, 0, 0, 1, -1};
        for (int l = 15; l >= 2; l--)
            for (int e = 0; e < qn[l]; e++) {
                int id = q[l][e];
                if (light[id] != l) continue;
                int j = id % H, k = id / H % nz, i = id / H / nz;
                for (int d = 0; d < 6; d++) {
                    int ii = i + di[d], jj = j + dj[d], kk = k + dk[d];
                    if (ii < 0 || ii >= nx || jj < 0 || jj >= H || kk < 0 || kk >= nz) continue;
                    int nid = (ii * nz + kk) * H + jj;
                    if (mat[nid] == ROCK || light[nid] >= l - 1) continue;
                    light[nid] = (byte) (l - 1);
                    q[l - 1] = push(q[l - 1], qn[l - 1]++, nid);
                }
            }
        // the faces
        for (int i = margin; i < nx - margin; i++) for (int k = margin; k < nz - margin; k++) for (int j = 1; j < H - 1; j++) {
            int id = (i * nz + k) * H + j;
            if (mat[id] != ROCK) continue;
            for (int d = 0; d < 6; d++) {
                int ii = i + di[d], jj = j + dj[d], kk = k + dk[d];
                int nid = (ii * nz + kk) * H + jj;
                if (mat[nid] != AIR) continue;
                int x = x0 + ii, y = yb + jj, z = z0 + kk;
                if (t.biomeAt(x, y, z) == com.explorationremix.world.terrain.DeepBiome.OBSIDIAN_TUBES) { out.tube[light[nid]]++; continue; }
                int over = y - table;
                out.hall[over < 30 ? 0 : over < 60 ? 1 : 2][light[nid]]++;
            }
        }
        return out;
    }

    private static int[] push(int[] a, int at, int v) {
        if (at == a.length) a = Arrays.copyOf(a, a.length * 2);
        a[at] = v;
        return a;
    }

    /** Rock within reach along d: air goes on, a fluid stops the look, the box's outside is none. */
    private static boolean along(byte[] mat, int nx, int nz, int H, int i, int j, int k, int dx, int dy, int dz, int reach) {
        for (int s = 1; s <= reach; s++) {
            int ii = i + dx * s, jj = j + dy * s, kk = k + dz * s;
            if (ii < 0 || ii >= nx || jj < 0 || jj >= H || kk < 0 || kk >= nz) return false;
            byte m = mat[(ii * nz + kk) * H + jj];
            if (m == AIR) continue;
            return m == ROCK;
        }
        return false;
    }

    public static double mean(long[] h) {
        double sum = 0;
        long n = 0;
        for (int l = 0; l < 16; l++) { sum += l * (double) h[l]; n += h[l]; }
        return n == 0 ? 0 : sum / n;
    }

    public static long under(long[] h, int lv) {
        long u = 0;
        for (int l = 0; l < lv; l++) u += h[l];
        return u;
    }

    public static String hist(long[] h) {
        long n = Arrays.stream(h).sum();
        return String.format(Locale.ROOT, "%d faces, mean %.2f, under 8 %.1f %%, under 6 %.1f %%", n, mean(h), 100.0 * under(h, 8) / Math.max(1, n), 100.0 * under(h, 6) / Math.max(1, n));
    }

    @Override public String toString() {
        long[] all = new long[16];
        for (long[] b : hall) for (int l = 0; l < 16; l++) all[l] += b[l];
        return String.format(Locale.ROOT, "hall faces %s (under 30 over the table: %s; 30-60: %s; 60+: %s); tube faces %s; %d glow spots, %d lava blocks",
                hist(all), hist(hall[0]), hist(hall[1]), hist(hall[2]), hist(tube), spots, lavaLights);
    }
}
