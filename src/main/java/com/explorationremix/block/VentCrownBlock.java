package com.explorationremix.block;

import com.explorationremix.registry.ERSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The glowing crown on a live vent's top (world.terrain.GardensDress#glow), a {@link NeonBlock} that simmers (0.20.9,
 * Taran's card pick for the Deep Gardens: "Hushed, vents breathe up close"). With a player within {@link #HEARD}
 * blocks, now and then a crown plays the vent's soft hiss and simmer ({@code vent.simmer}, four seconds, mono, so it
 * comes from the crown), on the blocks channel, so Sound Physics echoes it and muffles it behind rock.
 * <p>
 * One simmer starts at most every 2.25 to 3.5 seconds in the whole world, so a vent's many crowns make one steady
 * simmer that shifts round its rim, never a roar, and two vents side by side sound no louder than one. Dead vents have
 * no crowns, so they are silent, and a crown with something over it is still.
 */
public class VentCrownBlock extends NeonBlock {
    /** How near a player must be for a crown to simmer, in blocks: where the sound has faded out (sounds.json). */
    static final double HEARD = 10;
    /** The game time from which the next simmer may start. */
    private static long next;

    public VentCrownBlock(Properties properties) { super(properties); }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (random.nextInt(6) != 0) return;
        long now = level.getGameTime();
        if (next - now > 100) next = now; // a new world, whose clock is behind the last one's
        if (now < next || !level.getBlockState(pos.above()).isAir()) return;
        double x = pos.getX() + 0.5, y = pos.getY() + 1.0, z = pos.getZ() + 0.5;
        if (!near(level, x, y, z)) return;
        next = now + 45 + random.nextInt(26);
        level.playLocalSound(x, y, z, ERSounds.VENT_SIMMER.get(), SoundSource.BLOCKS, 1.0F, 0.9F + 0.2F * random.nextFloat(), false);
    }

    /** A player, spectators too, within {@link #HEARD} blocks of the point. */
    private static boolean near(Level level, double x, double y, double z) {
        for (Player p : level.players()) if (p.distanceToSqr(x, y, z) < HEARD * HEARD) return true;
        return false;
    }
}
