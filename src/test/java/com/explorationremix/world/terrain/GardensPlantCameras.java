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
    static final int X0 = env("CAMS_X0", -487), Z0 = env("CAMS_Z0", -355), S = env("CAMS_S", 200);

    static int env(String k, int fallback) {
        String v = System.getenv(k);
        return v == null ? fallback : Integer.parseInt(v.trim());
    }

    /** With CAMS_DUMP=x,y,z,r[,ry] in the environment (world blocks): every plant and oil block within r (ry up and down) of the point. */
    @Test
    void dump() {
        String d = System.getenv("CAMS_DUMP");
        if (d == null) return;
        String[] a = d.split(",");
        int x = Integer.parseInt(a[0]), y = Integer.parseInt(a[1]), z = Integer.parseInt(a[2]), r = Integer.parseInt(a[3]);
        int ry = a.length > 4 ? Integer.parseInt(a[4]) : r;
        Terrain t = GardensFieldTest.terrain();
        GardensField g = t.gardens;
        int bottom = t.shape.bottom(Terrain.GARDENS);
        for (int i = -r; i <= r; i++)
            for (int j = -r; j <= r; j++)
                for (int k = -ry; k <= ry; k++) {
                    int row = y + k - bottom;
                    if (row < 1 || row >= GardensPlan.SY - 1) continue;
                    int p = g.packed(x + i, row, z + j), c = p & 31;
                    if (!GardensField.plant(c) && c != GardensField.C_OIL) continue;
                    System.out.printf(Locale.ROOT, "dump %d %d %d code %d colour %d bit %d under %d%n", x + i, y + k, z + j, c, (p >> 5) & 3, (p >> 7) & 1,
                            g.packed(x + i, row - 1, z + j) & 31);
                }
    }

    @Test
    void cameras() {
        if (System.getenv("CAMS_DUMP") != null) return;
        Terrain t = GardensFieldTest.terrain();
        GardensField g = t.gardens;
        int bottom = t.shape.bottom(Terrain.GARDENS), SY = GardensPlan.SY;
        int[] codes = {GardensField.C_TALLG, GardensField.C_WORM, GardensField.C_THREAD, GardensField.C_REED, GardensField.C_SHROOM, GardensField.C_GRASS};
        String[] names = {"meadow", "worms", "threads", "reeds", "mushrooms", "grass_edge"};
        // per column and code: how many
        int[][] cnt = new int[codes.length][S * S];
        for (int i = 0; i < S; i++)
            for (int j = 0; j < S; j++)
                for (int y = 1; y < SY - 1; y++) {
                    int c = g.packed(X0 + i, y, Z0 + j) & 31;
                    for (int k = 0; k < codes.length; k++)
                        if (c == codes[k]) cnt[k][i * S + j]++;
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
                // the busiest row (with the rows either side; plants on several storeys share a column), and the
                // middle of the plants on it
                int[] hist = new int[SY];
                for (int a = -W; a <= W; a++)
                    for (int b = -W; b <= W; b++)
                        for (int y = 1; y < SY - 1; y++) if ((g.packed(X0 + bi + a, y, Z0 + bj + b) & 31) == codes[k]) hist[y]++;
                int mode = 2, mbest = -1;
                for (int y = 2; y < SY - 2; y++) {
                    int s3 = hist[y - 1] + hist[y] + hist[y + 1];
                    if (s3 > mbest) { mbest = s3; mode = y; }
                }
                double cx = 0, cz = 0;
                int cn = 0;
                for (int a = -W; a <= W; a++)
                    for (int b = -W; b <= W; b++)
                        for (int y = mode - 1; y <= mode + 1; y++)
                            if ((g.packed(X0 + bi + a, y, Z0 + bj + b) & 31) == codes[k]) { cx += bi + a; cz += bj + b; cn++; }
                double tx = X0 + cx / cn + 0.5, tz = Z0 + cz / cn + 0.5, ty = mode + 0.5;
                String cam = camera(g, bottom, tx, ty, tz, codes[k] == GardensField.C_THREAD, 5, 10);
                System.out.printf(Locale.ROOT, "%s_%d: %d blocks in %d x %d, %d of them within a row of %.0f, round %.1f %.1f -> %s%n", names[k], pick, best,
                        2 * W + 1, 2 * W + 1, mbest, ty + bottom, tx, tz, cam == null ? "no camera" : names[k] + "_" + pick + " @ " + cam);
                if (codes[k] == GardensField.C_WORM) {
                    String near = wormsClose(g, bottom, X0 + bi, Z0 + bj, W, mode);
                    System.out.printf(Locale.ROOT, "  %s_%d close: %s%n", names[k], pick, near == null ? "no camera" : near);
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

    /**
     * A close camera for the tube worms' pull-in: the open floor spot round a window's worms (cx, cz world, W round,
     * near row) that has the most worm blocks in a clear line within {@link #WORM_REACH} of the feet (and in the rows
     * TubeWormWatch looks at, five under the feet to two over), stood back from their middle (a block and a half or
     * more, at most 55 degrees down) and looking at it.
     */
    static String wormsClose(GardensField g, int bottom, int cx, int cz, int W, int row) {
        List<int[]> worms = new ArrayList<>();
        for (int a = -W - 4; a <= W + 4; a++)
            for (int b = -W - 4; b <= W + 4; b++)
                for (int y = Math.max(1, row - 8); y <= Math.min(GardensPlan.SY - 2, row + 8); y++)
                    if ((g.packed(cx + a, y, cz + b) & 31) == GardensField.C_WORM) worms.add(new int[]{cx + a, y, cz + b});
        String best = null;
        int bestN = 0;
        for (int a = -W - 2; a <= W + 2; a++)
            for (int b = -W - 2; b <= W + 2; b++)
                for (int feet = Math.max(2, row - 3); feet <= Math.min(GardensPlan.SY - 3, row + 6); feet++) {
                    int x = cx + a, z = cz + b;
                    if (g.open(x, feet - 1, z) || (g.packed(x, feet, z) & 31) != GardensField.C_AIR
                            || (g.packed(x, feet + 1, z) & 31) != GardensField.C_AIR) continue;
                    double fx = x + 0.5, fz = z + 0.5, ey = feet + 1.62;
                    double sx = 0, sy = 0, sz = 0;
                    int n = 0;
                    for (int[] w : worms) {
                        double dx = w[0] + 0.5 - fx, dy = w[1] + 0.4 - feet, dz = w[2] + 0.5 - fz;
                        if (dx * dx + dy * dy + dz * dz > WORM_REACH * WORM_REACH || w[1] - feet < -5 || w[1] - feet > 2) continue;
                        if (!clear(g, fx, ey, fz, w[0] + 0.5, w[1] + 0.5, w[2] + 0.5)) continue;
                        sx += w[0] + 0.5; sy += w[1] + 0.3; sz += w[2] + 0.5; n++;
                    }
                    if (n <= bestN) continue;
                    double tx = sx / n, ty = sy / n, tz = sz / n;
                    double hd = Math.hypot(tx - fx, tz - fz), pitch = Math.toDegrees(Math.atan2(ey - ty, hd));
                    // stood back from them, looking across rather than down at its feet
                    if (hd < 1.5 || pitch > 55) continue;
                    double yaw = Math.toDegrees(Math.atan2(-(tx - fx), tz - fz));
                    if (yaw < 0) yaw += 360;
                    bestN = n;
                    best = String.format(Locale.ROOT, "%d worm blocks in reach (of %d round) -> worms_close @ %.1f %d %.1f %.0f %.0f, middle %.1f %.1f %.1f",
                            n, worms.size(), fx, feet + bottom, fz, yaw, pitch, tx, ty + bottom, tz);
                }
        return best;
    }

    /** A little under TubeWormsBlock.REACH (4.5), so the picture's worms are surely pulled in. */
    static final double WORM_REACH = 4.2;

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
