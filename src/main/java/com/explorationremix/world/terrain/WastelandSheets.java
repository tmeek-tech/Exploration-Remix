package com.explorationremix.world.terrain;

/**
 * The Wasteland's tunnel sheets as both layers need them (0.5.1): the shared domain warp, each sheet's jittered cellular
 * lattice ({@link #cells}), a sheet's floor level with its undulation and ramps ({@link #sheetFloor}), and the weight of
 * the lowest sheet's opening dip before the Wasteland's guard ({@link #openingSite}).
 *
 * <p>Immutable and pure (every value a function of the seed, the layout and the position, the same on any thread and in
 * any order), built by {@link Terrain} before the breakthrough, the Galleries and the Wasteland. The Galleries find the
 * Wasteland's openings through {@link Breakthrough#openingSite}, which is this, and the Wasteland's own dip
 * ({@link WastelandField}) is {@link #dipWeight} of the same sheet-0 values times its guard ({@link Join#under}), so the
 * two halves of an opening cannot drift apart and neither field calls into the other's caches.
 */
public final class WastelandSheets {
    /** The layer's lava table (the first row above the lava). */
    final int W;
    /** How many sheets the layer has, and the rise from one to the next (WastelandField's constructor rule). */
    final int sheets;
    final double step;
    private final Perlin warpA, warpB, warpC, warpD, floorN, rampN;
    private final long[] sheetSeed = new long[WastelandField.MAX_SHEETS];
    private final double[] cos = new double[WastelandField.MAX_SHEETS], sin = new double[WastelandField.MAX_SHEETS],
            offA = new double[WastelandField.MAX_SHEETS], offB = new double[WastelandField.MAX_SHEETS];
    /** Gate 3's landings, for the top sheet's shift when the lowest sheet is the top one (a short layer); else null. */
    private final Plans plans;

    /** The sheets of the Wasteland (layer 2) of world seed {@code seed} over the layout {@code shape}. */
    WastelandSheets(long seed, Shape shape, TerrainSettings settings, Plans plans) {
        int li = Terrain.WASTELAND, top = shape.top(li);
        W = shape.waterY(li);
        // gate 3's lower landing floor (Plans.buildGate): 3 + 1.55 landing half-heights under the layer's top
        double lFloor = top - 3 - 1.55 * settings.landingRadius() / 1.6;
        sheets = Math.max(1, Math.min(WastelandField.MAX_SHEETS, (int) Math.floor((lFloor - 4 - (W + WastelandField.SHEET0)) / WastelandField.STEP) + 1));
        // a layer too high for the sheets (a thick band 3 gives it its rows) spaces them out, so the top one still sits
        // within 30 blocks under the landing and the pull to it stays walkable
        step = sheets == WastelandField.MAX_SHEETS && lFloor - (W + WastelandField.SHEET0 + WastelandField.STEP * (sheets - 1)) > 30
                ? (lFloor - 17 - (W + WastelandField.SHEET0)) / (sheets - 1) : WastelandField.STEP;
        this.plans = sheets == 1 ? plans : null;
        long s = seed ^ 0x510E527FADE682D1L;
        warpA = new Perlin(Hashing.mix(s, 1, 0, 0));
        warpB = new Perlin(Hashing.mix(s, 2, 0, 0));
        warpC = new Perlin(Hashing.mix(s, 3, 0, 0));
        warpD = new Perlin(Hashing.mix(s, 4, 0, 0));
        floorN = new Perlin(Hashing.mix(s, 5, 0, 0));
        rampN = new Perlin(Hashing.mix(s, 6, 0, 0));
        for (int k = 0; k < WastelandField.MAX_SHEETS; k++) {
            long h = Hashing.mix(s, 100 + k, 0, 0);
            sheetSeed[k] = h;
            double ang = 0.71 * k + 0.3 * Hashing.unit(Hashing.mix(h, 1, 0, 0));
            cos[k] = StrictMath.cos(ang);
            sin[k] = StrictMath.sin(ang);
            offA[k] = 1000 * Hashing.unit(Hashing.mix(h, 2, 0, 0));
            offB[k] = 1000 * Hashing.unit(Hashing.mix(h, 3, 0, 0));
        }
    }

    /** Scratch for {@link #cells} and its result. */
    static class Cells {
        final double[] px = new double[16], pz = new double[16], pd = new double[16];
        /** {distance to the nearest cell boundary, F3 - F1}, both in blocks. */
        final double[] out = new double[2];
        /** The nearest site and the one across the nearest boundary (indices into the 4 x 4 block from a0, b0). */
        int near1, across;
        int a0, b0;
    }

