package com.explorationremix.client.combat;

import java.util.Arrays;
import java.util.Objects;

/**
 * The pure parts of a rune sword's ready light (RuneGlow draws it): where the light can lie, along which way it runs,
 * how bright each spot is as it plays, its star glints, and when it plays.
 *
 * <p>The light lies on the item's own texels: each face the item is drawn with is cut into the squares of the texture
 * pixels it shows, and only the opaque ones are kept, so the light has the sword's own pixel edges, flat or 3D. Since
 * 0.22.7 (Taran, 2026-11-25) it is a sparkly glow: the whole sword swells with light at once (washing from the hand to
 * the tip in a blink), a burst of star glints flashes over it, and a soft halo spills past its edges; then it settles
 * into the sparkle it keeps while it has the energy to cast (Taran, 2026-11-11): texels picked at random flash and fade,
 * ten a second, the brighter ones with a small star that crosses the sword's edges.
 */
public final class BladeLight {
    private BladeLight() {}

    // ------------------------------------------------------------------------------------------------ cells

    /** A texture's pixels: ABGR (as NativeImage gives them), {@code x} and {@code y} inside the texture. */
    public interface Pixels {
        int abgr(int x, int y);
    }

    /** Pixels at least this opaque (of 255) carry light. */
    static final int OPAQUE = 26;
    /** A face is cut into at most this many cells each way (a huge texture keeps its light in coarser squares). */
    static final int MAX_CUTS = 64;

    /**
     * Squares cut from one face: their corners (4 x xyz each, in the face's own space), middles (xyz each), and the texel
     * each shows ({@link #texel}: its x and y in the texture, packed).
     */
    public static final class Cells {
        public static final Cells NONE = new Cells(0, new float[0], new float[0], new int[0]);
        public final int count;
        public final float[] corners;
        public final float[] middles;
        public final int[] texels;

        Cells(int count, float[] corners, float[] middles, int[] texels) {
            this.count = count;
            this.corners = corners;
            this.middles = middles;
            this.texels = texels;
        }
    }

    /** A texel's x and y in its texture, packed into one number (and back: {@link #texelX}, {@link #texelY}). */
    public static int texel(int x, int y) { return (x & 0xFFFF) | y << 16; }

    public static int texelX(int texel) { return texel & 0xFFFF; }

    public static int texelY(int texel) { return texel >>> 16; }

    /**
     * Cuts a face into the texels it shows. {@code pos}: its four corners (xyz each, in order round the face); {@code st}:
     * the texture pixel each corner maps to (fractional; the texture is {@code w} x {@code h}). A face whose texture runs
     * square to its sides (any face of a generated item or a Blockbench box) is cut along the texel lines; one mapped
     * aslant is one cell, lit if its middle texel is.
     */
    public static Cells cut(float[] pos, float[] st, int w, int h, Pixels pixels) {
        float sa = st[2] - st[0], ta = st[3] - st[1];
        float sb = st[6] - st[0], tb = st[7] - st[1];
        float slack = 0.25F;
        int na, nb;
        if (Math.abs(ta) <= slack && Math.abs(sb) <= slack) {
            na = cuts(sa);
            nb = cuts(tb);
        } else if (Math.abs(sa) <= slack && Math.abs(tb) <= slack) {
            na = cuts(ta);
            nb = cuts(sb);
        } else {
            na = 1;
            nb = 1;
        }
        float[] corners = new float[na * nb * 12];
        float[] middles = new float[na * nb * 3];
        int[] texels = new int[na * nb];
        int n = 0;
        float[] p = new float[3];
        float[] uv = new float[2];
        for (int i = 0; i < na; i++) {
            for (int j = 0; j < nb; j++) {
                float a0 = (float) i / na, a1 = (float) (i + 1) / na, b0 = (float) j / nb, b1 = (float) (j + 1) / nb;
                float am = (a0 + a1) * 0.5F, bm = (b0 + b1) * 0.5F;
                bilerp(st, 2, am, bm, uv);
                int x = clamp((int) Math.floor(uv[0]), 0, w - 1), y = clamp((int) Math.floor(uv[1]), 0, h - 1);
                if ((pixels.abgr(x, y) >>> 24) < OPAQUE) continue;
                int c = n * 12;
                bilerp(pos, 3, a0, b0, p);
                System.arraycopy(p, 0, corners, c, 3);
                bilerp(pos, 3, a1, b0, p);
                System.arraycopy(p, 0, corners, c + 3, 3);
                bilerp(pos, 3, a1, b1, p);
                System.arraycopy(p, 0, corners, c + 6, 3);
                bilerp(pos, 3, a0, b1, p);
                System.arraycopy(p, 0, corners, c + 9, 3);
                bilerp(pos, 3, am, bm, p);
                System.arraycopy(p, 0, middles, n * 3, 3);
                texels[n] = texel(x, y);
                n++;
            }
        }
        return n == 0 ? Cells.NONE : new Cells(n, Arrays.copyOf(corners, n * 12), Arrays.copyOf(middles, n * 3), Arrays.copyOf(texels, n));
    }

