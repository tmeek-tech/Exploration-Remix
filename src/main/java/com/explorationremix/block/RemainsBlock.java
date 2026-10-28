package com.explorationremix.block;

import com.explorationremix.item.AttunementBookItem;
import com.explorationremix.registry.ERItems;
import com.explorationremix.rune.AttunementBook;
import com.explorationremix.run.RunManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The remains of an earlier explorer: bones and a satchel. The satchel holds one attunement book for whoever is on
 * a run. Looted remains stay looted; the world remembers.
 */
public class RemainsBlock extends Block {
    public static final BooleanProperty LOOTED = BooleanProperty.create("looted");
    private static final VoxelShape SHAPE = Block.box(1, 0, 1, 15, 5, 15);

    public RemainsBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(LOOTED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(LOOTED); }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) { return SHAPE; }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos below = pos.below();
        return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
    }

    @Override
    public BlockState updateShape(BlockState state, Direction dir, BlockState neighbour, LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        if (dir == Direction.DOWN && !state.canSurvive(level, pos)) return Blocks.AIR.defaultBlockState();
        return super.updateShape(state, dir, neighbour, level, pos, neighbourPos);
    }

    /** Which family this satchel holds: fixed per position, so two players find the same book. */
    public static String attunementAt(BlockPos pos) {
        long h = Mth.getSeed(pos) * 0x9E3779B97F4A7C15L;
        return AttunementBook.ALL[(int) Math.floorMod(h >>> 17, (long) AttunementBook.ALL.length)];
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (state.getValue(LOOTED)) return InteractionResult.PASS;
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer p)) return InteractionResult.PASS;
        long run = RunManager.currentRun(p);
        if (run == 0L) {
            p.displayClientMessage(Component.translatable("run.explorationremix.remains_no_run").withStyle(ChatFormatting.GRAY), true);
            return InteractionResult.CONSUME;
        }
        ItemStack book = AttunementBookItem.of(ERItems.ATTUNEMENT_BOOK.get(), attunementAt(pos), run);
        if (!p.getInventory().add(book)) p.drop(book, false);
        level.setBlock(pos, state.setValue(LOOTED, true), 3);
        level.playSound(null, pos, SoundEvents.BONE_BLOCK_HIT, SoundSource.BLOCKS, 0.8F, 0.6F);
        level.playSound(null, pos, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 1.0F, 0.9F);
        if (level instanceof ServerLevel sl) sl.sendParticles(ParticleTypes.ENCHANT, pos.getX() + 0.5, pos.getY() + 0.6, pos.getZ() + 0.5, 24, 0.3, 0.2, 0.3, 0.4);
        p.displayClientMessage(Component.translatable("run.explorationremix.remains_found", book.getHoverName()).withStyle(ChatFormatting.AQUA), true);
        return InteractionResult.CONSUME;
    }
}
