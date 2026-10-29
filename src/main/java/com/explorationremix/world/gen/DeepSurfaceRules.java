package com.explorationremix.world.gen;

import com.explorationremix.mixin.accessor.SurfaceContextAccessor;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.DeepBiome;
import com.explorationremix.world.terrain.Hashing;
import com.explorationremix.world.terrain.Rock;
import com.explorationremix.world.terrain.Rootvaults;
import com.explorationremix.world.terrain.Terrain;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.SurfaceRules;

/**
 * Surface rules for the deepened overworld, run before the pack's own: the deep layers' rock near open space (per
 * biome, see {@code Palette.shell}), deepslate where the pack's bedrock floor used to be, the set pieces' rock, and the
 * shell of the Rootvaults' grottos (see {@code Palette.rootvaults}). Everything else falls through to the pack's rules
 * unchanged.
 */
public final class DeepSurfaceRules {
    private DeepSurfaceRules() {}

    /** The pack's rules with the deep world's in front of them. */
    public static SurfaceRules.RuleSource build(SurfaceRules.RuleSource original) {
        return SurfaceRules.sequence(DeepRule.INSTANCE, original);
    }

    public enum DeepRule implements SurfaceRules.RuleSource {
        INSTANCE;

        static final KeyDispatchDataCodec<DeepRule> CODEC = KeyDispatchDataCodec.of(MapCodec.unit(INSTANCE));

        @Override
        public KeyDispatchDataCodec<? extends SurfaceRules.RuleSource> codec() { return CODEC; }

        @Override
        public SurfaceRules.SurfaceRule apply(SurfaceRules.Context ctx) {
            SurfaceContextAccessor a = (SurfaceContextAccessor) (Object) ctx;
            Terrain t = DeepWorld.terrain();
            if (t == null) return (x, y, z) -> null;
            int packFloor = t.shape.packFloor;
            BlockState deepslate = DeepMaterials.state(Rock.DEEPSLATE);
            Holder<Biome> rootvaults = DeepBiomes.rootvaults();
            Rootvaults roots = DeepWorld.roots();
            Holder<Biome> fernRift = DeepBiomes.holder(DeepBiome.SILVER_FERN_RIFT), rootCavern = DeepBiomes.holder(DeepBiome.IVORY_ROOT_CAVERN);
            ChunkAccess chunk = a.er$chunk();
            int minX = chunk.getPos().getMinBlockX(), minZ = chunk.getPos().getMinBlockZ();
            // Per column: does a ravine run here? (The rule is made per chunk and asked block by block, column by column.)
            int[] lastColumn = {Integer.MIN_VALUE, Integer.MIN_VALUE};
            boolean[] ravineColumn = {false};
            return (x, y, z) -> {
                if (y < packFloor) {
                    Rock r = t.palette.shell(x, y, z, a.er$stoneDepthAbove(), a.er$stoneDepthBelow(), a.er$waterHeight() != Integer.MIN_VALUE);
                    return DeepMaterials.state(r);
                }
                if (y <= packFloor + 5) return deepslate; // the pack's old bedrock floor
                // The biome stored for this block's 4x4x4 cell, read one to two blocks off so borders are ragged.
                long h = Hashing.mix(t.seed, x, y, z);
                int bx = clamp(x + (int) (h & 3) - 1, minX, minX + 15);
                int by = y + (int) ((h >>> 2) & 3) - 1;
                int bz = clamp(z + (int) ((h >>> 4) & 3) - 1, minZ, minZ + 15);
                Holder<Biome> b = chunk.getNoiseBiome(QuartPos.fromBlock(bx), QuartPos.fromBlock(by), QuartPos.fromBlock(bz));
                boolean ravineWall = false;
                if (roots != null && b != rootvaults) {
                    if (x != lastColumn[0] || z != lastColumn[1]) {
                        lastColumn[0] = x;
                        lastColumn[1] = z;
                        ravineColumn[0] = roots.look(x, z) == Rootvaults.RAVINE;
                    }
                    ravineWall = ravineColumn[0] && roots.ravineWall(x, y, z);
                }
                if ((b == rootvaults || ravineWall) && rootvaults != null && roots != null) {
                    int look = ravineWall ? Rootvaults.RAVINE : roots.look(x, z);
                    return DeepMaterials.state(t.palette.rootvaults(look < 0 ? Rootvaults.HALL : look, x, y, z, a.er$stoneDepthAbove(),
                            a.er$stoneDepthBelow(), a.er$waterHeight() != Integer.MIN_VALUE, roots.shell(x, y, z)));
                }
                if (b == fernRift || b == rootCavern) {
                    DeepBiome db = b == fernRift ? DeepBiome.SILVER_FERN_RIFT : DeepBiome.IVORY_ROOT_CAVERN;
                    return DeepMaterials.state(t.palette.upperShell(db, x, y, z, a.er$stoneDepthAbove(), a.er$stoneDepthBelow(),
                            a.er$waterHeight() != Integer.MIN_VALUE));
                }
                return null;
            };
        }

        private static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
    }
}
