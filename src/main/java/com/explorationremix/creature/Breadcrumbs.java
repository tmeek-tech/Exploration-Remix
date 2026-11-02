package com.explorationremix.creature;

import java.util.Arrays;

/**
 * The way a roaming Mossback came, so it can find its way back to its den: a short list of the nodes it stood on, the
 * den first, one every {@link #SPACING} blocks or so. Paths in the caves reach only some twenty blocks, and a straight
 * line home runs into rock, so the way home is the way out, crumb by crumb, in reverse.
 *
 * <p>Loops are cut as they close: when a new crumb comes within {@link #LOOP} blocks of an older one, the trail goes
 * back to that older crumb, so a lizard that circled a cave leaves a trail with no circle in it, and the walk home is
 * as short as the way out allows (and a good one for a player to follow). The trail keeps at most {@link #MAX} crumbs;
 * past that it drops the crumb whose neighbours lie closest together, so the trail stays even, end to end.
 *
 * <p>Plain block coordinates, no game classes, so it can be tested on its own.
 */
public final class Breadcrumbs {
    /** A new crumb only this far (blocks) from the last one. */
    public static final double SPACING = 10.0;
    /** A new crumb this close to an older one closes a loop. */
    public static final double LOOP = 8.0;
    /** The most crumbs a trail keeps. */
    public static final int MAX = 32;

    private int[] xyz = new int[3 * (MAX + 1)];
    private int size;

    public int size() { return size; }
    public boolean isEmpty() { return size == 0; }
    public int x(int i) { return xyz[3 * i]; }
    public int y(int i) { return xyz[3 * i + 1]; }
    public int z(int i) { return xyz[3 * i + 2]; }

    public void clear() { size = 0; }

    /** A new trail that begins at (x, y, z) (the den). */
    public void start(int x, int y, int z) {
        size = 0;
        add(x, y, z);
    }

    /**
     * The lizard now stands at (x, y, z): a crumb there if it is far enough from the last one, after cutting any loop
     * it closes. Returns true when the trail changed.
     */
    public boolean record(int x, int y, int z) {
        if (size == 0) { add(x, y, z); return true; }
        if (dist(size - 1, x, y, z) < SPACING) return false;
        // back near an older crumb: the way since then was a loop, and that crumb stands for where it is now
        for (int i = 0; i < size - 1; i++) {
            if (dist(i, x, y, z) < LOOP) { size = i + 1; return true; }
        }
        add(x, y, z);
        if (size > MAX) thin();
        return true;
    }

    /** The crumb nearest the end of the trail is gone (reached, or given up on). */
    public void pop() {
        if (size > 0) size--;
    }

    /**
     * Walking home from (x, y, z): if it stands within {@code r} of a crumb, that crumb and every later one are behind
     * it. Takes the oldest such crumb (the one nearest home along the trail), so a short cut skips the rest. Returns
     * true when any were dropped.
     */
    public boolean reached(int x, int y, int z, double r) {
        for (int i = 0; i < size; i++) {
            if (dist(i, x, y, z) < r) { size = i; return true; }
        }
        return false;
    }

    /** The straight distance from crumb {@code i} to (x, y, z). */
    public double dist(int i, int x, int y, int z) {
        double dx = x(i) - x, dy = y(i) - y, dz = z(i) - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** The trail's length along its crumbs, in blocks. */
    public double length() {
        double l = 0;
        for (int i = 1; i < size; i++) l += dist(i - 1, x(i), y(i), z(i));
        return l;
    }

    /** For saving: x, y, z of each crumb in turn, the den's first. */
    public int[] toArray() {
        return Arrays.copyOf(xyz, 3 * size);
    }

    /** Read back what {@link #toArray} wrote (a trail longer than {@link #MAX} keeps its first crumbs). */
    public void fromArray(int[] a) {
        size = 0;
        for (int i = 0; i + 2 < a.length && size < MAX; i += 3) add(a[i], a[i + 1], a[i + 2]);
    }

    private void add(int x, int y, int z) {
        if (3 * size + 3 > xyz.length) xyz = Arrays.copyOf(xyz, xyz.length * 2);
        xyz[3 * size] = x;
        xyz[3 * size + 1] = y;
        xyz[3 * size + 2] = z;
        size++;
    }

    /** Drops the inner crumb whose neighbours lie closest together: where the trail is densest, it misses it least. */
    private void thin() {
        int best = -1;
        double bestGap = Double.MAX_VALUE;
        for (int i = 1; i < size - 1; i++) {
            double gap = dist(i - 1, x(i + 1), y(i + 1), z(i + 1));
            if (gap < bestGap) { bestGap = gap; best = i; }
        }
        if (best < 0) return;
        System.arraycopy(xyz, 3 * (best + 1), xyz, 3 * best, 3 * (size - best - 1));
        size--;
    }
}
