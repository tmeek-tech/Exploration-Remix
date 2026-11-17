package com.explorationremix.creature.body;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A small world of blocks for the body's tests: full blocks, or any boxes inside a block (slabs, steps, carpets). */
final class BlockWorld implements Terrain {
    private final Map<Long, List<double[]>> cells = new HashMap<>();

    private static long key(int x, int y, int z) {
        return ((long) (x + 2048) << 40) | ((long) (y + 2048) << 20) | (long) (z + 2048);
    }

    /** A full block. */
    BlockWorld block(int x, int y, int z) { return box(x, y, z, 0, 0, 0, 1, 1, 1); }

    /** A box inside the block at (x, y, z), in the block's own coordinates (0 to 1). */
    BlockWorld box(int x, int y, int z, double x0, double y0, double z0, double x1, double y1, double z1) {
        cells.computeIfAbsent(key(x, y, z), k -> new ArrayList<>()).add(new double[]{x0, y0, z0, x1, y1, z1});
        return this;
    }

    /** Fills every block from (x0, y0, z0) to (x1, y1, z1), inclusive. */
    BlockWorld fill(int x0, int y0, int z0, int x1, int y1, int z1) {
        for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) block(x, y, z);
        return this;
    }

    /** A bottom slab. */
    BlockWorld slab(int x, int y, int z) { return box(x, y, z, 0, 0, 0, 1, 0.5, 1); }

    void clear(int x, int y, int z) { cells.remove(key(x, y, z)); }

    boolean has(int x, int y, int z) { return cells.containsKey(key(x, y, z)); }

    @Override
    public void boxes(int x, int y, int z, BoxSink sink) {
        List<double[]> l = cells.get(key(x, y, z));
        if (l == null) return;
        for (double[] b : l) sink.box(x + b[0], y + b[1], z + b[2], x + b[3], y + b[4], z + b[5]);
    }

    /** The highest top of rock under the square (x0..x1, z0..z1) at or below {@code below}, or -inf. */
    double top(double x0, double z0, double x1, double z1, double below) {
        double best = Double.NEGATIVE_INFINITY;
        int bx0 = (int) Math.floor(x0), bx1 = (int) Math.floor(x1 - 1.0E-9), bz0 = (int) Math.floor(z0), bz1 = (int) Math.floor(z1 - 1.0E-9);
        int y1 = (int) Math.floor(below);
        for (int x = bx0; x <= bx1; x++)
            for (int z = bz0; z <= bz1; z++)
                for (int y = y1; y >= y1 - 8; y--) {
                    List<double[]> l = cells.get(key(x, y, z));
                    if (l == null) continue;
                    for (double[] b : l) {
                        double t = y + b[4];
                        if (t <= below + 1.0E-9 && t > best && x + b[3] > x0 && x + b[0] < x1 && z + b[5] > z0 && z + b[2] < z1) best = t;
                    }
                }
        return best;
    }

    /** Every box, for drawing. */
    void forEach(java.util.function.Consumer<double[]> f) {
        for (Map.Entry<Long, List<double[]>> e : cells.entrySet()) {
            long k = e.getKey();
            int x = (int) ((k >>> 40) & 0xFFFFF) - 2048, y = (int) ((k >>> 20) & 0xFFFFF) - 2048, z = (int) (k & 0xFFFFF) - 2048;
            for (double[] b : e.getValue()) f.accept(new double[]{x + b[0], y + b[1], z + b[2], x + b[3], y + b[4], z + b[5]});
        }
    }
}
