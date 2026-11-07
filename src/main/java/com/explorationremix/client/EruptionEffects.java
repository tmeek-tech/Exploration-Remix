package com.explorationremix.client;

import com.explorationremix.world.lava.LavaNet;

/**
 * An eruption of the Caldera on this client: smoke, the fountain, the plume and the sounds (0.9.0). Reached only from
 * {@code LavaNet}'s handlers through {@code DistExecutor}, so a dedicated server never loads it. For now it takes the
 * messages and plays nothing; the eruptions' client fills it in.
 */
public final class EruptionEffects {
    private EruptionEffects() {}

    /** An eruption starts, is held or released, or ends ({@link LavaNet.Eruption#END}), on the main thread. */
    public static void accept(LavaNet.Eruption e) {}
}
