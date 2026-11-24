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
 * turn and what runs a little later, which weapon has been in each hand since when (the swap delay), their dashes, a
 * Reel pulling them, the throws of their own blasts, a crossbow shot about to fire, On hit's count, a Lantern's light,
 * and whether the weapon in hand has its second phase awake. Never saved.
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
    /** The cast key is down: the runed sword in hand casts each time it is ready. */
    boolean castHeld;

    final List<Dashes.Dash> dashes = new ArrayList<>();
    /** Dashes since they last stood on the ground (or swam): one, or two with Dash's second phase. */
    int airDashes;

    /** A Reel pulling them to a wall, if one is. */
    @Nullable
    Grapples.Grapple pull;
    /** Pulls to a wall since they last stood on the ground (or swam): one, or two with Reel's second phase. */
    int airPulls;
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

    InteractionHand crossbowHand;
    long crossbowTick = -1;
    /** The crossbow shot this tick has cast already (a multishot's other arrows fly plain). */
    boolean crossbowCast;

    /** The last tick this player was told a cast couldn't go (sputter, too many out), so they're told twice a second. */
    long toldAt = Long.MIN_VALUE / 4;

    /** Something a rune does a little later (an echo, an aftershock, a wave's next stretch): at server tick {@code at}. */
    record Later(long at, Runnable run) {}

    /** What runs later, in the order it was set; at most {@link #MAX_LATER} wait at once. */
    final List<Later> later = new ArrayList<>();
    static final int MAX_LATER = 128;

    /** On hit: full hits on foes counted per weapon (by its id), every third casts for free. */
    final Map<Integer, Integer> hits = new HashMap<>();

    /** Lantern: the light that goes with them, while it stands. */
    @Nullable
    net.minecraft.core.GlobalPos lantern;

    /** Whether this player has been told their old rune rows were set into sockets (once a session). */
    boolean toldConverted;

    /** The weapon in hand when its second phase was last looked at (its id and active rune), and whether it was awake. */
    int phaseGear;
    @Nullable
    com.explorationremix.rune.engine.RuneId phaseRune;
    boolean phaseAwake;

    Hand hand(InteractionHand h) { return h == InteractionHand.MAIN_HAND ? main : off; }
}
