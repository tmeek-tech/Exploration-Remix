package com.explorationremix.block;

import com.explorationremix.registry.ERParticles;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A small plant of the Deep Gardens' vent field in one of the four neon colours (0.20.2: the filament grass and the
 * small neon mushrooms), like {@link NeonBlock}: the blockstate property {@code neon}, a model per colour, an item that
 * keeps its colour. It gives no light (Taran's card pick: "Glowing parts, no extra light"): only its glow overlay
 * shines. Now and then a spore drifts up off it in its colour (card pick: "A few rising motes"), one call of
 * {@link #animateTick} in {@code spores}.
 */
public class NeonPlantBlock extends CavePlantBlock {
    private final int spores;

    public NeonPlantBlock(Properties properties, VoxelShape shape, int spores) {
        super(properties, shape);
        this.spores = spores;
        registerDefaultState(stateDefinition.any().setValue(NeonBlock.NEON, Neon.MAGENTA));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(NeonBlock.NEON); }

    @Override
    public ItemStack getCloneItemStack(BlockGetter level, BlockPos pos, BlockState state) { return NeonBlock.stack(this, state.getValue(NeonBlock.NEON)); }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (spores > 0 && random.nextInt(spores) == 0) spore(level, pos, state.getValue(NeonBlock.NEON), 0.5, random);
    }

    /**
     * A spore in a neon colour lifting off a plant at {@code pos}, from {@code height} (blocks) over its foot at most,
     * somewhere over its block (with the plant's offset).
     */
    public static void spore(Level level, BlockPos pos, Neon neon, double height, RandomSource random) {
        Vec3 off = level.getBlockState(pos).getOffset(level, pos);
        double x = pos.getX() + off.x + 0.2 + random.nextDouble() * 0.6;
        double y = pos.getY() + off.y + 0.15 + random.nextDouble() * height;
        double z = pos.getZ() + off.z + 0.2 + random.nextDouble() * 0.6;
        int c = neon.rgb;
        level.addParticle(ERParticles.SPORE.get(), x, y, z, ((c >> 16) & 255) / 255.0, ((c >> 8) & 255) / 255.0, (c & 255) / 255.0);
    }
}
