package com.explorationremix.world.feature;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Optional;

/**
 * The broken machines of the Rust Springs, lying half-sunk in the mineral crust: big rusted cogwheels on their
 * shafts, runs of broken pipe, and casings with a press or a fan on top. Each part is mcremix's rusted (and waxed, so
 * it stays rusted) block when mcremix is installed, else Create's, else a vanilla stand-in. Nothing is powered.
 */
public final class BrokenMachineFeature extends Feature<NoneFeatureConfiguration> {
    private static final String[] LARGE_COG = {"mcremix:waxed_rusted_iron_large_cogwheel", "mcremix:waxed_rusted_steel_large_cogwheel", "create:large_cogwheel", "minecraft:waxed_exposed_cut_copper"};
    private static final String[] COG = {"mcremix:waxed_rusted_iron_cogwheel", "create:cogwheel", "minecraft:waxed_exposed_cut_copper_slab"};
    private static final String[] SHAFT = {"mcremix:waxed_rusted_iron_shaft", "create:shaft", "minecraft:chain"};
    private static final String[] GEARBOX = {"mcremix:waxed_rusted_iron_gearbox", "create:gearbox", "minecraft:waxed_exposed_copper"};
    private static final String[] CASING = {"mcremix:iron_casing", "create:andesite_casing", "minecraft:waxed_exposed_copper"};
    private static final String[] COPPER_CASING = {"create:copper_casing", "minecraft:waxed_exposed_cut_copper"};
    private static final String[] PIPE = {"create:fluid_pipe", "mcremix:iron_fluid_pipe", "minecraft:lightning_rod"};
    private static final String[] PRESS = {"mcremix:iron_mechanical_press", "create:mechanical_press", "minecraft:waxed_exposed_copper"};
    private static final String[] FAN = {"mcremix:iron_encased_fan", "create:encased_fan", "minecraft:waxed_exposed_copper"};

    public BrokenMachineFeature(Codec<NoneFeatureConfiguration> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        WorldGenLevel level = ctx.level();
        RandomSource random = ctx.random();
        BlockPos o = ctx.origin();
        // On a floor: air or water here, solid crust below.
        if (!open(level, o) || !crust(level, o.below())) return false;
        Direction d = Direction.Plane.HORIZONTAL.getRandomDirection(random);
        float kind = random.nextFloat();
        if (kind < 0.4F) gearPile(level, random, o, d);
        else if (kind < 0.75F) pipeRun(level, random, o, d);
        else machineBlock(level, random, o, d);
        return true;
    }

    /** A large cogwheel stood on edge and sunk to its axle in the crust, on a shaft running into a gearbox. */
    private void gearPile(WorldGenLevel level, RandomSource random, BlockPos o, Direction d) {
        Direction.Axis axis = d.getAxis();
        BlockPos hub = o.below();
        put(level, hub, part(LARGE_COG, "axis", axis.getName()), true);
        int len = 1 + random.nextInt(3);
        BlockPos p = hub;
        for (int i = 0; i < len; i++) {
            p = p.relative(d);
            if (!put(level, p, part(SHAFT, "axis", axis.getName()), true)) return;
        }
        put(level, p.relative(d), part(random.nextBoolean() ? GEARBOX : CASING, "axis", axis.getName()), true);
        // A small cog lying flat beside it, half in the crust.
        if (random.nextBoolean()) {
            BlockPos s = hub.relative(d.getClockWise(), 2).relative(d);
            put(level, s, part(COG, "axis", "y"), true);
        }
        // The other end of the shaft, snapped off.
        if (random.nextBoolean()) put(level, hub.relative(d.getOpposite()), part(SHAFT, "axis", axis.getName()), true);
    }

    /** A run of pipe lying in the crust, broken into pieces, ending in a casing. */
    private void pipeRun(WorldGenLevel level, RandomSource random, BlockPos o, Direction d) {
        int len = 4 + random.nextInt(5);
        BlockPos p = o;
        String a = d.getName(), b = d.getOpposite().getName();
        for (int i = 0; i < len; i++) {
            if (random.nextFloat() < 0.18F) { p = p.relative(d); continue; } // a gap where it broke
            BlockPos at = random.nextFloat() < 0.5F ? p.below() : p; // sunk or resting on the crust
            BlockState pipe = part(PIPE, a, "true");
            pipe = with(with(pipe, b, "true"), "facing", d.getName());
            if (!put(level, at, pipe, true)) break;
            p = p.relative(d);
        }
        put(level, p.below(), part(random.nextBoolean() ? COPPER_CASING : CASING, "axis", d.getAxis().getName()), true);
        put(level, p, part(random.nextBoolean() ? COPPER_CASING : CASING, "axis", d.getAxis().getName()), false);
    }

    /** Casings sunk in the crust with a press or a fan on top and a cogwheel on the side. */
    private void machineBlock(WorldGenLevel level, RandomSource random, BlockPos o, Direction d) {
        put(level, o.below(), part(CASING, "", ""), true);
        put(level, o, part(random.nextBoolean() ? COPPER_CASING : CASING, "", ""), false);
        BlockState top = random.nextBoolean()
                ? with(part(PRESS, "horizontal_facing", d.getName()), "facing", d.getName())
                : part(FAN, "facing", d.getName());
        put(level, o.above(), top, false);
        put(level, o.relative(d.getClockWise()), part(COG, "axis", d.getClockWise().getAxis().getName()), false);
        if (random.nextBoolean()) put(level, o.relative(d.getCounterClockWise()).below(), part(LARGE_COG, "axis", d.getCounterClockWise().getAxis().getName()), true);
    }

    // ------------------------------------------------------------------------------------------------ blocks

    /** The first part that exists, turned and rusted. Rusted mcremix machines keep their rust (waxed). */
    private static BlockState part(String[] ids, String prop, String value) {
        for (String id : ids) {
            Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(id));
            if (block == null || block == Blocks.AIR) continue;
            BlockState s = with(block.defaultBlockState(), "patina", "waxed_oxidized");
            return prop.isEmpty() ? s : with(s, prop, value);
        }
        return Blocks.STONE.defaultBlockState();
    }

    static BlockState with(BlockState s, String name, String value) {
        Property<?> p = s.getBlock().getStateDefinition().getProperty(name);
        return p == null ? s : set(s, p, value);
    }

    private static <T extends Comparable<T>> BlockState set(BlockState s, Property<T> p, String value) {
        Optional<T> v = p.getValue(value);
        return v.map(t -> s.setValue(p, t)).orElse(s);
    }

    /** Places a part where there is room: open space, or the crust itself when {@code sink} is set. */
    private static boolean put(WorldGenLevel level, BlockPos pos, BlockState s, boolean sink) {
        BlockState here = level.getBlockState(pos);
        boolean water = here.getFluidState().is(Fluids.WATER) && here.getFluidState().isSource();
        if (!(open(level, pos) || (sink && crust(level, pos)))) return false;
        if (Walkway.blocks(pos.getX(), pos.getY(), pos.getZ())) return false;
        s = with(s, "waterlogged", String.valueOf(water));
        level.setBlock(pos, s, 2);
        return true;
    }

    private static boolean open(WorldGenLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return s.isAir() || (s.getFluidState().is(Fluids.WATER) && s.getFluidState().isSource() && s.is(Blocks.WATER));
    }

    private static boolean crust(WorldGenLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return s.isSolidRender(level, pos) && !s.hasBlockEntity() && !s.is(BlockTags.FEATURES_CANNOT_REPLACE) && s.getFluidState().isEmpty();
    }
}
