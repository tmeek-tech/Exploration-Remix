package com.explorationremix.rune.cast;

import javax.annotation.Nullable;

/**
 * The pull on the player of this game, as the server last said: started by {@link com.explorationremix.rune.net.RuneNet.PullStart},
 * ended early by {@link com.explorationremix.rune.net.RuneNet.PullStop}, and steered by the client's own tick. Kept here,
 * with no client classes, so the packets can be read on either side; only the client game's main thread touches it.
 */
public final class LocalPull {
    @Nullable
    private static Pull.Steer steer;

    private LocalPull() {}

    @Nullable
    public static Pull.Steer current() { return steer; }

    public static void start(Pull.Steer s) { steer = s; }

    /** The server ended pull {@code id}; a newer one goes on. */
    public static void stop(int id) {
        if (steer != null && steer.id == id) steer = null;
    }

    /** This game ended it; the id to tell the server, or -1 if there was none. */
    public static int finish() {
        int id = steer == null ? -1 : steer.id;
        steer = null;
        return id;
    }
}
