package com.explorationremix.world.gen;

import com.explorationremix.mixin.accessor.SurfaceContextAccessor;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.DeepBiome;
import com.explorationremix.world.terrain.Entrances;
import com.explorationremix.world.terrain.RootKind;
import com.explorationremix.world.terrain.UpperShapes;
import net.minecraft.world.level.block.Blocks;
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
import net.minecraft.world.level.levelgen.Heightmap;
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
            UpperShapes sh = DeepWorld.shapes();
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
                if (sh != null) {
                    // The noise shapes' roots, icicles, stalactites and stalagmites, whatever biome holds them.
                    int f = sh.formation(x, y, z);
                    if (f != 0) return formation(sh, f, t.seed, x, y, z);
                }
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
                    // A ravine's or sinkhole's walls up to two blocks under the ground keep their rock too; anything
                    // else under such a column (a plant cave further down) is dressed as its own biome below.
                    if (ours || col.open(ent, x, z) && ent.openWall(x, y, z)) {
                        col.at(ent, x, z);
                        int look = col.look < 0 ? Entrances.HALL : col.look;
                        double shell = ent.shell(x, y, z);
                        Rock r;
                        if (b == oasis || look == Entrances.HOLLOW) r = t.palette.oasis(x, y, z, above, below, wet, shell, col.red);
                        else if (b == sinkhole || look == Entrances.SINKHOLE) r = t.palette.sinkhole(x, y, z, above, below, wet, shell, col.cold);
                        // A cold cluster is frozen throughout (its ravine walls near the ground lie in the surface
                        // biome: frozen too), thawing with distance from its openings.
                        else if (b == frozen || col.cold) r = t.palette.frozenVaults(look, col.chill(ent, x, y, z), x, y, z, above, below, wet, shell);
                        else r = t.palette.rootvaults(look, col.tropical, x, y, z, above, below, wet, shell);
                        return DeepMaterials.state(r);
                    }
                }
                if (sh != null) {
                    boolean ours = b == vaults || b == frozen || b == sinkhole || b == oasis;
                    // A sinkhole's pit walls up to two blocks under the ground keep its rock too.
                    if (ours || sh.openWall(x, y, z)) {
                        int look = sh.look(x, y, z);
                        double shell = sh.shell(x, y, z);
                        Rock r;
                        if (b == oasis || look == Entrances.HOLLOW) r = t.palette.oasis(x, y, z, above, below, wet, shell, sh.red(x, z));
                        else if (b == sinkhole || look == Entrances.SINKHOLE) r = t.palette.sinkhole(x, y, z, above, below, wet, shell, sh.cold(x, z));
                        else if (b == frozen || sh.cold(x, z)) r = t.palette.frozenVaults(look < 0 ? Entrances.HALL : look, sh.chill(x, y, z), x, y, z, above, below, wet, shell);
                        else r = t.palette.rootvaults(look < 0 ? Entrances.HALL : look, sh.tropical(x, z), x, y, z, above, below, wet, shell);
                        return DeepMaterials.state(r);
                    }
                    if (b == rootCavern) return DeepMaterials.state(t.palette.ivory(x, y, z, above, below, wet, sh.hallLake(x, z)));
                }
                // The grotto's rock only under a roof: never on the top three blocks of a column (its open ground), should
                // a grotto cell reach it next to a shaft of our own.
                if (b == frost && frost != null && y < chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 2)
                    return DeepMaterials.state(t.palette.frostGrotto(x, y, z, above, below, wet));
                if (b == fernRift || b == rootCavern) {
                    DeepBiome db = b == fernRift ? DeepBiome.SILVER_FERN_RIFT : DeepBiome.IVORY_ROOT_CAVERN;
                    return DeepMaterials.state(t.palette.upperShell(db, x, y, z, above, below, wet));
                }
                return null;
            };
        }

        private static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

        /**
         * A block of one of the noise shapes' formations: a root of the trees overhead (mangrove roots under mangroves),
         * packed ice with specks of blue ice, or limestone and calcite with dripstone at the tip.
         */
        private static BlockState formation(UpperShapes sh, int f, long seed, int x, int y, int z) {
            long h = Hashing.mix(seed ^ 0x6F726D61L, x, y, z);
            return switch (f) {
                case UpperShapes.ROOT -> rootLog(sh.trees(x, z));
                case UpperShapes.ICICLE -> (h & 15) < 2 ? Blocks.BLUE_ICE.defaultBlockState() : Blocks.PACKED_ICE.defaultBlockState();
                case UpperShapes.DRIP -> Blocks.DRIPSTONE_BLOCK.defaultBlockState();
                default -> DeepMaterials.state((h & 7) < 3 ? Rock.CALCITE : Rock.LIMESTONE);
            };
        }

        private static BlockState rootLog(RootKind kind) {
            return switch (kind) {
                case BIRCH -> Blocks.BIRCH_LOG.defaultBlockState();
                case SPRUCE -> Blocks.SPRUCE_LOG.defaultBlockState();
                case JUNGLE -> Blocks.JUNGLE_LOG.defaultBlockState();
                case DARK_OAK -> Blocks.DARK_OAK_LOG.defaultBlockState();
                case MANGROVE -> Blocks.MANGROVE_ROOTS.defaultBlockState();
                case CHERRY -> Blocks.CHERRY_LOG.defaultBlockState();
                default -> Blocks.OAK_LOG.defaultBlockState();
            };
        }
    }

    /** What the entrances put under one column, looked up once per column. */
    private static final class Column {
        int x = Integer.MIN_VALUE, z = Integer.MIN_VALUE;
        int look = -1;
        boolean cold, red, open, tropical;
        Entrances.Site site;

        void at(Entrances ent, int x, int z) {
            if (x == this.x && z == this.z) return;
            this.x = x;
            this.z = z;
            look = ent.look(x, z);
            Entrances.Site s = ent.siteAt(x, z);
            site = s;
            cold = s != null && s.cold;
            red = s != null && s.red;
            tropical = s != null && s.tropical();
            open = look == Entrances.RAVINE || look == Entrances.SINKHOLE;
        }

        /** How far the block lies past the snowy zone of the column's cold Root Vault cluster ({@link Entrances#chill}). */
        double chill(Entrances ent, int x, int y, int z) {
            return site != null && site.cold && site.kind == Entrances.Kind.VAULTS ? ent.chill(site, x, y, z) : ent.chill(x, y, z);
        }

        /** A ravine or a sinkhole runs under this column (its walls are dressed up to near the ground). */
        boolean open(Entrances ent, int x, int z) {
            at(ent, x, z);
            return open;
        }
    }
}
