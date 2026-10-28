package com.explorationremix.world.gen;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.mixin.accessor.SurfaceContextAccessor;
import com.explorationremix.registry.ERBlocks;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.Layer;
import com.explorationremix.world.plan.Hashing;
import com.explorationremix.world.plan.PlanNoise;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.placement.CaveSurface;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * Surface rules for everything below the pack's floor: bedrock at the new bottom, band stone, each layer's base rock
 * with Create's stones as bold patches, and per-biome floors and ceilings. Prepended to the pack's own rules.
 */
public final class DeepSurfaceRules {
    private DeepSurfaceRules() {}

    private static PlanNoise noise = new PlanNoise(0);
    private static long seed;

    public static void setSeed(long s) { seed = s; noise = new PlanNoise(s ^ 0x2545F4914F6CDD1DL); }

    // ------------------------------------------------------------------ conditions

    /** Y range check on the block itself (inclusive). */
    public record YRange(int min, int max) implements SurfaceRules.ConditionSource {
        static final KeyDispatchDataCodec<YRange> CODEC = KeyDispatchDataCodec.of(MapCodec.unit(() -> new YRange(0, 0)));
        @Override public KeyDispatchDataCodec<? extends SurfaceRules.ConditionSource> codec() { return CODEC; }
        @Override public SurfaceRules.Condition apply(SurfaceRules.Context ctx) {
            SurfaceContextAccessor a = (SurfaceContextAccessor) (Object) ctx;
            return () -> { int y = a.er$blockY(); return y >= min && y <= max; };
        }
    }

    /** True inside any band. */
    public record InBand() implements SurfaceRules.ConditionSource {
        static final KeyDispatchDataCodec<InBand> CODEC = KeyDispatchDataCodec.of(MapCodec.unit(InBand::new));
        @Override public KeyDispatchDataCodec<? extends SurfaceRules.ConditionSource> codec() { return CODEC; }
        @Override public SurfaceRules.Condition apply(SurfaceRules.Context ctx) {
            SurfaceContextAccessor a = (SurfaceContextAccessor) (Object) ctx;
            return () -> DeepWorld.isBand(a.er$blockY());
        }
    }

    /** Blobby 3D patches: true where the seeded noise rises above a threshold. */
    public record Patch(int salt, double scale, double threshold, double yStretch) implements SurfaceRules.ConditionSource {
        static final KeyDispatchDataCodec<Patch> CODEC = KeyDispatchDataCodec.of(MapCodec.unit(() -> new Patch(0, 1, 1, 1)));
        @Override public KeyDispatchDataCodec<? extends SurfaceRules.ConditionSource> codec() { return CODEC; }
        @Override public SurfaceRules.Condition apply(SurfaceRules.Context ctx) {
            SurfaceContextAccessor a = (SurfaceContextAccessor) (Object) ctx;
            return () -> noise.noise3((a.er$blockX() + salt * 1013.0) / scale, a.er$blockY() * yStretch / scale, (a.er$blockZ() - salt * 577.0) / scale) > threshold;
        }
    }

    /** Horizontal bands: true on a stripe pattern driven by y plus a slow 2D wobble. */
    public record Stripes(int salt, int period, int width) implements SurfaceRules.ConditionSource {
        static final KeyDispatchDataCodec<Stripes> CODEC = KeyDispatchDataCodec.of(MapCodec.unit(() -> new Stripes(0, 1, 1)));
        @Override public KeyDispatchDataCodec<? extends SurfaceRules.ConditionSource> codec() { return CODEC; }
        @Override public SurfaceRules.Condition apply(SurfaceRules.Context ctx) {
            SurfaceContextAccessor a = (SurfaceContextAccessor) (Object) ctx;
            return () -> {
                double wobble = noise.noise2((a.er$blockX() + salt * 91.0) / 40.0, a.er$blockZ() / 40.0) * 4.0;
                int yy = (int) Math.floor(a.er$blockY() + wobble);
                return Math.floorMod(yy, period) < width;
            };
        }
    }

    /** Per-block chance, deterministic from the seed and position. */
    public record Chance(int salt, double chance) implements SurfaceRules.ConditionSource {
        static final KeyDispatchDataCodec<Chance> CODEC = KeyDispatchDataCodec.of(MapCodec.unit(() -> new Chance(0, 0)));
        @Override public KeyDispatchDataCodec<? extends SurfaceRules.ConditionSource> codec() { return CODEC; }
        @Override public SurfaceRules.Condition apply(SurfaceRules.Context ctx) {
            SurfaceContextAccessor a = (SurfaceContextAccessor) (Object) ctx;
            return () -> Hashing.unit(Hashing.mix(seed + salt, a.er$blockX(), a.er$blockY(), a.er$blockZ())) < chance;
        }
    }