    private static int floor(double v) { int i = (int) v; return v < i ? i - 1 : i; }

    private static double clamp01(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    /** The warp's x part at column (x, z) (blocks): a broad bend and a short wiggle. */
    double warpX(double x, double z) {
        return WastelandField.WARP1 * warpA.noise2(x / WastelandField.WARP1_SCALE, z / WastelandField.WARP1_SCALE)
                + WastelandField.WARP2 * warpC.noise2(x / WastelandField.WARP2_SCALE, z / WastelandField.WARP2_SCALE);
    }

    /** The warp's z part at column (x, z) (blocks). */
    double warpZ(double x, double z) {
        return WastelandField.WARP1 * warpB.noise2(x / WastelandField.WARP1_SCALE, z / WastelandField.WARP1_SCALE)
                + WastelandField.WARP2 * warpD.noise2(x / WastelandField.WARP2_SCALE, z / WastelandField.WARP2_SCALE);
    }

    /**
     * Sheet k's cellular pattern at the warped point (u, v): {distance to the nearest cell boundary, F3 - F1}, both in
     * blocks, into {@code c.out}. The boundaries are the tunnels' middle lines; F3 - F1 is 0 where three cells meet (a
     * junction).
     */
    void cells(Cells c, int k, double u, double v) {
        double S = WastelandField.SPACING[k];
        double a = (cos[k] * u - sin[k] * v) / S + offA[k], b = (sin[k] * u + cos[k] * v) / S + offB[k];
        int ia = floor(a), ib = floor(b);
        int a0 = a - ia < 0.5 ? ia - 2 : ia - 1, b0 = b - ib < 0.5 ? ib - 2 : ib - 1;
        double d1 = 1e9, d2 = 1e9, d3 = 1e9;
        int i1 = 0;
        int n = 0;
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++, n++) {
            long hh = Hashing.mix(sheetSeed[k], a0 + i, b0 + j, 7);
            double sx = a0 + i + 0.5 + WastelandField.JITTER * ((hh & 0xFFFFFFFFL) * 0x1.0p-32 - 0.5);
            double sz = b0 + j + 0.5 + WastelandField.JITTER * ((hh >>> 32) * 0x1.0p-32 - 0.5);
            double dx = sx - a, dz = sz - b, d = dx * dx + dz * dz;
            c.px[n] = sx;
            c.pz[n] = sz;
            c.pd[n] = d;
            if (d < d1) { d3 = d2; d2 = d1; d1 = d; i1 = n; }
            else if (d < d2) { d3 = d2; d2 = d; }
            else if (d < d3) d3 = d;
        }
        // distance to the boundary of the nearest site's cell: the nearest of its bisectors with the other sites
        double e = 1e9, x1 = c.px[i1], z1 = c.pz[i1];
        int across = i1;
        for (int m = 0; m < 16; m++) {
            if (m == i1) continue;
            double dd = c.pd[m] - d1;
            double qx = c.px[m] - x1, qz = c.pz[m] - z1;
            double t = dd / (2 * Math.sqrt(qx * qx + qz * qz));
            if (t < e) { e = t; across = m; }
        }
        c.out[0] = e * S;
        c.out[1] = (Math.sqrt(d3) - Math.sqrt(d1)) * S;
        c.near1 = i1;
        c.across = across;
        c.a0 = a0;
        c.b0 = b0;
    }

    /** The undulation of sheet k's floor (blocks): its relief, two octaves. */
    double undulation(int k, double x, double z) {
        return WastelandField.FLOOR_AMP * floorN.noise2(x / WastelandField.FLOOR_SCALE + 31.7 * k, z / WastelandField.FLOOR_SCALE - 17.3 * k) / 0.75
                + WastelandField.FLOOR2_AMP * floorN.noise2(x / WastelandField.FLOOR2_SCALE - 7.1 * k, z / WastelandField.FLOOR2_SCALE + 3.3 * k) / 0.75;
    }

    /**
     * The floor level of sheet k at a column before dips and the gate (blocks): its level, the undulation {@code und}, and
     * its ramps, the rise to the next sheet scaled by {@code up} (the lowest sheet stays down where it opens).
     */
    double sheetFloor(int k, double x, double z, double up, double und) {
        double f = W + WastelandField.SHEET0 + step * k + und;
        double m = rampN.noise2(x / WastelandField.RAMP_SCALE + 51.3 * k, z / WastelandField.RAMP_SCALE - 23.9 * k);
        double span = WastelandField.RAMP_HI - WastelandField.RAMP_LO;
        if (k < sheets - 1) f += step * up * clamp01((m - WastelandField.RAMP_LO) / span);
        if (k > 0) f -= step * clamp01((-m - WastelandField.RAMP_LO) / span);
        return f;
    }

