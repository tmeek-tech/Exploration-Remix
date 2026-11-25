package com.explorationremix.block;

import com.explorationremix.registry.ERItems;
import com.explorationremix.registry.ERParticles;
import com.explorationremix.registry.ERSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The reliquary at an ancient city's heart (0.21.9): a deepslate casket, lid shut, holding one of the three choir keys
 * that call the Hollow Choir ({@link com.explorationremix.world.feature.ChoirReliquaryFeature} sets one in every
 * city). Open it and the key is yours; it stays open and empty after, so a city gives up one key and no more, and
 * three cities are needed for one summoning.
 *
 * <p>It glows pale blue through the seam of its lid, which is the only light in a city that is not sculk's, so it can
 * be picked out across the dark halls. It cannot be broken or pushed.
 */
public class ReliquaryBlock extends Block {
    public static final BooleanProperty OPEN = BlockStateProperties.OPEN;
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    /** The light out of the lid's seam while the key is still inside. */
    public static final int LIGHT = 7;

    private static final VoxelShape SHAPE = Block.box(2, 0, 2, 14, 11, 14);

    public ReliquaryBlock(Properties properties) {
        super(properties.sound(SoundType.DEEPSLATE_BRICKS));
        registerDefaultState(defaultBlockState().setValue(OPEN, false).setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(OPEN, FACING);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (state.getValue(OPEN)) return InteractionResult.PASS;
        if (!level.isClientSide) {
            level.setBlock(pos, state.setValue(OPEN, true), 3);
            level.playSound(null, pos, ERSounds.CHOIR_RELIQUARY.get(), SoundSource.BLOCKS, 0.9F, 1.0F);
            ItemStack key = new ItemStack(ERItems.CHOIR_KEY.get());
            if (!player.addItem(key)) player.drop(key, false);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (state.getValue(OPEN) || random.nextInt(5) != 0) return;
        int c = ChoirLook.GLOW;
        level.addParticle(ERParticles.SPORE.get(), pos.getX() + 0.2 + random.nextDouble() * 0.6,
                pos.getY() + 0.7, pos.getZ() + 0.2 + random.nextDouble() * 0.6,
                ((c >> 16) & 255) / 255.0, ((c >> 8) & 255) / 255.0, (c & 255) / 255.0);
    }
}
