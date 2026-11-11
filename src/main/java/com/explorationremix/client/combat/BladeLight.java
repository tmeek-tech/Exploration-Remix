package com.explorationremix.client.combat;

import java.util.Arrays;
import java.util.Objects;

/**
 * The pure parts of a rune sword's ready light (RuneGlow draws it): where the light can lie, along which way it runs,
 * how bright each spot is as it plays, and when it plays.
 *
 * <p>The light lies on the item's own texels: each face the item is drawn with is cut into the squares of the texture
 * pixels it shows, and only the opaque ones are kept, so the light has the sword's own pixel edges, flat or 3D. It runs
 * over the whole sword (Taran, 14:45 the same day), from the end in the hand to the tip: a bright band up it, the tip
 * flaring as it gets there, then a short afterglow that fades to nothing. And while the sword has the energy to cast,
 * it sparkles (Taran, 15:00): texels picked at random flash and fade, ten a second.
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
    public static final float END = 26.0F;
    /** Ticks the band takes to run from just below the pommel to just past the tip. */
    static final float SWEEP = 9.0F;
    /** Texels of the band's tail; of the band before and past the sword; of the tip that flares. */
    static final float TAIL = 2.5F, BEFORE = 2.0F, PAST = 3.0F, TIP = 2.0F;
    /** The afterglow's strength, and when it starts to fade. */
    static final float AFTER = 0.3F, AFTER_FADE = 12.0F;
    /** How long the tip's flare takes to die down (ticks). */
    static final float FLARE = 4.0F;

    /** Where the band's front is ({@code texels} from the start) at tick {@code t}, on a sword {@code len} texels long. */
    static float front(float t, float len) { return smooth(0.0F, SWEEP, t) * (len + BEFORE + PAST) - BEFORE; }

    /** When the band's front reaches the tip of a sword {@code len} texels long. */
    static float tipAt(float len) {
        float y = (len + BEFORE) / (len + BEFORE + PAST);
        // the inverse of smoothstep
        float k = (float) (0.5 - Math.sin(Math.asin(1.0 - 2.0 * y) / 3.0));
        return k * SWEEP;
    }

    /**
     * How bright the light is at tick {@code t} of its play, {@code x} texels along a sword {@code len} long; and in
     * {@code white[0]}, how near white it burns there (the band and the flare burn whitest).
     */
    public static float light(float x, float len, float t, float[] white) {
        white[0] = 0;
        if (t < 0 || t >= END || x < 0) return 0;
        float d = front(t, len) - x;
        float band = d >= 0 ? (float) Math.exp(-d / TAIL) : (float) Math.exp(d * 2.0F);
        float after = d >= 0 ? AFTER * (1.0F - smooth(AFTER_FADE, END, t)) : 0.0F;
        float flare = 0.0F;
        if (x >= len - TIP) {
            float at = tipAt(len);
            flare = smooth(at - 1.5F, at, t) * (t < at ? 1.0F : (float) Math.exp(-(t - at) / FLARE));
        }
        float end = 1.0F - smooth(END - 4.0F, END, t);
        float hot = Math.max(band * 0.9F, flare);
        white[0] = hot * end;
        return Math.max(hot, after) * end;
    }

    static float smooth(float e0, float e1, float x) {
        float k = Math.min(1.0F, Math.max(0.0F, (x - e0) / (e1 - e0)));
        return k * k * (3.0F - 2.0F * k);
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
        long[] texels = Arrays.copyOf(keys, n);
        Arrays.sort(texels);
        int d = 0;
        for (int i = 0; i < n; i++) if (d == 0 || texels[i] != texels[d - 1]) texels[d++] = texels[i];
        float[] own = twinkles(d, t, seed);
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
    }
}
