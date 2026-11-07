package com.explorationremix.rune.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A block a rune makes, that undoes itself: a Barrier's glass wall, Frost's ice on still water, a Glow mote's light.
 * Placing one (by a rune, a command, a contraption putting it back, or a quake moving it) schedules its end, and the
 * scheduled tick is saved with the chunk, so it cleans itself up even if the chunk unloads first. It drops nothing,
 * pistons can't push it and Create can't carry it (tag {@code create:non_movable}).
 */
public class TempBlock extends Block {
    public enum Kind {
        /** 8 s of rune glass; back to air. */
        BARRIER(160),
        /** About 10 s of walkable ice on still water; back to water. */
        ICE(200),
        /** A minute of light where a Glow mote stopped; back to air. */
        LIGHT(1200);

        public final int life;

        Kind(int life) { this.life = life; }
    }

    private static final VoxelShape MOTE = Block.box(6.5, 6.5, 6.5, 9.5, 9.5, 9.5);

    public final Kind kind;

    public TempBlock(Kind kind, Properties props) {
        super(props);
        this.kind = kind;
    }

    public BlockState restored() { return kind == Kind.ICE ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState(); }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moving) {
        super.onPlace(state, level, pos, old, moving);
        if (!level.isClientSide && !old.is(this)) level.scheduleTick(pos, this, kind.life);
    }

    @Override
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        fade(level, pos);
    }

    /** Ends it now: glass shatters, ice melts, a light goes out. */
    public void fade(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.is(this)) return;
        level.setBlock(pos, restored(), Block.UPDATE_ALL);
        double x = pos.getX() + 0.5, y = pos.getY() + 0.5, z = pos.getZ() + 0.5;
        switch (kind) {
            case BARRIER -> {
                level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_BREAK, SoundSource.BLOCKS, 0.5F, 1.4F);
                level.sendParticles(ParticleTypes.END_ROD, x, y, z, 3, 0.3, 0.3, 0.3, 0.01);
            }
            case ICE -> {
                level.playSound(null, pos, SoundEvents.GLASS_BREAK, SoundSource.BLOCKS, 0.3F, 1.6F);
                level.sendParticles(ParticleTypes.SPLASH, x, y + 0.4, z, 4, 0.3, 0.1, 0.3, 0.0);
            }
            case LIGHT -> level.sendParticles(ParticleTypes.WAX_OFF, x, y, z, 2, 0.1, 0.1, 0.1, 0.0);
        }
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (kind == Kind.LIGHT && random.nextInt(4) == 0) {
            level.addParticle(ParticleTypes.END_ROD, pos.getX() + 0.5 + (random.nextDouble() - 0.5) * 0.3, pos.getY() + 0.5,
                    pos.getZ() + 0.5 + (random.nextDouble() - 0.5) * 0.3, 0, 0.005, 0);
        }
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return kind == Kind.LIGHT ? MOTE : Shapes.block();
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return kind == Kind.LIGHT ? Shapes.empty() : Shapes.block();
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) { return kind != Kind.ICE; }

    @Override
    public float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) { return 1.0F; }

    @Override
    public boolean skipRendering(BlockState state, BlockState next, net.minecraft.core.Direction side) {
        return next.is(this) || super.skipRendering(state, next, side);
    }
}
