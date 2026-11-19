package com.explorationremix.world.terrain;

import java.util.Arrays;

/**
 * The array helpers the Deep Gardens' fields need, written to give what the offline previews' numpy and scipy calls give
 * ({@code scripts/} in project files {@code deep-gardens/shape-options-r2}): the exact Euclidean distance transform, a
 * Gaussian blur with scipy's reflecting edges, a priority flood for the oil's spill levels, and float32 ramps.
 * Arrays are column-major over a window: index {@code i * nz + j} for column (i, j).
 */
final class GardensMath {
    private GardensMath() {}

    /** numpy's float32 smoothstep ramp: clip((v - lo) / (hi - lo), 0, 1), then t * t * (3 - 2 t), all in float32. */
    static float rampF(float v, double lo, double hi) {
        float t = (v - (float) lo) / (float) (hi - lo);
        t = t < 0f ? 0f : (t > 1f ? 1f : t);
        return t * t * (3f - 2f * t);
    }

    static float clipF(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

    /** fields.pull: 1 near (cx, cz), 0 past r1, a smoothstep between (float64, rounded to float32 as numpy stores it). */
    static float pull(double x, double z, double cx, double cz, double r0, double r1) {
        double d = Math.sqrt((x - cx) * (x - cx) + (z - cz) * (z - cz));
        double t = (d - r0) / (r1 - r0);
        t = t < 0 ? 0 : (t > 1 ? 1 : t);
        return (float) (1 - t * t * (3 - 2 * t));
    }

    /**
     * scipy.ndimage.distance_transform_edt of a mask over an (nx, nz) window: for every true cell the distance to the
     * nearest false one (0 on false cells), exact (Felzenszwalb and Huttenlocher's two passes). A window with no false
     * cell in reach gives {@code cap}. Results are capped at {@code cap}.
     */
    static float[] edt(boolean[] mask, int nx, int nz, float cap) {
        final double INF = 1e20;
        double[] g = new double[nx * nz];
        int n = Math.max(nx, nz);
        double[] f = new double[n], d = new double[n], zz = new double[n + 1];
        int[] v = new int[n];
        // along z for each x
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < nz; j++) f[j] = mask[i * nz + j] ? INF : 0;
            dt1(f, nz, d, v, zz);
            for (int j = 0; j < nz; j++) g[i * nz + j] = d[j];
        }
        float[] out = new float[nx * nz];
        double cap2 = (double) cap * cap;
        for (int j = 0; j < nz; j++) {
            for (int i = 0; i < nx; i++) f[i] = g[i * nz + j];
            dt1(f, nx, d, v, zz);
            for (int i = 0; i < nx; i++) {
                double s = d[i];
                out[i * nz + j] = s >= cap2 ? cap : (float) Math.sqrt(s);
            }
        }
        return out;
    }

    /** One dimension of the squared distance transform (lower envelope of parabolas). */
    private static void dt1(double[] f, int n, double[] d, int[] v, double[] z) {
        final double INF = 1e30;
        int k = 0;
        v[0] = 0;
        z[0] = -INF;
        z[1] = INF;
        for (int q = 1; q < n; q++) {
            int p = v[k];
            double s = ((f[q] + (double) q * q) - (f[p] + (double) p * p)) / (2.0 * q - 2.0 * p);
            while (s <= z[k]) {
                k--;
                p = v[k];
                s = ((f[q] + (double) q * q) - (f[p] + (double) p * p)) / (2.0 * q - 2.0 * p);
            }
            k++;
            v[k] = q;
            z[k] = s;
            z[k + 1] = INF;
        }
        k = 0;
        for (int q = 0; q < n; q++) {
            while (z[k + 1] < q) k++;
            int p = v[k];
            d[q] = (double) (q - p) * (q - p) + f[p];
        }
    }

    /**
     * scipy.ndimage.gaussian_filter (mode 'reflect', truncate 4) of a float32 window: along x, then along z, each pass
     * accumulated in double and stored as float32, as scipy does.
     */
    static float[] gaussian(float[] a, int nx, int nz, double sigma) {
        int r = (int) (4.0 * sigma + 0.5);
        double[] w = new double[2 * r + 1];
        double s2 = sigma * sigma;
        for (int i = -r; i <= r; i++) w[i + r] = Math.exp(-0.5 / s2 * (double) (i * i));
        double sum = pairwiseSum(w);
        for (int i = 0; i < w.length; i++) w[i] /= sum;
        // scipy's correlate1d with a symmetric kernel: centre first, then the pairs from the farthest in
        double[] line = new double[Math.max(nx, nz) + 2 * r];
        float[] t = new float[nx * nz];
        for (int j = 0; j < nz; j++) {
            for (int q = -r; q < nx + r; q++) line[q + r] = a[reflect(q, nx) * nz + j];
            for (int i = 0; i < nx; i++) {
                int c = i + r;
                double s = line[c] * w[r];
                for (int q = -r; q < 0; q++) s += (line[c + q] + line[c - q]) * w[q + r];
                t[i * nz + j] = (float) s;
            }
        }
        float[] out = new float[nx * nz];
        for (int i = 0; i < nx; i++) {
            int b = i * nz;
            for (int q = -r; q < nz + r; q++) line[q + r] = t[b + reflect(q, nz)];
            for (int j = 0; j < nz; j++) {
                int c = j + r;
                double s = line[c] * w[r];
                for (int q = -r; q < 0; q++) s += (line[c + q] + line[c - q]) * w[q + r];
                out[b + j] = (float) s;
            }
        }
        return out;
    }

    /** numpy's pairwise sum of a short float64 array (up to 128 entries: eight running sums, then the rest). */
    static double pairwiseSum(double[] a) {
        int n = a.length;
        if (n < 8) {
            double res = 0;
            for (double v : a) res += v;
            return res;
        }
        if (n > 128) throw new IllegalArgumentException("pairwiseSum: " + n);
        double[] r = new double[8];
        System.arraycopy(a, 0, r, 0, 8);
        int i = 8;
        for (; i < n - (n % 8); i += 8) for (int j = 0; j < 8; j++) r[j] += a[i + j];
        double res = ((r[0] + r[1]) + (r[2] + r[3])) + ((r[4] + r[5]) + (r[6] + r[7]));
        for (; i < n; i++) res += a[i];
        return res;
    }

    /** scipy's 'reflect' (half-sample symmetric) edge: d c b a | a b c d | d c b a. */
    static int reflect(int i, int n) {
        if (n == 1) return 0;
        while (i < 0 || i >= n) {
            if (i < 0) i = -i - 1;
            if (i >= n) i = 2 * n - i - 1;
        }
        return i;
    }

    /** scipy.ndimage.binary_dilation with the cross (4-neighbour) structure, iterated. */
    static boolean[] dilateCross(boolean[] m, int nx, int nz, int iterations) {
        boolean[] cur = m.clone();
        for (int it = 0; it < iterations; it++) {
            boolean[] nxt = cur.clone();
            for (int i = 0; i < nx; i++)
                for (int j = 0; j < nz; j++) {
                    int c = i * nz + j;
                    if (cur[c]) continue;
                    if ((i > 0 && cur[c - nz]) || (i < nx - 1 && cur[c + nz]) || (j > 0 && cur[c - 1]) || (j < nz - 1 && cur[c + 1]))
                        nxt[c] = true;
                }
            cur = nxt;
        }
        return cur;
    }

    static double wrap(double a) {
        while (a > Math.PI) a -= 2 * Math.PI;
        while (a < -Math.PI) a += 2 * Math.PI;
        return a;
    }
}
