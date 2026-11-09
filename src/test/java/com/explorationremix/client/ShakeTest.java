package com.explorationremix.client;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The view's shake (Shake): a big quake rocks slowly (its yaw and roll at 0.6 to 1.5 a second, the roll at least as
 * large as the yaw), a tremor shivers fast (8 to 13 a second) and faintly when it toppled nothing near, the lava's
 * warning is drawn exactly as 0.8.0 drew it, nothing shakes while a quake or the lava clock is held, the client's
 * strength and Distortion Effects scale it, and an eruption's jolt comes with its boom, round(d / 2) ticks after the
 * burst at d blocks.
 */
class ShakeTest {
    private static final int[] SEEDS = {1, 7, 12345, -99, 2026, Integer.MIN_VALUE, Integer.MAX_VALUE};

    @Test
    void aBigQuakeRocksSlowlyWithTheRollAsLargeAsTheYaw() {
        int duration = 1200;
        for (int seed : SEEDS) {
            for (double s : new double[]{0.5, 0.75, 1.0}) {
                // its hold, from after the onset's jolt has died away to before its fall: one sample a tick
                int n = 1150 - 60;
                double[] yaw = new double[n], roll = new double[n];
                for (int i = 0; i < n; i++) {
                    Shake sh = new Shake();
                    sh.big(60 + i, duration, s, 1, seed);
                    yaw[i] = sh.yaw;
                    roll[i] = sh.roll;
                }
                String at = "seed " + seed + ", strength " + s;
                assertTrue(bandShare(yaw, 20, 0.6, 1.5, true, 4) > 0.99, "yaw outside 0.6-1.5 Hz, " + at);
                assertTrue(bandShare(roll, 20, 0.6, 1.5, true, 4) > 0.99, "roll outside 0.6-1.5 Hz, " + at);
                assertTrue(rms(roll) >= rms(yaw), "roll smaller than yaw, " + at);
            }
        }
    }

    @Test
    void aBigQuakeAtFullStrengthRollsAboutTwoAndAHalfDegrees() {
        double maxRoll = 0, maxYaw = 0, maxPitch = 0;
        for (int t = 60; t < 1150; t++) {
            Shake sh = new Shake();
            sh.big(t, 1200, 1, 1, 2026);
            maxRoll = Math.max(maxRoll, Math.abs(sh.roll));
            maxYaw = Math.max(maxYaw, Math.abs(sh.yaw));
            maxPitch = Math.max(maxPitch, Math.abs(sh.pitch));
        }
        assertTrue(maxRoll > 2.3 && maxRoll <= 2.5 + 1e-9, "roll " + maxRoll);
        assertTrue(maxYaw > 1.15 && maxYaw <= 1.25 + 1e-9, "yaw " + maxYaw);
        assertTrue(maxPitch > 0.8 && maxPitch <= 0.875 + 0.12 + 0.01, "pitch " + maxPitch);
        // the onset: a 2 degree downward kick, half as much roll, and the field of view narrowed 3 degrees for 8 ticks
        Shake onset = new Shake();
        onset.big(0, 1200, 1, 1, 2026);
        assertEquals(2.0, onset.pitch, 1e-9);
        assertEquals(1.0, onset.roll, 1e-9);
        assertEquals(0, onset.yaw, 1e-9);
        assertEquals(-3.0, onset.fov, 1e-9);
        Shake later = new Shake();
        later.big(8, 1200, 1, 1, 2026);
        assertEquals(0, later.fov, 1e-9);
        // the jolt halves in about five ticks
        Shake five = new Shake();
        five.big(5, 1200, 1, 1, 2026);
        assertEquals(2.0 * Math.pow(0.88, 5), five.pitch - sway(5, 1, 2026), 1e-9);
        // before it starts and after it ends, nothing
        for (double t : new double[]{-1, 1200, 1300}) {
            Shake none = new Shake();
            none.big(t, 1200, 1, 1, 2026);
            assertEquals(0, none.yaw + Math.abs(none.pitch) + Math.abs(none.roll) + Math.abs(none.fov), 0, "at " + t);
        }
    }

    /** A big quake's pitch from its sway and buzz alone at {@code t} (its jolt taken away). */
    private static double sway(double t, double s, int seed) {
        double env = Shake.bigEnvelope(t, 1200);
        return 0.35 * (1.0 + 1.5 * s) * env * Math.sin(0.29 * t + Shake.phase(seed, 5)) + 0.12 * s * env * Math.sin(3.3 * t + Shake.phase(seed, 6));
    }

