package com.explorationremix.client;

import com.explorationremix.world.lava.LavaNet;

/** A quake in the Basalt Galleries on this client: the shake, the rumble and the dust (0.5.1; filled in by the quake build). */
public final class QuakeEffects {
    private QuakeEffects() {}

    /** A quake has started (on the main thread). */
    public static void accept(LavaNet.Quake q) {}

    /** The quake's share of the view's shake now, in degrees (LavaWarnings adds it to its own). */
    public static float tremor() { return 0; }
}
