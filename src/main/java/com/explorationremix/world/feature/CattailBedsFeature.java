package com.explorationremix.world.feature;

import com.explorationremix.block.SilverCattailsBlock;
import com.explorationremix.registry.ERBlocks;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.Hashing;
import com.explorationremix.world.terrain.Network;
import com.explorationremix.world.terrain.Perlin;
import com.explorationremix.world.terrain.Terrain;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.material.FluidState;

/**
 * Silver cattail beds (0.21.7; Taran's cards: reeds "By water in the rifts", "Two to three blocks, mixed", the "Silver
 * cattails" look, and "Hidden while you're in them"): stands of {@link SilverCattailsBlock} by the Blue Reaches' rift
 * water, the streams along the rifts' bottoms and the ponds on their shelves ({@link Network#pondsIn}), for a player to
 * hide in from the Skeins. A spot is a block of water one deep over rock (the shallows, where the plant's foot stands
 * in the water), the edge of water two deep (a plant three tall, which stands out of it), or a dry floor beside water
 * (the bank). Beds lie where a patch noise is high, so stands of reeds alternate with open shore; a pond's shore lies
 * in one more often than not, while its spill and the streams' deep middles stay open water. In a bed most spots take
 * a plant, fewer at its fringe, two or three tall, three more often towards its middle.
 */
public final class CattailBedsFeature extends Feature<NoneFeatureConfiguration> {
    /** The patch noise's scale (blocks), the level a bed starts at, and how much more a pond's shore lies in one. */
    static final double BED_SCALE = 11, BED = 0.05, POND_BOOST = 0.4;
    /** The share of a bed's spots that take a plant in its middle (its fringe, where the noise is just over BED, fewer). */
    static final double FILL = 0.85;
    private static final int[] DX = {1, -1, 0, 0}, DZ = {0, 0, 1, -1};
    private static final int NONE = 0, SHALLOW = 1, DEEP = 2, BANK = 3;

    /** The patch noise, for the world's seed. */
    private record Noise(long seed, Perlin perlin) {}
    private static volatile Noise noise;

    public CattailBedsFeature(Codec<NoneFeatureConfiguration> codec) { super(codec); }

    private static Perlin noise(long seed) {
        Noise n = noise;
        if (n == null || n.seed() != seed) noise = n = new Noise(seed, new Perlin(Hashing.mix(seed, 0x6CA7, 0x7A11, 21)));
        return n.perlin();
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        Terrain t = DeepWorld.terrain();
        if (t == null || !DeepWorld.installed()) return false;
        WorldGenLevel level = ctx.level();
        ChunkPos cp = new ChunkPos(ctx.origin());
        int x0 = cp.getMinBlockX(), z0 = cp.getMinBlockZ();
        int W = t.shape.waterY(Terrain.BLUE);
        Perlin bed = noise(t.seed);
        SilverCattailsBlock block = (SilverCattailsBlock) ERBlocks.SILVER_CATTAILS.get();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        boolean[] done = new boolean[256];
        boolean any = false;
        // the ponds first, their shores more often in a bed: the shallow ring, the edge of a middle two deep, the rim
        for (Network.PondInfo pond : t.network.pondsIn(x0, z0, x0 + 15, z0 + 15)) {
            double reach = pond.r() + 3;
            int ax = Math.max(x0, (int) Math.floor(pond.x() - reach)), bx = Math.min(x0 + 15, (int) Math.ceil(pond.x() + reach));
            int az = Math.max(z0, (int) Math.floor(pond.z() - reach)), bz = Math.min(z0 + 15, (int) Math.ceil(pond.z() + reach));
            for (int x = ax; x <= bx; x++) for (int z = az; z <= bz; z++) {
                double ox = x - pond.x(), oz = z - pond.z(), r = Math.hypot(ox, oz);
                if (r > reach || r < pond.r() - 2.6) continue;
                double a = ox * pond.sx() + oz * pond.sz(), across = Math.abs(ox * pond.sz() - oz * pond.sx());
                if (pond.spills() && a > pond.r() - 2 && across < pond.sw() + 1.5) continue;   // the spill stays open
                double n = bedAt(bed, x, z) + POND_BOOST;
                for (int y = pond.top() - 1; y <= pond.feet(); y++) {
                    int kind = kind(level, p, x, y, z);
                    if (kind == NONE) continue;
                    done[((x - x0) << 4) | (z - z0)] = true;
                    any |= plant(level, p, block, t.seed, x, y, z, kind, n);
                    break;
                }
            }
        }
        // the streams: the spots from the stream's bed to its bank in a bed, in the columns of the chunk in a rift (the
        // blocks are looked at first, the plan only where they hold a spot)
        for (int x = x0; x < x0 + 16; x++) for (int z = z0; z < z0 + 16; z++) {
            if (done[((x - x0) << 4) | (z - z0)]) continue;
            double n = bedAt(bed, x, z);
            if (n <= BED) continue;
            for (int y = W - 3; y <= W + 1; y++) {
                int kind = kind(level, p, x, y, z);
                if (kind == NONE) continue;
                if (t.network.inNoiseRift(x, z, 0) || t.network.inMainRift(x, z, 0)) any |= plant(level, p, block, t.seed, x, y, z, kind, n);
                break;
            }
        }
        return any;
    }

