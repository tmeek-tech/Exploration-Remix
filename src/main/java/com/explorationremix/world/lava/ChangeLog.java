package com.explorationremix.world.lava;

import it.unimi.dsi.fastutil.ints.Int2LongMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;

/**
 * What one chunk of the Basalt Galleries looked like before players, quakes and eruptions changed it: for each block
 * changed since the last terrain reset, the block that was there first and the reset generation it changed in. Pure
 * (blocks are ints: the game's block state ids at run time), so it is tested offline; {@link GalleryChanges} feeds it
 * real blocks, {@link LavaChunk} saves it with the chunk and {@link TerrainReset} puts the blocks back.
 *
 * <p>A position is a 17-bit key: x and z in the chunk (0 to 15) and dy, its height over the Galleries' bottom (0 to
 * {@link #MAX_DY}; the layer is never that tall, even when the bands' thickness adds rows to it). The record keeps the
 * bottom it was written against ({@link #base}); a changed config moves the layer, and {@link #align} moves the keys
 * with it, dropping those that leave 0 to {@link #MAX_DY}.
 *
 * <p>Only the first change of a position is kept (the terrain's own block), until the reset puts it back or a later
 * change happens to bring it back by itself ({@link GalleryChanges} then removes it).
 */
public final class ChangeLog {
    /** Marks a log not yet written against any bottom. */
    public static final int NO_BASE = Integer.MIN_VALUE;
    /** What {@link #state} and {@link #gen} answer for a position the log does not hold. */
    public static final int NONE = -1;
    /** The highest height over the Galleries' bottom a key holds. */
    public static final int MAX_DY = 511;
    /** Keys are below this. */
    static final int KEYS = 1 << 17;
    private static final long ABSENT = Long.MIN_VALUE;

    private final Int2LongOpenHashMap map = new Int2LongOpenHashMap();
    private int base = NO_BASE;
    /** The oldest generation held, or MAX_VALUE; recomputed when an entry of that generation goes. */
    private int oldest = Integer.MAX_VALUE;
    private boolean oldestStale;

    public ChangeLog() { map.defaultReturnValue(ABSENT); }

    // ================================================================================================ keys

    /**
     * The key of the block at x, z in the chunk (0 to 15) and dy over the Galleries' bottom (0 to {@link #MAX_DY}).
     * Callers check dy first ({@link #holds}): this throws, and it runs inside the game's block changes.
     */
    public static int key(int x, int dy, int z) {
        if ((x | z) >>> 4 != 0 || !holds(dy)) throw new IllegalArgumentException("position " + x + ", " + dy + ", " + z);
        return x | z << 4 | dy << 8;
    }

    /** True for a height over the Galleries' bottom that a key can hold. */
    public static boolean holds(int dy) { return dy >= 0 && dy <= MAX_DY; }

    public static int x(int key) { return key & 15; }
    public static int z(int key) { return key >> 4 & 15; }
    public static int dy(int key) { return key >> 8 & MAX_DY; }

    private static long pack(int state, int gen) { return (long) state << 32 | (gen & 0xFFFFFFFFL); }
    private static int stateOf(long v) { return (int) (v >> 32); }
    private static int genOf(long v) { return (int) v; }

    // ================================================================================================ entries

    /**
     * Records that the block at {@code key} was {@code state} before it changed in generation {@code gen}, unless the
     * log already holds that position (its first block is the terrain's). Returns true when it was added.
     */
    public boolean record(int key, int state, int gen) {
        if (state < 0) throw new IllegalArgumentException("state " + state);
        if (map.containsKey(key)) return false;
        map.put(key, pack(state, gen));
        if (gen < oldest) oldest = gen;
        return true;
    }

    /** True when the log holds the position. */
    public boolean has(int key) { return map.containsKey(key); }

    /** The block the position held first, or {@link #NONE}. */
    public int state(int key) {
        long v = map.get(key);
        return v == ABSENT ? NONE : stateOf(v);
    }

    /** The generation the position first changed in, or {@link #NONE}. */
    public int gen(int key) {
        long v = map.get(key);
        return v == ABSENT ? NONE : genOf(v);
    }

    /** Forgets the position (it was put back). Returns true when it was there. */
    public boolean remove(int key) {
        long v = map.remove(key);
        if (v == ABSENT) return false;
        if (genOf(v) <= oldest) oldestStale = true;
        return true;
    }

