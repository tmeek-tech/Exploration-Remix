package com.explorationremix.world.lava;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntIterator;

/**
 * What the rising and falling lava does to the blocks of one chunk, one layer of blocks (a slice) at a time: pure
 * rules, so they are tested without Minecraft. {@link LavaFlood} feeds them real chunks.
 *
 * <p>The flood band is the {@code rise} blocks over the Galleries' lava table: slice i is the blocks at the table's Y
 * plus i, for i from 0 to rise - 1. A chunk's lava stands at height h when every slice under h has been flooded and
 * none at or over it: raising it to h + 1 floods slice h, lowering it to h - 1 drains slice h - 1. Nothing outside
 * the band is ever touched.
 *
 * <ul>
 *     <li><b>Rising</b>, the lava takes every block of the slice that is air, fire, flowing lava (a fall or a
 *     spill, which the sea swallows) or the halls' invisible glow, putting a lava source there, and records it in the
 *     chunk's {@link Mask}; a glow it takes is also marked in a second mask, and any other block it takes (a fall's
 *     flowing lava at its level, fire) is kept exactly in the chunk's {@link Kept} record, so the drain can put back
 *     exactly what was there. Everything else stays as it is: rock, water, natural lava sources (pools, which join
 *     the sea while it covers them), and anything else that is not air, so the terrain's own decoration and players'
 *     torches, rungs and signs come through a flood (the lava closes round them, as vanilla lava does round a ladder).
 *     Every open block under the lava's height is filled, connected to the sea or not: lava seeps through the rock
 *     into sealed pockets.</li>
 *     <li><b>Draining</b>, the lava leaves only the blocks it recorded that still hold lava, putting back exactly what
 *     it took there: air, the glow, or the kept block (a lava fall comes back as the same falling lava, level for
 *     level, so it is the fall the terrain made, not one vanilla's flow rules pour again: re-poured, a fall runs out
 *     over the floor at its foot, feeds lava that leaked from the sea into the blocks beside it for good, and turns to
 *     basalt where Immersive Weathering finds flowing lava on basalt). Natural lava, players' lava, and whatever a
 *     player put in its place stay. Then the slice's caller is asked to wake the lava round each block the drain
 *     left ({@link Slice#wake}); the game wakes only lava that changed since the last terrain reset (players'), never
 *     the terrain's own.</li>
 * </ul>
 */
public final class Flood {
    private Flood() {}

    /**
     * What the flood sees in a block: something it leaves alone, air, fire, a lava source, flowing lava, or the
     * Galleries' invisible glow (explorationremix:glow).
     */
    public static final int OTHER = 0, AIR = 1, FIRE = 2, LAVA = 3, FLOWING = 4, GLOW = 5;

    /** Rising: true when the lava takes a block of this kind (a lava source goes there). */
    public static boolean takes(int kind) { return kind == AIR || kind == FIRE || kind == FLOWING || kind == GLOW; }

    /** Draining: true when the lava leaves a block of this kind that it put there (it becomes air). */
    public static boolean leaves(int kind) { return kind == LAVA || kind == FLOWING; }

    /**
     * The slice a chunk works on next while its lava moves from height {@code applied} toward {@code target}: slice
     * {@code applied} when rising, {@code applied - 1} when draining, -1 when it is there.
     */
    public static int nextSlice(int applied, int target) {
        if (applied < target) return applied;
        if (applied > target) return applied - 1;
        return -1;
    }

