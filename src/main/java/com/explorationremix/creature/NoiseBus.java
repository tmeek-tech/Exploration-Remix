package com.explorationremix.creature;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Noises that creatures can hear: fights, blasts, lures, running. Hunters subscribe here; sound travels through cave
 * air, so a loud noise behind a wall is quieter than the same noise down an open tunnel (see SoundReach).
 */
public final class NoiseBus {
    public record Noise(ServerLevel level, Vec3 pos, double loudness, @Nullable Entity source, boolean lure) {}

    public interface Listener { void onNoise(Noise noise); }

    private static final List<Listener> LISTENERS = new ArrayList<>();
    private NoiseBus() {}

    public static void subscribe(Listener l) { LISTENERS.add(l); }

    /** Loudness is roughly the radius in blocks at which a hunter notices it in open air. */
    public static void emit(ServerLevel level, Vec3 pos, double loudness, @Nullable Entity source) { emit(level, pos, loudness, source, false); }

    public static void emit(ServerLevel level, Vec3 pos, double loudness, @Nullable Entity source, boolean lure) {
        if (loudness <= 0) return;
        Noise n = new Noise(level, pos, loudness, source, lure);
        for (Listener l : LISTENERS) l.onNoise(n);
    }
}
