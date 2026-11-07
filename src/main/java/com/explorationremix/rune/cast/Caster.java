package com.explorationremix.rune.cast;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One player's casting on the server: what they have out (bolts and fields, for the limit), payloads waiting their
 * turn, which gear has been in each hand since when (the swap delay, a bow's regeneration), their dashes, a Reel
 * pulling them, the throws of their own blasts, their last sword swing (a full-strength hit charges the sword), and a
 * crossbow shot about to fire. Never saved.
 */
final class Caster {
    /** A hand's gear: its id (0 for none), the inventory slot it is in, and since when. */
    static final class Hand {
        int id;
        int slot = -1;
        long since;
    }

    final Set<Entity> carriers = new HashSet<>();
    /** The server tick each carrier was first seen on ground that has stopped running. */
    final Map<Entity, Long> stillSince = new HashMap<>();
    final ReleaseQueue<Executor.Pending> queue = new ReleaseQueue<>();

    final Hand main = new Hand(), off = new Hand();

    final List<Dashes.Dash> dashes = new ArrayList<>();
    boolean airDashUsed;

    /** A Reel pulling them to a wall, if one is. */
    @Nullable
    Grapples.Grapple pull;
    /** One pull to a wall each time they touch the ground (or water): set by a pull, cleared once they stand again. */
    boolean airPullUsed;
    long pullTick = -1;
    /** The last pull's number, so the player's game and the server agree on which pull a word is about. */
    int pullIds;
    /** A throw (a dash, a blast) set this tick: kept until it is sent, so the server's own move doesn't slow it. */
    @Nullable
    Vec3 launch;

    /** Their own blasts: one throw a tick, one while off the ground. */
    long blastTick = -1;
    boolean airBlastUsed;
    /** Until this tick, and while no lower than {@code safeAboveY}, landing from a blast of their own can't hurt them. */
    long safeUntil = -1;
    double safeAboveY = Double.NEGATIVE_INFINITY;

    int swingTarget = -1;
    long swingTick = -1;
    boolean swingFull;

    InteractionHand crossbowHand;
    long crossbowTick = -1;

    /** The last tick this player was told a cast couldn't go (sputter, too many out), so they're told twice a second. */
    long toldAt = Long.MIN_VALUE / 4;

    Hand hand(InteractionHand h) { return h == InteractionHand.MAIN_HAND ? main : off; }
}