    /** One slice of one chunk, as the flood sees and changes it; x and z are 0 to 15 within the chunk. */
    public interface Slice {
        /**
         * The kind of block at (x, z): one of {@link #OTHER}, {@link #AIR}, {@link #FIRE}, {@link #LAVA}, {@link #FLOWING},
         * {@link #GLOW}.
         */
        int kind(int x, int z);
        /**
         * The block at (x, z) exactly, as a non-negative id the slice can {@link #put} back (the game's block state id),
         * or -1 when it keeps no such ids. Asked only for blocks the lava takes that are neither air nor the glow: a
         * fall's flowing lava, fire.
         */
        default int state(int x, int z) { return -1; }
        /** Puts a lava source at (x, z). */
        void fill(int x, int z);
        /** Puts air at (x, z). */
        void clear(int x, int z);
        /** Puts the halls' glow back at (x, z), where the lava took one; air when the slice has no glow to give. */
        default void glow(int x, int z) { clear(x, z); }
        /** Puts back at (x, z) the block {@link #state} gave for it before the lava took it. */
        default void put(int x, int z, int state) { clear(x, z); }
        /**
         * After a drain: the drain has put back what the lava took at (x, z); the caller may wake lava next to it (the
         * game wakes only lava changed since the last terrain reset, never the terrain's own: see the class comment).
         */
        void wake(int x, int z);
    }

    /** {@link #rise(Slice, Mask, Mask, Kept, int)} with no glow and nothing else to keep. */
    public static int rise(Slice s, Mask m, int i) { return rise(s, m, null, null, i); }

    /** {@link #rise(Slice, Mask, Mask, Kept, int)} keeping the glow but no other block. */
    public static int rise(Slice s, Mask m, Mask glow, int i) { return rise(s, m, glow, null, i); }

