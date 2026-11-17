package com.explorationremix.world.terrain;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Fern Rift redone by depth (0.14.5; Taran, 17 Nov: "Plants changing with depth is pretty"): its bands, green up
 * top, silver through the middle and light blue by the floor, share every block between them and melt into each other
 * with height, and the rift's ground and walls follow them (Palette.riftBand, riftBandAt, upperShell).
 */
class RiftBandsTest {
    private static final Terrain T = new Terrain(TerrainSettings.defaults(), -64, 8675309L);
    private static final int N = 400, TOP = 60;

    @Test
    void theBandsShareEveryBlockAndChangeWithDepth() {
        Palette p = T.palette;
        int floor = T.shape.packFloor;
        Random r = new Random(3);
        double[][] mean = new double[TOP + 1][3];
        for (int h = 0; h <= TOP; h++) {
            for (int k = 0; k < N; k++) {
                int x = r.nextInt(4000) - 2000, z = r.nextInt(4000) - 2000, y = floor + h;
                double sum = 0;
                for (int b = 0; b < 3; b++) {
                    double w = p.riftBand(b, x, y, z);
                    assertTrue(w >= 0 && w <= 1, "a share of " + w + " at " + x + " " + y + " " + z);
                    sum += w;
                    mean[h][b] += w / N;
                }
                assertEquals(1.0, sum, 1e-9, "the shares at " + x + " " + y + " " + z);
                // the rock takes a band the block lies in
                int at = p.riftBandAt(x, y, z);
                assertTrue(p.riftBand(at, x, y, z) > 0, "band " + at + " for the rock at " + x + " " + y + " " + z);
            }
        }
        assertTrue(mean[8][Palette.RIFT_BLUE] > 0.95, "light blue by the floor: " + mean[8][Palette.RIFT_BLUE]);
        assertTrue(mean[26][Palette.RIFT_SILVER] > 0.8, "silver through the middle: " + mean[26][Palette.RIFT_SILVER]);
        assertTrue(mean[46][Palette.RIFT_GREEN] > 0.95, "green up top: " + mean[46][Palette.RIFT_GREEN]);
        for (int h = 1; h <= TOP; h++) {
            // no step: each edge melts over seven blocks (RIFT_BLEND each way), so a share changes by at most about a
            // fifth from one block to the next
            for (int b = 0; b < 3; b++)
                assertTrue(Math.abs(mean[h][b] - mean[h - 1][b]) < 0.25, "band " + b + " melts into the next, at " + h + " over the old floor");
            assertTrue(mean[h][Palette.RIFT_BLUE] <= mean[h - 1][Palette.RIFT_BLUE] + 0.02, "less blue higher up, at " + h);
            assertTrue(mean[h][Palette.RIFT_GREEN] >= mean[h - 1][Palette.RIFT_GREEN] - 0.02, "more green higher up, at " + h);
        }
    }

    @Test
    void theGroundAndWallsFollowTheBands() {
        Palette p = T.palette;
        int floor = T.shape.packFloor;
        Random r = new Random(4);
        int[] asurine = new int[3], walls = new int[3];
        for (int k = 0; k < 60000; k++) {
            int x = r.nextInt(4000) - 2000, z = r.nextInt(4000) - 2000, y = floor + 2 + r.nextInt(TOP);
            // a ledge's top block: vanilla's moss up top, deep moss through the middle, none by the floor
            Rock g = p.upperShell(DeepBiome.SILVER_FERN_RIFT, x, y, z, 1, 6, false);
            int band = p.riftBandAt(x, y, z);
            if (band == Palette.RIFT_BLUE) assertTrue(g != Rock.MOSS && g != Rock.DEEP_MOSS, g + " on a blue ledge at " + x + " " + y + " " + z);
            if (band == Palette.RIFT_SILVER) assertNotEquals(Rock.MOSS, g, "vanilla moss on a silver ledge at " + x + " " + y + " " + z);
            if (band == Palette.RIFT_GREEN) assertNotEquals(Rock.DEEP_MOSS, g, "deep moss on a green ledge at " + x + " " + y + " " + z);
            // a wall
            Rock w = p.upperShell(DeepBiome.SILVER_FERN_RIFT, x, y, z, 6, 6, false);
            walls[band]++;
            if (w == Rock.ASURINE) asurine[band]++;
        }
        double green = asurine[0] / (double) walls[0], silver = asurine[1] / (double) walls[1], blue = asurine[2] / (double) walls[2];
        assertTrue(green < silver && silver < blue, "the walls bluer with depth: asurine " + green + ", " + silver + ", " + blue);
    }
}
