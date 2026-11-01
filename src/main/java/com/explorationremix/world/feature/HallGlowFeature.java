package com.explorationremix.world.feature;

import com.explorationremix.block.GlowBlock;
import com.explorationremix.registry.ERBlocks;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.DeepBiome;
import com.explorationremix.world.terrain.Hashing;
import com.explorationremix.world.terrain.Perlin;
import com.explorationremix.world.terrain.Shape;
import com.explorationremix.world.terrain.Terrain;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

/**
 * The Basalt Galleries' lava glow (Taran, 2026-11-01: "brightly lit"): invisible light ({@link GlowBlock}) in the air
 * near the rock, so the halls' walls, ledges and vaults read lit by the lava. Under Complementary unlit rock is near
 * black and a block's light shows only a few blocks out, so the lava sea lights a thin rim of a hall 100 blocks tall and
 * the rest showed only its lava falls and the specks of ember crust.
 * <p>
 * The air of the layer is cut into cubes {@code spacing} blocks on a side, on the world's grid, and each cube gets one
 * try at a spot hashed from the world seed and the cube: it lights if that spot is air with rock (any block that is not
 * air, a fluid or another glow) within {@code reach} along one of the six axes, so the light sits along the rock and
 * none in the middle of a hall. Over the flood band its level is {@code low} at the band's top and fades to
 * {@code high} at {@code fade} blocks above it, brightest near the lava as the lava's own glow would be, and a slow noise
 * moves it up to {@code patch} either way so the light comes in warmer and duller stretches. The Obsidian Tubes, the
 * refuge, take {@code tubes} (0: none).
 * <p>
 * In the flood band (the {@code Terrain.surge} blocks over the lava table) every glow is {@link #BAND_LEVEL}: the
 * rising sea takes it with the air and marks it, and the drain puts {@link #bandGlow()} back where it was
 * ({@code Flood}, {@code LavaChunk}), so the low halls come out of every flood lit as before. Runs once per chunk after
 * every other feature (top_layer_modification, after open_way), on its own chunk's columns.
 */
public final class HallGlowFeature extends Feature<HallGlowFeature.Config> {
    public HallGlowFeature(Codec<Config> codec) { super(codec); }

    /** The level of every glow in the flood band, the one the lava's drain puts back. */
    public static final int BAND_LEVEL = HallGlow.BAND_LEVEL;

    /** The glow the drain puts back in the flood band. */
    public static BlockState bandGlow() { return ERBlocks.GLOW.get().defaultBlockState().setValue(GlowBlock.LEVEL, BAND_LEVEL); }

    public record Config(int spacing, int reach, int low, int high, int fade, int patch, int tubes) implements FeatureConfiguration {
        public static final Codec<Config> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.intRange(2, 8).fieldOf("spacing").forGetter(Config::spacing),
                Codec.intRange(1, 8).fieldOf("reach").forGetter(Config::reach),
                Codec.intRange(1, 15).fieldOf("low").forGetter(Config::low),
                Codec.intRange(1, 15).fieldOf("high").forGetter(Config::high),
                Codec.intRange(1, 384).fieldOf("fade").forGetter(Config::fade),
                Codec.intRange(0, 6).optionalFieldOf("patch", 0).forGetter(Config::patch),
                Codec.intRange(0, 15).optionalFieldOf("tubes", 0).forGetter(Config::tubes)
        ).apply(i, Config::new));
    }

    /** The patch noise, made once per world seed. */
    private record Patches(long seed, Perlin noise) {}
    private static volatile Patches patches;

    @Override
    public boolean place(FeaturePlaceContext<Config> ctx) {
        Terrain t = DeepWorld.terrain();
        if (t == null || !DeepWorld.installed()) return false;
        Shape s = t.shape;
        int g = Terrain.GALLERIES;
        if (g >= s.layerCount) return false;
        Config c = ctx.config();
        WorldGenLevel level = ctx.level();
        int table = s.waterY(g), y0 = table + Terrain.surge(g), y1 = s.top(g);
        if (y1 < table) return false;
        ChunkPos cp = new ChunkPos(ctx.origin());
        int minX = cp.getMinBlockX(), minZ = cp.getMinBlockZ(), maxX = minX + 15, maxZ = minZ + 15;
        int n = c.spacing();
        Perlin noise = c.patch() > 0 ? patches(t.seed) : null;
        Block glow = ERBlocks.GLOW.get();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(), probe = new BlockPos.MutableBlockPos();
        boolean any = false;
        for (int cx = Math.floorDiv(minX, n); cx <= Math.floorDiv(maxX, n); cx++) {
            for (int cz = Math.floorDiv(minZ, n); cz <= Math.floorDiv(maxZ, n); cz++) {
                for (int cy = Math.floorDiv(table, n); cy <= Math.floorDiv(y1, n); cy++) {
                    long h = Hashing.mix(t.seed ^ 0x61C8864680B583EBL, cx, cy, cz);
                    int x = cx * n + (int) Long.remainderUnsigned(h, n);
                    int y = cy * n + (int) Long.remainderUnsigned(h >>> 16, n);
                    int z = cz * n + (int) Long.remainderUnsigned(h >>> 32, n);
                    if (x < minX || x > maxX || z < minZ || z > maxZ || y < table || y > y1) continue;
                    if (!level.getBlockState(pos.set(x, y, z)).isAir()) continue;
                    if (!nearRock(level, pos, probe, c.reach(), glow)) continue;
                    DeepBiome b = t.biomeAt(x, y, z);
                    int lv;
                    if (y < y0) lv = galleries(b) ? BAND_LEVEL : 0;
                    else if (b == DeepBiome.OBSIDIAN_TUBES) lv = c.tubes();
                    else if (galleries(b)) lv = HallGlow.levelAt(c, y - y0, noise, x, y, z);
                    else continue;
                    if (lv <= 0) continue;
                    level.setBlock(pos, glow.defaultBlockState().setValue(GlowBlock.LEVEL, lv), 2);
                    any = true;
                }
            }
        }
        return any;
    }

    /** Whether rock lies within {@code reach} of {@code pos} along one of the six axes. */
    static boolean nearRock(WorldGenLevel level, BlockPos pos, BlockPos.MutableBlockPos probe, int reach, Block glow) {
        for (Direction d : Direction.values()) {
            probe.set(pos);
            for (int k = 1; k <= reach; k++) {
                BlockState st = level.getBlockState(probe.move(d));
                if (st.isAir() || st.is(glow)) continue;
                if (!st.getFluidState().isEmpty()) break;
                return true;
            }
        }
        return false;
    }

    /** The Galleries' biomes that glow with the halls' light (the Obsidian Tubes take their own). */
    static boolean galleries(DeepBiome b) {
        return b == DeepBiome.BASALT_GALLERIES || b == DeepBiome.CALDERA || b == DeepBiome.CAUSEWAY || b == DeepBiome.SCORIA_CANYONS;
    }

    private static Perlin patches(long seed) {
        Patches p = patches;
        if (p == null || p.seed() != seed) patches = p = new Patches(seed, new Perlin(Hashing.mix(seed, 9311, 0, 0)));
        return p.noise();
    }
}
