package com.explorationremix.creature;

/**
 * The giant Skein's strike (0.18.8), as plain numbers that the server (for the bite) and the client (for the drawing)
 * work out alike: its front comes up off the rock from {@link #NECK} blocks back along its body (the anchor), coils
 * there with its head drawn up and back over its body, then shoots its head out at what it strikes at, up to {@link
 * #REACH} from the anchor, and draws it back down to where it lay. Between the anchor and the head its front lies along
 * a smooth curve (a cubic leaving the anchor the way the body runs and coming into the head the way the head points),
 * so the reared neck is one easy S, never a hinge.
 *
 * <p>Positions are in blocks; vectors are three doubles. Plain numbers, no game classes, so it can be tested on its own.
 */
public final class GiantStrike {
    private GiantStrike() {}

    /** The phases: lying (no strike), coiling, striking out, drawing back. */
    public static final int NONE = 0, COIL = 1, OUT = 2, BACK = 3;
    /** How far back along its body its front leaves the rock (blocks). */
    public static final double NECK = 9.0;
    /** Coiled: its head this far off its rock, and this share of the neck out towards what it strikes at. */
    public static final double COIL_RISE = 4.5, COIL_OUT = 0.3;
    /**
     * The furthest its head goes from the anchor (blocks): its neck stretches to about one and a half times its length
     * at the most, so with its jaws it reaches some 14 blocks from the anchor, 3 or 4 straight on past its jaws as it
     * lay, 10 or 11 out to the side.
     */
    public static final double REACH = 12.5;
    /** Its jaws are this far ahead of its head's point. */
    public static final double JAWS = 1.6;
    /** Of the strike out, the share it takes the head to get there (it holds there the rest). */
    public static final double SNAP = 0.45;
    /** How many pieces the neck's curve is measured in. */
    static final int PIECES = 24;

    static double clamp01(double t) { return t < 0 ? 0 : (t > 1 ? 1 : t); }

    /** Smoothstep. */
    public static double ease(double t) {
        t = clamp01(t);
        return t * t * (3 - 2 * t);
    }

    /** Fast at first, settling at the end. */
    public static double snap(double t) {
        t = clamp01(t);
        double u = 1 - t;
        return 1 - u * u * u;
    }

    /**
     * How much of its front lies along the strike's curve rather than on the rock: rising as it coils, all of it as it
     * strikes, sinking back as it draws back.
     */
    public static double weight(int phase, double k) {
        return switch (phase) {
            case COIL -> ease(k);
            case OUT -> 1.0;
            case BACK -> 1.0 - ease(k);
            default -> 0.0;
        };
    }

    /**
     * Where its head is and the way it points, at {@code k} (0 to 1) through {@code phase}: the anchor {@code a} on rock
     * facing {@code n}, the body running from it towards the head along {@code d} (unit), the head at rest at {@code h0},
     * the aim at {@code t}. Writes the head's point to out[0..2] and the way it points (unit) to out[3..5].
     */
    public static void head(double[] a, double[] n, double[] d, double[] h0, double[] t, int phase, double k, double[] out) {
        head(a, n, d, h0, t, phase, k, COIL_RISE, out);
    }

    /**
     * As {@link #head(double[], double[], double[], double[], double[], int, double, double[])}, its head coiling only
     * {@code rise} off its rock (0.21.5: under a low roof, no higher than there is room for).
     */
    public static void head(double[] a, double[] n, double[] d, double[] h0, double[] t, int phase, double k, double rise, double[] out) {
        double[] c = coil(a, n, d, t, rise), s = struck(a, c, t);
        double[] toT = unit(sub(t, c), d);
        switch (phase) {
            case COIL -> {
                double e = ease(k);
                set(out, 0, lerp(h0, c, e));
                set(out, 3, unit(lerp(d, unit(sub(t, out), toT), e), d));
            }
            case OUT -> {
                set(out, 0, lerp(c, s, snap(k / SNAP)));
                set(out, 3, toT);
            }
            case BACK -> {
                double e = ease(k);
                set(out, 0, lerp(s, h0, e));
                set(out, 3, unit(lerp(toT, d, e), d));
            }
            default -> {
                set(out, 0, h0);
                set(out, 3, d);
            }
        }
    }

    /** Coiled: up off the rock over the anchor, a little way out towards the aim along the rock. */
    static double[] coil(double[] a, double[] n, double[] d, double[] t) {
        return coil(a, n, d, t, COIL_RISE);
    }

    /** Coiled, {@code rise} up off the rock over the anchor. */
    static double[] coil(double[] a, double[] n, double[] d, double[] t, double rise) {
        double[] to = sub(t, a);
        double along = dot(to, n);
        double[] flat = unit(new double[]{to[0] - n[0] * along, to[1] - n[1] * along, to[2] - n[2] * along}, d);
        return new double[]{a[0] + n[0] * rise + flat[0] * NECK * COIL_OUT, a[1] + n[1] * rise + flat[1] * NECK * COIL_OUT,
                a[2] + n[2] * rise + flat[2] * NECK * COIL_OUT};
    }

