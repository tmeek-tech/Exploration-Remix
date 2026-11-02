package com.explorationremix.client;

import com.explorationremix.world.lava.LavaNet;

/** An eruption of the Caldera on this client: smoke, the fountain, the sounds (0.5.1; filled in by the eruption build). */
public final class EruptionEffects {
    private EruptionEffects() {}

    /** An eruption has started (on the main thread). */
    public static void accept(LavaNet.Eruption e) {}
}
