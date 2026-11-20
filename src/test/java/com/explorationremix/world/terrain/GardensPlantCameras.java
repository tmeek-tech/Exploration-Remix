package com.explorationremix.world.terrain;

import com.explorationremix.creature.MantisHerds;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Scratch camera finder for the 0.20.2 plant pictures (not a check): the densest spots of each plant over an area of
 * seed 8675309 and a camera for each (a floor to stand on with a clear line to the spot), printed as shot-list lines.
 */
@Tag("slow")
class GardensPlantCameras {
    static final int X0 = Integer.getInteger("cams.x0", -487), Z0 = Integer.getInteger("cams.z0", -355), S = Integer.getInteger("cams.s", 200);

    @Test
    void cameras() {
        Terrain t = GardensFieldTest.terrain();
        GardensField g = t.gardens;
        int bottom = t.shape.bottom(Terrain.GARDENS), SY = GardensPlan.SY;
        int[] codes = {GardensField.C_TALLG, GardensField.C_WORM, GardensField.C_THREAD, GardensField.C_REED, GardensField.C_SHROOM, GardensField.C_GRASS};
        String[] names = {"meadow", "worms", "threads", "reeds", "mushrooms", "grass_edge"};
        // per column and code: how many, and the mean row
        int[][] cnt = new int[codes.length][S * S];
        long[][] rows = new long[codes.length][S * S];
        for (int i = 0; i < S; i++)
            for (int j = 0; j < S; j++)
                for (int y = 1; y < SY - 1; y++) {
                    int c = g.packed(X0 + i, y, Z0 + j) & 31;
                    for (int k = 0; k < codes.length; k++)
                        if (c == codes[k]) { cnt[k][i * S + j]++; rows[k][i * S + j] += y; }
                }
        for (int k = 0; k < codes.length; k++) {
            int W = codes[k] == GardensField.C_THREAD ? 10 : 7;
            List<int[]> picked = new ArrayList<>();
            for (int pick = 0; pick < 3; pick++) {
                int best = -1, bi = 0, bj = 0;
                for (int i = W; i < S - W; i += 2)
                    for (int j = W; j < S - W; j += 2) {
                        boolean far = true;
                        for (int[] p : picked) if (Math.abs(p[0] - i) < 40 && Math.abs(p[1] - j) < 40) far = false;
                        if (!far) continue;
                        int sum = 0;
                        for (int a = -W; a <= W; a++) for (int b = -W; b <= W; b++) sum += cnt[k][(i + a) * S + j + b];
                        if (sum > best) { best = sum; bi = i; bj = j; }
                    }
                if (best <= 0) break;
                picked.add(new int[]{bi, bj});
                long rs = 0;
                int rn = 0;
                for (int a = -W; a <= W; a++) for (int b = -W; b <= W; b++) { rs += rows[k][(bi + a) * S + bj + b]; rn += cnt[k][(bi + a) * S + bj + b]; }
                double row = (double) rs / rn;
                double tx = X0 + bi + 0.5, tz = Z0 + bj + 0.5, ty = row + 0.5;
                String cam = camera(g, bottom, tx, ty, tz, codes[k] == GardensField.C_THREAD, 5, 10);
                System.out.printf(Locale.ROOT, "%s_%d: %d blocks in %d x %d round %.0f %.0f %.0f -> %s%n", names[k], pick, best, 2 * W + 1, 2 * W + 1, tx, ty + bottom, tz,
                        cam == null ? "no camera" : names[k] + "_" + pick + " @ " + cam);
                if (codes[k] == GardensField.C_WORM) {
                    String near = camera(g, bottom, tx, ty, tz, false, 3, 4);
                    System.out.printf(Locale.ROOT, "  %s_%d close: %s%n", names[k], pick, near == null ? "no camera" : names[k] + "_" + pick + "_close @ " + near);
                }
            }
        }
        // how much of the mats the grass covers
        int[] floors = new int[3], grown = new int[3];
        for (int i = 0; i < S; i++)
            for (int j = 0; j < S; j++)
                for (int y = 1; y < SY - 2; y++) {
                    int c = g.packed(X0 + i, y, Z0 + j) & 31, up = g.packed(X0 + i, y + 1, Z0 + j) & 31;
                    if (!GardensField.airy(up) || GardensField.airy(c) || c == GardensField.C_OIL) continue;
                    int kind = c == GardensField.C_MAT ? 0 : (c == GardensField.C_MATE ? 1 : 2);
                    floors[kind]++;
                    if (up == GardensField.C_GRASS || up == GardensField.C_TALLG) grown[kind]++;
                }
        System.out.printf(Locale.ROOT, "cover: mat %d of %d, edge mat %d of %d, other floors %d of %d%n", grown[0], floors[0], grown[1], floors[1], grown[2], floors[2]);
        // the herds in the area and the grass round each home
        for (int cx = Math.floorDiv(X0, MantisHerds.CELL); cx <= Math.floorDiv(X0 + S, MantisHerds.CELL); cx++)
            for (int cz = Math.floorDiv(Z0, MantisHerds.CELL); cz <= Math.floorDiv(Z0 + S, MantisHerds.CELL); cz++)
                for (MantisHerds.Home h : MantisHerds.homes(t, cx, cz)) {
                    int grass = 0;
                    for (int a = -8; a <= 8; a++)
                        for (int b = -8; b <= 8; b++)
                            for (int dy = -4; dy <= 4; dy++) {
                                int r = h.y() - bottom + dy;
                                if (r < 1 || r >= SY - 1) continue;
                                int c = g.packed(h.x() + a, r, h.z() + b) & 31;
                                if (c == GardensField.C_GRASS || c == GardensField.C_TALLG) grass++;
                            }
                    String cam = camera(g, bottom, h.x() + 0.5, h.y() - bottom + 1.0, h.z() + 0.5, false, 9, 13);
                    System.out.printf(Locale.ROOT, "herd %s x%d at %d %d %d: %d grass within 8 -> %s%n", h.colour(), h.size(), h.x(), h.y(), h.z(), grass,
                            cam == null ? "no camera" : "herd @ " + cam);
                }
    }

