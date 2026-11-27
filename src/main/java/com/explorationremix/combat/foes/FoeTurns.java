package com.explorationremix.combat.foes;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Whose turn it is to strike each quarry (the card of 27 Nov, built at its recommended answer while it is open: a group
 * circles you and takes turns, two at most winding up at once). A mob takes a turn before its wind-up and gives it back
 * once it has recovered from its blow; a turn not used within {@link #HOLD} ticks lapses (a mob that can't get
 * through). The mobs waiting stand round the quarry in the order they came ({@link #rank}): the first behind it, the
 * next on its flanks. Server side, never saved.
 */
final class FoeTurns {
    /** A turn lapses this long after it was last held (ticks). */
    static final int HOLD = 80;
    /** A mob that hasn't said it is waiting for this long (ticks) is no longer counted as waiting. */
    static final int WAIT = 30;

    private static final class Q {
        final List<Mob> strikers = new ArrayList<>(4);
        final List<Long> held = new ArrayList<>(4);
        final List<Mob> waiting = new ArrayList<>(8);
        final List<Long> waited = new ArrayList<>(8);
    }

    private static final Map<UUID, Q> MAP = new HashMap<>();

    private FoeTurns() {}

    private static void prune(Q q, long now) {
        for (int i = q.strikers.size() - 1; i >= 0; i--) {
            Mob m = q.strikers.get(i);
            if (!m.isAlive() || m.isRemoved() || now - q.held.get(i) > HOLD) {
                q.strikers.remove(i);
                q.held.remove(i);
            }
        }
        for (int i = q.waiting.size() - 1; i >= 0; i--) {
            Mob m = q.waiting.get(i);
            if (!m.isAlive() || m.isRemoved() || now - q.waited.get(i) > WAIT || q.strikers.contains(m)) {
                q.waiting.remove(i);
                q.waited.remove(i);
            }
        }
    }

    /**
     * Whether {@code mob} may strike {@code quarry} now: it holds a turn already, or one is free ({@code most} at a time; 0
     * or less: any number). Holding one keeps it from lapsing.
     */
    static boolean take(Mob mob, LivingEntity quarry, int most) {
        if (most <= 0) return true;
        long now = mob.level().getGameTime();
        Q q = MAP.computeIfAbsent(quarry.getUUID(), u -> new Q());
        prune(q, now);
        int i = q.strikers.indexOf(mob);
        if (i >= 0) {
            q.held.set(i, now);
            return true;
        }
        if (q.strikers.size() >= most) return false;
        q.strikers.add(mob);
        q.held.add(now);
        int w = q.waiting.indexOf(mob);
        if (w >= 0) {
            q.waiting.remove(w);
            q.waited.remove(w);
        }
        return true;
    }

    /** {@code mob} is done with its turn at {@code quarry} (or gave up on it). */
    static void give(Mob mob, LivingEntity quarry) {
        Q q = MAP.get(quarry.getUUID());
        if (q == null) return;
        int i = q.strikers.indexOf(mob);
        if (i >= 0) {
            q.strikers.remove(i);
            q.held.remove(i);
        }
        if (q.strikers.isEmpty() && q.waiting.isEmpty()) MAP.remove(quarry.getUUID());
    }

    /** Whether every turn at {@code quarry} is taken by others. */
    static boolean full(Mob mob, LivingEntity quarry, int most) {
        if (most <= 0) return false;
        Q q = MAP.get(quarry.getUUID());
        if (q == null) return false;
        prune(q, mob.level().getGameTime());
        return q.strikers.size() >= most && !q.strikers.contains(mob);
    }

    /** {@code mob} waits its turn at {@code quarry}: its place among those waiting (0 first), held while it keeps saying so. */
    static int rank(Mob mob, LivingEntity quarry) {
        long now = mob.level().getGameTime();
        Q q = MAP.computeIfAbsent(quarry.getUUID(), u -> new Q());
        prune(q, now);
        int i = q.waiting.indexOf(mob);
        if (i < 0) {
            q.waiting.add(mob);
            q.waited.add(now);
            return q.waiting.size() - 1;
        }
        q.waited.set(i, now);
        return i;
    }

    /** How many hold a turn at {@code quarry} now (for the debug command): lapsed turns and gone mobs left out. */
    static int strikers(LivingEntity quarry) {
        Q q = MAP.get(quarry.getUUID());
        if (q == null) return 0;
        prune(q, quarry.level().getGameTime());
        return q.strikers.size();
    }

    /** How many wait their turn at {@code quarry} now (for the debug command). */
    static int waiting(LivingEntity quarry) {
        Q q = MAP.get(quarry.getUUID());
        if (q == null) return 0;
        prune(q, quarry.level().getGameTime());
        return q.waiting.size();
    }

    static void clear() { MAP.clear(); }
}
