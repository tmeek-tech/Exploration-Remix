package com.explorationremix.block;

import com.explorationremix.creature.choir.ChoirSummon;
import com.explorationremix.registry.ERParticles;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * One of the three sockets round the rim of the Hollow Choir's arena (0.21.9): a worked deepslate cradle with a pale
 * blue hollow, which takes one choir key. Fill all three and the Choir is called ({@link ChoirSummon}).
 *
 * <p>A socket keeps its key. Lose the fight and the three stay filled, so the retry is free: using a filled socket
 * calls the Choir again (Taran's pick: the keys stay, a free retry, the Choir heals). Win and all three empty, and the
 * arena waits for three keys from three more cities. The sockets cannot be broken, so no arena can be taken apart to
 * get a key back.
 */
public class KeySocketBlock extends Block {
    public static final BooleanProperty FILLED = BooleanProperty.create("filled");

    private static final VoxelShape SHAPE = Block.box(1, 0, 1, 15, 11, 15);

    public KeySocketBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FILLED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FILLED);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP);
    }

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        ItemStack held = player.getItemInHand(hand);
        if (!state.getValue(FILLED)) {
            if (!held.is(com.explorationremix.registry.ERItems.CHOIR_KEY.get())) {
                ChoirSummon.sayWhatIsWanted(level, player);
                return InteractionResult.sidedSuccess(level.isClientSide);
            }
            if (!level.isClientSide) {
                level.setBlock(pos, state.setValue(FILLED, true), 3);
                if (!player.isCreative()) held.shrink(1);
                ChoirSummon.keySet(level, pos, player);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        // every socket filled already: a lost fight leaves them so, and using one calls the Choir again
        if (!level.isClientSide) ChoirSummon.relight(level, pos, player);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (!state.getValue(FILLED) || random.nextInt(4) != 0) return;
        int c = ChoirLook.GLOW;
        level.addParticle(ERParticles.SPORE.get(), pos.getX() + 0.3 + random.nextDouble() * 0.4,
                pos.getY() + 0.7, pos.getZ() + 0.3 + random.nextDouble() * 0.4,
                ((c >> 16) & 255) / 255.0, ((c >> 8) & 255) / 255.0, (c & 255) / 255.0);
    }
}