    public int size() { return map.size(); }
    public boolean isEmpty() { return map.isEmpty(); }

    public void clear() {
        map.clear();
        oldest = Integer.MAX_VALUE;
        oldestStale = false;
    }

    /** The oldest generation the log holds, or MAX_VALUE when it is empty. */
    public int oldestGen() {
        if (oldestStale) {
            int o = Integer.MAX_VALUE;
            for (LongIterator it = map.values().iterator(); it.hasNext(); ) o = Math.min(o, genOf(it.nextLong()));
            oldest = o;
            oldestStale = false;
        }
        return map.isEmpty() ? Integer.MAX_VALUE : oldest;
    }

    /** True when the log holds a change from before generation {@code gen} (one the reset of {@code gen} puts back). */
    public boolean hasOlderThan(int gen) { return oldestGen() < gen; }

    /** How many of the changes are from before generation {@code gen}. */
    public int countOlderThan(int gen) {
        if (!hasOlderThan(gen)) return 0;
        int n = 0;
        for (LongIterator it = map.values().iterator(); it.hasNext(); ) if (genOf(it.nextLong()) < gen) n++;
        return n;
    }

    /**
     * The keys of the changes from before generation {@code gen}, lowest first (by dy, then z, then x), so a pass that
     * runs out of budget leaves the higher blocks for the next one.
     */
    public int[] olderThan(int gen) {
        IntArrayList out = new IntArrayList();
        for (Int2LongMap.Entry e : map.int2LongEntrySet()) if (genOf(e.getLongValue()) < gen) out.add(e.getIntKey());
        int[] a = out.toIntArray();
        // a key is x | z << 4 | dy << 8 (non-negative): dy is its most significant field, so sorting orders them by dy, z, x
        java.util.Arrays.sort(a);
        return a;
    }

    /** Calls back for every entry: key, first block, generation. */
    public void forEach(Entry c) {
        for (Int2LongMap.Entry e : map.int2LongEntrySet()) c.accept(e.getIntKey(), stateOf(e.getLongValue()), genOf(e.getLongValue()));
    }

    /** One entry of the log. */
    @FunctionalInterface
    public interface Entry {
        void accept(int key, int state, int gen);
    }

    // ================================================================================================ the layer's bottom

    /** The Galleries' bottom Y the keys count from, or {@link #NO_BASE}. */
    public int base() { return base; }

    /**
     * Lines the log up with the Galleries' bottom at {@code bottomY}: a log written against another bottom has its
     * keys moved by the difference, and those that leave 0 to {@link #MAX_DY} are dropped (that part of the world moved
     * out of the layer when the config changed).
     */
    public void align(int bottomY) {
        if (base == bottomY) return;
        if (base != NO_BASE && !map.isEmpty()) {
            int by = base - bottomY;
            Int2LongOpenHashMap moved = new Int2LongOpenHashMap();
            moved.defaultReturnValue(ABSENT);
            for (Int2LongMap.Entry e : map.int2LongEntrySet()) {
                int k = e.getIntKey(), dy = dy(k) + by;
                if (!holds(dy)) continue;
                moved.put(x(k) | z(k) << 4 | dy << 8, e.getLongValue());
            }
            map.clear();
            map.putAll(moved);
            oldestStale = true;
        }
        base = bottomY;
    }

    // ================================================================================================ saving

    /** The keys, first blocks and generations, in the same order, for saving. */
    public int[][] toArrays() {
        int n = map.size();
        int[] keys = new int[n], states = new int[n], gens = new int[n];
        int i = 0;
        for (Int2LongMap.Entry e : map.int2LongEntrySet()) {
            keys[i] = e.getIntKey();
            states[i] = stateOf(e.getLongValue());
            gens[i] = genOf(e.getLongValue());
            i++;
        }
        return new int[][]{keys, states, gens};
    }

    /**
     * Reads saved entries back (replacing what the log held), written against the bottom {@code base}. Arrays of
     * different lengths keep what they have in common; keys outside 17 bits and negative states are skipped.
     */
    public void load(int base, int[] keys, int[] states, int[] gens) {
        clear();
        this.base = base;
        int n = Math.min(keys.length, Math.min(states.length, gens.length));
        for (int i = 0; i < n; i++) {
            if (keys[i] < 0 || keys[i] >= KEYS || states[i] < 0) continue;
            record(keys[i], states[i], gens[i]);
        }
    }
}