    @Test
    void aTremorShiversFastAndFaintlyWhenItToppledNothingNear() {
        for (int seed : SEEDS) {
            for (int duration : new int[]{30, 40, 50}) {
                // eight samples a tick: 160 a second
                int n = duration * 8;
                double[] pitch = new double[n], roll = new double[n];
                for (int i = 0; i < n; i++) {
                    double t = i / 8.0;
                    Shake felt = new Shake(), faint = new Shake();
                    felt.quake(false, t, duration, 0.3, 1, 1, seed, false);
                    faint.quake(false, t, duration, 0.3, 1, Shake.FAINT, seed, false);
                    pitch[i] = felt.pitch;
                    roll[i] = felt.roll;
                    assertEquals(0.35 * felt.pitch, faint.pitch, 1e-12);
                    assertEquals(0.35 * felt.roll, faint.roll, 1e-12);
                    assertEquals(0, felt.yaw + felt.fov, 0, "a tremor has no sway and no thump");
                }
                String at = "seed " + seed + ", " + duration + " ticks";
                assertTrue(bandShare(pitch, 160, 8, 13, false, 8) > 0.9, "pitch outside 8-13 Hz, " + at);
                assertTrue(bandShare(roll, 160, 8, 13, false, 8) > 0.9, "roll outside 8-13 Hz, " + at);
                // a light shiver: never more than half a degree
                for (double p : pitch) assertTrue(Math.abs(p) <= 0.5 + 1e-9);
            }
        }
        // no jolt: it starts from nothing
        Shake start = new Shake();
        start.tremor(0, 40, 1, 1, 1, 7);
        assertEquals(0, Math.abs(start.pitch) + Math.abs(start.roll), 0);
    }

    @Test
    void theLavasShakeIsUnchanged() {
        Random r = new Random(81);
        for (int i = 0; i < 2000; i++) {
            float a = r.nextFloat() * 1.2F;
            double t = r.nextInt(100000) + r.nextDouble();
            // LavaWarnings.camera of 0.8.0, its waveform as it was
            float yaw = (float) (Math.sin(t * 0.83 + 1.3) * 0.5 + Math.sin(t * 1.91 + 0.4) * 0.35 + Math.sin(t * 3.07 + 2.2) * 0.15);
            float pitch = (float) (Math.sin(t * 0.97 + 2.9) * 0.5 + Math.sin(t * 2.23 + 1.7) * 0.35 + Math.sin(t * 3.41 + 0.9) * 0.15);
            float roll = (float) (Math.sin(t * 1.13 + 0.6) * 0.6 + Math.sin(t * 2.71 + 2.4) * 0.4);
            Shake sh = new Shake();
            sh.lava(a, t);
            assertEquals(a * yaw, (float) sh.yaw, 0);
            assertEquals(a * pitch, (float) sh.pitch, 0);
            assertEquals(0.6F * a * roll, (float) sh.roll, 0);
            assertEquals(0, sh.fov, 0);
            // 0.8.0 scaled the amplitude by Distortion Effects before the waveform; the gate scales the sum after it
            float d = r.nextFloat();
            float before = a * d * yaw;
            assertEquals(before, Shake.gate(true, 1, d, false) * sh.yaw, 1e-5);
        }
    }

    @Test
    void nothingShakesWhileAQuakeOrTheLavaClockIsHeld() {
        for (boolean big : new boolean[]{true, false}) {
            for (double t = 0; t < 200; t += 0.5) {
                Shake sh = new Shake();
                sh.quake(big, t, 200, 1, 1, 1, 42, true);
                assertEquals(0, Math.abs(sh.yaw) + Math.abs(sh.pitch) + Math.abs(sh.roll) + Math.abs(sh.fov), 0);
            }
        }
        assertEquals(0, Shake.gate(true, 1, 1, true), 0);
        assertEquals(0, Shake.gate(false, 1, 1, false), 0, "screenShake off");
        assertEquals(1, Shake.gate(true, 1, 1, false), 0);
    }

    @Test
    void theClientsStrengthAndDistortionEffectsScaleIt() {
        assertEquals(0.4, Shake.gate(true, 0.5, 0.8, false), 1e-12);
        assertEquals(0, Shake.gate(true, 0, 1, false), 0);
        assertEquals(0, Shake.gate(true, 1, 0, false), 0);
        assertEquals(0.25, Shake.gate(true, 0.25, 1, false), 1e-12);
        assertEquals(1, Shake.gate(true, 3, 2, false), 0, "clamped to 1");
    }

