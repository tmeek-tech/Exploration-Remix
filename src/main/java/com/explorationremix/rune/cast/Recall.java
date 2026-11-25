package com.explorationremix.rune.cast;

import com.explorationremix.mixin.accessor.AbstractArrowAccessor;
import com.explorationremix.rune.RuneGear;
import com.explorationremix.rune.effect.RuneFx;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Recall, a bow's or crossbow's passive: its arrows come back. One that sticks in rock or earth waits a moment, then
 * flies back to its player through whatever is in the way, as a loyal trident does, trailing light, and drops into
 * their quiver as it reaches them; one that strikes a creature is back in the quiver at once (unless it flies on
 * through it). Only arrows that could be picked up come back: not a crossbow's extra multishot arrows, nor an Infinity
 * bow's, nor a creative player's. Server side.
 */
final class Recall {
    /** Ticks an arrow stays stuck before it comes back. */
    static final int WAIT = 10;
    /** Ticks an arrow may fly without landing before it is let go; ticks it may take to come back before it drops at their feet. */
    static final int FLYING_MOST = 400, BACK_MOST = 120;
    /** Past this far from its player, an arrow stays where it is. */
    static final double REACH = 96.0;
    /** How near it comes before it drops into the quiver. */
    static final double IN_HAND = 1.3;

    private static final class Back {
        final AbstractArrow arrow;
        final UUID owner;
        int age, stuck, coming;

        Back(AbstractArrow arrow, UUID owner) {
            this.arrow = arrow;
            this.owner = owner;
        }
    }

    /** By entity id: every arrow on the server has its own. */
    private static final Map<Integer, Back> ARROWS = new HashMap<>();

    private Recall() {}

    static void track(AbstractArrow arrow, ServerPlayer p) { ARROWS.put(arrow.getId(), new Back(arrow, p.getUUID())); }

    /** The arrow's own hit on a creature landed: unless it flies on through (Pierce), it is back in the quiver now. */
    static void struck(ServerPlayer p, AbstractArrow arrow) {
        Back b = ARROWS.get(arrow.getId());
        if (b == null || b.arrow != arrow || arrow.getPierceLevel() > 0) return;
        ARROWS.remove(arrow.getId());
        ItemStack item = ((AbstractArrowAccessor) arrow).er$pickupItem();
        if (item.isEmpty()) return;
        RuneGear.give(p, item);
        RuneFx.play(p.serverLevel(), p.position().add(0, 1, 0), SoundEvents.AMETHYST_BLOCK_CHIME, 0.35F, 1.9F);
    }

    /** Each server tick's end: stuck arrows wait their moment, coming ones fly on toward their player. */
    static void tick(MinecraftServer server) {
        if (ARROWS.isEmpty()) return;
        for (Iterator<Back> it = ARROWS.values().iterator(); it.hasNext(); ) {
            Back b = it.next();
            AbstractArrow a = b.arrow;
            ServerPlayer p = server.getPlayerList().getPlayer(b.owner);
            if (a.isRemoved() || p == null || !p.isAlive() || p.level() != a.level() || a.distanceToSqr(p) > REACH * REACH) {
                // its player is gone or far off: it stays (or falls) where it is
                if (!a.isRemoved() && b.coming > 0) a.setNoPhysics(false);
                it.remove();
                continue;
            }
            b.age++;
            if (b.coming == 0) {
                if (((AbstractArrowAccessor) a).er$inGround()) {
                    if (++b.stuck >= WAIT) {
                        b.coming = 1;
                        a.setNoPhysics(true);
                        RuneFx.play((ServerLevel) a.level(), a.position(), SoundEvents.AMETHYST_BLOCK_CHIME, 0.5F, 1.6F);
                    }
                } else if (b.age > FLYING_MOST) {
                    it.remove();
                }
                continue;
            }
            b.coming++;
            Vec3 to = p.getEyePosition().subtract(0, 0.5, 0).subtract(a.position());
            double d = to.length();
            if (d < IN_HAND || b.coming > BACK_MOST) {
                ItemStack item = ((AbstractArrowAccessor) a).er$pickupItem();
                if (!item.isEmpty()) {
                    if (p.getInventory().add(item)) p.take(a, 1);
                    else p.drop(item, false);
                }
                a.discard();
                it.remove();
                continue;
            }
            // faster as it comes, as a trident's does
            double speed = Math.min(1.8, 0.35 + 0.06 * b.coming);
            a.setDeltaMovement(to.scale(speed / d));
            a.hasImpulse = true;
            if (b.coming % 2 == 0) ((ServerLevel) a.level()).sendParticles(ParticleTypes.END_ROD, a.getX(), a.getY(), a.getZ(), 1, 0.02, 0.02, 0.02, 0.0);
        }
    }

    /** The player left: their arrows stay where they are (one on its way falls there). */
    static void forget(UUID owner) {
        for (Iterator<Back> it = ARROWS.values().iterator(); it.hasNext(); ) {
            Back b = it.next();
            if (!b.owner.equals(owner)) continue;
            if (!b.arrow.isRemoved() && b.coming > 0) b.arrow.setNoPhysics(false);
            it.remove();
        }
    }

    static void clear() { ARROWS.clear(); }
}
