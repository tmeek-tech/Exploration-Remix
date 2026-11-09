package com.explorationremix.client;

import java.util.ArrayList;
import java.util.List;

/**
 * The view's shake, summed from its sources (0.9.0), kept apart from Minecraft so a test can follow it, as
 * {@link SmokePuff} is. Each source has its own waveform, so a quake never feels like the lava's warning:
 * <ul>
 *     <li><b>The lava</b> ({@link #lava}): LavaWarnings' fine buzz of 2.6 to 10.9 a second, a fraction of a degree with
 *     bumps of up to about one, exactly as 0.8.0 drew it.</li>
 *     <li><b>A big quake</b> ({@link #big}): a hard jolt at its onset (a downward kick of the view, half as much roll),
 *     then a slow rolling sway of 0.6 to 1.3 a second with the roll as large as the rest (the ground rocking), a light
 *     buzz on top, and a thump of the field of view at the onset. About 2.5 degrees of roll at full strength.</li>
 *     <li><b>A tremor or a climb answer</b> ({@link #tremor}): a short shiver of 10 to 12 a second, no sway, no jolt.</li>
 *     <li><b>An eruption's jolt</b> ({@link Jolts}): a kick when its boom arrives, timed in client ticks.</li>
 * </ul>
 * Angles are in degrees, added to the camera's; time is in ticks (the game time and the partial tick for quakes, the
 * lava's own clock for the lava). {@link CameraShake} adds the sum to the camera after the gates of {@link #gate}.
 */
final class Shake {
    /** The sum so far: yaw, pitch and roll in degrees, and the change of the field of view in degrees. */
    double yaw, pitch, roll, fov;

    /** A big quake's sway rises over this many ticks and falls over the last {@link #BIG_FALL}. */
    static final int BIG_RISE = 20, BIG_FALL = 50;
    /** A big quake's onset jolt keeps this share of itself each tick (a half-life of about 5 ticks). */
    static final double BIG_JOLT_DECAY = 0.88;
    /** How long the field of view's thump at a big quake's onset takes to die away, ticks. */
    static final int FOV_TICKS = 8;
    /** A tremor's shiver rises over this many ticks and dies away over its last {@link #TREMOR_DECAY}. */
    static final int TREMOR_RISE = 3, TREMOR_DECAY = 35;
    /** An eruption's jolt keeps this share of itself each tick. */
    static final double ERUPTION_JOLT_DECAY = 0.85;
    /** How strongly a tremor that toppled nothing near the player is felt when it is felt at all. */
    static final double FAINT = 0.35;
    /** Over how many rows above the Galleries' top a quake fades out. */
    static final int FADE_ROWS = 48;

    /** The client's {@code tremorEffects} choices, in the order of ERClientConfig.TremorEffects. */
    static final int ALL = 0, WHERE_BLOCKS_FALL = 1, NONE = 2;

    void clear() { yaw = pitch = roll = fov = 0; }

    // ================================================================================================ the sources

    /**
     * Adds the lava's shake of amplitude {@code a} degrees at {@code t} on the lava's own clock (LavaWarnings' client
     * ticks and the partial tick): a few incommensurate waves of 2 to 10 a second, a rumble's tremor rather than a sway.
     */
    void lava(double a, double t) {
        if (a == 0) return;
        float y = (float) (Math.sin(t * 0.83 + 1.3) * 0.5 + Math.sin(t * 1.91 + 0.4) * 0.35 + Math.sin(t * 3.07 + 2.2) * 0.15);
        float p = (float) (Math.sin(t * 0.97 + 2.9) * 0.5 + Math.sin(t * 2.23 + 1.7) * 0.35 + Math.sin(t * 3.41 + 0.9) * 0.15);
        float r = (float) (Math.sin(t * 1.13 + 0.6) * 0.6 + Math.sin(t * 2.71 + 2.4) * 0.4);
        yaw += (float) a * y;
        pitch += (float) a * p;
        roll += 0.6F * (float) a * r;
    }