    /** A camera (feet x y z yaw pitch) on a floor 5 to 10 away with a clear line to (tx, ty, tz) in layer rows. */
    static String camera(GardensField g, int bottom, double tx, double ty, double tz, boolean up, int d0, int d1) {
        String best = null;
        double bestScore = -1e9;
        for (int d = d0; d <= d1; d++)
            for (int a = 0; a < 24; a++) {
                double th = a * Math.PI / 12;
                int x = (int) Math.floor(tx + d * Math.cos(th)), z = (int) Math.floor(tz + d * Math.sin(th));
                for (int dy = -6; dy <= (up ? 2 : 8); dy++) {
                    int feet = (int) Math.floor(ty) + dy - (up ? 8 : 0);
                    if (feet < 2 || feet >= GardensPlan.SY - 3) continue;
                    if (g.open(x, feet - 1, z) || !g.open(x, feet, z) || !g.open(x, feet + 1, z)) continue;
                    double ex = x + 0.5, ey = feet + 1.62, ez = z + 0.5;
                    if (!clear(g, ex, ey, ez, tx, ty, tz)) continue;
                    double hd = Math.hypot(tx - ex, tz - ez), pitch = Math.toDegrees(Math.atan2(ey - ty, hd));
                    double yaw = Math.toDegrees(Math.atan2(-(tx - ex), tz - ez));
                    if (yaw < 0) yaw += 360;
                    double want = up ? -30 : 22;
                    double score = -Math.abs(pitch - want) - 2 * Math.abs(d - (d0 + d1) / 2.0);
                    if (score > bestScore) {
                        bestScore = score;
                        best = String.format(Locale.ROOT, "%.1f %d %.1f %.0f %.0f", ex, feet + bottom, ez, yaw, pitch);
                    }
                }
            }
        return best;
    }

    /** Whether the line between two points crosses only open blocks (to within the last block before the target). */
    static boolean clear(GardensField g, double ax, double ay, double az, double bx, double by, double bz) {
        double len = Math.sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay) + (bz - az) * (bz - az));
        int n = (int) Math.ceil(len * 4);
        for (int s = 0; s < n - 4; s++) {
            double f = (double) s / n;
            int x = (int) Math.floor(ax + (bx - ax) * f), y = (int) Math.floor(ay + (by - ay) * f), z = (int) Math.floor(az + (bz - az) * f);
            if (y < 0 || y >= GardensPlan.SY || !g.open(x, y, z)) return false;
        }
        return true;
    }
}
