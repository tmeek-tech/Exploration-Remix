package com.explorationremix.world.gen;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.world.terrain.Overhead;
import com.explorationremix.world.terrain.RootKind;
import com.explorationremix.world.terrain.Upper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.levelgen.DensityFunction;
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
    private final int seaLevel, minY, maxY;
    private final boolean linearDepth;
    private final AtomicReferenceArray<Entry> cache = new AtomicReferenceArray<>(SIZE);
    private final ConcurrentHashMap<Holder<Biome>, Info> infos = new ConcurrentHashMap<>();

    private record Entry(long key, Surface surface, Holder<Biome> biome) {}

    private record Info(RootKind kind, boolean wet, boolean noRavine, Cover cover, boolean cold) {}

    /**
     * @param pack   built from the pack's noise settings before the deep world changed them
     * @param source the overworld's biome source
     */
    public SurfaceOracle(RandomState pack, BiomeSource source, int seaLevel, int minY, int maxY) {
        this.sampler = pack.sampler();
        this.density = pack.router().finalDensity();
        this.source = source;
        this.multiNoise = source instanceof MultiNoiseBiomeSource m ? m : null;
        this.seaLevel = seaLevel;
        this.minY = minY;
        this.maxY = maxY;
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
        int y;
        if (linearDepth) {
            int y0 = (int) Math.round(128.0 * Climate.unquantizeCoord(t.depth()));
            y = ground(x, z, Math.max(minY + 8, Math.min(maxY - 8, y0)));
        } else {
            y = scan(x, z);
        }
        Holder<Biome> b = multiNoise != null
                ? multiNoise.getNoiseBiome(new Climate.TargetPoint(t.temperature(), t.humidity(), t.continentalness(), t.erosion(), 0L, t.weirdness()))
                : source.getNoiseBiome(qx, QuartPos.fromBlock(y), qz, sampler);
        Info info = infos.computeIfAbsent(b, SurfaceOracle::classify);
        boolean ravineOk = !info.wet && !info.noRavine && y >= seaLevel + 4;
        // Snow lies where the biome is snowy or cold at the ground's height: groves and high taigas too, which the pack
        // (WWOO) makes a little warmer than vanilla.
        boolean cold = info.cold || b.value().coldEnoughToSnow(new BlockPos(x, y + 1, z));
        return new Entry(key, new Surface(y, info.kind, info.wet, ravineOk, info.cover, cold), b);
    }

    /** The pack's own final density at a block: positive in rock, negative in its caves and in the open air. */
    @Override
    public double packDensity(int x, int y, int z) { return density.compute(new DensityFunction.SinglePointContext(x, y, z)); }

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
