package com.explorationremix.world.feature;

import com.explorationremix.registry.ERBlocks;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.DeepBiome;
import com.explorationremix.world.terrain.Hashing;
import com.explorationremix.world.terrain.Net;
import com.explorationremix.world.terrain.Network;
import com.explorationremix.world.terrain.Terrain;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/**
 * The vines of the deep layers' climbs (section 10). Every climb, and every stage of a staged climb, gets one column of
 * vines on the wall facing the way out at its top ({@link Network#vineColumn}): it hangs from the first rock over the
 * floor the stage climbs to (at most 16 blocks over it) down through the air to the stage's foot, so a climber steps
 * off its top onto that floor. The vine follows the climb's biome: wisteria by the Lantern Lake, ember vines in the
 * Ember Gardens and the Basalt Galleries (which have no climbing plant of their own), blue glow vines in the Blue
 * Reaches and dusk vines in the deep dark. The voxel harness's {@code markShafts} models exactly this column.
 */
public final class ClimbVinesFeature extends Feature<NoneFeatureConfiguration> {
    /** How far over the floor at a stage's top its vines may hang from. */
    static final int HOLD = 16;

    public ClimbVinesFeature(Codec<NoneFeatureConfiguration> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        Terrain t = DeepWorld.terrain();
        if (t == null || !DeepWorld.installed()) return false;
        WorldGenLevel level = ctx.level();
        ChunkPos cp = new ChunkPos(ctx.origin());
        int minX = cp.getMinBlockX(), minZ = cp.getMinBlockZ(), maxX = minX + 15, maxZ = minZ + 15;
        boolean any = false;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int li = 0; li < t.shape.layerCount; li++) {
            for (Net.Shaft s : t.shaftsIn(minX, minZ, maxX, maxZ, li)) {
                if (s.vineX < minX || s.vineX > maxX || s.vineZ < minZ || s.vineZ > maxZ) continue;
                int foot = Network.vineFoot(s), top = Network.vineTop(s);
                int hold = top + 1;
                while (hold <= top + HOLD && level.getBlockState(p.set(s.vineX, hold, s.vineZ)).isAir()) hold++;
                if (hold > top + HOLD || !level.getBlockState(p.set(s.vineX, hold, s.vineZ)).isFaceSturdy(level, p, Direction.DOWN)) continue;
                Block[] vine = vine(s.biome, Hashing.mix(t.seed, 8812, s.vineX, s.vineZ));
                any |= hang(level, s.vineX, hold - 1, foot, s.vineZ, vine[0], vine[1]);
            }
        }
        return any;
    }

    /** The climbing plant of a biome: {head, body}. */
    static Block[] vine(DeepBiome b, long h) {
        if (b == null) b = DeepBiome.BASALT_GALLERIES;
        return switch (b) {
            case LANTERN_LAKE -> (h & 1) == 0 ? new Block[]{ERBlocks.WISTERIA.get(), ERBlocks.WISTERIA_PLANT.get()}
                    : new Block[]{ERBlocks.PALE_WISTERIA.get(), ERBlocks.PALE_WISTERIA_PLANT.get()};
            case DEEP_BLUE_RIFT, ASURINE_GEODES -> new Block[]{ERBlocks.BLUE_GLOW_VINES.get(), ERBlocks.BLUE_GLOW_VINES_PLANT.get()};
            case DEEP_DARK -> new Block[]{ERBlocks.DUSK_VINES.get(), ERBlocks.DUSK_VINES_PLANT.get()};
            default -> new Block[]{ERBlocks.EMBER_VINES.get(), ERBlocks.EMBER_VINES_PLANT.get()};
        };
    }

    /**
     * Hangs a vine in column (x, z) from {@code from} down through the air to {@code to} (or the first block that is not
     * air): the body, then the growing tip at the bottom. Returns whether anything was placed.
     */
    static boolean hang(WorldGenLevel level, int x, int from, int to, int z, Block head, Block body) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int y = from;
        while (y > to && level.getBlockState(p.set(x, y - 1, z)).isAir()) y--;
        int bottom = y;
        if (!level.getBlockState(p.set(x, from, z)).isAir()) return false;
        for (int yy = from; yy >= bottom; yy--) level.setBlock(p.set(x, yy, z), (yy == bottom ? head : body).defaultBlockState(), 2);
        return true;
    }
}
