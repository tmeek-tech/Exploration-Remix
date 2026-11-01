package com.explorationremix.world.gen;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.world.terrain.Overhead;
import com.explorationremix.world.terrain.RootKind;
import com.explorationremix.world.terrain.Upper;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.RandomState;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Answers "what is over this column?" from the pack's own noise, cheaply and the same way every time, on any thread:
 * the ground's height and the surface biome, per 4x4 column (the biome grid; density corners sit on the same grid).
 *
 * <p>The climate is sampled once per column at y 0. Depth falls by 1/128 per block in both vanilla's router and
 * Tectonic's ({@code 1 - y/128 + offset}), so {@code 128 * depth(0)} is the level where the pack puts its ground; the
 * pack's own final density, read at that level and a few blocks around it, finds the real ground where Tectonic's
 * jagged peaks, jungle pillars or roughness move it. The surface biome is the pack's biome for that climate with depth
 * 0, as vanilla's spawn finder asks it. All of it comes from a {@link RandomState} built from the pack's settings before
 * the deep world patched them, so the answer never includes our own carving, and outside a noise chunk its density
 * functions are plain, stateless functions of position.
 *
 * <p>The density is read the way the world's noise chunks read it in two respects. Flat caches are filled at y 0 on
 * the quart corner: outside a noise chunk a flat cache would pass the asked height through, and Tectonic flat-caches a
 * 3D noise (its mountain ridges), so on the peaks a point sample read another ridge field than the world builds and
 * put the ground up to 22 blocks too high (a Frost Fern Grotto then reached the top of a mountain). And for what must
 * stay under the ground in every block column of a 4x4 column (the plant caves), {@link #low} takes the ground at the
 * column's noise-cell corners, where the world's terrain is exactly the density, with straight lines between the
 * grid heights as the world interpolates them.
 */
public final class SurfaceOracle implements Overhead, Upper.SurfaceProbe {
    private static final int BITS = 16, SIZE = 1 << BITS;
    private static final TagKey<Biome> NO_RAVINES = tag("rootvaults/no_ravines");
    private static final TagKey<Biome> PLAINS = tag("upper/plains"), DESERT = tag("upper/desert"), BADLANDS = tag("upper/badlands");
    /** Snowy biomes by name (groves, snowy slopes and peaks), whatever temperature the pack gives them. */
    private static final TagKey<Biome> SNOWY = TagKey.create(Registries.BIOME, new ResourceLocation("forge", "is_snowy"));
    /** Snow falls on biomes this cold or colder (vanilla's line between rain and snow, before any height adjustment). */
    private static final float SNOW_LINE = 0.15F;
    private static final EnumMap<RootKind, TagKey<Biome>> KIND_TAGS = new EnumMap<>(RootKind.class);

    static {
        for (RootKind k : RootKind.TAGGED) KIND_TAGS.put(k, tag("rootvaults/" + k.id));
    }

    private final Climate.Sampler sampler;
    private final DensityFunction density;
    private final BiomeSource source;
    @Nullable private final MultiNoiseBiomeSource multiNoise;
    private final int seaLevel, minY, maxY, cellW, cellH;
    private final boolean linearDepth;
    private final AtomicReferenceArray<Entry> cache = new AtomicReferenceArray<>(SIZE);
    private final AtomicReferenceArray<Corner> corners = new AtomicReferenceArray<>(SIZE);
    private final ConcurrentHashMap<Holder<Biome>, Info> infos = new ConcurrentHashMap<>();

    private record Entry(long key, Surface surface, int low, Holder<Biome> biome) {}

    /** The ground in one noise-cell corner column. */
    private record Corner(long key, int ground) {}

    private record Info(RootKind kind, boolean wet, boolean noRavine, Cover cover, boolean cold) {}

    /**
     * @param pack   built from the pack's noise settings before the deep world changed them
     * @param source the overworld's biome source
     * @param cellW  the pack's noise cell width and height in blocks (4 and 8 for vanilla and Tectonic)
     */
    public SurfaceOracle(RandomState pack, BiomeSource source, int seaLevel, int minY, int maxY, int cellW, int cellH) {
        this.sampler = pack.sampler();
        this.density = pack.router().finalDensity().mapAll(FlatCachesAtY0.INSTANCE);
        this.source = source;
        this.multiNoise = source instanceof MultiNoiseBiomeSource m ? m : null;
        this.seaLevel = seaLevel;
        this.minY = minY;
        this.maxY = maxY;
        this.cellW = Math.max(4, cellW & ~3);
        this.cellH = Math.max(1, cellH);
        this.linearDepth = checkDepth();
        ExplorationRemix.LOGGER.info("Surface oracle: depth {} linear in y, biomes {}", linearDepth ? "is" : "is NOT (scanning the density instead)",
                multiNoise != null ? "asked at depth 0" : "asked at the ground (not a multi-noise biome source)");
    }

    private static TagKey<Biome> tag(String path) {
        return TagKey.create(Registries.BIOME, new ResourceLocation(ExplorationRemix.MOD_ID, path));
    }

    /** True when the pack's depth falls by 1/128 per block below y 32 (vanilla and Tectonic both build it so). */
    private boolean checkDepth() {
        int[][] at = {{0, 0}, {400, -300}, {-750, 520}, {1200, 1900}};
        for (int[] p : at) {
            int qx = QuartPos.fromBlock(p[0]), qz = QuartPos.fromBlock(p[1]);
            double d0 = Climate.unquantizeCoord(sampler.sample(qx, 0, qz).depth());
            double d1 = Climate.unquantizeCoord(sampler.sample(qx, QuartPos.fromBlock(-64), qz).depth());
            if (Math.abs(d1 - d0 - 0.5) > 0.02) return false;
        }
        return true;
    }

    @Override
    public Surface at(int x, int z) { return entry(x >> 2, z >> 2).surface; }

    /**
     * The lowest ground of the 4x4 column over (x, z): the least of the grounds at the corners of the noise cell that
     * holds it. The world blends its terrain between those corners, so wherever all four corner columns are rock, so
     * is everything between them.
     */
    @Override
    public int low(int x, int z) { return entry(x >> 2, z >> 2).low; }

    @Override
    public int seaLevel() { return seaLevel; }

    /** The pack's surface biome over a column. */
    public Holder<Biome> biome(int x, int z) { return entry(x >> 2, z >> 2).biome; }

    /** For the upper set pieces: true over oceans, rivers and beaches. */
    @Override
    public boolean wet(int x, int z) { return at(x, z).wet(); }

    private Entry entry(int qx, int qz) {
        long key = ((long) qx << 32) ^ (qz & 0xffffffffL);
        int i = (int) ((key * 0x9E3779B97F4A7C15L) >>> (64 - BITS));
        Entry e = cache.get(i);
        if (e != null && e.key == key) return e;
        e = compute(qx, qz, key);
        cache.set(i, e);
        return e;
    }

    private Entry compute(int qx, int qz, long key) {
        int x = QuartPos.toBlock(qx) + 2, z = QuartPos.toBlock(qz) + 2;
        Climate.TargetPoint t = sampler.sample(qx, 0, qz);
        int y, low;
        if (linearDepth) {
            y = ground(x, z, levelOf(t));
            int x0 = Math.floorDiv(x, cellW) * cellW, z0 = Math.floorDiv(z, cellW) * cellW;
            low = Math.min(Math.min(corner(x0, z0), corner(x0 + cellW, z0)), Math.min(corner(x0, z0 + cellW), corner(x0 + cellW, z0 + cellW)));
        } else {
            y = scan(x, z);
            low = y;
        }
        Holder<Biome> b = multiNoise != null
                ? multiNoise.getNoiseBiome(new Climate.TargetPoint(t.temperature(), t.humidity(), t.continentalness(), t.erosion(), 0L, t.weirdness()))
                : source.getNoiseBiome(qx, QuartPos.fromBlock(y), qz, sampler);
        Info info = infos.computeIfAbsent(b, SurfaceOracle::classify);
        boolean ravineOk = !info.wet && !info.noRavine && y >= seaLevel + 4;
        // Snow lies where the biome is snowy or cold at the ground's height: groves and high taigas too, which the pack
        // (WWOO) makes a little warmer than vanilla.
        boolean cold = info.cold || b.value().coldEnoughToSnow(new BlockPos(x, y + 1, z));
        return new Entry(key, new Surface(y, info.kind, info.wet, ravineOk, info.cover, cold), Math.min(low, y), b);
    }

    /** Where the pack puts its ground for a climate: 128 times its depth at y 0, kept inside the world. */
    private int levelOf(Climate.TargetPoint t) {
        int y0 = (int) Math.round(128.0 * Climate.unquantizeCoord(t.depth()));
        return Math.max(minY + 8, Math.min(maxY - 8, y0));
    }

    /** The ground in the noise-cell corner column at (x, z), cached: each corner serves four columns. */
    private int corner(int x, int z) {
        long key = ((long) x << 32) ^ (z & 0xffffffffL);
        int i = (int) ((key * 0x9E3779B97F4A7C15L) >>> (64 - BITS));
        Corner c = corners.get(i);
        if (c != null && c.key == key) return c.ground;
        int g = cornerGround(x, z, levelOf(sampler.sample(QuartPos.fromBlock(x), 0, QuartPos.fromBlock(z))));
        corners.set(i, new Corner(key, g));
        return g;
    }

    /**
     * The top solid block of the pack's terrain in a noise-cell corner column, as the world builds it: the density at
     * the grid heights (minY plus whole cells), straight between them. From the grid height at or under y0, one cell at
     * a time: up while the next grid height is rock, or down to the first one that is (16 cells either way).
     */
    private int cornerGround(int x, int z, int y0) {
        int h = cellH, y = minY + Math.floorDiv(y0 - minY, h) * h;
        double d = packDensity(x, y, z), up;
        if (d > 0) {
            up = packDensity(x, y + h, z);
            for (int i = 0; i < 16 && up > 0 && y + 2 * h <= maxY; i++) {
                y += h;
                d = up;
                up = packDensity(x, y + h, z);
            }
            if (up > 0) return y + h;
        } else {
            up = d;
            for (int i = 0; ; i++) {
                if (i == 16 || y - h < minY) return y;
                d = packDensity(x, y - h, z);
                y -= h;
                if (d > 0) break;
                up = d;
            }
        }
        return Overhead.topBetween(y, h, d, up);
    }

    /** The pack's own final density at a block: positive in rock, negative in its caves and in the open air. */
    @Override
    public double packDensity(int x, int y, int z) { return density.compute(new DensityFunction.SinglePointContext(x, y, z)); }

    /** Turns every flat cache into {@link FlatAtY0}, as a noise chunk would fill it. */
    private enum FlatCachesAtY0 implements DensityFunction.Visitor {
        INSTANCE;

        @Override
        public DensityFunction apply(DensityFunction f) {
            return f instanceof DensityFunctions.Marker m && m.type() == DensityFunctions.Marker.Type.FlatCache ? new FlatAtY0(m.wrapped()) : f;
        }
    }

    /**
     * A flat cache read as {@code NoiseChunk.FlatCache} fills it: the wrapped function at y 0 on the quart corner of the
     * asked block, whatever height is asked. Stateless, so safe on any thread; never serialised.
     */
    private record FlatAtY0(DensityFunction wrapped) implements DensityFunction {
        @Override
        public double compute(FunctionContext c) { return wrapped.compute(new SinglePointContext(c.blockX() & ~3, 0, c.blockZ() & ~3)); }

        @Override
        public void fillArray(double[] a, ContextProvider p) { p.fillAllDirectly(a, this); }

        @Override
        public DensityFunction mapAll(Visitor v) { return v.apply(new FlatAtY0(wrapped.mapAll(v))); }

        @Override
        public double minValue() { return wrapped.minValue(); }

        @Override
        public double maxValue() { return wrapped.maxValue(); }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() { return KeyDispatchDataCodec.of(MapCodec.unit(this)); }
    }

    private boolean solid(int x, int y, int z) { return packDensity(x, y, z) > 0.0; }

    /** The top solid block near y0: four-block steps up or down to where the terrain turns, then two halvings. */
    private int ground(int x, int z, int y0) {
        int y = y0;
        if (solid(x, y, z)) {
            for (int i = 0; i < 16 && y + 4 < maxY && solid(x, y + 4, z); i++) y += 4;
        } else {
            int i = 0;
            while (i++ < 16 && y - 4 > minY && !solid(x, y - 4, z)) y -= 4;
            y -= 4;
        }
        // The ground is now within y .. y + 3.
        if (solid(x, y + 2, z)) y += 2;
        if (solid(x, y + 1, z)) y += 1;
        return y;
    }

    /** Without a linear depth: from high up, eight-block steps down to the first solid block, then refined. */
    private int scan(int x, int z) {
        int y = Math.min(maxY - 8, 256);
        while (y > minY + 8 && !solid(x, y, z)) y -= 8;
        return ground(x, z, y);
    }

    private static Info classify(Holder<Biome> b) {
        boolean wet = b.is(BiomeTags.IS_OCEAN) || b.is(BiomeTags.IS_DEEP_OCEAN) || b.is(BiomeTags.IS_RIVER) || b.is(BiomeTags.IS_BEACH);
        RootKind kind = wet ? RootKind.NONE : kindOf(b);
        Cover cover = wet ? Cover.OTHER : kind.trees() ? Cover.FOREST : b.is(DESERT) ? Cover.DESERT : b.is(BADLANDS) ? Cover.BADLANDS
                : b.is(PLAINS) ? Cover.PLAINS : Cover.OTHER;
        return new Info(kind, wet, b.is(NO_RAVINES), cover, b.value().getBaseTemperature() <= SNOW_LINE || b.is(SNOWY));
    }

    /** The kind of trees a biome grows, by the {@code explorationremix:rootvaults/<kind>} biome tags. */
    public static RootKind kindOf(Holder<Biome> b) {
        for (RootKind k : RootKind.TAGGED) if (b.is(KIND_TAGS.get(k))) return k;
        return RootKind.NONE;
    }
}