    /** True inside a refuge chamber, so refuges can stay bare. */
    public record InRefuge() implements SurfaceRules.ConditionSource {
        static final KeyDispatchDataCodec<InRefuge> CODEC = KeyDispatchDataCodec.of(MapCodec.unit(InRefuge::new));
        @Override public KeyDispatchDataCodec<? extends SurfaceRules.ConditionSource> codec() { return CODEC; }
        @Override public SurfaceRules.Condition apply(SurfaceRules.Context ctx) {
            SurfaceContextAccessor a = (SurfaceContextAccessor) (Object) ctx;
            return () -> DeepWorld.plans() != null && DeepWorld.plans().insideRefuge(a.er$blockX(), a.er$blockY(), a.er$blockZ());
        }
    }

    /** True on the floor and lower walls of the bedrock landing, which are bedrock. */
    public record BedrockLanding() implements SurfaceRules.ConditionSource {
        static final KeyDispatchDataCodec<BedrockLanding> CODEC = KeyDispatchDataCodec.of(MapCodec.unit(BedrockLanding::new));
        @Override public KeyDispatchDataCodec<? extends SurfaceRules.ConditionSource> codec() { return CODEC; }
        @Override public SurfaceRules.Condition apply(SurfaceRules.Context ctx) {
            SurfaceContextAccessor a = (SurfaceContextAccessor) (Object) ctx;
            return () -> DeepWorld.plans() != null && DeepWorld.plans().bedrockLandingFloor(a.er$blockX(), a.er$blockY(), a.er$blockZ());
        }
    }

    // ------------------------------------------------------------------ helpers

    private static SurfaceRules.RuleSource state(Block b) { return SurfaceRules.state(b.defaultBlockState()); }

