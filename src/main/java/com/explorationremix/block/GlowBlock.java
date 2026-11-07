package com.explorationremix.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The Basalt Galleries' lava glow: light with nothing to see, set in the air near the rock by
 * {@code HallGlowFeature} so the halls read lit by their lava. Complementary leaves unlit rock near black and shows a
 * block's light only a few blocks out, so the lava alone lights a thin rim of a hall 100 blocks tall.
 * <p>
 * Nothing is drawn but its light ({@link #LEVEL}, 0 to 15): nothing collides with it or can aim at it, a placed block or
 * a falling one takes its place, a fluid flows into it quietly (it is a {@link LiquidBlockContainer} only so that lava
 * does not fizz as it would taking any other block), and an explosion takes it as it takes tall grass (it has no
 * resistance). It is not air to the game ({@code isAir()} is false, as for vanilla's light block), so the chunk's light
 * still finds it in a section with nothing else; the mod's own looks, tools and features ask {@link #open} instead. A
 * nether portal and fire may take its place ({@code PortalShapeMixin}, {@code BaseFireBlockMixin}) and a growing tree
 * grows through it ({@code #minecraft:replaceable_by_trees}). Vanilla code that asks for air elsewhere still sees it as
 * a block, as it sees vanilla's light block: {@code /fill ... keep}, {@code /setblock ... keep} and
 * {@code replace minecraft:air} skip it (fill with {@code replace explorationremix:glow}), and sugar cane, cactus,
 * bamboo, stems, a hoe or shovel under it, fire spreading into it, a fireball (a dispensed fire charge, a blaze's) and
 * lava setting things alight stop at it. It drops nothing, makes no sound and has no item.
 * <p>
 * Once something else has taken a glow's place (a block placed and later mined, a bucket, a flow that recedes, an
 * explosion, a piston, a falling block) the glow itself never comes back; in the Basalt Galleries the terrain reset puts
 * it back from its change log (which records a fluid flowing into a glow like any other change), and the flood band's
 * glow, which the rising lava takes and marks, comes back with the drain ({@code Flood}, {@code LavaChunk} "glow"),
 * always at {@code HallGlowFeature.BAND_LEVEL} (the mask keeps no level, and worldgen gives the band no other).
 * <p>
 * For the quakes, eruptions and the terrain reset: ask {@link #open}, not {@code isAir()}, whether a spot is empty
 * (under a placed block, under a ceiling); never count a glow as support. {@code FallingBlockEntity.fall(level, pos,
 * state)} writes air at {@code pos}, so debris spawned under a ceiling must start only where {@code isAir()} holds, or
 * it erases a glow. A falling block that lands in a glow, a placed block and an explosion ({@code onBlockExploded} sets
 * air) take it for good, so a change log records a glow like any other block and the reset puts it back from that log.
 * Where the glow goes depends on the blocks round it when its chunk's pass ran (and the second pass a neighbour
 * decorated later gave it), so it cannot be worked out again from {@code Terrain}; and in a slice the lava holds, the
 * reset puts a glow back as lava with its mask and glow bits set ({@code LavaChunk}), never as the block.
 */
public class GlowBlock extends Block implements LiquidBlockContainer {
    public static final IntegerProperty LEVEL = BlockStateProperties.LEVEL;

    /** Air, or the glow: what the mod's own looks and tools treat as open space (the glow is not air to the game). */
    public static boolean open(BlockState state) { return state.isAir() || state.getBlock() instanceof GlowBlock; }

    public GlowBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(LEVEL, 15));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(LEVEL); }

    @Override
    public RenderShape getRenderShape(BlockState state) { return RenderShape.INVISIBLE; }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return Shapes.empty(); }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return Shapes.empty(); }

    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) { return true; }

    @Override
    public float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) { return 1.0F; }

    @Override
    public ItemStack getCloneItemStack(BlockGetter level, BlockPos pos, BlockState state) { return ItemStack.EMPTY; }

    /** Any fluid may take the glow's place. */
    @Override
    public boolean canPlaceLiquid(BlockGetter level, BlockPos pos, BlockState state, Fluid fluid) { return true; }

    /** The fluid takes the glow's place, without the fizz lava makes when it flows into any other block. */
    @Override
    public boolean placeLiquid(LevelAccessor level, BlockPos pos, BlockState state, FluidState fluid) {
        if (!level.isClientSide()) level.setBlock(pos, fluid.createLegacyBlock(), 3);
        return true;
    }
}
