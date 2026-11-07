package com.explorationremix.rune.cast;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One player's casting on the server: what they have out (bolts and fields, for the limit), payloads waiting their
 * turn, which gear has been in each hand since when (the swap delay, a bow's regeneration), their dashes and the
 * throws of their own blasts and Reels, their last sword swing (a full-strength hit charges the sword), and a crossbow
 * shot about to fire. Never saved.
 */
final class Caster {
    /** A hand's gear: its id (0 for none), the inventory slot it is in, and since when. */
    static final class Hand {
        int id;
        int slot = -1;
        long since;
    }

    final Set<Entity> carriers = new HashSet<>();
    final ReleaseQueue<Executor.Pending> queue = new ReleaseQueue<>();

    final Hand main = new Hand(), off = new Hand();

    final List<Dashes.Dash> dashes = new ArrayList<>();
    boolean airDashUsed;

    /** Their own blasts: one throw a tick, one while off the ground. */
    long blastTick = -1;
    boolean airBlastUsed;
    /** Until this tick a throw of their own (a Reel to a wall, a blast) can't hurt them when they land. */
    long safeUntil = -1;

    int swingTarget = -1;
    long swingTick = -1;
    boolean swingFull;

    InteractionHand crossbowHand;
    long crossbowTick = -1;

    /** The last tick this player was told a cast couldn't go (sputter, too many out), so they're told twice a second. */
    long toldAt = Long.MIN_VALUE / 4;

    Hand hand(InteractionHand h) { return h == InteractionHand.MAIN_HAND ? main : off; }
}