    private static BlockState lookup(String id, Block fallback) {
        Block b = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(id));
        if (b == null || b == Blocks.AIR) {
            ExplorationRemix.LOGGER.warn("Block {} not found, using {} instead", id, ForgeRegistries.BLOCKS.getKey(fallback));
            return fallback.defaultBlockState();
        }
        return b.defaultBlockState();
    }

    private static SurfaceRules.ConditionSource biome(String id) {
        return SurfaceRules.isBiome(ResourceKey.create(Registries.BIOME, new ResourceLocation(ExplorationRemix.MOD_ID, id)));
    }

    private static final SurfaceRules.ConditionSource FLOOR = SurfaceRules.stoneDepthCheck(0, false, CaveSurface.FLOOR);
    private static final SurfaceRules.ConditionSource UNDER_FLOOR = SurfaceRules.stoneDepthCheck(0, true, 3, CaveSurface.FLOOR);
    private static final SurfaceRules.ConditionSource CEILING = SurfaceRules.stoneDepthCheck(0, false, CaveSurface.CEILING);

    private static SurfaceRules.RuleSource floor(String biomeId, int salt, Object... chancesAndStates) {
        return surface(FLOOR, biomeId, salt, chancesAndStates);
    }

    private static SurfaceRules.RuleSource ceiling(String biomeId, int salt, Object... chancesAndStates) {
        return surface(CEILING, biomeId, salt, chancesAndStates);
    }

    /** chancesAndStates alternates (double chance, BlockState state); chances are cumulative tries in order. */
    private static SurfaceRules.RuleSource surface(SurfaceRules.ConditionSource where, String biomeId, int salt, Object... chancesAndStates) {
        List<SurfaceRules.RuleSource> rules = new ArrayList<>();
        for (int i = 0; i + 1 < chancesAndStates.length; i += 2) {
            double chance = ((Number) chancesAndStates[i]).doubleValue();
            BlockState st = (BlockState) chancesAndStates[i + 1];
            rules.add(SurfaceRules.ifTrue(new Chance(salt * 31 + i, chance), SurfaceRules.state(st)));
        }
        return SurfaceRules.ifTrue(biome(biomeId), SurfaceRules.ifTrue(where, SurfaceRules.sequence(rules.toArray(new SurfaceRules.RuleSource[0]))));
    }

    // ------------------------------------------------------------------ build

    public static SurfaceRules.RuleSource build(SurfaceRules.RuleSource original) {
        int packFloor = DeepWorld.packFloor();
        int floorY = DeepWorld.floorY();
        List<Layer> layers = DeepWorld.layers();

        BlockState ochrum = lookup("create:ochrum", Blocks.TERRACOTTA);
        BlockState limestone = lookup("create:limestone", Blocks.CALCITE);
        BlockState asurine = lookup("create:asurine", Blocks.BLUE_TERRACOTTA);
        BlockState crimsite = lookup("create:crimsite", Blocks.RED_TERRACOTTA);
        BlockState scoria = lookup("create:scoria", Blocks.BLACKSTONE);
        BlockState scorchia = lookup("create:scorchia", Blocks.POLISHED_BLACKSTONE);
        BlockState veridium = lookup("create:veridium", Blocks.OXIDIZED_COPPER);

        List<SurfaceRules.RuleSource> deep = new ArrayList<>();
        deep.add(SurfaceRules.ifTrue(SurfaceRules.verticalGradient("explorationremix:bedrock_floor", VerticalAnchor.absolute(floorY), VerticalAnchor.absolute(floorY + 5)), state(Blocks.BEDROCK)));
        deep.add(SurfaceRules.ifTrue(new BedrockLanding(), state(Blocks.BEDROCK)));
        deep.add(SurfaceRules.ifTrue(new InBand(), state(ERBlocks.BAND_STONE.get())));

        // Biome floors and ceilings (refuges stay bare stone by design: only crystal, dripstone, ice or obsidian).
        deep.add(floor("dripstone_halls", 1, 0.45, Blocks.DRIPSTONE_BLOCK.defaultBlockState(), 0.25, limestone));
        deep.add(ceiling("dripstone_halls", 2, 0.35, Blocks.DRIPSTONE_BLOCK.defaultBlockState()));
        deep.add(floor("ivory_root_cavern", 3, 0.35, Blocks.CALCITE.defaultBlockState(), 0.12, Blocks.ROOTED_DIRT.defaultBlockState(), 0.2, limestone));
        deep.add(ceiling("ivory_root_cavern", 4, 0.2, Blocks.CALCITE.defaultBlockState()));
        deep.add(floor("lantern_lake", 5, 0.3, Blocks.MUD.defaultBlockState(), 0.25, Blocks.MOSS_BLOCK.defaultBlockState(), 0.15, Blocks.CLAY.defaultBlockState()));
        deep.add(ceiling("lantern_lake", 6, 0.15, Blocks.MOSS_BLOCK.defaultBlockState()));
        deep.add(floor("amber_hive", 7, 0.15, Blocks.HONEYCOMB_BLOCK.defaultBlockState(), 0.45, ochrum));
        deep.add(ceiling("amber_hive", 8, 0.4, ERBlocks.HONEYCOMB_OCHRUM.get().defaultBlockState(), 0.3, ochrum));
        deep.add(floor("asurine_geodes", 9, 0.15, Blocks.PACKED_ICE.defaultBlockState(), 0.35, asurine));
        deep.add(ceiling("asurine_geodes", 10, 0.35, asurine, 0.1, Blocks.BLUE_ICE.defaultBlockState()));
        deep.add(floor("deep_blue_rift", 11, 0.3, Blocks.TUFF.defaultBlockState(), 0.15, asurine));
        deep.add(ceiling("deep_blue_rift", 12, 0.2, Blocks.DEEPSLATE.defaultBlockState()));
        deep.add(floor("silver_fern_rift", 13, 0.45, Blocks.MOSS_BLOCK.defaultBlockState(), 0.15, veridium));
        deep.add(ceiling("silver_fern_rift", 14, 0.2, Blocks.MOSS_BLOCK.defaultBlockState()));
        deep.add(floor("deep_dark_reach", 15, 0.55, Blocks.SCULK.defaultBlockState(), 0.1, Blocks.DEEPSLATE.defaultBlockState()));
        deep.add(ceiling("deep_dark_reach", 16, 0.25, Blocks.SCULK.defaultBlockState()));
        deep.add(floor("banded_iron_halls", 17, 0.5, ERBlocks.BANDED_IRON.get().defaultBlockState()));
        deep.add(ceiling("banded_iron_halls", 18, 0.3, ERBlocks.BANDED_IRON.get().defaultBlockState()));
        deep.add(floor("rust_springs", 19, 0.35, crimsite, 0.15, Blocks.RAW_IRON_BLOCK.defaultBlockState()));
        deep.add(ceiling("rust_springs", 20, 0.2, crimsite));
        deep.add(floor("ember_gardens", 21, 0.25, Blocks.BLACKSTONE.defaultBlockState(), 0.3, crimsite, 0.1, Blocks.MAGMA_BLOCK.defaultBlockState()));
        deep.add(ceiling("ember_gardens", 22, 0.25, crimsite));
        deep.add(floor("obsidian_tubes", 23, 0.45, Blocks.OBSIDIAN.defaultBlockState(), 0.1, Blocks.CRYING_OBSIDIAN.defaultBlockState()));
        deep.add(ceiling("obsidian_tubes", 24, 0.35, Blocks.OBSIDIAN.defaultBlockState()));
        deep.add(floor("basalt_galleries", 25, 0.35, Blocks.BASALT.defaultBlockState(), 0.25, Blocks.SMOOTH_BASALT.defaultBlockState()));
        deep.add(ceiling("basalt_galleries", 26, 0.3, Blocks.BASALT.defaultBlockState()));
        deep.add(floor("smolder_groves", 27, 0.3, scoria, 0.2, scorchia, 0.08, Blocks.MAGMA_BLOCK.defaultBlockState()));
        deep.add(ceiling("smolder_groves", 28, 0.25, scoria));

        // Layer rock with Create's stones as bold patches over it.
        for (Layer layer : layers) {
            Block base = ForgeRegistries.BLOCKS.getValue(layer.baseStone());
            if (base == null) base = Blocks.DEEPSLATE;
            List<SurfaceRules.RuleSource> rules = new ArrayList<>();
            switch (layer.index()) {
                case 0 -> {
                    rules.add(SurfaceRules.ifTrue(new Patch(1, 26, 0.46, 1.6), SurfaceRules.state(ochrum)));
                    rules.add(SurfaceRules.ifTrue(new Stripes(2, 9, 2), SurfaceRules.ifTrue(new Patch(3, 60, 0.05, 0.3), SurfaceRules.state(limestone))));
                }
                case 1 -> {
                    rules.add(SurfaceRules.ifTrue(new Patch(4, 28, 0.44, 1.4), SurfaceRules.state(asurine)));
                    rules.add(SurfaceRules.ifTrue(new Patch(5, 18, 0.56, 1.0), state(Blocks.TUFF)));
                }
                case 2 -> {
                    rules.add(SurfaceRules.ifTrue(new Patch(6, 26, 0.44, 1.5), SurfaceRules.state(crimsite)));
                    rules.add(SurfaceRules.ifTrue(new Patch(7, 20, 0.52, 1.0), state(Blocks.BLACKSTONE)));
                    rules.add(SurfaceRules.ifTrue(new Stripes(8, 7, 1), SurfaceRules.ifTrue(new Patch(9, 50, 0.1, 0.3), SurfaceRules.state(veridium))));
                }
                default -> {
                    rules.add(SurfaceRules.ifTrue(new Patch(10, 24, 0.46, 1.4), SurfaceRules.state(scoria)));
                    rules.add(SurfaceRules.ifTrue(new Patch(11, 22, 0.5, 1.0), SurfaceRules.state(scorchia)));
                    rules.add(SurfaceRules.ifTrue(new Patch(12, 14, 0.6, 2.5), state(Blocks.OBSIDIAN)));
                }
            }
            rules.add(state(base));
            deep.add(SurfaceRules.ifTrue(new YRange(layer.bottom(), layer.top()), SurfaceRules.sequence(rules.toArray(new SurfaceRules.RuleSource[0]))));
        }
        deep.add(state(Blocks.DEEPSLATE));

        SurfaceRules.RuleSource deepRule = SurfaceRules.ifTrue(new YRange(Integer.MIN_VALUE, packFloor - 1), SurfaceRules.sequence(deep.toArray(new SurfaceRules.RuleSource[0])));
        // The pack's old bedrock floor becomes plain deepslate so the first band is the real floor.
        SurfaceRules.RuleSource oldFloor = SurfaceRules.ifTrue(new YRange(packFloor, packFloor + 4), state(Blocks.DEEPSLATE));
        // Rootvaults sit in the pack's region: mossy floors and roots.
        SurfaceRules.RuleSource rootvaults = SurfaceRules.sequence(
                floor("rootvaults", 29, 0.55, Blocks.MOSS_BLOCK.defaultBlockState(), 0.25, Blocks.ROOTED_DIRT.defaultBlockState()),
                ceiling("rootvaults", 30, 0.2, Blocks.MOSS_BLOCK.defaultBlockState()));
        return SurfaceRules.sequence(deepRule, oldFloor, rootvaults, original);
    }
}
