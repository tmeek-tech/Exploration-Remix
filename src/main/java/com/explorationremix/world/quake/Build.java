package com.explorationremix.world.quake;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.block.GlowBlock;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * {@code /remix quake build}: a player's tower for the in-pack checks (0.9.0, spec 4.6). Every few ticks (a player's
 * towering pace by default) one cobblestone goes on the column's current top, placed by Forge's fake player with
 * {@code ItemStack.useOn} on the top face, so the placement event fires as for a player's own block (the climb answers
 * and {@code quakes.toppleAtOnce} see it); with a rider, that player is first moved up to stand a block higher and the
 * block goes in under their feet. It stops after its height or when a block cannot be placed, then waits ten seconds
 * (the tower may still be coming down under the rider) and logs and tells the source how it went, heights counted over
 * the block under the build's position.
 */
final class Build {
    /** How long a build waits after its last block before it reports, in ticks. */
    static final int SETTLE = 200;
    /** How far up from the build's position its column's top is looked for. */
    private static final int REACH = 256;

    final BlockPos pos;
    final int height, every;
    @Nullable final UUID rider;
    final CommandSourceStack source;
    int placed, maxUp, firstCreak = Integer.MIN_VALUE;
    long next, endAt = -1;

    private static final List<Build> BUILDS = new ArrayList<>();

    private Build(BlockPos pos, int height, int every, @Nullable UUID rider, CommandSourceStack source, long now) {
        this.pos = pos;
        this.height = height;
        this.every = every;
        this.rider = rider;
        this.source = source;
        this.next = now;
    }

    /** Starts a build; false when there is no room over its position (no open block within reach, or not loaded). */
    static boolean start(ServerLevel level, BlockPos pos, int height, int every, @Nullable ServerPlayer rider, CommandSourceStack source) {
        if (top(level, pos) == null) return false;
        BUILDS.add(new Build(pos, height, every, rider == null ? null : rider.getUUID(), source, level.getGameTime()));
        return true;
    }

    /** Every overworld tick the quakes run. */
    static void tick(ServerLevel level, long now) {
        for (Iterator<Build> it = BUILDS.iterator(); it.hasNext(); ) {
            Build b = it.next();
            if (!b.step(level, now)) it.remove();
        }
    }

    /** Every build stops at once, unreported (the quakes are turned off, the server stops). */
    static void clear() { BUILDS.clear(); }

    /** A creak came for the column (x, z): a build there notes how high its rider was at the first one. */
    static void creaked(ServerLevel level, int x, int z) {
        for (Build b : BUILDS) {
            if (b.pos.getX() != x || b.pos.getZ() != z || b.firstCreak != Integer.MIN_VALUE) continue;
            b.firstCreak = b.up(level);
        }
    }

    /** Returns false once the build has reported. */
    private boolean step(ServerLevel level, long now) {
        int up = up(level);
        if (up != Integer.MIN_VALUE) maxUp = Math.max(maxUp, up);
        if (endAt < 0 && now >= next) {
            next = now + every;
            if (!place(level)) endAt = now + SETTLE;
            else if (++placed >= height) endAt = now + SETTLE;
        }
        if (endAt < 0 || now < endAt) return true;
        report(level);
        return false;
    }

    /** Puts one cobblestone on the column's top (under the rider, moved up a block first). */
    private boolean place(ServerLevel level) {
        BlockPos at = top(level, pos);
        if (at == null) return false;
        ServerPlayer r = rider(level);
        if (r != null) {
            r.teleportTo(at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5);
            r.resetFallDistance();
        }
        FakePlayer fake = FakePlayerFactory.getMinecraft(level);
        ItemStack stack = new ItemStack(Items.COBBLESTONE, 64);
        fake.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockPos against = at.below();
        BlockHitResult hit = new BlockHitResult(new Vec3(against.getX() + 0.5, against.getY() + 1, against.getZ() + 0.5), Direction.UP, against, false);
        InteractionResult res = stack.useOn(new UseOnContext(fake, InteractionHand.MAIN_HAND, hit));
        fake.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        BlockState now = Topple.stateAt(level, at);
        return res.consumesAction() && now != null && now.is(Blocks.COBBLESTONE);
    }

    /** The first open block (air or the glow) at or over {@code pos} in its column, or null (none within reach, or not loaded). */
    @Nullable
    private static BlockPos top(ServerLevel level, BlockPos pos) {
        BlockPos.MutableBlockPos m = pos.mutable();
        for (int k = 0; k < REACH && m.getY() < level.getMaxBuildHeight(); k++, m.move(Direction.UP)) {
            BlockState s = Topple.stateAt(level, m);
            if (s == null) return null;
            if (GlowBlock.open(s)) return m.immutable();
        }
        return null;
    }

    @Nullable
    private ServerPlayer rider(ServerLevel level) {
        if (rider == null) return null;
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(rider);
        return p != null && p.level() == level && !p.isRemoved() ? p : null;
    }

    /** How many blocks over the block under the build's position the rider stands, or MIN_VALUE without one. */
    private int up(ServerLevel level) {
        ServerPlayer r = rider(level);
        return r == null ? Integer.MIN_VALUE : (int) Math.floor(r.getY() + 1e-3) - pos.getY();
    }

    private void report(ServerLevel level) {
        String msg;
        if (rider == null) {
            BlockPos at = top(level, pos);
            msg = String.format(Locale.ROOT, "The build at %d %d %d ended: %d blocks placed; the column now stands %s blocks high.",
                    pos.getX(), pos.getY(), pos.getZ(), placed, at == null ? "?" : String.valueOf(at.getY() - pos.getY()));
        } else {
            int up = up(level);
            msg = String.format(Locale.ROOT, "The build at %d %d %d ended: %d blocks placed; the rider was at most %d blocks up and is now %s up (%s).",
                    pos.getX(), pos.getY(), pos.getZ(), placed, maxUp, up == Integer.MIN_VALUE ? "not here" : String.valueOf(up),
                    firstCreak == Integer.MIN_VALUE ? "no creak" : "first creak at " + firstCreak);
        }
        ExplorationRemix.LOGGER.info(msg);
        try {
            source.sendSuccess(() -> Component.literal(msg), false);
        } catch (RuntimeException e) {
            // the source may have gone (a player who logged out): the log has it
        }
    }
}
