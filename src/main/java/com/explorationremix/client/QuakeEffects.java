package com.explorationremix.client;

import com.explorationremix.world.lava.LavaNet;

/**
 * A quake in the Basalt Galleries on this client: the shake, the sound and the dust, and the warnings, landings and
 * creaks of rock coming down (0.9.0). Reached only from {@code LavaNet}'s handlers through {@code DistExecutor}, so a
 * dedicated server never loads it. For now it takes the messages and plays nothing; the quakes' client fills it in.
 */
public final class QuakeEffects {
    private QuakeEffects() {}

    /** A quake starts, goes on, is held or released, or ends ({@link LavaNet.Quake#END}), on the main thread. */
    public static void accept(LavaNet.Quake q) {}

    /** A block of rock is about to come down, stays after all, shatters on someone, or a build creaks (on the main thread). */
    public static void debris(LavaNet.Debris d) {}

    /** The quake's share of the view's shake now, in degrees (LavaWarnings adds it to its own). */
    public static float tremor() { return 0; }
}
