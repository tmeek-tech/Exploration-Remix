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
 * It behaves like air in nearly every way but its light ({@link #LEVEL}, 0 to 15): nothing is drawn, nothing collides
 * with it or can aim at it, a placed block or a falling one takes its place, a fluid flows into it quietly (it is a
 * {@link LiquidBlockContainer} only so that lava does not fizz as it would taking any other block), and an explosion
 * takes it as it takes tall grass (it has no resistance). It is not air to the game ({@code isAir()} is false, as for
 * vanilla's light block), so the chunk's light still finds it in a section with nothing else; the mod's own looks and
 * tools ask {@link #open} instead. It drops nothing, makes no sound and has no item.
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
