package com.explorationremix.block;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.GrowingPlantBodyBlock;
import net.minecraft.world.level.block.GrowingPlantHeadBlock;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.function.Supplier;

/** The hanging length of a vine above its tip. */
public class HangingVinesPlantBlock extends GrowingPlantBodyBlock {
    private static final VoxelShape SHAPE = Block.box(1, 0, 1, 15, 16, 15);
    private final Supplier<? extends Block> head;

    public HangingVinesPlantBlock(Properties properties, Supplier<? extends Block> head) {
        super(properties, Direction.DOWN, SHAPE, false);
        this.head = head;
    }

    @Override
    protected GrowingPlantHeadBlock getHeadBlock() { return (GrowingPlantHeadBlock) head.get(); }
}
