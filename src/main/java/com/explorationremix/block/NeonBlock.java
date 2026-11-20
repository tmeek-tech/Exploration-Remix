package com.explorationremix.block;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;

/**
 * A glowing block of the Deep Gardens' vent field in one of its four neon colours ({@link Neon}, the blockstate property
 * {@code neon}): the glow mat, glow cap, vent crown, den fissure, vent glow, throat vein and vent beads. Each colour
 * has a model of its own (tools/gen_data.py): the rock (or the cap) lit by the world, and the glowing pixels drawn over
 * it a second time at full brightness, so the dark rock stays shaded while the neon glows. The light level is the
 * block's own, the same in every colour.
 * <p>
 * A broken block drops itself in its colour (its loot table copies {@code neon} into the item's BlockStateTag, which
 * the item places again), and pick block keeps the colour too. An item without the tag places the default, magenta.
 */
public class NeonBlock extends Block {
    public static final EnumProperty<Neon> NEON = EnumProperty.create("neon", Neon.class);

    public NeonBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(NEON, Neon.MAGENTA));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(NEON); }

    @Override
    public ItemStack getCloneItemStack(BlockGetter level, BlockPos pos, BlockState state) { return stack(this, state.getValue(NEON)); }

    /** An item of a neon block that places it in colour {@code neon}. */
    public static ItemStack stack(ItemLike block, Neon neon) {
        ItemStack stack = new ItemStack(block);
        CompoundTag states = new CompoundTag();
        states.putString(NEON.getName(), neon.getSerializedName());
        stack.addTagElement(BlockItem.BLOCK_STATE_TAG, states);
        return stack;
    }

    /** The colour an item of a neon block places: its BlockStateTag's, or magenta (the default state's) without one. */
    public static Neon neon(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null) return Neon.MAGENTA;
        return NEON.getValue(tag.getCompound(BlockItem.BLOCK_STATE_TAG).getString(NEON.getName())).orElse(Neon.MAGENTA);
    }
}