    /**
     * Floods slice i of a chunk: every block the lava takes gets a lava source and its bit in the mask, a glow it takes
     * its bit in {@code glow} too (when given), and any other block it takes that is not air (a fall's flowing lava,
     * fire) its exact state in {@code kept} (when given and the slice gives states). Returns how many blocks changed.
     */
    public static int rise(Slice s, Mask m, Mask glow, Kept kept, int i) {
        int n = 0;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int k = s.kind(x, z);
                if (!takes(k)) continue;
                if (kept != null) {
                    int st = k != AIR && k != GLOW ? s.state(x, z) : -1;
                    if (st >= 0) kept.put(i, x, z, st);
                    else kept.remove(i, x, z);
                }
                s.fill(x, z);
                m.set(i, x, z);
                if (k == GLOW && glow != null) glow.set(i, x, z);
                n++;
            }
        }
        return n;
    }

    /** {@link #drain(Slice, Mask, Mask, Kept, int)} with no glow and nothing else to put back. */
    public static int drain(Slice s, Mask m, int i) { return drain(s, m, null, null, i); }

    /** {@link #drain(Slice, Mask, Mask, Kept, int)} putting back the glow but no other block. */
    public static int drain(Slice s, Mask m, Mask glow, int i) { return drain(s, m, glow, null, i); }

    /**
     * Drains slice i of a chunk: every block whose bit is set loses its bit (in {@code glow} and {@code kept} too), and
     * if it still holds lava gets back exactly what the lava took there: the glow where it took one, the kept block
     * where it kept one, air everywhere else; then the slice is asked to wake round each block it put back. Returns how
     * many blocks changed.
     */
    public static int drain(Slice s, Mask m, Mask glow, Kept kept, int i) {
        if (!m.any(i)) {
            // nothing of the lava's in the slice: no kept block can be left there either
            if (kept != null) kept.clear(i);
            return 0;
        }
        // the blocks the lava left, one bit each as in the mask (z << 4 | x)
        long[] cleared = new long[4];
        int n = 0;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                if (!m.get(i, x, z)) continue;
                m.unset(i, x, z);
                boolean lit = glow != null && glow.get(i, x, z);
                if (lit) glow.unset(i, x, z);
                int st = kept == null ? -1 : kept.remove(i, x, z);
                if (!leaves(s.kind(x, z))) continue;
                if (lit) s.glow(x, z);
                else if (st >= 0) s.put(x, z, st);
                else s.clear(x, z);
                int b = z << 4 | x;
                cleared[b >>> 6] |= 1L << (b & 63);
                n++;
            }
        }
        // a kept block whose lava bit was gone (never so in the game's records) is dropped with the slice
        if (kept != null) kept.clear(i);
        // wake only once the whole slice is dry, so no lava of this slice is woken to flow into its neighbours
        for (int w = 0; w < 4; w++) {
            for (long bits = cleared[w]; bits != 0; bits &= bits - 1) {
                int b = w << 6 | Long.numberOfTrailingZeros(bits);
                s.wake(b & 15, b >> 4);
            }
        }
        return n;
    }

    /**
     * The blocks of a chunk's flood band the lava put there: one bit per block, 256 per slice. Empty until the lava
     * first fills something (most chunks never hold any of the sea).
     */
    public static final class Mask {
        private final int slices;
        private long[] bits;

        public Mask(int slices) {
            if (slices < 0) throw new IllegalArgumentException("slices " + slices);
            this.slices = slices;
        }

        public int slices() { return slices; }

        private static int bit(int i, int x, int z) { return i << 8 | z << 4 | x; }

        public boolean get(int i, int x, int z) {
            if (bits == null || i < 0 || i >= slices) return false;
            int b = bit(i, x, z);
            return (bits[b >>> 6] & 1L << (b & 63)) != 0;
        }

        public void set(int i, int x, int z) {
            if (i < 0 || i >= slices) throw new IndexOutOfBoundsException("slice " + i + " of " + slices);
            if (bits == null) bits = new long[slices * 4];
            int b = bit(i, x, z);
            bits[b >>> 6] |= 1L << (b & 63);
        }

        public void unset(int i, int x, int z) {
            if (bits == null || i < 0 || i >= slices) return;
            int b = bit(i, x, z);
            bits[b >>> 6] &= ~(1L << (b & 63));
        }

        /** True when any block of slice i is the lava's. */
        public boolean any(int i) {
            if (bits == null || i < 0 || i >= slices) return false;
            int w = i * 4;
            return (bits[w] | bits[w + 1] | bits[w + 2] | bits[w + 3]) != 0;
        }

        /** How many blocks of slice i are the lava's. */
        public int count(int i) {
            if (bits == null || i < 0 || i >= slices) return 0;
            int w = i * 4;
            return Long.bitCount(bits[w]) + Long.bitCount(bits[w + 1]) + Long.bitCount(bits[w + 2]) + Long.bitCount(bits[w + 3]);
        }

        /** True when no block is the lava's. */
        public boolean isEmpty() {
            if (bits == null) return true;
            for (long w : bits) if (w != 0) return false;
            return true;
        }

        /** The bits for saving, or null when there are none. */
        public long[] toArray() { return isEmpty() ? null : bits.clone(); }

        /** Reads saved bits back (a wrong length, from another band height, keeps what fits). */
        public void fromArray(long[] a) {
            bits = null;
            if (a == null || a.length == 0 || slices == 0) return;
            bits = new long[slices * 4];
            System.arraycopy(a, 0, bits, 0, Math.min(a.length, bits.length));
            if (isEmpty()) bits = null;
        }

        /**
         * Moves every bit up by {@code by} slices (down when negative), dropping those that leave the band: for a
         * chunk saved when the lava table sat {@code by} blocks higher than it does now (the config was changed).
         */
        public void shift(int by) {
            if (bits == null || by == 0) return;
            long[] n = new long[bits.length];
            for (int i = 0; i < slices; i++) {
                int j = i + by;
                if (j < 0 || j >= slices) continue;
                System.arraycopy(bits, i * 4, n, j * 4, 4);
            }
            bits = n;
            if (isEmpty()) bits = null;
        }
    }

    /**
     * The blocks the rising lava took that were neither air nor the glow (a fall's flowing lava at its level, fire),
     * kept exactly so the drain puts them back as they were: one id per block (the game's block state id), keyed like
     * the {@link Mask}'s bits. Empty in almost every chunk (only the falls' columns and their spills in the band).
     */
    public static final class Kept {
        private final int slices;
        private final Int2IntOpenHashMap map = new Int2IntOpenHashMap();

        public Kept(int slices) {
            if (slices < 0) throw new IllegalArgumentException("slices " + slices);
            this.slices = slices;
            map.defaultReturnValue(-1);
        }

        public int slices() { return slices; }

        private static int bit(int i, int x, int z) { return i << 8 | z << 4 | x; }

        /** The kept block at (i, x, z), or -1. */
        public int get(int i, int x, int z) {
            if (i < 0 || i >= slices) return -1;
            return map.get(bit(i, x, z));
        }

        /** Keeps block {@code state} (non-negative) at (i, x, z), in place of any kept there before. */
        public void put(int i, int x, int z, int state) {
            if (i < 0 || i >= slices) throw new IndexOutOfBoundsException("slice " + i + " of " + slices);
            if (state < 0) throw new IllegalArgumentException("state " + state);
            map.put(bit(i, x, z), state);
        }

        /** Forgets the block kept at (i, x, z); returns it, or -1 when none was. */
        public int remove(int i, int x, int z) {
            if (i < 0 || i >= slices || map.isEmpty()) return -1;
            return map.remove(bit(i, x, z));
        }

        /** Forgets every block kept in slice i. */
        public void clear(int i) {
            if (map.isEmpty()) return;
            for (IntIterator it = map.keySet().iterator(); it.hasNext(); ) if (it.nextInt() >> 8 == i) it.remove();
        }

        public void clear() { map.clear(); }
        public boolean isEmpty() { return map.isEmpty(); }
        public int size() { return map.size(); }

        /** How many blocks of slice i are kept. */
        public int count(int i) {
            int n = 0;
            for (IntIterator it = map.keySet().iterator(); it.hasNext(); ) if (it.nextInt() >> 8 == i) n++;
            return n;
        }

        /** Moves every kept block up by {@code by} slices (down when negative), dropping those that leave the band. */
        public void shift(int by) {
            if (by == 0 || map.isEmpty()) return;
            Int2IntOpenHashMap moved = new Int2IntOpenHashMap();
            for (Int2IntMap.Entry e : map.int2IntEntrySet()) {
                int b = e.getIntKey(), j = (b >> 8) + by;
                if (j < 0 || j >= slices) continue;
                moved.put(j << 8 | (b & 255), e.getIntValue());
            }
            map.clear();
            map.putAll(moved);
        }

        /** The kept blocks as {keys, states} (a key is i << 8 | z << 4 | x), for saving. */
        public int[][] toArrays() {
            int n = map.size(), k = 0;
            int[] keys = new int[n], states = new int[n];
            for (Int2IntMap.Entry e : map.int2IntEntrySet()) {
                keys[k] = e.getIntKey();
                states[k] = e.getIntValue();
                k++;
            }
            return new int[][]{keys, states};
        }

        /** Reads saved blocks back (replacing what was kept); keys outside the band and negative states are skipped. */
        public void fromArrays(int[] keys, int[] states) {
            map.clear();
            int n = Math.min(keys.length, states.length);
            for (int k = 0; k < n; k++) {
                int b = keys[k];
                if (b < 0 || b >> 8 >= slices || states[k] < 0) continue;
                map.put(b, states[k]);
            }
        }
    }

    /**
     * The k-th chunk (0 .. 8r - 1) on the square ring r chunks out from a centre, as {dx, dz}; ring 0 is the centre.
     * Walking the rings outward visits the chunks nearest a player first, each once.
     */
    public static void ring(int r, int k, int[] out) {
        if (r == 0) {
            out[0] = 0;
            out[1] = 0;
            return;
        }
        int side = 2 * r;
        int edge = k / side, along = k % side;
        switch (edge) {
            case 0 -> { out[0] = -r + along; out[1] = -r; }        // north edge, west to east
            case 1 -> { out[0] = r; out[1] = -r + along; }         // east edge, north to south
            case 2 -> { out[0] = r - along; out[1] = r; }          // south edge, east to west
            default -> { out[0] = -r; out[1] = r - along; }        // west edge, south to north
        }
    }

    /** How many chunks lie on ring r. */
    public static int ringSize(int r) { return r == 0 ? 1 : 8 * r; }
}
