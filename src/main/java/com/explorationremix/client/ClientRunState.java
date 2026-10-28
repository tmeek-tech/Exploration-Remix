package com.explorationremix.client;

import com.explorationremix.run.RunSummary;

/** What the client knows about its own run, for tooltips and the HUD. */
public final class ClientRunState {
    public static volatile long runId;
    public static volatile int layerIndex = -1;
    public static volatile RunSummary lastSummary;
    public static volatile long startedAt;
    private ClientRunState() {}

    public static boolean inRun() { return runId != 0; }
}
