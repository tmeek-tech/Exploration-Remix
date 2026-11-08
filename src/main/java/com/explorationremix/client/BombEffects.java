package com.explorationremix.client;

import com.explorationremix.entity.LavaBomb;

/**
 * A lava bomb on this client (0.9.0, spec 5.5): its trail in flight, and what its burst and fizzle look and sound like
 * (entity events {@link LavaBomb#BURST} and {@link LavaBomb#FIZZLE}). Client only: {@link LavaBomb} reaches it only
 * through {@code DistExecutor}, so a dedicated server never loads it. For now each takes the bomb and plays nothing; the
 * eruptions' client fills them in.
 */
public final class BombEffects {
    private BombEffects() {}

    /** Every client tick of a bomb in flight, before it moves: its trail. */
    public static void tick(LavaBomb bomb) {}

    /** The bomb burst (entity event 3), where the client has it. */
    public static void burst(LavaBomb bomb) {}

    /** The bomb fizzled in a fluid or ran out its time (entity event 4), where the client has it. */
    public static void fizzle(LavaBomb bomb) {}
}
