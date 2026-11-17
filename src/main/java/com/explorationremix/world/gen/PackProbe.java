package com.explorationremix.world.gen;

import com.explorationremix.world.terrain.UpperShapes;
import com.mojang.serialization.MapCodec;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.Arrays;

/**
 * The pack's own final density (Tectonic's terrain and caves) at any block, the same number the world's noise chunks
 * build there, so the Upper Caves can ask where the pack's caves lie round their own (see
 * {@link UpperShapes#carve}): a noise chunk only answers for the block it is filling.
 *
 * <p>It reads the density of a {@link RandomState} built from the pack's settings before the deep world changed them
 * (the same seed and noises as the world's), with each of a noise chunk's caches put back the way the chunk reads it:
 * <ul>
 *     <li>an interpolated part is worked out at the corners of its noise cell (every 4 blocks across and 8 up) and
 *     blended straight between them, as {@code NoiseChunk.NoiseInterpolator} does; the corners are kept per thread, so
 *     the blocks of a cell and of its neighbours share them;</li>
 *     <li>a flat cache is read at y 0 on the quart corner of the block, as {@code NoiseChunk.FlatCache} fills it
 *     (Tectonic flat-caches a 3D ridge noise, see {@link SurfaceOracle});</li>
 *     <li>the other caches give the same values as what they wrap.</li>
 * </ul>
 * Blending with old chunks and the structures' beards are left out (neither is in a new world's caves). Safe on any
 * thread.
 */
public final class PackProbe implements UpperShapes.Pack {
    private final DensityFunction density;
    private final int cellW, cellH;

    /**
     * @param pack  built from the pack's noise settings before the deep world changed them
     * @param cellW the noise cell's width and height in blocks (4 and 8 for vanilla and Tectonic)
     */
    public PackProbe(RandomState pack, int cellW, int cellH) {
        this.cellW = cellW;
        this.cellH = cellH;
        this.density = pack.router().finalDensity().mapAll(new Replica());
    }

    @Override
    public double density(int x, int y, int z) { return density.compute(new DensityFunction.SinglePointContext(x, y, z)); }

    /** Puts each of the noise chunk's caches back as the chunk reads it. */
    private final class Replica implements DensityFunction.Visitor {
        @Override
        public DensityFunction apply(DensityFunction f) {
            if (!(f instanceof DensityFunctions.Marker m)) return f;
            return switch (m.type()) {
                case Interpolated -> new Interp(m.wrapped());
                case FlatCache -> new Flat(m.wrapped());
                default -> m.wrapped();
            };
        }
    }

    /** Per thread, a direct-mapped table of values by a cell corner or a quart column. */
    private static final class Table {
        final long[] key;
        final double[] value;
        final int mask;

        Table(int bits) {
            key = new long[1 << bits];
            value = new double[key.length];
            mask = key.length - 1;
            Arrays.fill(key, Long.MIN_VALUE);
        }
    }

    private static long cornerKey(int cx, int cy, int cz) {
        return ((long) cx << 40) | ((long) (cy & 0xFFFF) << 24) | (cz & 0xFFFFFFL);
    }

    private static int slot(long key, int mask) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h ^ (h >>> 29)) & mask;
    }

    /** An interpolated part: straight between the values at its noise cell's eight corners. */
    private final class Interp implements DensityFunction {
        private final DensityFunction inner;
        private final ThreadLocal<Table> corners = ThreadLocal.withInitial(() -> new Table(13));

        Interp(DensityFunction inner) { this.inner = inner; }

        private double corner(Table t, int cx, int cy, int cz) {
            long k = cornerKey(cx, cy, cz);
            int i = slot(k, t.mask);
            if (t.key[i] == k) return t.value[i];
            double v = inner.compute(new SinglePointContext(cx * cellW, cy * cellH, cz * cellW));
            t.key[i] = k;
            t.value[i] = v;
            return v;
        }

        @Override
        public double compute(FunctionContext c) {
            int x = c.blockX(), y = c.blockY(), z = c.blockZ();
            int cx = Math.floorDiv(x, cellW), cy = Math.floorDiv(y, cellH), cz = Math.floorDiv(z, cellW);
            double fx = (double) (x - cx * cellW) / cellW, fy = (double) (y - cy * cellH) / cellH, fz = (double) (z - cz * cellW) / cellW;
            Table t = corners.get();
            return Mth.lerp3(fx, fy, fz,
                    corner(t, cx, cy, cz), corner(t, cx + 1, cy, cz), corner(t, cx, cy + 1, cz), corner(t, cx + 1, cy + 1, cz),
                    corner(t, cx, cy, cz + 1), corner(t, cx + 1, cy, cz + 1), corner(t, cx, cy + 1, cz + 1), corner(t, cx + 1, cy + 1, cz + 1));
        }

        @Override
        public void fillArray(double[] a, ContextProvider p) { p.fillAllDirectly(a, this); }

        @Override
        public DensityFunction mapAll(Visitor v) { return v.apply(new Interp(inner.mapAll(v))); }

        @Override
        public double minValue() { return inner.minValue(); }

        @Override
        public double maxValue() { return inner.maxValue(); }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() { return KeyDispatchDataCodec.of(MapCodec.unit(this)); }
    }

    /** A flat cache: what it wraps at y 0 on the block's quart corner, kept per thread by quart column. */
    private static final class Flat implements DensityFunction {
        private final DensityFunction inner;
        private final ThreadLocal<Table> columns = ThreadLocal.withInitial(() -> new Table(10));

        Flat(DensityFunction inner) { this.inner = inner; }

        @Override
        public double compute(FunctionContext c) {
            int x = c.blockX() & ~3, z = c.blockZ() & ~3;
            long k = ((long) x << 32) ^ (z & 0xFFFFFFFFL);
            Table t = columns.get();
            int i = slot(k, t.mask);
            if (t.key[i] == k) return t.value[i];
            double v = inner.compute(new SinglePointContext(x, 0, z));
            t.key[i] = k;
            t.value[i] = v;
            return v;
        }

        @Override
        public void fillArray(double[] a, ContextProvider p) { p.fillAllDirectly(a, this); }

        @Override
        public DensityFunction mapAll(Visitor v) { return v.apply(new Flat(inner.mapAll(v))); }

        @Override
        public double minValue() { return inner.minValue(); }

        @Override
        public double maxValue() { return inner.maxValue(); }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() { return KeyDispatchDataCodec.of(MapCodec.unit(this)); }
    }
}