    /**
     * Adds a quake {@code t} ticks into it (of {@code duration} ticks, strength {@code s} from 0 to 1, its phases drawn
     * from {@code seed}), for a player whose height gives {@code h} ({@link #height}) and who feels it by {@code f}
     * ({@link #tremorShare}; 1 for a big quake). A quake held by command adds nothing: the client plays its moment
     * steadily, so a picture is not shaken.
     */
    void quake(boolean big, double t, int duration, double s, double h, double f, int seed, boolean held) {
        if (held) return;
        if (big) big(t, duration, s, h * f, seed);
        else tremor(t, duration, s, h, f, seed);
    }

    /** Adds a big quake {@code t} ticks into it, of {@code duration} ticks at strength {@code s}, at height factor {@code h}. */
    void big(double t, int duration, double s, double h, int seed) {
        if (t < 0 || t >= duration || h <= 0) return;
        // the onset: a downward kick of the view, half as much roll, gone in a second or so
        double jolt = (0.8 + 1.2 * s) * h * Math.pow(BIG_JOLT_DECAY, t);
        pitch += jolt;
        roll += 0.5 * jolt;
        // the sway: the ground rocking, slow and large, the roll as large as yaw and pitch together
        double env = bigEnvelope(t, duration);
        double a = (1.0 + 1.5 * s) * h * env;
        if (a > 0) {
            yaw += 0.5 * a * (0.7 * Math.sin(0.20 * t + phase(seed, 1)) + 0.3 * Math.sin(0.33 * t + phase(seed, 2)));
            roll += a * (0.65 * Math.sin(0.24 * t + phase(seed, 3)) + 0.35 * Math.sin(0.41 * t + phase(seed, 4)));
            pitch += 0.35 * a * Math.sin(0.29 * t + phase(seed, 5));
            // and a light buzz of grinding rock on it
            pitch += 0.12 * s * h * env * Math.sin(3.3 * t + phase(seed, 6));
        }
        if (t < FOV_TICKS) fov -= (1 + 2 * s) * h * (1 - t / FOV_TICKS);
    }

    /**
     * Adds a tremor or a climb answer {@code t} ticks into it, of {@code duration} ticks at strength {@code s}, at height
     * factor {@code h}, felt by {@code f} (1 in full, {@link #FAINT} faintly): a fine shiver of pitch and roll.
     */
    void tremor(double t, int duration, double s, double h, double f, int seed) {
        double a = (0.10 + 0.4 * s) * h * f * tremorEnvelope(t, duration);
        if (a <= 0) return;
        pitch += a * Math.sin(3.1 * t + phase(seed, 7));
        roll += 0.6 * a * Math.sin(3.9 * t + phase(seed, 8));
    }

    /** Adds an eruption's jolt of {@code degrees}, {@code since} ticks after its boom arrived: a kick, half as much roll. */
    void jolt(double degrees, double since) {
        if (since < 0) return;
        double j = degrees * Math.pow(ERUPTION_JOLT_DECAY, since);
        pitch += j;
        roll += 0.5 * j;
    }

    // ================================================================================================ envelopes

    /** A big quake's sway at {@code t} ticks in, 0 to 1: a smooth rise over 20 ticks, a hold, a smooth fall over the last 50. */
    static double bigEnvelope(double t, int duration) {
        if (t < 0 || t >= duration) return 0;
        return smooth(t / BIG_RISE) * smooth((duration - t) / BIG_FALL);
    }

    /** A tremor's shiver at {@code t} ticks in, 0 to 1: up in 3 ticks, dying away over its last 35. */
    static double tremorEnvelope(double t, int duration) {
        if (t < 0 || t >= duration) return 0;
        return Math.min(1, t / TREMOR_RISE) * Math.min(1, (duration - t) / TREMOR_DECAY);
    }