    private static int cuts(float span) { return clamp(Math.round(Math.abs(span)), 1, MAX_CUTS); }

    /** The point at (a, b) across a four-cornered face whose corners' values are {@code k} each in {@code v}. */
    private static void bilerp(float[] v, int k, float a, float b, float[] out) {
        for (int d = 0; d < k; d++) {
            float c0 = v[d], c1 = v[k + d], c2 = v[2 * k + d], c3 = v[3 * k + d];
            out[d] = (1 - a) * (1 - b) * c0 + a * (1 - b) * c1 + a * b * c2 + (1 - a) * b * c3;
        }
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : Math.min(v, hi); }

    // ------------------------------------------------------------------------------------------------ the sword's run

    /** Where the light runs: each cell's place along the sword (0 at the end in the hand, 1 at the tip) and its length. */
    public record Run(float[] along, float texels) {}

    /**
     * The way the light runs over cells whose middles are given in the hand's space (the hand at the origin), a texel
     * being about {@code texel} long there: along the cells' longest spread, over the whole sword, from the end the hand
     * is nearer (the pommel) to the far end (the tip).
     */
    public static Run run(float[] mid, int n, float texel) {
        float[] along = new float[n];
        if (n == 0 || texel <= 0) return new Run(along, 0);
        float mx = 0, my = 0, mz = 0;
        for (int i = 0; i < n; i++) {
            mx += mid[i * 3];
            my += mid[i * 3 + 1];
            mz += mid[i * 3 + 2];
        }
        mx /= n;
        my /= n;
        mz /= n;
        double xx = 0, xy = 0, xz = 0, yy = 0, yz = 0, zz = 0;
        for (int i = 0; i < n; i++) {
            double dx = mid[i * 3] - mx, dy = mid[i * 3 + 1] - my, dz = mid[i * 3 + 2] - mz;
            xx += dx * dx;
            xy += dx * dy;
            xz += dx * dz;
            yy += dy * dy;
            yz += dy * dz;
            zz += dz * dz;
        }
        // the longest spread, by repeated multiplication from the widest of the three axes
        double ax, ay, az;
        if (xx >= yy && xx >= zz) { ax = 1; ay = 0; az = 0; } else if (yy >= zz) { ax = 0; ay = 1; az = 0; } else { ax = 0; ay = 0; az = 1; }
        ax += 0.1;
        ay += 0.1;
        az += 0.1;
        for (int k = 0; k < 48; k++) {
            double nx = xx * ax + xy * ay + xz * az, ny = xy * ax + yy * ay + yz * az, nz = xz * ax + yz * ay + zz * az;
            double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1.0E-20) break;
            ax = nx / len;
            ay = ny / len;
            az = nz / len;
        }
        float[] t = new float[n];
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            t[i] = (float) ((mid[i * 3] - mx) * ax + (mid[i * 3 + 1] - my) * ay + (mid[i * 3 + 2] - mz) * az);
            lo = Math.min(lo, t[i]);
            hi = Math.max(hi, t[i]);
        }
        // the hand's end first
        float hand = (float) (-mx * ax - my * ay - mz * az);
        if (hand > (lo + hi) * 0.5F) {
            for (int i = 0; i < n; i++) t[i] = -t[i];
            float l = lo;
            lo = -hi;
            hi = -l;
        }
        float span = hi - lo;
        if (span < texel) {
            Arrays.fill(along, -1);
            return new Run(along, 0);
        }
        for (int i = 0; i < n; i++) along[i] = Math.min(1.0F, Math.max(0.0F, (t[i] - lo) / span));
        return new Run(along, span / texel);
    }

    // ------------------------------------------------------------------------------------------------ the light as it plays

    /** Ticks the light lasts, all told. */
    public static final float END = 22.0F;
    /** The swell comes up in this long (ticks), holds at its height this long, then fades away by {@link #END}. */
    static final float SWELL_UP = 3.0F, SWELL_HOLD = 1.5F;
    /** It reaches the tip this long after the end in the hand (ticks): all at once, but with a hint of flow. */
    static final float WASH = 1.2F;
    /** The halo round the sword at the swell's height, as strong as this (the sword's own texels are at full). */
    public static final float HALO = 0.3F;

    /** The swell's strength at tick {@code t} of the light (0 to 1): up fast, a moment held, then a soft fade that lingers. */
    public static float swell(float t) {
        if (t < 0 || t >= END) return 0;
        if (t < SWELL_UP) return smooth(0.0F, SWELL_UP, t);
        float f = (t - SWELL_UP - SWELL_HOLD) / (END - SWELL_UP - SWELL_HOLD);
        if (f <= 0) return 1;
        return (float) Math.exp(-3.0 * f) * (1.0F - f);
    }

    /**
     * How bright the light is at tick {@code t} of its play, {@code x} texels along a sword {@code len} long; and in
     * {@code white[0]}, how near white it burns there (whitest at the swell's height).
     */
    public static float light(float x, float len, float t, float[] white) {
        white[0] = 0;
        if (t < 0 || t >= END || x < 0) return 0;
        float lag = len <= 0 ? 0.0F : WASH * Math.min(1.0F, x / len);
        float k = swell(t - lag);
        white[0] = 0.9F * k * k;
        return k;
    }

    static float smooth(float e0, float e1, float x) {
        float k = Math.min(1.0F, Math.max(0.0F, (x - e0) / (e1 - e0)));
        return k * k * (3.0F - 2.0F * k);
    }

    // ------------------------------------------------------------------------------------------------ star glints

    /** The burst: how many star glints flash over the sword as it swells, from when and over how long (ticks). */
    static final int BURST = 7;
    static final float BURST_FROM = 0.5F, BURST_SPREAD = 6.0F;
    /** Ticks one star glint lasts; how big a burst's stars are at their brightest (texels, from the middle to a point). */
    static final float GLINT = 6.0F, GLINT_SIZE = 3.2F;
    /** A resting twinkle this bright or brighter shows a star, this big at a full twinkle (texels). */
    static final float STAR_FROM = 0.45F, TWINKLE_STAR = 1.9F;

    /**
     * One star glint: on which of the sword's texels (counted in key order), from when (ticks into the light), how big
     * (texels, at its brightest), and how its points are turned from upright (radians).
     */
    public record Glint(int texel, float at, float size, float tilt) {}

    /**
     * The burst over a sword of {@code d} texels (in key order), for the light {@code seed} names: {@link #BURST} star
     * glints, one in each share of the texels (so they spread over the sword), at times picked through the first moments.
     */
    public static Glint[] burst(int d, int seed) {
        int m = Math.min(BURST, d);
        Glint[] g = new Glint[m];
        for (int i = 0; i < m; i++) {
            int h = mix(seed * 0x9E3779B1 + (i + 1) * 0x85EBCA6B);
            int lo = i * d / m, hi = Math.max(lo + 1, (i + 1) * d / m);
            int pick = lo + Math.min(hi - lo - 1, (int) (unit(h) * (hi - lo)));
            float at = BURST_FROM + BURST_SPREAD * unit(mix(h));
            float size = GLINT_SIZE * (0.75F + 0.5F * unit(mix(h ^ 0x5BD1E995)));
            g[i] = new Glint(pick, at, size, tilt(h));
        }
        return g;
    }

    /** A star glint's strength {@code age} ticks after it starts: a sharp flash, then a fade as it shrinks. */
    public static float glint(float age) {
        if (age < 0 || age >= GLINT) return 0;
        float k = age / GLINT;
        if (k < RISE) return smooth(0.0F, RISE, k);
        float f = 1.0F - (k - RISE) / (1.0F - RISE);
        return f * f;
    }

    /** A star's turn from upright (radians), from a mixed number: within a quarter of a right angle either way. */
    static float tilt(int h) { return (unit(mix(h ^ 0x27D4EB2F)) - 0.5F) * (float) (Math.PI / 4.0); }

    /** A resting twinkle's star on the sword's {@code d}th texel (key order): its turn, the same each time it twinkles. */
    public static float twinkleTilt(int d, int seed) { return tilt(mix(seed * 0x2C1B3C6D + d * 0x297A2D39)); }

    /** A resting twinkle this bright ({@link #twinkles}) shows a star this strong (0 below {@link #STAR_FROM}). */
    public static float twinkleStar(float twinkle) {
        return twinkle <= STAR_FROM ? 0.0F : Math.min(1.0F, (twinkle - STAR_FROM) / (1.0F - STAR_FROM));
    }

    /** A sword's texels, each once, in key order (a texel's front, back and edge cells share a key). */
    public static long[] distinct(long[] keys, int n) {
        long[] texels = Arrays.copyOf(keys, n);
        Arrays.sort(texels);
        int d = 0;
        for (int i = 0; i < n; i++) if (d == 0 || texels[i] != texels[d - 1]) texels[d++] = texels[i];
        return Arrays.copyOf(texels, d);
    }

    // ------------------------------------------------------------------------------------------------ the halo

    /** The halo is this many soft glows strung along the sword's run, each this wide (texels, its middle to its rim). */
    public static final int HALO_GLOWS = 8;
    public static final float HALO_RADIUS = 2.8F;

    /**
     * Where the halo's glows sit: the cells' middles ({@code xyz}, three each) averaged over each of {@code glows} equal
     * stretches of the sword's run ({@code along}, from {@link #run}); four numbers a glow: x, y, z, and how far along
     * the sword it is (-1: no cell in its stretch).
     */
    public static float[] haloGlows(float[] along, float[] xyz, int n, int glows) {
        float[] out = new float[glows * 4];
        int[] count = new int[glows];
        for (int i = 0; i < n; i++) {
            float a = along[i];
            if (a < 0) continue;
            int g = Math.min(glows - 1, (int) (a * glows));
            out[g * 4] += xyz[i * 3];
            out[g * 4 + 1] += xyz[i * 3 + 1];
            out[g * 4 + 2] += xyz[i * 3 + 2];
            out[g * 4 + 3] += a;
            count[g]++;
        }
        for (int g = 0; g < glows; g++) {
            if (count[g] == 0) {
                out[g * 4 + 3] = -1.0F;
                continue;
            }
            for (int d = 0; d < 4; d++) out[g * 4 + d] /= count[g];
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ the sparkle while charged

    /**
     * Twinkles a second over a whole sword while it has the energy to cast, however many texels it has: on a steady
     * beat (a little loose), not by chance, so the sword never goes dark for a moment and never blazes.
     */
    static final float TWINKLES = 10.0F;
    /** Ticks one twinkle lasts. */
    static final float TWINKLE = 6.0F;
    /**
     * How much of a twinkle's flash comes up (the rest is its fade); how bright the four texels round it light at its
     * peak (they fade faster than the middle, so a twinkle reads as a glint that shrinks to one texel).
     */
    static final float RISE = 0.15F, ARM = 0.4F;

    /** A texel's name across the whole sword: which texture it is in ({@code salt}) and its place there. */
    public static long key(int salt, int texel) { return (long) salt << 32 | (texel & 0xFFFFFFFFL); }

    /**
     * The sparkle over a sword's cells at tick {@code t} (any steady clock): {@code keys[i]} names the texel cell i shows
     * ({@link #key}; a texel's front, back and edge cells share it), and {@code seed} tells one sword in hand from another
     * so two don't twinkle in step. Into {@code out[i]} goes how brightly the cell sparkles, and into {@code white[i]}
     * how near white: a texel picked at random flashes and fades, {@link #TWINKLES} a second, lighting the texels beside
     * it a little (only the sword's own: nothing lights round its edge).
     */
    public static void sparkle(long[] keys, int n, float t, int seed, float[] out, float[] white) {
        long[] texels = distinct(keys, n);
        sparkle(keys, n, texels, twinkles(texels.length, t, seed), out, white);
    }

    /** The same, from the sword's {@link #distinct} texels and their own twinkles ({@link #twinkles}). */
    public static void sparkle(long[] keys, int n, long[] texels, float[] own, float[] out, float[] white) {
        int d = texels.length;
        for (int i = 0; i < n; i++) {
            long k = keys[i];
            float self = at(texels, d, own, k);
            float near = Math.max(Math.max(at(texels, d, own, beside(k, -1, 0)), at(texels, d, own, beside(k, 1, 0))),
                    Math.max(at(texels, d, own, beside(k, 0, -1)), at(texels, d, own, beside(k, 0, 1))));
            float arms = ARM * near * near;
            out[i] = Math.max(self, arms);
            white[i] = self >= arms ? self : 0.5F * arms;
        }
    }

    /**
     * Each of a sword's {@code d} texels' own twinkle at tick {@code t} (0 if it isn't twinkling), the texels counted in
     * order. A twinkle starts each beat ({@code 20 / TWINKLES} ticks), somewhere in its first four fifths, on a texel picked
     * from the beat and the seed.
     */
    static float[] twinkles(int d, float t, int seed) {
        float[] own = new float[d];
        if (d == 0) return own;
        double beat = 20.0 / TWINKLES;
        long last = (long) Math.floor(t / beat);
        long first = last - (long) Math.ceil(TWINKLE / beat);
        for (long j = last; j >= first; j--) {
            int h = mix(seed * 0x01000193 ^ mix((int) j ^ (int) (j >>> 32)));
            double age = t - (j + 0.8 * unit(h)) * beat;
            if (age < 0 || age >= TWINKLE) continue;
            int g = mix(h);
            int pick = Math.min(d - 1, (int) (unit(g) * d));
            float k = (float) (age / TWINKLE);
            float fade = 1.0F - (k - RISE) / (1.0F - RISE);
            float shape = k < RISE ? smooth(0.0F, RISE, k) : fade * fade;
            own[pick] = Math.max(own[pick], (0.75F + 0.25F * unit(mix(g))) * shape);
        }
        return own;
    }

    /** The texel {@code dx, dy} from the one {@code key} names, in the same texture (or -1: off its edge). */
    private static long beside(long key, int dx, int dy) {
        int x = texelX((int) key) + dx, y = texelY((int) key) + dy;
        if (x < 0 || y < 0 || x > 0xFFFF || y > 0xFFFF) return -1L;
        return key(texelSalt(key), texel(x, y));
    }

    private static int texelSalt(long key) { return (int) (key >>> 32); }

    private static float at(long[] texels, int d, float[] own, long key) {
        if (key == -1L) return 0.0F;
        int i = Arrays.binarySearch(texels, 0, d, key);
        return i >= 0 ? own[i] : 0.0F;
    }

    private static int mix(int x) {
        x ^= x >>> 16;
        x *= 0x7FEB352D;
        x ^= x >>> 15;
        x *= 0x846CA68B;
        x ^= x >>> 16;
        return x;
    }

    /** 0 to 1 (never 1) from a mixed number. */
    private static float unit(int h) { return (h >>> 8) / 16777216.0F; }

    // ------------------------------------------------------------------------------------------------ when it plays

    /**
     * When a sword's light plays: once each time it comes back to enough energy for its next cast (and nothing holds it
     * from casting), not again while it stays there, and not for a sword just taken in hand already full.
     */
    public static final class Trigger {
        private Object held;
        private boolean seen, armed;
        private float startedAt = -1.0E9F;

        /**
         * One tick: what is held ({@code held}: anything that tells one sword in hand from another) and how ready it is (0:
         * short of energy; 1: ready to cast now; between: enough energy, waiting out a delay). True when the light starts.
         */
        public boolean update(Object held, float readiness, float now) {
            if (!seen || !Objects.equals(held, this.held)) {
                seen = true;
                this.held = held;
                armed = readiness <= 0;
                startedAt = -1.0E9F;
                return false;
            }
            if (readiness <= 0) {
                armed = true;
                return false;
            }
            if (readiness >= 1 && armed && now - startedAt >= END) {
                armed = false;
                startedAt = now;
                return true;
            }
            return false;
        }

        /** Ticks since the light started (huge if it never has). */
        public float age(float now) { return now - startedAt; }

        /** When the light last started (on the clock {@link #update} was given). */
        public float startedAt() { return startedAt; }
    }
}
