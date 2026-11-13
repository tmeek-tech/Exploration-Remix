package com.explorationremix.world.terrain;

/**
 * The rock the caves near the surface are carved into (Taran, 2026-11-12): big blobs in the pack's stone, the way tuff
 * lies in deepslate but wider, of the rock the ground over each blob suggests ({@link Overhead#host}). That is limestone
 * under most land, veridium under the tropics, sandstone under deserts and terracotta under badlands; where the pack
 * (WWOO) turns its stone into another near the ground, that stone instead (granite under birch forests, diorite under
 * flower forests, and so on). No blobs under oceans, rivers and beaches.
 *
 * <p>Blobs sit in the cells of a 3D grid, {@link #CELL_XZ} wide and {@link #CELL_Y} tall, at most one to a cell and
 * small enough that a block need only ask its own cell and the neighbours on its near sides. Nine in ten cells hold one:
 * a lumpy, flattened ellipsoid 14 to 27 blocks across and about half as tall, its rock chosen by the ground over its
 * middle, so a blob never changes rock at a biome's edge. About a tenth of the rock is blob. Only above {@link #MIN_Y},
 * where the pack's deepslate gives way to stone.
 *
 * <p>A pure function of the seed and the surface: safe on any thread. What a column needs is worked out once and cached
 * per thread.
 */
public final class HostRock {
    /** The grid's cells, in blocks. */
    static final int CELL_XZ = 36, CELL_Y = 20;
    /** No blob reaches below this: the top of the pack's deepslate. */
    public static final int MIN_Y = 8;
    /** How far the lumps push a blob's edge in or out, as a share of its radius. */
    static final double LUMP = 0.3;
    private static final double REACH = (1 + LUMP) * (1 + LUMP), CORE = (1 - LUMP) * (1 - LUMP);
    /** Blob radii across, and its height as a share of that. Small enough that no blob reaches past its cell's neighbours. */
    static final double R_MIN = 7, R_MAX = 13.5, FLAT = 0.55;
    private static final int MAX = 80;

    private final long seed;
    private final Overhead overhead;
    private final Perlin lump;

    private static final class Col {
        int x, z;
        boolean valid;
        int n;
        final int[] lo = new int[MAX], hi = new int[MAX];
        final double[] cy = new double[MAX], rv = new double[MAX], qh = new double[MAX];
        final Rock[] rock = new Rock[MAX];
    }

    private final ThreadLocal<Col[]> cols = ThreadLocal.withInitial(() -> {
        Col[] a = new Col[1024];
        for (int i = 0; i < a.length; i++) a[i] = new Col();
        return a;
    });

    public HostRock(long seed, Overhead overhead) {
        this.seed = seed ^ 0x486F7374526F636BL;
        this.overhead = overhead;
        this.lump = new Perlin(Hashing.mix(this.seed, 1, 0, 0));
    }

    /** The blob's rock at a block, or null where the block lies in no blob. */
    public Rock at(int x, int y, int z) {
        if (y < MIN_Y) return null;
        Col c = column(x, z);
        for (int i = 0; i < c.n; i++) {
            if (y < c.lo[i] || y > c.hi[i]) continue;
            double dy = (y + 0.5 - c.cy[i]) / c.rv[i];
            double q = c.qh[i] + dy * dy;
            if (q >= REACH) continue;
            if (q > CORE) {
                double f = 1 + LUMP * lump.noise3(x / 6.0, y / 4.5, z / 6.0);
                if (q > f * f) continue;
            }
            return c.rock[i];
        }
        return null;
    }

    private Col column(int x, int z) {
        Col[] arr = cols.get();
        Col c = arr[((x & 31) << 5) | (z & 31)];
        if (c.valid && c.x == x && c.z == z) return c;
        c.x = x;
        c.z = z;
        c.valid = true;
        fill(c);
        return c;
    }

    private void fill(Col c) {
        c.n = 0;
        int x = c.x, z = c.z;
        int top = overhead.at(x, z).y() + 16;
        int cx0 = Math.floorDiv(x, CELL_XZ), cz0 = Math.floorDiv(z, CELL_XZ);
        int cx1 = cx0 + (x - cx0 * CELL_XZ >= CELL_XZ / 2 ? 1 : -1), cz1 = cz0 + (z - cz0 * CELL_XZ >= CELL_XZ / 2 ? 1 : -1);
        int cyLo = Math.floorDiv(MIN_Y, CELL_Y), cyHi = Math.floorDiv(top, CELL_Y);
        for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++) {
            int cx = a == 0 ? cx0 : cx1, cz = b == 0 ? cz0 : cz1;
            for (int cy = cyLo; cy <= cyHi && c.n < MAX; cy++) {
                long h = Hashing.mix(seed, cx, cy, cz);
                if (Hashing.unit(h) >= 0.9) continue;
                double rh = R_MIN + (R_MAX - R_MIN) * Hashing.unit(Hashing.mix(h, 1, 0, 0));
                double rv = rh * FLAT;
                // the middle anywhere in the cell where the blob stays within its cell's neighbours
                double mx = cx * CELL_XZ + CELL_XZ * Hashing.unit(Hashing.mix(h, 2, 0, 0));
                double mz = cz * CELL_XZ + CELL_XZ * Hashing.unit(Hashing.mix(h, 3, 0, 0));
                double my = cy * CELL_Y + CELL_Y * Hashing.unit(Hashing.mix(h, 4, 0, 0));
                if (my - rv * (1 + LUMP) < MIN_Y) continue;
                double dx = (x + 0.5 - mx) / rh, dz = (z + 0.5 - mz) / rh;
                double qh = dx * dx + dz * dz;
                if (qh >= REACH) continue;
                Rock rock = overhead.host((int) Math.floor(mx), (int) Math.floor(mz));
                if (rock == null) continue;
                double half = rv * Math.sqrt(REACH - qh);
                int i = c.n++;
                c.lo[i] = (int) Math.floor(my - half);
                c.hi[i] = (int) Math.ceil(my + half);
                c.cy[i] = my;
                c.rv[i] = rv;
                c.qh[i] = qh;
                c.rock[i] = rock;
            }
        }
    }
}
