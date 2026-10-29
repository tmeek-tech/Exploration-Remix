package com.explorationremix.block;

import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.GrowingPlantHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.function.Supplier;

/**
 * The growing tip of a vine that hangs down from a ceiling, like weeping vines. It doesn't spread by itself, so the
 * caves look the way they were generated; bone meal still lengthens it.
 */
public class HangingVinesBlock extends GrowingPlantHeadBlock {
    private static final VoxelShape SHAPE = Block.box(4, 9, 4, 12, 16, 12);
    private final Supplier<? extends Block> body;

    public HangingVinesBlock(Properties properties, Supplier<? extends Block> body) {
        super(properties, Direction.DOWN, SHAPE, false, 0.0);
        this.body = body;
    }

    @Override
    public boolean isRandomlyTicking(BlockState state) { return false; }

    @Override
    protected int getBlocksToGrowWhenBonemealed(RandomSource random) { return 1 + random.nextInt(2); }

    @Override
    protected boolean canGrowInto(BlockState state) { return state.isAir(); }

    @Override
    protected Block getBodyBlock() { return body.get(); }
}
