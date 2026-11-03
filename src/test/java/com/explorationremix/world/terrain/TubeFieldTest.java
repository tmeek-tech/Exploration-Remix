package com.explorationremix.world.terrain;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Obsidian Tubes as a noise field (0.5.1, spec-tubes 12): statistics over 4 seeds x 36 tube cells of the countries
 * that stand in the real Basalt Galleries (where one does not, the Galleries ignore it and no tube exists), measured
 * along every tube line (on the line's own normal, at the axis of the twin where a line splits), with the Galleries' own
 * density as the cavern; and block by block over the columns of those countries (every value finite, every open block
 * of a tube lined, the hanging column ends whole column cells). Bars have margin over the measured values (section 10).
 */
class TubeFieldTest {
    static final long[] SEEDS = {8675309, 12345, -42, 77};
    /** Cells -R..R-1 on each axis per seed. */
    static final int R = 3;

    /** What the tube lines of the standing countries measured. */
    static final class Stats {
        int cells, countries, standing;
        double lenSingle, lenTwin;
        int runs, runsBoth, runsWalkBoth, runsNone;
        /** Sections left out of the widths because they cross the cavern's open air (a side opening). */
        int sideOpen;
        /** level, step down 2..15, fall > 15, step up > 1, blind, over lava, too short, in the open. */
        final int[] ends = new int[8];
        /** Widths across the tube (on the line's normal, in the cavern's rock) at the axis height and at the eye row (F + 1). */
        final List<Integer> widthsAxis = new ArrayList<>(), widths = new ArrayList<>(), headRib = new ArrayList<>(), headBay = new ArrayList<>(),
                floors = new ArrayList<>(), vaults = new ArrayList<>();
        /** Rib rings against the next bay's middle: how much wider the bay is at the eye row and at the floor row (both sides), the crown's depth, the opening under the ring at the eye row. */
        final List<Integer> ringEye = new ArrayList<>(), ringFloor = new ArrayList<>(), ringCrown = new ArrayList<>(), ribOpen = new ArrayList<>();
        /** For every axis block of an enclosed stretch: its stretch's length between walkable exits (length-weighted). */
        final List<Integer> stretch = new ArrayList<>();
        long axisSteps, axisSteps2, axisSteps4;
        int pools, poolBlocks, poolBad;
        /** Rock blocks face-adjacent to pool water (beds and sides), and those not lined, not in the tubes' biome or deep rock. */
        int poolFaces, poolFacesBad;
        String poolBadAt = "", poolFaceBadAt = "", blindAt = "", stepAt = "";
        /** Block by block over the countries' columns. */
        long spanCols, values, nonFinite, openBlocks, unlined, tubeCols, unionCols, hangCols, spikes, deepSpikes, cellsHung, cellsMixed;
        String nonFiniteAt = "", unlinedAt = "", mixedAt = "";
        /** Rock faces of the blocks the tubes open (air or water) over the whole countries, and those not lined. */
        long faces, facesBad;
        String facesBadAt = "";
        /** Falls over 15 onto rock from a standing place of the cavern into a tube's column, and those the tube made. */
        int caveFalls, caveFallsMade;
        String caveFallAt = "";
        /** The seed being measured, and a few mouths of each seed (seed, x, the tube's walking level, z) for the biome check. */
        long seed;
        final List<long[]> mouths = new ArrayList<>();

        double km() { return (lenSingle + 2 * lenTwin) / 1000; }
        int endsTotal() { return Arrays.stream(ends).sum() - ends[6]; }

        @Override public String toString() {
            return String.format(java.util.Locale.ROOT, "%d cells, %d countries, %d standing; tube %.1f km (twin %.0f %%); runs %d: open both ends %.1f %%, walkable out"
                            + " both %.1f %%, neither %.1f %%; ends level %d, down %d, fall %d, up %d, blind %d, lava %d, open %d; axis steps of 2+ %.3f %%, 4+ %d;"
                            + " width at the axis height %s; at the eye row %s; bay head %s; rib head %s; floors over W %s; vaults over W %s; pools %d (%.1f per km), %d blocks, %d unsealed%s%s",
                    cells, countries, standing, km(), 100 * 2 * lenTwin / (lenSingle + 2 * lenTwin), runs, 100.0 * runsBoth / runs, 100.0 * runsWalkBoth / runs,
                    100.0 * runsNone / runs, ends[0], ends[1], ends[2], ends[3], ends[4], ends[5], ends[7], 100.0 * axisSteps2 / Math.max(1, axisSteps), axisSteps4,
                    dist(widthsAxis), dist(widths), dist(headBay), dist(headRib), dist(floors), dist(vaults), pools, pools / km(), poolBlocks, poolBad, poolBadAt, blindAt)
                    + "; pool faces " + poolFaces + ", unlined " + poolFacesBad + poolFaceBadAt
                    + "; sections open to the side " + sideOpen + "; steps of 4+:" + stepAt + "; moves " + axisSteps
                    + "; rings (x2) eye " + dist(ringEye) + ", floor " + dist(ringFloor) + ", crown " + dist(ringCrown) + ", opening " + dist(ribOpen)
                    + "; enclosed stretch " + dist(stretch)
                    + String.format(java.util.Locale.ROOT, "; columns with a span %d, values %d, non-finite %d%s; open blocks %d, unlined %d%s; tube columns %d, hung %d,"
                                    + " isolated spikes %d (%.2f %%), 2+ deep %d; hung cells %d, mixed %d%s",
                            spanCols, values, nonFinite, nonFiniteAt, openBlocks, unlined, unlinedAt, tubeCols, hangCols, spikes, 100.0 * spikes / Math.max(1, tubeCols),
                            deepSpikes, cellsHung, cellsMixed, mixedAt)
                    + "; tube columns two lines reach " + unionCols
                    + "; faces of the tubes' open blocks " + faces + ", unlined " + facesBad + facesBadAt
                    + "; falls over 15 from the cavern into a tube " + caveFalls + ", the tube's making " + caveFallsMade + caveFallAt;
        }
    }

    private static Stats stats;

    @AfterAll
    static void forget() { stats = null; }

    static synchronized Stats stats() {
        if (stats == null) {
            Stats s = new Stats();
            for (long seed : SEEDS) {
                Terrain t = new Terrain(TerrainSettings.defaults(), -64, seed);
                s.seed = seed;
                for (int ci = -R; ci < R; ci++) for (int cj = -R; cj < R; cj++) {
                    s.cells++;
                    TubeField.Country c = t.tubes.country(ci, cj);
                    if (!c.on) continue;
                    s.countries++;
                    if (!t.galleries.countryStands(ci, cj)) continue;
                    s.standing++;
                    lines(t, c, s);
                    pools(t, c, s);
                    columns(t, c, s);
                }
            }
            System.out.println("TubeFieldTest " + s);
            stats = s;
        }
        return stats;
    }

    static String dist(List<Integer> l) {
        int[] v = l.stream().mapToInt(Integer::intValue).sorted().toArray();
        if (v.length == 0) return "none";
        return String.format("n %d, min %d, 5%% %d, median %d, 95%% %d, max %d", v.length, v[0], v[(int) (v.length * 0.05)], v[v.length / 2], v[(int) (v.length * 0.95)], v[v.length - 1]);
    }

    static int pct(List<Integer> l, double q) {
        int[] v = l.stream().mapToInt(Integer::intValue).sorted().toArray();
        return v[Math.min(v.length - 1, (int) Math.floor(q * v.length))];
    }

    /** Along every line of the country, its runs (a line's stretch of tube): length, width, headroom, steps and ends. */
    static void lines(Terrain t, TubeField.Country c, Stats s) {
        TubeField tf = t.tubes;
        TubeField.Pt p = new TubeField.Pt();
        int n = (int) (2 * TubeField.A_MAX * 1.45);
        double v0 = -n / 2.0;
        for (int k = -10; k <= 10; k++) {
            boolean[] on = new boolean[n];
            double[][] q = new double[n][];
            boolean any = false;
            for (int i = 0; i < n; i++) {
                q[i] = tf.axisPoint(c, k, v0 + i);
                tf.at(q[i][0], q[i][1], p);
                on[i] = p.on && p.line == k && p.fade > 0.5;
                any |= on[i];
            }
            if (!any) continue;
            for (int i = 0; i < n; ) {
                if (!on[i]) { i++; continue; }
                int a = i;
                while (i < n && on[i]) i++;
                run(t, c, q, a, i - 1, n, s);
            }
        }
    }

    static void run(Terrain t, TubeField.Country c, double[][] q, int a, int b, int n, Stats s) {
        TubeField tf = t.tubes;
        GalleriesField g = t.galleries;
        TubeField.Pt p = new TubeField.Pt(), pb = new TubeField.Pt();
        double len = 0;
        for (int i = a; i < b; i++) {
            double d = Math.hypot(q[i + 1][0] - q[i][0], q[i + 1][1] - q[i][1]);
            tf.at(q[i][0], q[i][1], p);
            if (p.o > p.R + 0.5) s.lenTwin += d;
            else s.lenSingle += d;
            len += d;
        }
        if (len < 12) { s.ends[6] += 2; return; }
        s.runs++;
        // width and headroom inside the massif every 4 blocks, in the cavern's rock (not in a collapse gap)
        for (int i = a; i <= b; i += 4) {
            tf.at(q[i][0], q[i][1], p);
            if (p.rho < 0.05 || p.fade < 1) continue;
            int[] col = axisCol(tf, q[i], p);
            int bx = col[0], bz = col[1];
            int[] r = tf.rows(bx, bz);
            if (r == null || r[1] <= r[0]) continue;
            if (g.density(bx, r[1] - 1, bz) <= 0 || g.density(bx, r[0], bz) <= 0) continue;
            int F = r[3] > r[2] ? r[3] : r[0];
            tf.at(bx + 0.5, bz + 0.5, pb);
            (pb.rib > 0.5 ? s.headRib : s.headBay).add(r[1] - F);
            s.floors.add(F - tf.W);
            s.vaults.add(r[1] - tf.W);
            int wE = widthAt(t, bx + 0.5, bz + 0.5, F + 1, pb.gx, pb.gz), wA = widthAt(t, bx + 0.5, bz + 0.5, (int) Math.floor(pb.yc), pb.gx, pb.gz);
            if (wE < 0 || wA < 0) { s.sideOpen++; continue; }   // the section crosses the cavern's open air: no width
            s.widths.add(wE);
            s.widthsAxis.add(wA);
        }
        rings(t, q, a, b, s);
        exits(t, q, a, b, s);
        // steps of the walking level (the water's top or the floor) along the axis where the tube is enclosed: inside the
        // massif and in the cavern's rock (where the cavern is open its own floor is walked)
        int prevF = Integer.MIN_VALUE;
        for (int i = a; i <= b; i++) {
            tf.at(q[i][0], q[i][1], p);
            int[] col = axisCol(tf, q[i], p);
            int bx = col[0], bz = col[1];
            double ax = bx + 0.5, az = bz + 0.5;
            int[] r = tf.rows(bx, bz);
            tf.at(bx + 0.5, bz + 0.5, pb);
            if (r == null || r[1] <= r[0] || p.rho < 0.02 || pb.rho < 0.02 || g.density(bx, r[0], bz) <= 0) { prevF = Integer.MIN_VALUE; continue; }
            int F = r[3] > r[2] ? r[3] : r[0];
            if (prevF != Integer.MIN_VALUE) {
                int dF = Math.abs(F - prevF);
                s.axisSteps++;
                if (dF >= 2) s.axisSteps2++;
                if (dF >= 4) {
                    s.axisSteps4++;
                    if (s.stepAt.length() < 160) s.stepAt += String.format(" (%d,%d: %d to %d, rows %s, rho %.2f, o %.1f)", (int) Math.floor(ax), (int) Math.floor(az), prevF, F,
                            Arrays.toString(r), p.rho, p.o);
                }
            }
            prevF = F;
        }
        int[] ma = new int[3], mb = new int[3];
        int ea = end(t, c, q, a, -1, n, ma), eb = end(t, c, q, b, +1, n, mb);
        for (int[] m : new int[][]{ea <= 3 ? ma : null, eb <= 3 ? mb : null})
            if (m != null && s.mouths.stream().filter(o -> o[0] == s.seed).count() < 6) s.mouths.add(new long[]{s.seed, m[0], m[1], m[2]});
        s.ends[ea]++;
        s.ends[eb]++;
        if (ea == 4 && s.blindAt.length() < 120) s.blindAt += String.format(" (blind %.0f,%.0f)", q[a][0], q[a][1]);
        if (eb == 4 && s.blindAt.length() < 120) s.blindAt += String.format(" (blind %.0f,%.0f)", q[b][0], q[b][1]);
        if (ea != 4 && eb != 4) s.runsBoth++;
        if (ea == 4 && eb == 4) s.runsNone++;
        if ((ea == 0 || ea == 1 || ea == 7) && (eb == 0 || eb == 1 || eb == 7)) s.runsWalkBoth++;
    }

    /**
     * A run's end: walks from inside the run outward until the cavern is open at the tube's rows (the mouth), then
     * compares the cavern's floor there with the tube's walking level just inside: 0 level (within 1), 1 a step down of
     * 2 to 15, 2 a fall over 15, 3 a step up over 1, 4 blind (the tube closes first), 5 over lava, 7 the end lies in the
     * open cavern. A mouth's column and the tube's walking level just inside go to {@code at}: {x, level, z}.
     */
    static int end(Terrain t, TubeField.Country c, double[][] q, int i0, int dir, int n, int[] at) {
        TubeField tf = t.tubes;
        GalleriesField g = t.galleries;
        TubeField.Pt p = new TubeField.Pt();
        int lastF = Integer.MIN_VALUE;
        boolean enclosed = false;
        for (int i = i0 - dir * 80; dir * (i - i0) <= 30; i += dir) {
            if (i < 0 || i >= n) { if (dir * (i - i0) > 0) break; continue; }
            tf.at(q[i][0], q[i][1], p);
            int[] col = axisCol(tf, q[i], p);
            int bx = col[0], bz = col[1];
            int[] r = tf.rows(bx, bz);
            if (r != null && r[1] > r[0]) {
                boolean open = false;
                for (int y = r[0]; y < r[1] && !open; y++) open = g.density(bx, y, bz) <= 0;
                int F = r[3] > r[2] ? r[3] : r[0];
                if (!open) { lastF = F; enclosed = true; continue; }
                if (!enclosed) continue;
                at[0] = bx;
                at[1] = lastF;
                at[2] = bz;
                int cf = firstOpen(g, bx, bz);
                if (cf == Integer.MAX_VALUE) return 4;
                if (cf < tf.W) return 5;
                int step = cf - lastF;
                if (Math.abs(step) <= 1) return 0;
                if (step < 0) return -step <= 15 ? 1 : 2;
                return 3;
            } else if (dir * (i - i0) > 0) return enclosed ? 4 : 7;
        }
        return enclosed ? 4 : 7;
    }

    /**
     * The column on the axis of the tube at axis point q of a line (p the field there): q itself for one tube, else the
     * twin on the line's + side: o along the line's normal, then the column within 4 blocks either way along it that is
     * nearest the twin's axis.
     */
    static int[] axisCol(TubeField tf, double[] q, TubeField.Pt p) {
        double ax = q[0], az = q[1];
        if (p.o > 0.5) {
            TubeField.Pt t = new TubeField.Pt();
            double best = 1e9, bx = ax + p.o * p.gx, bz = az + p.o * p.gz;
            for (double e = -4; e <= 4; e += 0.5) {
                double x = ax + (p.o + e) * p.gx, z = az + (p.o + e) * p.gz;
                tf.at(Math.floor(x) + 0.5, Math.floor(z) + 0.5, t);
                if (!t.on || t.line != p.line || t.s < 0) continue;
                if (t.d < best) { best = t.d; bx = x; bz = z; }
            }
            ax = bx;
            az = bz;
        }
        return new int[]{(int) Math.floor(ax), (int) Math.floor(az)};
    }

    /**
     * Open width at row y across the tube through (ax, az) along (nx, nz): columns whose tube rows hold y in the cavern's
     * rock. Negative (minus the width so far) where the tube's air there runs into the cavern's open air, a side opening.
     */
    static int widthAt(Terrain t, double ax, double az, int y, double nx, double nz) {
        TubeField tf = t.tubes;
        GalleriesField g = t.galleries;
        int[] r0 = tf.rows((int) Math.floor(ax), (int) Math.floor(az));
        if (r0 == null || !(r0[0] <= y && y < r0[1])) return 0;
        int w = 1;
        boolean side = false;
        for (int sgn = -1; sgn <= 1; sgn += 2) for (double d = 1; d < 40; d += 1) {
            int cx = (int) Math.floor(ax + sgn * d * nx), cz = (int) Math.floor(az + sgn * d * nz);
            int[] rr = tf.rows(cx, cz);
            if (rr == null || !(rr[0] <= y && y < rr[1])) break;
            if (g.density(cx, y, cz) <= 0) { side = true; break; }
            w++;
        }
        return side ? -w : w;
    }

    /**
     * Rib rings on a single, fully ribbed tube inside the massif: each rib's middle against the next bay's middle, the
     * width at the floor row and the eye row (the rib's floor, compared where the bay's floor is within a row of it), the
     * vault's rise from rib to bay at the crown, and the opening under the rib at the eye row.
     */
    static void rings(Terrain t, double[][] q, int a, int b, Stats s) {
        TubeField tf = t.tubes;
        GalleriesField g = t.galleries;
        TubeField.Pt p = new TubeField.Pt(), pb = new TubeField.Pt();
        int[] rib = null;   // the last rib's widths at F and F+1, its vault and floor
        for (int i = a; i <= b; i++) {
            tf.at(q[i][0], q[i][1], p);
            if (p.o > 0.05 || p.rho < 0.05 || p.fade < 1 || p.ribK < 0.95) { rib = null; continue; }
            int bx = (int) Math.floor(q[i][0]), bz = (int) Math.floor(q[i][1]);
            int[] r = tf.rows(bx, bz);
            if (r == null || r[1] <= r[0] || r[3] > r[2] || g.density(bx, r[1] - 1, bz) <= 0) { rib = null; continue; }
            tf.at(bx + 0.5, bz + 0.5, pb);
            int F = r[0];
            if (pb.rib >= 0.999 && pb.ribQ < 0.5) {
                rib = new int[]{widthAt(t, bx + 0.5, bz + 0.5, F, pb.gx, pb.gz), widthAt(t, bx + 0.5, bz + 0.5, F + 1, pb.gx, pb.gz), r[1], F};
                if (rib[0] < 0 || rib[1] < 0) rib = null;   // a side opening: no ring to measure
            } else if (rib != null && pb.rib == 0 && pb.ribQ > pb.ribP * 0.5 - 0.6) {
                int wF = widthAt(t, bx + 0.5, bz + 0.5, rib[3], pb.gx, pb.gz), wE = widthAt(t, bx + 0.5, bz + 0.5, rib[3] + 1, pb.gx, pb.gz);
                if (Math.abs(F - rib[3]) <= 1 && wF >= 0 && wE >= 0) {
                    s.ringFloor.add(wF - rib[0]);
                    s.ringEye.add(wE - rib[1]);
                    s.ringCrown.add(r[1] - rib[2]);
                    s.ribOpen.add(rib[1]);
                }
                rib = null;
            }
        }
    }

    /**
     * The enclosed stretches of a run between walkable exits: an axis block is an exit where the tube's air shares two
     * rows or more with the cavern's and the cavern's open run there starts at most 1 over the tube's walking level and
     * at most 15 under it; every enclosed block adds its stretch's length (length-weighted).
     */
    static void exits(Terrain t, double[][] q, int a, int b, Stats s) {
        TubeField tf = t.tubes;
        GalleriesField g = t.galleries;
        TubeField.Pt p = new TubeField.Pt();
        int m = b - a + 1;
        int[] kind = new int[m];   // 0 enclosed, 1 a walkable exit, 2 open but not walkable out, 3 no tube rows
        for (int i = a; i <= b; i++) {
            tf.at(q[i][0], q[i][1], p);
            int[] col = axisCol(tf, q[i], p);
            int bx = col[0], bz = col[1];
            int[] r = tf.rows(bx, bz);
            if (r == null || r[1] <= r[0]) { kind[i - a] = 3; continue; }
            int shared = 0, first = Integer.MAX_VALUE;
            for (int y = r[0]; y < r[1]; y++) if (g.density(bx, y, bz) <= 0) { shared++; first = Math.min(first, y); }
            if (shared < 2) continue;
            int cf = first;
            while (cf > g.B && g.density(bx, cf - 1, bz) <= 0) cf--;
            int F = r[3] > r[2] ? r[3] : r[0];
            kind[i - a] = cf - F <= 1 && F - cf <= 15 ? 1 : 2;
        }
        for (int i = 0; i < m; ) {
            if (kind[i] != 0) { i++; continue; }
            int st = i;
            while (i < m && kind[i] == 0) i++;
            for (int j = st; j < i; j++) s.stretch.add(i - st);
        }
    }

    /**
     * Block by block over the columns of a country (the ellipse and its apron's inner part): every value the tube field
     * gives is finite (tube() over each span and 4 rows past it, shellDepth() over the span and 12 rows past it, the
     * massif, apron and apron floor at a column cell site's kind of point, the region); every open block the tube makes
     * in the cavern's rock (air or a pool's water) is lined (shellDepth finite and under SHELL_BIOME, inLining); and the
     * hanging column ends are whole column cells (in the bays, where the vault hangs, every column of a cell hangs the
     * same), with few isolated spikes (an open column lower than all 4 open neighbours).
     */
    static void columns(Terrain t, TubeField.Country c, Stats s) {
        TubeField tf = t.tubes;
        GalleriesField g = t.galleries;
        double ext = 1.3 * Math.max(c.a, c.b);
        int x0 = (int) Math.floor(c.ox - ext), x1 = (int) Math.ceil(c.ox + ext), z0 = (int) Math.floor(c.oz - ext), z1 = (int) Math.ceil(c.oz + ext);
        java.util.Map<Long, Integer> hang = new java.util.HashMap<>();
        java.util.Set<Long> mixed = new java.util.HashSet<>();
        TubeField.Pt pb = new TubeField.Pt();
        int[] cell = new int[2];
        int yb = t.shape.bottom(Terrain.GALLERIES), yt = t.shape.top(Terrain.GALLERIES);
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            double u = c.u(x + 0.5, z + 0.5) / c.b, v = c.v(x + 0.5, z + 0.5) / c.a;
            if (u * u + v * v > 1.69) continue;
            finite(s, x, z, 0, tf.massifAt(x + 0.37, z + 0.81), "massif");
            finite(s, x, z, 0, tf.apronAt(x + 0.37, z + 0.81), "apron");
            finite(s, x, z, 0, tf.apronFloorAt(x + 0.37, z + 0.81), "apron floor");
            finite(s, x, z, 0, tf.region(x, z), "region");
            int lo = tf.spanLo(x, z), hi = tf.spanHi(x, z);
            if (lo > hi) continue;
            s.spanCols++;
            for (int y = lo - 4; y <= hi + 4; y++) finite(s, x, z, y, tf.tube(x, y, z), "tube");
            for (int y = lo - 12; y <= hi + 12; y += 2) finite(s, x, z, y, tf.shellDepth(x, y, z), "shellDepth");
            // the palette and the biome read the lining at any row of the layer (Terrain.tubeShellDepth): every 9th row of
            // it, the phase moving from column to column, so every row is read somewhere
            for (int y = yb + Math.floorMod(3 * x + 5 * z, 9); y <= yt; y += 9) finite(s, x, z, y, tf.shellDepth(x, y, z), "shellDepth");
            if (!g.tubesAt(x + 0.5, z + 0.5)) continue;
            int[] r = tf.rows(x, z);
            if (r == null || r[1] <= r[0]) continue;
            for (int y = r[0]; y < r[1]; y++) {
                if (g.density(x, y, z) <= 0) continue;   // the cavern's own open space
                s.openBlocks++;
                double sd = tf.shellDepth(x, y, z);
                if (!(sd < TubeField.SHELL_BIOME) || !tf.inLining(x, y, z)) {
                    s.unlined++;
                    if (s.unlinedAt.length() < 120) s.unlinedAt += String.format(java.util.Locale.ROOT, " (%d %d %d: %.2f)", x, y, z, sd);
                }
                if (g.fineBlocks(x, y, z) > 0) continue;   // a roof the cavern keeps over the tube
                // every rock face of the block the tube opens is the lining: lined, surface rock, in the biome
                for (int[] d : SIX) {
                    int a = x + d[0], b = y + d[1], e = z + d[2];
                    double fb = g.fineBlocks(a, b, e);
                    if (fb <= 0) continue;
                    s.faces++;
                    double sda = g.tubeShellDepth(a, b, e);
                    if (sda < 1.8 && fb <= 6 && t.biomeAt(a, b, e) == DeepBiome.OBSIDIAN_TUBES) continue;
                    s.facesBad++;
                    if (s.facesBadAt.length() < 160) s.facesBadAt += String.format(java.util.Locale.ROOT, " (%d %d %d: depth %.2f, density %.2f, %s)", a, b, e, sda, fb, t.biomeAt(a, b, e));
                }
            }
            caveFalls(t, x, z, r, s);
            // hanging ends: enclosed bay columns of the tube (no pool, the cavern's rock at the vault)
            if (r[3] > r[2] || g.density(x, r[1] - 1, z) <= 0) continue;
            s.tubeCols++;
            // where two lines' tubes reach a column it opens the union of their rows: the ends are read where one is near
            if (tf.nearLines(x, z) > 1) s.unionCols++;
            if (tf.soleLine(x, z, pb) && pb.rib == 0) {
                double yv = TubeField.vaultY(pb.yc, pb.d, pb.R, pb.Rv);
                int h = (int) Math.round(yv) - r[1];
                if (h > 0) s.hangCols++;
                tf.cellOf(x, z, cell);
                long key = ((long) cell[0] << 32) ^ (cell[1] & 0xffffffffL);
                Integer prev = hang.putIfAbsent(key, h);
                if (prev != null && prev != h && mixed.add(key) && s.mixedAt.length() < 120)
                    s.mixedAt += String.format(java.util.Locale.ROOT, " (%d %d: %d and %d)", x, z, prev, h);
            }
            int lower = 0, open = 0, minDiff = 99;
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int[] rn = tf.rows(x + d[0], z + d[1]);
                if (rn == null || rn[1] <= rn[0]) continue;
                open++;
                if (r[1] < rn[1]) { lower++; minDiff = Math.min(minDiff, rn[1] - r[1]); }
            }
            if (open == 4 && lower == 4) {
                s.spikes++;
                if (minDiff >= 2) s.deepSpikes++;
            }
        }
        for (java.util.Map.Entry<Long, Integer> e : hang.entrySet()) if (e.getValue() > 0) s.cellsHung++;
        s.cellsMixed += mixed.size();
    }

    static final int[][] SIX = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /**
     * Falls from the cavern into tube column (x, z) with open rows r: a standing place of the cavern beside it (a cell's
     * floor or a band's top, rock under it and two open rows over it as the blocks stand) at a row over the tube's floor
     * the column is open at (that row, the one under and the one over), dropping more than 15 rows onto rock (not into a
     * pool). The tube made it where the cavern alone is rock there or drops 15 or less.
     */
    static void caveFalls(Terrain t, int x, int z, int[] r, Stats s) {
        GalleriesField g = t.galleries;
        for (int[] d : SIX) {
            if (d[1] != 0) continue;
            int a = x + d[0], e = z + d[2];
            GalleriesField.Cell n = g.column(a, e);
            if (n.closed()) continue;
            for (int k = 0; k < 2; k++) {
                if (k == 1 && n.bl >= n.bh) break;
                int y = k == 0 ? n.f : n.bh;
                if (y <= r[0] || g.fineBlocks(x, y, z) > 0 || g.fineBlocks(x, y - 1, z) > 0 || g.fineBlocks(x, y + 1, z) > 0) continue;
                if (g.fineBlocks(a, y - 1, e) <= 0 || g.fineBlocks(a, y, e) > 0 || g.fineBlocks(a, y + 1, e) > 0) continue;
                int yl = y - 1;
                while (yl > g.B && g.fineBlocks(x, yl, z) <= 0 && t.fluidAt(x, yl, z) == Terrain.AIR) yl--;
                int drop = y - (yl + 1);
                if (drop <= 15 || g.fineBlocks(x, yl, z) <= 0) continue;   // a step, or the fall ends in water or lava
                s.caveFalls++;
                boolean cave = g.density(x, y - 1, z) <= 0 && g.density(x, y, z) <= 0 && g.density(x, y + 1, z) <= 0;
                int yc = y - 1;
                if (cave) while (yc > g.B && g.density(x, yc, z) <= 0) yc--;
                if (cave && y - (yc + 1) > 15) continue;
                s.caveFallsMade++;
                if (s.caveFallAt.length() < 200) s.caveFallAt += String.format(java.util.Locale.ROOT, " (seed %d: %d from %d %d %d into %d %d)", s.seed, drop, a, y, e, x, z);
            }
        }
    }

    static void finite(Stats s, int x, int z, int y, double v, String what) {
        s.values++;
        if (Double.isFinite(v)) return;
        s.nonFinite++;
        if (s.nonFiniteAt.length() < 160) s.nonFiniteAt += String.format(java.util.Locale.ROOT, " (%s at %d %d %d: %s)", what, x, y, z, v);
    }

    /** The cavern's lowest open row in a column (lava included), or MAX_VALUE where the column is rock. */
    static int firstOpen(GalleriesField g, int x, int z) {
        for (int y = g.B; y <= g.T; y++) if (g.density(x, y, z) <= 0) return y;
        return Integer.MAX_VALUE;
    }

    /** The country's pools: every water block over water or rock, beside water or rock, in the cavern's rock, high. */
    static void pools(Terrain t, TubeField.Country c, Stats s) {
        TubeField tf = t.tubes;
        GalleriesField g = t.galleries;
        double ext = TubeField.A_MAX * 1.3;
        int gi0 = (int) Math.floor((c.ox - ext) / TubeField.DIP_SP), gi1 = (int) Math.floor((c.ox + ext) / TubeField.DIP_SP);
        int gj0 = (int) Math.floor((c.oz - ext) / TubeField.DIP_SP), gj1 = (int) Math.floor((c.oz + ext) / TubeField.DIP_SP);
        int[][] four = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        int[][] six = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int gi = gi0; gi <= gi1; gi++) for (int gj = gj0; gj <= gj1; gj++) {
            double cx = (gi + 0.5) * TubeField.DIP_SP, cz = (gj + 0.5) * TubeField.DIP_SP;
            TubeField.Country o = tf.countryAt(cx, cz);
            if (o.ci != c.ci || o.cj != c.cj) continue;
            int[] v = tf.dip(gi, gj);
            if (v[0] == 0) continue;
            double px = v[2] / 16.0, pz = v[3] / 16.0, rd = v[4] / 16.0;
            int blocks = 0;
            boolean bad = false;
            for (int x = (int) Math.floor(px - rd - 1); x <= (int) Math.ceil(px + rd + 1); x++)
                for (int z = (int) Math.floor(pz - rd - 1); z <= (int) Math.ceil(pz + rd + 1); z++) {
                    int[] r = tf.rows(x, z);
                    if (r == null || r[3] <= r[2]) continue;
                    for (int y = r[2]; y < r[3]; y++) {
                        blocks++;
                        boolean wrong = !tf.pool(x, y - 1, z) && g.fineBlocks(x, y - 1, z) <= 0;
                        for (int[] d : four) if (!tf.pool(x + d[0], y, z + d[1]) && g.fineBlocks(x + d[0], y, z + d[1]) <= 0) wrong = true;
                        if (g.density(x, y, z) <= 0 || y < tf.W + Terrain.FLOOD_RISE) wrong = true;
                        if (wrong && s.poolBadAt.length() < 80) s.poolBadAt += String.format(" (%d,%d,%d)", x, y, z);
                        bad |= wrong;
                        // the pool's bed and sides are the tube's lining (critique T3): lined, in the biome, surface rock
                        for (int[] d : six) {
                            int a = x + d[0], b = y + d[1], e = z + d[2];
                            double fb = g.fineBlocks(a, b, e);
                            if (fb <= 0) continue;
                            s.poolFaces++;
                            if (g.tubeShellDepth(a, b, e) < 1.8 && fb <= 6 && t.biomeAt(a, b, e) == DeepBiome.OBSIDIAN_TUBES) continue;
                            s.poolFacesBad++;
                            if (s.poolFaceBadAt.length() < 120) s.poolFaceBadAt += String.format(" (%d,%d,%d: %.2f, %.2f)", a, b, e, g.tubeShellDepth(a, b, e), fb);
                        }
                    }
                }
            if (blocks == 0) continue;
            s.pools++;
            s.poolBlocks += blocks;
            if (bad) s.poolBad++;
        }
    }

    // ================================================================================================ tests

    /**
     * Sections are picture 2's size (critique tubes-scale; spec-tubes 10, a6ac8ea's narrow tubes in brackets): the width
     * at the axis height 5-95 % within 15..36 (measured 16..33, median 21; before 8..17), the floor's width at the eye row
     * (F + 1) at least 11 at the 5th percentile (13; before 7), headroom in the bays at least 16 at the 5th percentile
     * (19, median 24; before 8) and under the ribs at least 12 (15, median 19; before 5), with two open rows everywhere;
     * floors at least W + 46 and vaults at most W + 94 plus the bays' bulge over the Galleries' lava table.
     */
    @Test
    void sectionsAreRoundAndSized() {
        Stats s = stats();
        assertTrue(s.standing >= 40, "too few standing countries to measure: " + s);
        assertTrue(s.widths.size() > 3000, "too few sections measured: " + s);
        assertTrue(pct(s.widthsAxis, 0.05) >= 15 && pct(s.widthsAxis, 0.95) <= 36, "widths at the axis height: " + dist(s.widthsAxis));
        assertTrue(pct(s.widths, 0.05) >= 11, "floor widths at the eye row: " + dist(s.widths));
        assertTrue(pct(s.headBay, 0.05) >= 16, "headroom in the bays: " + dist(s.headBay));
        assertTrue(pct(s.headRib, 0.05) >= 12, "headroom under the ribs: " + dist(s.headRib));
        assertTrue(Collections.min(s.headRib) >= 2 && Collections.min(s.headBay) >= 2, "two open rows: " + dist(s.headRib) + "; " + dist(s.headBay));
        assertTrue(Collections.min(s.floors) >= TubeField.FLOOR_MIN, "floors over W: " + dist(s.floors));
        assertTrue(Collections.max(s.vaults) <= TubeField.VAULT_MAX + TubeField.VSTRETCH * TubeField.BULGE, "vaults over W: " + dist(s.vaults));
    }

    /**
     * The rib rings stand in on their legs, not only at the crown (critique tubes-ribs: the bays bulge out round them):
     * against the next bay's middle a ring stands in a median of at least 1 block a side at the eye row (measured 1.5,
     * before 0) and at least 0.5 at the floor row (1; before 0), its crown at least 4 rows under the bay's vault (6;
     * before 3), and the opening under it at the eye row stays at least 9 wide at the 5th percentile (12): no doorways.
     */
    @Test
    void ribRingsStandIn() {
        Stats s = stats();
        assertTrue(s.ringEye.size() > 300, "too few rib rings measured: " + s);
        assertTrue(pct(s.ringEye, 0.5) >= 2, "a ring's legs at the eye row (both sides): " + dist(s.ringEye));
        assertTrue(pct(s.ringFloor, 0.5) >= 1, "a ring's legs at the floor row (both sides): " + dist(s.ringFloor));
        assertTrue(pct(s.ringCrown, 0.5) >= 4, "a ring's crown under the bays' vaults: " + dist(s.ringCrown));
        assertTrue(pct(s.ribOpen, 0.05) >= 9, "the opening under a ring at the eye row: " + dist(s.ribOpen));
    }

    /**
     * Tubes have exits along them (critique tubes-runs: collapse gaps cut each country across the flow): the enclosed
     * stretch between walkable exits that an axis block lies in, length-weighted, has its median at most 90 blocks and its
     * 90th percentile at most 120 (measured 71 and 92; before 264 and 317).
     */
    @Test
    void tubesHaveExits() {
        Stats s = stats();
        assertTrue(s.stretch.size() > 10_000, "too few enclosed axis blocks: " + s);
        assertTrue(pct(s.stretch, 0.5) <= 90 && pct(s.stretch, 0.9) <= 120, "enclosed stretches between walkable exits: " + dist(s.stretch));
    }

    /**
     * Floors are walkable: under 0.1 % of 1-block moves along the axes step 2 or more (0.03 %), at most 0.01 % step 4 or
     * more (0).
     */
    @Test
    void floorsAreWalkable() {
        Stats s = stats();
        assertTrue(s.axisSteps > 15_000, "too few moves measured: " + s);
        assertTrue(s.axisSteps2 < s.axisSteps * 0.001, "floor steps of 2 or more: " + s.axisSteps2 + " of " + s.axisSteps);
        assertTrue(s.axisSteps4 <= s.axisSteps * 0.0001, "floor steps of 4 or more: " + s.axisSteps4 + " of " + s.axisSteps + ":" + s.stepAt);
    }

    /**
     * Every value of the field is finite (critique T1, T4): a NaN density reads as open in the game (the aquifer's
     * {@code density > 0}), and the old envelope gave NaN on every ribbed tube end. Over the columns of the standing
     * countries: tube() over each span and 4 rows past it, shellDepth() over the span and 12 rows past it and on every
     * 9th row of the whole layer (the palette and the biome read it at any row), the massif, the apron, the apron's floor
     * and the region; and the Galleries' per-block density over boxes round the old NaN columns.
     */
    @Test
    void finite() {
        Stats s = stats();
        assertTrue(s.spanCols > 500_000 && s.values > 20_000_000, "too few values checked: " + s.spanCols + " columns, " + s.values + " values");
        assertEquals(0, s.nonFinite, "non-finite values:" + s.nonFiniteAt);
        Terrain t = new Terrain(TerrainSettings.defaults(), -64, 8675309);
        GalleriesField g = t.galleries;
        int bad = 0, n = 0;
        for (int[] at : new int[][]{{186, -274}, {-1504, -1000}}) {
            for (int x = at[0] - 24; x < at[0] + 24; x++) for (int z = at[1] - 24; z < at[1] + 24; z++) for (int y = g.B; y < t.join.lo; y++) {
                n++;
                if (!Double.isFinite(g.fineBlocks(x, y, z))) bad++;
            }
        }
        assertTrue(n > 300_000);
        assertEquals(0, bad, "non-finite Galleries densities round the old NaN columns");
    }

    /**
     * Every block a tube opens in the cavern's rock, air or a pool's water, is lined (critique T3, T4): its shellDepth is
     * finite and under SHELL_BIOME and it is inLining, so it is in the Obsidian Tubes; a guard that only hid the NaN (the
     * end's rock read SOLID) left the tube's air at the tips out.
     */
    @Test
    void tubeAirIsLined() {
        Stats s = stats();
        assertTrue(s.openBlocks > 5_000_000, "too few open blocks checked: " + s.openBlocks);
        assertEquals(0, s.unlined, "tube blocks not lined:" + s.unlinedAt);
    }

    /**
     * Every rock face of the tubes over whole countries is their lining (round 2: a tube reaching past the middle between
     * two lines was cut flat there and the rock beyond read as no tube's, 2,792 deep, undressed faces over these seeds at
     * 69 places): every rock block beside a block a tube opens in the cavern's rock (its air or a pool's water), over every
     * column of the standing countries, lies within 1.8 of the tube's surface (the palette lines it), is surface rock (at
     * most 6 blocks deep in the game's density) and is in the Obsidian Tubes (measured 5.07 M faces, counted per open
     * block, all lined).
     */
    @Test
    void everyFaceIsLined() {
        Stats s = stats();
        assertTrue(s.faces > 1_000_000, "too few faces checked: " + s.faces);
        assertEquals(0, s.facesBad, "faces of the tubes not lined:" + s.facesBadAt);
    }

    /**
     * The cavern does not drop a walker into a tube (round 2: the tubes cut through rock bands crossing their mouths and
     * left falls of 16 to 24 rows from the cavern into tube air, 194 over seeds 1, 2024 and -987654321): from the cavern's
     * standing places beside the tubes' columns, at most 12 falls of more than 15 rows onto rock that the tube made (where
     * the cavern alone is rock or drops 15 or less). Measured 4, all at one place of seed 12345 where a 15-row step of the
     * cavern meets a tube floor one row under the cavern's; bands stay out of the mouths and the cavern keeps its roofs.
     * Falls the cavern makes alone into a tube's column (a cliff of the cavern over the apron's floor where a tube's rows lie
     * in the cavern's air: 171 edges) are the cavern's.
     */
    @Test
    void noFallsIntoTubes() {
        Stats s = stats();
        assertTrue(s.caveFalls > 0, "no falls looked at");
        assertTrue(s.caveFallsMade <= 12, "falls the tubes made from the cavern (" + s.caveFallsMade + "):" + s.caveFallAt);
    }

    /**
     * The hanging column ends are whole column cells (critique tubes-fringe): in the bays every column of a column cell
     * hangs the same number of blocks from the vault (0 cells mixed; measured 4 to 9 % of the bays' columns hang; read
     * where one line's tube is near, since a column two lines' tubes reach opens the union of their rows), and
     * under 1 % of the tube's enclosed columns are isolated spikes, an open column whose vault is lower than all 4 of its
     * open neighbours' (0.26 %; the per-block fringe gave 3.1 %), under 0.1 % two blocks deep or more (0.01 %; 0.87 %).
     */
    @Test
    void hangingEndsAreWholeCells() {
        Stats s = stats();
        assertTrue(s.tubeCols > 300_000 && s.cellsHung > 1000, "too few tube columns or hanging cells: " + s.tubeCols + ", " + s.cellsHung);
        assertEquals(0, s.cellsMixed, "column cells whose columns hang differently:" + s.mixedAt);
        assertTrue(s.hangCols > s.tubeCols * 0.02, "hanging ends too rare: " + s.hangCols + " of " + s.tubeCols);
        assertTrue(s.spikes < s.tubeCols * 0.01, "isolated spikes: " + s.spikes + " of " + s.tubeCols);
        assertTrue(s.deepSpikes < s.tubeCols * 0.001, "isolated spikes 2 deep or more: " + s.deepSpikes + " of " + s.tubeCols);
    }

    /**
     * Pools are sealed and common: every block of a pool's water lies over water or rock, beside water or rock, in the
     * cavern's rock, over the flood's reach (0 violations); 4 to 12 pools per km of tube (7.6). Their beds and sides are
     * the tube's lining (critique T3: 70 % of the prototype's beds read deeper than the skin): every rock block beside the
     * water is lined, in the Obsidian Tubes and surface rock.
     */
    @Test
    void poolsAreSealedAndCommon() {
        Stats s = stats();
        assertEquals(0, s.poolBad, "pools that would leak: " + s);
        assertTrue(s.poolFaces > 10_000, "too few pool faces: " + s.poolFaces);
        assertEquals(0, s.poolFacesBad, "pool beds or sides not lined:" + s.poolFaceBadAt);
        double perKm = s.pools / s.km();
        assertTrue(perKm >= 4 && perKm <= 12, "pools per km of tube: " + perKm + "; " + s);
    }

    /**
     * Mouths open into the real Galleries: runs open at both ends 95 % or more (98.3), walkable out at both ends 85 % or
     * more (92.8), blind ends at most 3 % (0.8), falls over 15 at a mouth at most 1 % (0). Fewer, wider tubes: about 180
     * runs over the sample (a6ac8ea's narrow ones, about 320).
     */
    @Test
    void mouthsOpen() {
        Stats s = stats();
        assertTrue(s.runs > 120, "too few runs: " + s);
        assertTrue(s.runsBoth >= s.runs * 0.95, "runs open at both ends: " + s);
        assertTrue(s.runsWalkBoth >= s.runs * 0.85, "runs walkable out at both ends: " + s);
        assertTrue(s.ends[4] <= s.endsTotal() * 0.03, "blind ends: " + s);
        assertTrue(s.ends[2] <= s.endsTotal() * 0.01, "falls over 15 at mouths: " + s);
    }

    /**
     * Tube countries are met: from 2000 random points over 3 seeds, the straight-line distance to the nearest country's
     * massif has its median at most 250 blocks and its 90th percentile at most 400 (spec 10: 160-168 and 332-336). In the
     * game the Galleries keep only the countries that stand in their landscape (spec-galleries 6.1, about two thirds), so
     * a player walks further: there the median is at most 300 and the 90th percentile at most 650 (232 and 500).
     */
    @Test
    void countriesAreMet() {
        List<Integer> all = new ArrayList<>(), standing = new ArrayList<>();
        for (long seed : new long[]{8675309, 12345, -42}) {
            Terrain t = new Terrain(TerrainSettings.defaults(), -64, seed);
            Random r = new Random(seed);
            for (int k = 0; k < 667; k++) {
                int x = r.nextInt(100_000) - 50_000, z = r.nextInt(100_000) - 50_000;
                all.add(nearestMassif(t, x, z, false));
                standing.add(nearestMassif(t, x, z, true));
            }
        }
        Collections.sort(all);
        Collections.sort(standing);
        int med = all.get(all.size() / 2), p90 = all.get((int) (all.size() * 0.9));
        int smed = standing.get(standing.size() / 2), sp90 = standing.get((int) (standing.size() * 0.9));
        System.out.println("TubeFieldTest nearest massif: median " + med + ", p90 " + p90 + "; of standing countries median " + smed + ", p90 " + sp90);
        assertTrue(med <= 250 && p90 <= 400, "tube countries too far apart: median " + med + ", p90 " + p90);
        assertTrue(smed <= 300 && sp90 <= 650, "standing tube countries too far apart: median " + smed + ", p90 " + sp90);
    }

    /**
     * Distance (blocks, rings 4 apart, up to 2000) from (x, z) to the nearest column of a country's massif (of one that
     * stands in the Galleries, when {@code standing}).
     */
    static int nearestMassif(Terrain t, int x, int z, boolean standing) {
        TubeField tf = t.tubes;
        GalleriesField g = t.galleries;
        for (int rr = 0; rr <= 2000; rr += 4) {
            // the ring of radius rr, every 4 blocks along it
            int steps = Math.max(1, (int) (2 * Math.PI * rr / 4));
            for (int a = 0; a < steps; a++) {
                double th = 2 * Math.PI * a / steps;
                int px = (int) Math.floor(x + rr * Math.cos(th)), pz = (int) Math.floor(z + rr * Math.sin(th));
                if (tf.massif(px, pz) > 0 && (!standing || g.tubesAt(px + 0.5, pz + 0.5))) return rr;
            }
        }
        return 2000;
    }

    /**
     * The lining and the biome: in boxes round the tube spots of standing countries and round mouths (where the tube's
     * air meets the cavern's), every rock block beside a tube's air or water (open space the cavern itself does not have)
     * is within 1.8 of the tube's surface (the palette lines it), surface rock (at most 6 blocks deep in the game's
     * density, so it is dressed, not the deep rock) and in the Obsidian Tubes (critique T3); every block the tube opens in
     * the cavern's rock (air or a pool's water) is in the Obsidian Tubes and no block of the cavern's own open space is
     * (spec-tubes 12, the biome check NetworkTest's galleriesSubBiomes made of the plan's tubes); and the tube spots
     * (/remix tp, the shot planner) stand in a tube's air on rock. Every pool's bed and sides: poolsAreSealedAndCommon.
     */
    @Test
    void liningAndBiome() {
        int checked = 0, spots = 0, tubeOpen = 0, caveOpen = 0, mouths = 0;
        List<String> bad = new ArrayList<>();
        // boxes of 32 x 32 columns round mouths, where the tube's air meets the cavern's
        java.util.Map<Long, Terrain> terrains = new java.util.HashMap<>();
        for (long[] m : stats().mouths) {
            Terrain t = terrains.computeIfAbsent(m[0], sd -> new Terrain(TerrainSettings.defaults(), -64, sd));
            int[] counts = liningBox(t, (int) m[1] - 16, (int) m[3] - 16, 32, bad);
            checked += counts[0];
            tubeOpen += counts[1];
            caveOpen += counts[2];
            mouths++;
        }
        for (long seed : new long[]{8675309, 12345}) {
            Terrain t = new Terrain(TerrainSettings.defaults(), -64, seed);
            GalleriesField g = t.galleries;
            int found = 0;
            for (int ci = -R; ci < R && found < 2; ci++) for (int cj = -R; cj < R && found < 2; cj++) {
                int[] spot = g.tubeSpot(ci, cj);
                if (spot == null) continue;
                found++;
                spots++;
                int x = spot[0], y = spot[1], z = spot[2];
                if (t.fine(x, y - 1, z, 0) <= 0 || t.fine(x, y, z, 0) > 0 || t.fine(x, y + 1, z, 0) > 0 || g.density(x, y, z) <= 0)
                    bad.add("spot " + Arrays.toString(spot) + " is not tube air on rock");
                // a box of 48 x 48 columns round the spot, the tube's rows
                int[] counts = liningBox(t, x - 24, z - 24, 48, bad);
                checked += counts[0];
                tubeOpen += counts[1];
                caveOpen += counts[2];
            }
        }
        System.out.println("TubeFieldTest lining: " + checked + " rock blocks beside tube air, " + tubeOpen + " blocks the tubes open, " + caveOpen
                + " of the cavern's, in boxes round " + spots + " spots and " + mouths + " mouths");
        assertTrue(spots >= 3, "too few tube spots: " + spots);
        assertTrue(mouths >= 12, "too few mouths: " + mouths);
        assertTrue(checked > 5000, "too few lining blocks checked: " + checked);
        assertTrue(tubeOpen > 20_000, "too few tube blocks checked: " + tubeOpen);
        assertTrue(caveOpen > 1000, "too few cavern blocks checked: " + caveOpen);
        assertTrue(bad.isEmpty(), "lining or spots wrong: " + bad.subList(0, Math.min(8, bad.size())) + " (" + bad.size() + ")");
    }

    /**
     * Checks the lining and the biome of a box; returns {rock blocks beside tube air, blocks the tubes open in the
     * cavern's rock, blocks of the cavern's own open space}.
     */
    static int[] liningBox(Terrain t, int x0, int z0, int size, List<String> bad) {
        GalleriesField g = t.galleries;
        int W = t.shape.waterY(Terrain.GALLERIES), lo = W + 30, hi = W + 110;
        int n = 0, tubeOpen = 0, caveOpen = 0;
        int[][] six = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int x = x0; x < x0 + size; x++) for (int z = z0; z < z0 + size; z++) for (int y = lo; y <= hi; y++) {
            if (g.density(x, y, z) <= 0) {
                caveOpen++;
                if (t.biomeAt(x, y, z) == DeepBiome.OBSIDIAN_TUBES) bad.add(String.format("%d %d %d: the cavern's open space in the Obsidian Tubes", x, y, z));
                continue;
            }
            if (g.fineBlocks(x, y, z) <= 0) {
                tubeOpen++;
                DeepBiome b = t.biomeAt(x, y, z);
                if (b != DeepBiome.OBSIDIAN_TUBES) bad.add(String.format("%d %d %d: the tube's open block in %s", x, y, z, b));
                continue;
            }
            boolean byTube = false;
            for (int[] d : six) {
                int a = x + d[0], b = y + d[1], c = z + d[2];
                if (g.fineBlocks(a, b, c) <= 0 && g.density(a, b, c) > 0 && g.fluid(a, b, c) != Terrain.LAVA) { byTube = true; break; }
            }
            if (!byTube) continue;
            n++;
            // lined (the palette's skin), in the biome, and surface rock (the game's deep rock, over 6 blocks, is undressed)
            double sd = g.tubeShellDepth(x, y, z), fb = g.fineBlocks(x, y, z);
            DeepBiome b = t.biomeAt(x, y, z);
            if (sd >= 1.8 || fb > 6 || b != DeepBiome.OBSIDIAN_TUBES) bad.add(String.format("%d %d %d: shell depth %.2f, density %.2f, %s", x, y, z, sd, fb, b));
        }
        return new int[]{n, tubeOpen, caveOpen};
    }

    /**
     * The field is pure and seamless: two instances, one asked column strips in order and one in shuffled order on a
     * parallel stream, give the same values; and nothing of a country reaches within 2 blocks of its cell's border (no
     * apron, no massif within the apron's reach, no tube rows), so neighbouring cells never meet.
     */
    @Test
    void pureAndSeamless() {
        long seed = 12345;
        int W = new Terrain(TerrainSettings.defaults(), -64, seed).shape.waterY(Terrain.GALLERIES);
        TubeField a = new TubeField(seed, W), b = new TubeField(seed, W);
        // strips across standing and empty cells: 120 strips of 64 columns, every 4th row of the tubes' heights
        Random r = new Random(61);
        int[][] strips = new int[120][];
        for (int i = 0; i < strips.length; i++) strips[i] = new int[]{r.nextInt(6000) - 3000, r.nextInt(6000) - 3000, r.nextBoolean() ? 1 : 0};
        long[] one = new long[strips.length];
        for (int i = 0; i < strips.length; i++) one[i] = strip(a, strips[i], W);
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < strips.length; i++) order.add(i);
        Collections.shuffle(order, new Random(67));
        long[] two = new long[strips.length];
        order.parallelStream().forEach(i -> two[i] = strip(b, strips[i], W));
        assertArrayEquals(one, two, "strips that differ between instances, orders and threads");
        assertEquals(0, nonFinite.get(), "non-finite values in the strips (a NaN hashes alike in every instance)");

        int touched = 0, samples = 0;
        for (int line = -20; line <= 20; line++) for (int along = -6400; along < 6400; along += 3) for (int off = -2; off <= 1; off++) {
            int u = line * TubeField.CELL + off;
            for (int xz = 0; xz < 2; xz++) {
                int x = xz == 0 ? u : along, z = xz == 0 ? along : u;
                samples++;
                if (a.apronAt(x + 0.5, z + 0.5) > 0 || a.massif(x, z) > -TubeField.APRON_OUT || a.spanLo(x, z) <= a.spanHi(x, z)) touched++;
            }
        }
        assertTrue(samples > 300_000);
        assertEquals(0, touched, "border samples a country reaches");
    }

    private static final java.util.concurrent.atomic.AtomicLong nonFinite = new java.util.concurrent.atomic.AtomicLong();

    /** Folds a value into a strip's hash; a non-finite one is counted too, as every NaN hashes alike. */
    static long fold(long h, double v) {
        if (!Double.isFinite(v)) nonFinite.incrementAndGet();
        return h * 31 + Double.doubleToLongBits(v);
    }

    static long strip(TubeField f, int[] s, int W) {
        long h = 1;
        for (int i = 0; i < 64; i++) {
            int x = s[0] + (s[2] == 1 ? i : 0), z = s[1] + (s[2] == 1 ? 0 : i);
            h = fold(h, f.massif(x, z));
            h = fold(h, f.apronAt(x + 0.5, z + 0.5));
            h = fold(h, f.apronFloorAt(x + 0.5, z + 0.5));
            h = fold(h, f.region(x, z));
            h = h * 31 + f.spanLo(x, z) * 7L + f.spanHi(x, z);
            h = h * 31 + f.floorCell(x, z);
            h = fold(h, f.ribShare(x, z));
            for (int y = W + 30; y <= W + 110; y += 4) {
                h = fold(h, f.tube(x, y, z));
                h = fold(h, f.shellDepth(x, y, z));
                h = h * 31 + (f.pool(x, y, z) ? 1 : 0) + (f.inLining(x, y, z) ? 2 : 0);
            }
        }
        return h;
    }

    /**
     * T7: one gate, the cavern field's tubesAt, stands in front of every tube query. Over the columns the tube field
     * reaches, a country that does not stand gives the Galleries' no-tube answers (the cavern's own density, rock for the
     * tube, no lining, rib, floor cell, pool or tube biome), and a standing one passes the tube field's own, but for the
     * roofs the cavern keeps over a tube (a tube block one or two rows under the cavern's open space reads rock).
     */
    @Test
    void everyTubeQueryIsGated() {
        long gated = 0, passed = 0, roofs = 0;
        for (long seed : new long[]{SEEDS[0], SEEDS[1]}) {
            Terrain t = new Terrain(TerrainSettings.defaults(), -64, seed);
            TubeField tf = t.tubes;
            GalleriesField g = t.galleries;
            for (int ci = -R; ci < R; ci++) for (int cj = -R; cj < R; cj++) {
                if (!tf.country(ci, cj).on) continue;
                for (int x = ci * TubeField.CELL; x < (ci + 1) * TubeField.CELL; x += 7) for (int z = cj * TubeField.CELL; z < (cj + 1) * TubeField.CELL; z += 7) {
                    int lo = tf.spanLo(x, z), hi = tf.spanHi(x, z);
                    if (lo > hi) continue;
                    boolean at = g.tubesAt(x + 0.5, z + 0.5);
                    String w = " at " + x + " " + z + " (seed " + seed + ")";
                    if (at) assertEquals(tf.floorCell(x, z), g.tubeFloorCell(x, z), "floor cell" + w);
                    else assertEquals(0, g.tubeFloorCell(x, z), "floor cell" + w);
                    for (int y = lo; y <= hi; y += 3) {
                        if (at) {
                            // the tube field's own value, but for the roofs the cavern keeps over a tube (GalleriesField.roofed)
                            double tv = tf.tube(x, y, z), gv = g.tube(x, y, z);
                            GalleriesField.Cell cc = g.column(x, z);
                            if (gv != tv) {
                                assertTrue(tv < 0 && gv == 0.5 && (cc.open(y + 1) || cc.open(y + 2)), "tube" + w + ": " + tv + " " + gv);
                                if (!cc.open(y)) roofs++;   // in the cavern's own air the roof's value changes no block
                            }
                            assertEquals(tf.shellDepth(x, y, z), g.tubeShellDepth(x, y, z), "lining" + w);
                            assertEquals(Math.min(g.density(x, y, z), gv), g.fineBlocks(x, y, z), "density" + w);
                            passed++;
                        } else {
                            assertEquals(Terrain.SOLID, g.tube(x, y, z), "tube" + w);
                            assertEquals(1e9, g.tubeShellDepth(x, y, z), "lining" + w);
                            assertFalse(g.tubeRib(x, y, z), "rib" + w);
                            assertEquals(g.density(x, y, z), g.fineBlocks(x, y, z), "density" + w);
                            assertNotEquals(Terrain.WATER, g.fluid(x, y, z), "pool" + w);
                            assertNotEquals(DeepBiome.OBSIDIAN_TUBES, g.biome(x, y, z), "biome" + w);
                            gated++;
                        }
                    }
                }
            }
        }
        System.out.println("TubeFieldTest gate: " + gated + " samples gated, " + passed + " passed (" + roofs + " tube blocks closed by a roof the cavern keeps)");
        assertTrue(gated > 2000 && passed > 2000, "too few samples: " + gated + " gated, " + passed + " passed");
    }

    /**
     * The tubes cost little: the tube part of the per-block pass (the span, the veto, the tube's value with the roofs the
     * cavern keeps over it) takes at most 60 % of the Galleries' own per-block pass in chunks with a tube (8 x 8 chunks
     * round a standing country's spot), and at most 12 % over 96 chunks drawn at random from 16 tube cells (0.6.0's wider
     * tubes: about 0.27-0.35 against 0.6-0.8 ms a chunk in tube chunks, 43-52 %, and 0.04-0.05 at random, 5 %; 0.5.1's
     * narrow tubes 0.19 against 0.45, 42 %). One thread, best of three rounds after a warm-up.
     */
    @Test
    void cost() {
        Terrain t = new Terrain(TerrainSettings.defaults(), -64, 8675309);
        GalleriesField g = t.galleries;
        int[] spot = null;
        for (int ci = -R; ci < R && spot == null; ci++) for (int cj = -R; cj < R && spot == null; cj++) spot = g.tubeSpot(ci, cj);
        assertNotNull(spot, "no standing tube country near the origin");
        int[][] near = new int[64][], random = new int[96][];
        int cx0 = Math.floorDiv(spot[0], 16) - 4, cz0 = Math.floorDiv(spot[2], 16) - 4;
        for (int c = 0; c < 64; c++) near[c] = new int[]{(cx0 + c % 8) * 16, (cz0 + c / 8) * 16};
        Random r = new Random(71);
        int span = 4 * TubeField.CELL / 16;
        for (int c = 0; c < random.length; c++) random[c] = new int[]{(r.nextInt(span) - span / 2) * 16, (r.nextInt(span) - span / 2) * 16};
        double[] inTube = {Double.MAX_VALUE, Double.MAX_VALUE}, all = {Double.MAX_VALUE, Double.MAX_VALUE};
        for (int round = 0; round < 4; round++) {
            double[] a = passes(t, near, true), b = passes(t, random, false);
            if (round == 0) continue;
            if (a[0] / a[1] < inTube[0] / inTube[1]) inTube = a;
            if (b[0] / b[1] < all[0] / all[1]) all = b;
        }
        System.out.printf(java.util.Locale.ROOT, "TubeFieldTest cost: in tube chunks %.3f ms a chunk against the Galleries' %.3f (%.0f %%); at random %.3f against %.3f (%.1f %%)%n",
                inTube[0] / 1e6, inTube[1] / 1e6, 100 * inTube[0] / inTube[1], all[0] / 1e6, all[1] / 1e6, 100 * all[0] / all[1]);
        assertTrue(inTube[0] <= 0.6 * inTube[1], "the tubes cost too much in their chunks");
        assertTrue(all[0] <= 0.12 * all[1], "the tubes cost too much on average");
    }

    /**
     * Nanoseconds a chunk for the tube part of the per-block pass and for the Galleries' own density over the layer's rows
     * under the join, over the chunks given (only those with a tube when {@code tubeOnly}): {tube, own}.
     */
    static double[] passes(Terrain t, int[][] chunks, boolean tubeOnly) {
        GalleriesField g = t.galleries;
        TubeField tf = t.tubes;
        int lo = t.shape.bottom(Terrain.GALLERIES), hi = t.join.lo - 1;
        double tube = 0, own = 0, sink = 0;
        int n = 0;
        for (int[] ch : chunks) {
            int bx = ch[0], bz = ch[1];
            long t0 = System.nanoTime();
            for (int x = bx; x < bx + 16; x++) for (int z = bz; z < bz + 16; z++) for (int y = lo; y <= hi; y++) sink += g.density(x, y, z);
            long t1 = System.nanoTime();
            boolean any = false;
            for (int x = bx; x < bx + 16; x++) for (int z = bz; z < bz + 16; z++) {
                int sl = tf.spanLo(x, z), sh = tf.spanHi(x, z);
                if (sl > sh || !g.tubesAt(x + 0.5, z + 0.5)) continue;
                any = true;
                GalleriesField.Cell cell = g.column(x, z);   // the per-block pass has it from the density
                for (int y = Math.max(lo, sl); y <= Math.min(hi, sh); y++) sink += g.roofed(cell, x, y, z);
            }
            long t2 = System.nanoTime();
            if (tubeOnly && !any) continue;
            n++;
            own += t1 - t0;
            tube += t2 - t1;
        }
        assertTrue(Double.isFinite(sink));
        assertTrue(n > 0, "no chunk with a tube");
        return new double[]{tube / n, own / n};
    }
}