    private static double smooth(double x) {
        if (x <= 0) return 0;
        if (x >= 1) return 1;
        return x * x * (3 - 2 * x);
    }

    // ================================================================================================ who feels what

    /**
     * How much of a quake a player at {@code y} feels from their height: in full inside the Galleries' rows from the
     * last Sync ({@code bottom <= y < top + 2}, as the lava's warning has it), fading to nothing over the
     * {@link #FADE_ROWS} rows above (the Wasteland's lowest caves feel it faintly), nothing under the floor.
     */
    static double height(double y, int bottom, int top) {
        if (y < bottom) return 0;
        double over = y - (top + 2);
        if (over < 0) return 1;
        return Math.max(0, 1 - over / FADE_ROWS);
    }

    /**
     * How a tremor or climb answer is felt by the client's {@code tremorEffects} ({@code mode}, {@link #ALL},
     * {@link #WHERE_BLOCKS_FALL} or {@link #NONE}): 1 in full, {@link #FAINT} faintly, 0 not at all. With the default,
     * one that toppled something within 32 blocks of the player ({@code felt}) is felt in full, and of the rest one in
     * three (by its seed, so every frame of one agrees) faintly.
     */
    static double tremorShare(int mode, boolean felt, int seed) {
        return switch (mode) {
            case ALL -> 1;
            case NONE -> 0;
            default -> felt ? 1 : seed % 3 == 0 ? FAINT : 0;
        };
    }

    /**
     * The share of the sum the camera gets: nothing with {@code screenShake} off or while the lava clock or a quake is
     * held (pictures stay still), else the client's {@code shakeStrength} times the game's Distortion Effects, each 0 to 1.
     */
    static double gate(boolean screenShake, double strength, double distortion, boolean frozen) {
        if (!screenShake || frozen) return 0;
        return clamp01(strength) * clamp01(distortion);
    }

    private static double clamp01(double v) { return v < 0 ? 0 : v > 1 ? 1 : v; }

    /** An eruption's boom (and its jolt) arrives this many ticks after the burst at {@code d} blocks: sound goes about 40 blocks a second. */
    static int boomDelay(double d) { return (int) Math.round(d / 2); }

    /** The jolt of an eruption's boom of strength {@code s} at {@code d} blocks, degrees: strong near, nothing past 128 blocks. */
    static double eruptionJolt(double s, double d) { return 1.2 * s * Math.max(0, 1 - d / 128); }

    /** The phase {@code k} of a quake drawn from its seed, 0 to 2 pi. */
    static double phase(int seed, int k) {
        long z = seed * 0x9E3779B97F4A7C15L + k * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z >>> 11) * 0x1.0p-53 * 2 * Math.PI;
    }

    // ================================================================================================ eruption jolts

    /**
     * The eruptions' jolts waiting to arrive or dying away, timed in client ticks (a far boom arrives later; a slow
     * client stays in step with its own boom). One client thread only.
     */
    static final class Jolts {
        /** Each jolt's arrival tick and degrees. */
        private final List<double[]> list = new ArrayList<>();

        /** A jolt of {@code degrees} arriving {@code delay} ticks after client tick {@code now}. */
        void add(long now, int delay, double degrees) {
            if (degrees <= 0) return;
            list.add(new double[]{now + Math.max(0, delay), degrees});
        }

        /** Adds every jolt that has arrived by {@code t} (client ticks and the partial tick) to {@code out}. */
        void sample(Shake out, double t) {
            for (double[] j : list) out.jolt(j[1], t - j[0]);
        }

        /** Forgets the jolts that have died away by client tick {@code now}. */
        void prune(long now) {
            list.removeIf(j -> now > j[0] && j[1] * Math.pow(ERUPTION_JOLT_DECAY, now - j[0]) < 1e-3);
        }

        int size() { return list.size(); }

        void clear() { list.clear(); }
    }
}
