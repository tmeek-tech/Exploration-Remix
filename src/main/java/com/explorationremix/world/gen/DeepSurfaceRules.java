package com.explorationremix.world.gen;

import com.explorationremix.mixin.accessor.SurfaceContextAccessor;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.DeepBiome;
import com.explorationremix.world.terrain.Entrances;
import com.explorationremix.world.terrain.Hashing;
import com.explorationremix.world.terrain.Rock;
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
 * biome, see {@code Palette.shell}), deepslate where the pack's bedrock floor used to be, the set pieces' rock, the
 * shells of the Upper Caves' entrances (Root Vaults, frozen Root Vaults, sinkholes and oasis hollows) and the Frost Fern
 * Grotto's ice and moss. Everything else falls through to the pack's rules unchanged.
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
            Holder<Biome> vaults = DeepBiomes.area(Entrances.Area.ROOTVAULTS), frozen = DeepBiomes.area(Entrances.Area.FROZEN_ROOTVAULTS);
            Holder<Biome> sinkhole = DeepBiomes.area(Entrances.Area.SINKHOLE), oasis = DeepBiomes.area(Entrances.Area.OASIS_HOLLOW);
            Holder<Biome> frost = DeepBiomes.frostGrotto();
            Entrances ent = DeepWorld.entrances();
            Holder<Biome> fernRift = DeepBiomes.holder(DeepBiome.SILVER_FERN_RIFT), rootCavern = DeepBiomes.holder(DeepBiome.IVORY_ROOT_CAVERN);
            ChunkAccess chunk = a.er$chunk();
            int minX = chunk.getPos().getMinBlockX(), minZ = chunk.getPos().getMinBlockZ();
            // Per column: the entrance's look and site under it. (The rule is made per chunk and asked block by block,
            // column by column.)
            Column col = new Column();
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
                int above = a.er$stoneDepthAbove(), below = a.er$stoneDepthBelow();
                boolean wet = a.er$waterHeight() != Integer.MIN_VALUE;
                if (ent != null) {
                    boolean ours = b == vaults || b == frozen || b == sinkhole || b == oasis;
                    if (ours || col.open(ent, x, z)) {
                        col.at(ent, x, z);
                        // A ravine's or sinkhole's walls up to two blocks under the ground keep their rock too.
                        if (!ours && !ent.openWall(x, y, z)) return null;
                        int look = col.look < 0 ? Entrances.HALL : col.look;
                        double shell = ent.shell(x, y, z);
                        Rock r;
                        if (b == oasis || look == Entrances.HOLLOW) r = t.palette.oasis(x, y, z, above, below, wet, shell, col.red);
                        else if (b == sinkhole || look == Entrances.SINKHOLE) r = t.palette.sinkhole(x, y, z, above, below, wet, shell, col.cold);
                        else if (b == frozen) r = t.palette.frozenVaults(look, x, y, z, above, below, wet, shell);
                        else r = t.palette.rootvaults(look, x, y, z, above, below, wet, shell);
                        return DeepMaterials.state(r);
                    }
                }
                if (b == frost && frost != null) return DeepMaterials.state(t.palette.frostGrotto(x, y, z, above, below, wet));
                if (b == fernRift || b == rootCavern) {
                    DeepBiome db = b == fernRift ? DeepBiome.SILVER_FERN_RIFT : DeepBiome.IVORY_ROOT_CAVERN;
                    return DeepMaterials.state(t.palette.upperShell(db, x, y, z, above, below, wet));
                }
                return null;
            };
        }

        private static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
    }

    /** What the entrances put under one column, looked up once per column. */
    private static final class Column {
        int x = Integer.MIN_VALUE, z = Integer.MIN_VALUE;
        int look = -1;
        boolean cold, red, open;

        void at(Entrances ent, int x, int z) {
            if (x == this.x && z == this.z) return;
            this.x = x;
            this.z = z;
            look = ent.look(x, z);
            Entrances.Site s = ent.siteAt(x, z);
            cold = s != null && s.cold;
            red = s != null && s.red;
            open = look == Entrances.RAVINE || look == Entrances.SINKHOLE;
        }

        /** A ravine or a sinkhole runs under this column (its walls are dressed up to near the ground). */
        boolean open(Entrances ent, int x, int z) {
            at(ent, x, z);
            return open;
        }
    }
}