    /** The patch noise at a column, about -1 to 1. */
    private static double bedAt(Perlin bed, int x, int z) {
        return 1.381 * bed.noise2(x / BED_SCALE, z / BED_SCALE);
    }

    /** What kind of spot (x, y, z) is for a plant's foot, if any: see the class comment. */
    private static int kind(WorldGenLevel level, BlockPos.MutableBlockPos p, int x, int y, int z) {
        BlockState at = level.getBlockState(p.set(x, y, z));
        boolean wet = water(at.getFluidState()) && at.is(Blocks.WATER);
        if (!wet && !at.isAir()) return NONE;
        if (!level.getFluidState(p.set(x, y - 1, z)).isEmpty() || !level.getBlockState(p).isFaceSturdy(level, p, Direction.UP)) return NONE;
        BlockState up = level.getBlockState(p.set(x, y + 1, z));
        if (!wet) {
            // a bank: dry floor with water a block or two under its top, within two blocks
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                if (Math.abs(dx) + Math.abs(dz) > 3 || (dx == 0 && dz == 0)) continue;
                if (water(level.getFluidState(p.set(x + dx, y - 1, z + dz))) || water(level.getFluidState(p.set(x + dx, y - 2, z + dz)))) return BANK;
            }
            return NONE;
        }
        if (up.isAir()) return SHALLOW;
        if (!up.is(Blocks.WATER) || !water(up.getFluidState()) || !level.getBlockState(p.set(x, y + 2, z)).isAir()) return NONE;
        // two deep: only at its edge, beside shallower water or the shore, so the middle stays open
        for (int d = 0; d < 4; d++)
            if (!water(level.getFluidState(p.set(x + DX[d], y, z + DZ[d])))) return DEEP;
        return NONE;
    }

    private static boolean water(FluidState fs) { return fs.isSource() && fs.is(FluidTags.WATER); }

    /**
     * A plant at the spot, if the bed's roll gives one (n: the patch noise there): three tall in water two deep, else
     * two or three. Each of its blocks takes the water it stands in, and none goes where anything but air or that water
     * is (two tall then, or none).
     */
    private static boolean plant(WorldGenLevel level, BlockPos.MutableBlockPos p, SilverCattailsBlock block, long seed, int x, int y, int z, int kind,
                                 double n) {
        double inBed = clamp((n - BED) / 0.3);
        if (Hashing.unit(Hashing.mix(seed ^ 0x3C0FFEEL, x, y, z)) >= FILL * inBed * inBed * (3 - 2 * inBed)) return false;
        int height = kind == DEEP || Hashing.unit(Hashing.mix(seed ^ 0x7EEDL, x, y, z)) < 0.3 + 0.4 * inBed ? 3 : 2;
        boolean[] wet = new boolean[3];
        for (int i = 0; i < height; i++) {
            BlockState s = level.getBlockState(p.set(x, y + i, z));
            if (s.isAir()) continue;
            if (s.is(Blocks.WATER) && water(s.getFluidState()) && i < 2) { wet[i] = true; continue; }
            if (i < 2 || kind == DEEP) return false;
            height = 2;
        }
        if (height == 2 && wet[1]) return false;   // its top under water
        for (int i = 0; i < height; i++) level.setBlock(p.set(x, y + i, z), block.state(height, i, wet[i]), 2);
        return true;
    }

    private static double clamp(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }
}