    @Test
    void aQuakeIsFeltByHeight() {
        int bottom = -832, top = -689;
        assertEquals(1, Shake.height(-820, bottom, top), 0);
        assertEquals(1, Shake.height(bottom, bottom, top), 0);
        assertEquals(1, Shake.height(top + 1.99, bottom, top), 0);
        assertEquals(0.5, Shake.height(top + 2 + 24, bottom, top), 1e-12);
        assertEquals(0, Shake.height(top + 2 + 48, bottom, top), 0);
        assertEquals(0, Shake.height(top + 200, bottom, top), 0);
        assertEquals(0, Shake.height(bottom - 1, bottom, top), 0);
        // and by its height factor, linearly
        Shake full = new Shake(), half = new Shake();
        full.big(300, 600, 0.8, 1, 5);
        half.big(300, 600, 0.8, 0.5, 5);
        assertEquals(0.5 * full.roll, half.roll, 1e-12);
    }

    @Test
    void tremorEffectsChooseWhichTremorsAreFelt() {
        int faint = 0;
        for (int seed = -3000; seed < 3000; seed++) {
            assertEquals(1, Shake.tremorShare(Shake.ALL, false, seed), 0);
            assertEquals(1, Shake.tremorShare(Shake.ALL, true, seed), 0);
            assertEquals(0, Shake.tremorShare(Shake.NONE, true, seed), 0);
            assertEquals(1, Shake.tremorShare(Shake.WHERE_BLOCKS_FALL, true, seed), 0);
            double f = Shake.tremorShare(Shake.WHERE_BLOCKS_FALL, false, seed);
            assertEquals(seed % 3 == 0 ? Shake.FAINT : 0, f, 0);
            if (f > 0) faint++;
        }
        assertEquals(2000, faint, "one in three of those that toppled nothing near is felt faintly");
    }

    @Test
    void anEruptionsJoltComesWithItsBoom() {
        assertEquals(0, Shake.boomDelay(0));
        assertEquals(20, Shake.boomDelay(40));
        assertEquals(32, Shake.boomDelay(64));
        assertEquals(51, Shake.boomDelay(101));
        assertEquals(128, Shake.boomDelay(256));
        assertEquals(1.2, Shake.eruptionJolt(1, 0), 1e-12);
        assertEquals(0.6, Shake.eruptionJolt(1, 64), 1e-12);
        assertEquals(0, Shake.eruptionJolt(1, 128), 0);
        assertEquals(0, Shake.eruptionJolt(1, 200), 0);

        Shake.Jolts jolts = new Shake.Jolts();
        double d = 64, deg = Shake.eruptionJolt(1, d);
        jolts.add(100, Shake.boomDelay(d), deg);
        Shake before = new Shake();
        jolts.sample(before, 131.9);
        assertEquals(0, before.pitch + before.roll, 0, "not before the boom arrives");
        Shake at = new Shake();
        jolts.sample(at, 132);
        assertEquals(deg, at.pitch, 1e-12);
        assertEquals(deg / 2, at.roll, 1e-12);
        Shake after = new Shake();
        jolts.sample(after, 134);
        assertEquals(deg * 0.85 * 0.85, after.pitch, 1e-12);
        jolts.prune(140);
        assertEquals(1, jolts.size());
        jolts.prune(200);
        assertEquals(0, jolts.size(), "gone once it has died away");
    }

    // ================================================================================================ helpers

    private static double rms(double[] x) {
        double s = 0;
        for (double v : x) s += v * v;
        return Math.sqrt(s / x.length);
    }

    /**
     * The share of a signal's power between {@code lo} and {@code hi} Hz, sampled {@code rate} times a second: a plain
     * DFT, zero-padded {@code pad} times, with a Hann window or none.
     */
    private static double bandShare(double[] x, double rate, double lo, double hi, boolean hann, int pad) {
        int n = x.length, m = n * pad;
        double[] w = new double[n];
        for (int i = 0; i < n; i++) w[i] = x[i] * (hann ? 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1)) : 1);
        double band = 0, all = 0;
        for (int k = 0; k <= m / 2; k++) {
            // the bin's phasor turned a step a sample
            double re = 0, im = 0, step = -2 * Math.PI * k / m, c = Math.cos(step), sn = Math.sin(step), pr = 1, pi = 0;
            for (int i = 0; i < n; i++) {
                re += w[i] * pr;
                im += w[i] * pi;
                double q = pr * c - pi * sn;
                pi = pr * sn + pi * c;
                pr = q;
            }
            double p = re * re + im * im, f = k * rate / m;
            all += p;
            if (f >= lo && f <= hi) band += p;
        }
        return band / all;
    }
}