    /**
     * Struck out: its head where its jaws (pointing from the coil {@code c} at the aim, as it strikes) close on the aim,
     * or as near there as its reach from the anchor lets it go.
     */
    static double[] struck(double[] a, double[] c, double[] t) {
        double[] f = unit(sub(t, c), new double[]{0, 0, 0});
        double[] s = {t[0] - f[0] * JAWS, t[1] - f[1] * JAWS, t[2] - f[2] * JAWS};
        double[] to = sub(s, a);
        double l = len(to);
        if (l <= REACH) return s;
        return new double[]{a[0] + to[0] / l * REACH, a[1] + to[1] / l * REACH, a[2] + to[2] / l * REACH};
    }

    /** Its jaws: ahead of the head's point (from {@link #head}'s out) along the way it points. */
    public static void jaws(double[] head, double[] out) {
        out[0] = head[0] + head[3] * JAWS;
        out[1] = head[1] + head[4] * JAWS;
        out[2] = head[2] + head[5] * JAWS;
    }

    /**
     * The neck's curve from the anchor (a, facing n, the body running on along d) to the head (from {@link #head}'s
     * out): {@code fraction} of the way from the head (0) to the anchor (1) along it, by length. Writes the point to
     * o[off..off+2], and to o[off+3..off+5] the way the curve runs there towards the head (unit).
     */
    public static void neck(double[] a, double[] n, double[] d, double[] head, double fraction, double[] o, int off) {
        double[] h = {head[0], head[1], head[2]}, f = {head[3], head[4], head[5]};
        double span = len(sub(h, a));
        double[] p1 = {a[0] + d[0] * span * 0.4 + n[0] * span * 0.15, a[1] + d[1] * span * 0.4 + n[1] * span * 0.15,
                a[2] + d[2] * span * 0.4 + n[2] * span * 0.15};
        double[] p2 = {h[0] - f[0] * span * 0.35, h[1] - f[1] * span * 0.35, h[2] - f[2] * span * 0.35};
        // its length along PIECES chords, from the head back
        double[] acc = new double[PIECES + 1];
        double[] prev = bezier(a, p1, p2, h, 1.0), cur;
        for (int i = 1; i <= PIECES; i++) {
            cur = bezier(a, p1, p2, h, 1.0 - (double) i / PIECES);
            acc[i] = acc[i - 1] + len(sub(cur, prev));
            prev = cur;
        }
        double want = clamp01(fraction) * acc[PIECES];
        int i = 1;
        while (i < PIECES && acc[i] < want) i++;
        double seg = acc[i] - acc[i - 1];
        double u = 1.0 - ((i - 1) + (seg < 1.0E-9 ? 0.0 : (want - acc[i - 1]) / seg)) / PIECES;
        double[] p = bezier(a, p1, p2, h, u);
        double[] tan = unit(sub(bezier(a, p1, p2, h, Math.min(1.0, u + 0.01)), bezier(a, p1, p2, h, Math.max(0.0, u - 0.01))), f);
        o[off] = p[0]; o[off + 1] = p[1]; o[off + 2] = p[2];
        o[off + 3] = tan[0]; o[off + 4] = tan[1]; o[off + 5] = tan[2];
    }

    static double[] bezier(double[] p0, double[] p1, double[] p2, double[] p3, double u) {
        double v = 1 - u, b0 = v * v * v, b1 = 3 * v * v * u, b2 = 3 * v * u * u, b3 = u * u * u;
        return new double[]{b0 * p0[0] + b1 * p1[0] + b2 * p2[0] + b3 * p3[0], b0 * p0[1] + b1 * p1[1] + b2 * p2[1] + b3 * p3[1],
                b0 * p0[2] + b1 * p1[2] + b2 * p2[2] + b3 * p3[2]};
    }

    // ------------------------------------------------------------------------------------------------ vectors
    static double[] sub(double[] a, double[] b) { return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }
    static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    static double len(double[] a) { return Math.sqrt(dot(a, a)); }

    static double[] lerp(double[] a, double[] b, double t) {
        return new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
    }

    /** {@code v} at unit length, or {@code fallback} when it has next to none. */
    static double[] unit(double[] v, double[] fallback) {
        double l = len(v);
        if (l < 1.0E-6) return new double[]{fallback[0], fallback[1], fallback[2]};
        return new double[]{v[0] / l, v[1] / l, v[2] / l};
    }

    private static void set(double[] out, int off, double[] v) {
        out[off] = v[0];
        out[off + 1] = v[1];
        out[off + 2] = v[2];
    }
}