    /** How far the lowest sheet opens where the breakthrough noise is {@code beta}: 0 under Join.HOLE_BETA, 1 from 0.7. */
    static double hole(double beta) { return Terrain.smooth((beta - Join.HOLE_BETA) / Join.HOLE_RAMP); }

    /** Whether the lowest sheet lies at its own level (not up a ramp) at floor f: 1 to W + 6, 0 from W + 10. */
    double low(double f) { return clamp01((W + 10 - f) / 4); }

    /**
     * The lowest sheet's dip weight before the guard: {@code hole} x {@code low} x clamp01((HOLE_R - j) / HOLE_FADE), j
     * the sheet's F3 - F1 (the dip lies in the middle of a junction room). The Wasteland multiplies it by its guard.
     */
    static double dipWeight(double hole, double low, double j) {
        double hw = hole * low;
        return hw * clamp01((WastelandField.HOLE_R - j) / WastelandField.HOLE_FADE);
    }

    /**
     * The shift (warped blocks) that brings the top sheet's nearest tunnel line to a landing's centre (lx, lz): from the
     * centre's warped point straight to the nearest boundary of its cell, so its length is the landing's distance from the
     * line. {@code out} gets {shift u, shift v, that distance}.
     */
    void landingShift(double lx, double lz, Cells c, double[] out) {
        int x = (int) Math.floor(lx), z = (int) Math.floor(lz);
        int t = sheets - 1;
        cells(c, t, x + warpX(x, z), z + warpZ(x, z));
        double S = WastelandField.SPACING[t];
        double qx = c.px[c.across] - c.px[c.near1], qz = c.pz[c.across] - c.pz[c.near1], ql = Math.sqrt(qx * qx + qz * qz);
        double el = c.out[0] / S, da = el * qx / ql, db = el * qz / ql;
        out[0] = S * (cos[t] * da + sin[t] * db);
        out[1] = S * (-sin[t] * da + cos[t] * db);
        out[2] = c.out[0];
    }

    /** The share of a landing's shift a column at {@code entryD} blocks from its centre takes (1 within SHIFT_R0). */
    static double shiftShare(double entryD) {
        return Terrain.smooth((WastelandField.SHIFT_R1 - entryD) / (WastelandField.SHIFT_R1 - WastelandField.SHIFT_R0));
    }

    /**
     * The weight of the Wasteland's opening dip at column (x, z) before its guard, 0..1, where the breakthrough noise is
     * {@code beta}: its lowest sheet's junction room falling through its floor ({@link #dipWeight}). Computed afresh from
     * the noise (no cache), exactly as the Wasteland's column pass computes it.
     */
    double openingSite(double beta, double x, double z) {
        if (beta <= Join.HOLE_BETA) return 0;
        double hole = hole(beta);
        double u = x + warpX(x, z), v = z + warpZ(x, z);
        Cells c = new Cells();
        if (plans != null) {
            // a layer of one sheet: its top sheet is the lowest, and it slides toward gate 3's landing
            Plans.Gate en = plans.gate(Terrain.WASTELAND + 1, plans.cellOf(x), plans.cellOf(z));
            double entryD = StrictMath.hypot(x - en.lx, z - en.lz);
            if (entryD < WastelandField.SHIFT_R1) {
                double[] sh = new double[3];
                landingShift(en.lx, en.lz, c, sh);
                double sft = shiftShare(entryD);
                u += sh[0] * sft;
                v += sh[1] * sft;
            }
        }
        cells(c, 0, u, v);
        double f = sheetFloor(0, x, z, 1 - hole, undulation(0, x, z));
        return dipWeight(hole, low(f), c.out[1]);
    }

    // the tests' and tools' view of the pattern
    Perlin warpA() { return warpA; }
    Perlin warpB() { return warpB; }
    Perlin warpC() { return warpC; }
    Perlin warpD() { return warpD; }
    long sheetSeed(int k) { return sheetSeed[k]; }
    double offA(int k) { return offA[k]; }
    double offB(int k) { return offB[k]; }
    double cos(int k) { return cos[k]; }
    double sin(int k) { return sin[k]; }
}
