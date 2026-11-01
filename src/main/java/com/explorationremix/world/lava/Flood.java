package com.explorationremix.world.lava;

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
 *     <li><b>Rising</b>, the lava takes every block of the slice that is air, fire, or flowing lava (a fall or a
 *     spill, which the sea swallows), putting a lava source there, and records it in the chunk's {@link Mask}.
 *     Everything else stays as it is: rock, water, natural lava sources (pools, which join the sea while it covers
 *     them), and anything else that is not air, so the terrain's own decoration and players' torches, rungs and signs
 *     come through a flood (the lava closes round them, as vanilla lava does round a ladder). Every open block under
 *     the lava's height is filled, connected to the sea or not: lava seeps through the rock into sealed pockets.</li>
 *     <li><b>Draining</b>, the lava leaves only the blocks it recorded that still hold lava; natural lava, players'
 *     lava, and whatever a player put in its place stay. Then the lava next to each block it left is woken (a fluid
 *     tick) so lava falls whose foot the sea swallowed flow down again.</li>
 * </ul>
 */
public final class Flood {
    private Flood() {}

    /** What the flood sees in a block: something it leaves alone, air, fire, a lava source, flowing lava. */
    public static final int OTHER = 0, AIR = 1, FIRE = 2, LAVA = 3, FLOWING = 4;

    /** Rising: true when the lava takes a block of this kind (a lava source goes there). */
    public static boolean takes(int kind) { return kind == AIR || kind == FIRE || kind == FLOWING; }

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
        /** The kind of block at (x, z): one of {@link #OTHER}, {@link #AIR}, {@link #FIRE}, {@link #LAVA}, {@link #FLOWING}. */
        int kind(int x, int z);
        /** Puts a lava source at (x, z). */
        void fill(int x, int z);
        /** Puts air at (x, z). */
        void clear(int x, int z);
        /**
         * After a drain: wakes the lava next to (x, z), above it, below it (unless that is the sea's own) and on its four
         * sides, so falls flow again and lava the sea fed downward drains away.
         */
        void wake(int x, int z);
    }

    /**
     * Floods slice i of a chunk: every block the lava takes gets a lava source and its bit in the mask. Returns how
     * many blocks changed.
     */
    public static int rise(Slice s, Mask m, int i) {
        int n = 0;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                if (!takes(s.kind(x, z))) continue;
                s.fill(x, z);
                m.set(i, x, z);
                n++;
            }
        }
        return n;
    }

    /**
     * Drains slice i of a chunk: every block whose bit is set loses its bit, and if it still holds lava becomes air;
     * then the lava round each block that became air is woken. Returns how many blocks changed.
     */
    public static int drain(Slice s, Mask m, int i) {
        if (!m.any(i)) return 0;
        // the blocks that became air, one bit each as in the mask (z << 4 | x)
        long[] cleared = new long[4];
        int n = 0;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                if (!m.get(i, x, z)) continue;
                m.unset(i, x, z);
                if (!leaves(s.kind(x, z))) continue;
                s.clear(x, z);
                int b = z << 4 | x;
                cleared[b >>> 6] |= 1L << (b & 63);
                n++;
            }
        }
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
