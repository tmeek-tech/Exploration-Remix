package com.explorationremix.creature;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The giant Skein's body is the Skein's, four times the size: laid out four times as long, and walked along any way at
 * all it lies exactly as a Skein's body walked along the same way at a quarter of the size, four times over; written
 * out and read back it lies as it did.
 */
class GiantBodyTest {
    private static final double SCALE = 4.0, KEEP = GiantSkeinEntity.TRAIL_KEEP;
    private static final int JOINTS = 50;

    @Test
    void laidOutItIsFourTimesTheSkeinsLength() {
        GiantBody g = new GiantBody(SCALE, KEEP, JOINTS);
        g.lay(100, 64, 100, -1, 0, 0, 0, 1, 0);
        g.layBody();
        SkeinSpine plain = new SkeinSpine(JOINTS);
        assertEquals(plain.length() * SCALE, g.length(), 1.0E-9);
        assertTrue(g.length() > GiantSkeinEntity.BODY_LENGTH - 0.5, "long enough for its plates: " + g.length());
        double[] o = new double[6];
        for (double s = 0.0; s <= GiantSkeinEntity.BODY_LENGTH; s += 2.5) {
            g.sample(s, 1.0F, o, 0);
            assertEquals(100 - s, o[0], 0.05, "along it at " + s);
            assertEquals(64, o[1], 1.0E-6);
            assertEquals(100, o[2], 1.0E-6);
            assertEquals(1.0, o[4], 1.0E-6, "its back up");
        }
    }

    @Test
    void walkedAboutItLiesAsTheSkeinsDoesAtFourTimesTheSize() {
        GiantBody g = new GiantBody(SCALE, KEEP, JOINTS);
        SkeinTrail trail = new SkeinTrail(KEEP / SCALE);
        SkeinSpine spine = new SkeinSpine(JOINTS);
        double x = 40, y = 20, z = -12, h = 0.3;
        g.lay(x, y, z, -Math.cos(h), 0, -Math.sin(h), 0, 1, 0);
        trail.lay(x / SCALE, y / SCALE, z / SCALE, -Math.cos(h), 0, -Math.sin(h), 0, 1, 0);
        g.layBody();
        spine.lay(trail);
        double[] a = new double[6], b = new double[6];
        for (int t = 0; t < 900; t++) {
            // a slow wander, a sharp turn now and then, and a climb up a wall half way
            h += t % 150 == 75 ? 1.9 : 0.02 * Math.sin(t * 0.05);
            double fx = Math.cos(h), fz = Math.sin(h), step = 0.13;
            boolean wall = t > 450 && t < 600;
            double nx = wall ? -1 : 0, ny = wall ? 0 : 1, fy = wall ? 1 : 0;
            if (wall) { y += step; } else { x += fx * step; z += fz * step; }
            g.record(x, y, z, nx, ny, 0);
            trail.record(x / SCALE, y / SCALE, z / SCALE, nx, ny, 0);
            g.follow(wall ? 0 : fx, fy, wall ? 0 : fz);
            spine.follow(trail, wall ? 0 : fx, fy, wall ? 0 : fz);
            if (t % 50 != 0) continue;
            for (double s = 0.0; s <= GiantSkeinEntity.BODY_LENGTH; s += 1.2) {
                g.sample(s, 1.0F, a, 0);
                spine.sample(s / SCALE, 1.0F, b, 0);
                for (int i = 0; i < 3; i++) assertEquals(b[i] * SCALE, a[i], 1.0E-6, "tick " + t + " at " + s);
                for (int i = 3; i < 6; i++) assertEquals(b[i], a[i], 1.0E-9, "its back, tick " + t + " at " + s);
            }
        }
        assertEquals(trail.lastStep() * SCALE, g.lastStep(), 1.0E-9);
    }

    /** Where its k-th plate lies (GiantSkeinEntity.plateAt, kept here: that class cannot load outside the game). */
    private static double plateAt(int k) {
        return GiantSkeinEntity.PLATE_START + GiantSkeinEntity.PLATE_STEP * k;
    }

    @Test
    void writtenOutAndReadBackItLiesAsItDid() {
        GiantBody g = new GiantBody(SCALE, KEEP, JOINTS);
        double x = 0, y = 70, z = 0, h = 0.0;
        g.lay(x, y, z, -1, 0, 0, 0, 1, 0);
        g.layBody();
        for (int t = 0; t < 400; t++) {
            h += 0.012;
            x += Math.cos(h) * 0.12;
            z += Math.sin(h) * 0.12;
            g.record(x, y, z, 0, 1, 0);
            g.follow(Math.cos(h), 0, Math.sin(h));
        }
        double[] kept = new double[GiantSkeinEntity.PLATES + 2];
        for (int k = 0; k < GiantSkeinEntity.PLATES; k++) kept[k + 1] = plateAt(k);
        kept[GiantSkeinEntity.PLATES + 1] = KEEP;
        float[] data = g.write(kept, x, y, z);
        GiantBody read = new GiantBody(SCALE, KEEP, JOINTS);
        assertTrue(read.read(data, x, y, z));
        read.layBody();
        double[] a = new double[6], b = new double[6];
        for (int k = 0; k < GiantSkeinEntity.PLATES; k++) {
            double s = plateAt(k);
            g.sample(s, 1.0F, a, 0);
            read.sample(s, 1.0F, b, 0);
            double d = Math.sqrt(Math.pow(a[0] - b[0], 2) + Math.pow(a[1] - b[1], 2) + Math.pow(a[2] - b[2], 2));
            assertTrue(d < 0.35, "plate " + k + " lies " + d + " from where it was");
        }
    }
}
