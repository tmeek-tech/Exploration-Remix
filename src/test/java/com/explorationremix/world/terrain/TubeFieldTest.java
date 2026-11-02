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
 * along every tube line as the spec's prototype measured them, with the Galleries' own density as the cavern. Bars have
 * margin over the measured values (section 10).
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
        /** level, step down 2..15, fall > 15, step up > 1, blind, over lava, too short, in the open. */
        final int[] ends = new int[8];
        final List<Integer> widths = new ArrayList<>(), headRib = new ArrayList<>(), headBay = new ArrayList<>(), floors = new ArrayList<>(), vaults = new ArrayList<>();
        long axisSteps, axisSteps2, axisSteps4;
        int pools, poolBlocks, poolBad;
        String poolBadAt = "", blindAt = "", stepAt = "";
        /** The seed being measured, and a few mouths of each seed (seed, x, the tube's walking level, z) for the biome check. */
        long seed;
        final List<long[]> mouths = new ArrayList<>();

        double km() { return (lenSingle + 2 * lenTwin) / 1000; }
        int endsTotal() { return Arrays.stream(ends).sum() - ends[6]; }

        @Override public String toString() {
            return String.format(java.util.Locale.ROOT, "%d cells, %d countries, %d standing; tube %.1f km (twin %.0f %%); runs %d: open both ends %.1f %%, walkable out"
                            + " both %.1f %%, neither %.1f %%; ends level %d, down %d, fall %d, up %d, blind %d, lava %d, open %d; axis steps of 2+ %.3f %%, 4+ %d;"
                            + " width %s; bay head %s; rib head %s; floors over W %s; vaults over W %s; pools %d (%.1f per km), %d blocks, %d unsealed%s%s",
                    cells, countries, standing, km(), 100 * 2 * lenTwin / (lenSingle + 2 * lenTwin), runs, 100.0 * runsBoth / runs, 100.0 * runsWalkBoth / runs,
                    100.0 * runsNone / runs, ends[0], ends[1], ends[2], ends[3], ends[4], ends[5], ends[7], 100.0 * axisSteps2 / Math.max(1, axisSteps), axisSteps4,
                    dist(widths), dist(headBay), dist(headRib), dist(floors), dist(vaults), pools, pools / km(), poolBlocks, poolBad, poolBadAt, blindAt)
                    + "; steps of 4+:" + stepAt + "; moves " + axisSteps;
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
        double nx = -c.sin, nz = c.cos;   // across the flow (near enough the line's normal for a width)
        // width and headroom inside the massif every 4 blocks
        for (int i = a; i <= b; i += 4) {
            tf.at(q[i][0], q[i][1], p);
            if (p.rho < 0.05 || p.fade < 1) continue;
            double ax = q[i][0] + p.o * nx, az = q[i][1] + p.o * nz;
            int bx = (int) Math.floor(ax), bz = (int) Math.floor(az);
            int[] r = tf.rows(bx, bz);
            if (r == null || r[1] <= r[0]) continue;
            int F = r[3] > r[2] ? r[3] : r[0];
            tf.at(bx + 0.5, bz + 0.5, pb);
            (tf.rib(pb) > 0.5 ? s.headRib : s.headBay).add(r[1] - F);
            s.floors.add(F - tf.W);
            s.vaults.add(r[1] - tf.W);
            int eye = F + 1, w = 1;
            for (int sgn = -1; sgn <= 1; sgn += 2) for (double d = 1; d < 20; d += 1) {
                int cx = (int) Math.floor(ax + sgn * d * nx), cz = (int) Math.floor(az + sgn * d * nz);
                int[] rr = tf.rows(cx, cz);
                if (rr == null || !(rr[0] <= eye && eye < rr[1]) || g.density(cx, eye, cz) <= 0) break;
                w++;
            }
            s.widths.add(w);
        }
        // steps of the walking level (the water's top or the floor) along the axis where the tube is enclosed: inside the
        // massif and in the cavern's rock (where the cavern is open its own floor is walked)
        int prevF = Integer.MIN_VALUE;
        for (int i = a; i <= b; i++) {
            tf.at(q[i][0], q[i][1], p);
            double ax = q[i][0], az = q[i][1];
            if (p.o > 0.5) { ax += p.o * nx; az += p.o * nz; }
            int bx = (int) Math.floor(ax), bz = (int) Math.floor(az);
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
            double ax = q[i][0], az = q[i][1];
            if (p.o > 0.5) { ax += p.o * -c.sin; az += p.o * c.cos; }
            int bx = (int) Math.floor(ax), bz = (int) Math.floor(az);
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
     * Sections are round and sized: eye-level width 5-95 % within 7..15 (median 10), headroom in the bays 5-95 % within
     * 8..13, under the ribs 2 at least everywhere (a player's two open rows), floors at least W + 46 and vaults at most
     * W + 94 over the Galleries' lava table.
     */
    @Test
    void sectionsAreRoundAndSized() {
        Stats s = stats();
        assertTrue(s.standing >= 40, "too few standing countries to measure: " + s);
        assertTrue(s.widths.size() > 5000, "too few sections measured: " + s);
        assertTrue(pct(s.widths, 0.05) >= 7 && pct(s.widths, 0.95) <= 15, "eye-level widths: " + dist(s.widths));
        assertTrue(pct(s.headBay, 0.05) >= 8 && pct(s.headBay, 0.95) <= 13, "headroom in the bays: " + dist(s.headBay));
        assertTrue(Collections.min(s.headRib) >= 2, "headroom under the ribs: " + dist(s.headRib));
        assertTrue(Collections.min(s.floors) >= TubeField.FLOOR_MIN, "floors over W: " + dist(s.floors));
        assertTrue(Collections.max(s.vaults) <= TubeField.VAULT_MAX, "vaults over W: " + dist(s.vaults));
    }

    /** Floors are walkable: under 0.1 % of 1-block moves along the axes step 2 or more (0.013 %), none 4 or more (max 3). */
    @Test
    void floorsAreWalkable() {
        Stats s = stats();
        assertTrue(s.axisSteps > 40_000, "too few moves measured: " + s);
        assertTrue(s.axisSteps2 < s.axisSteps * 0.001, "floor steps of 2 or more: " + s.axisSteps2 + " of " + s.axisSteps);
        assertEquals(0, s.axisSteps4, "floor steps of 4 or more");
    }

    /**
     * Pools are sealed and common: every block of a pool's water lies over water or rock, beside water or rock, in the
     * cavern's rock, over the flood's reach (0 violations); 4 to 12 pools per km of tube (7.5).
     */
    @Test
    void poolsAreSealedAndCommon() {
        Stats s = stats();
        assertEquals(0, s.poolBad, "pools that would leak: " + s);
        double perKm = s.pools / s.km();
        assertTrue(perKm >= 4 && perKm <= 12, "pools per km of tube: " + perKm + "; " + s);
    }

    /**
     * Mouths open into the real Galleries: runs open at both ends 95 % or more (98.5), walkable out at both ends 85 % or
     * more (90.4), blind ends at most 3 % (0.9), falls over 15 at a mouth at most 1 % (0).
     */
    @Test
    void mouthsOpen() {
        Stats s = stats();
        assertTrue(s.runs > 200, "too few runs: " + s);
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
     * air meets the cavern's), every rock block beside a tube's air (air the cavern
     * itself does not have; a pool's water is not air, its bowl dips under the section) is within 1.8 of the tube's
     * surface (the palette lines it) and in the Obsidian Tubes; every block the tube opens in the cavern's rock (air or a
     * pool's water) is in the Obsidian Tubes and no block of the cavern's own open space is (spec-tubes 12, the biome
     * check NetworkTest's galleriesSubBiomes made of the plan's tubes); and the tube spots (/remix tp, the shot planner)
     * stand in a tube's air on rock.
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
                if (g.fineBlocks(a, b, c) <= 0 && g.density(a, b, c) > 0 && g.fluid(a, b, c) == Terrain.AIR) { byTube = true; break; }
            }
            if (!byTube) continue;
            n++;
            double sd = g.tubeShellDepth(x, y, z);
            DeepBiome b = t.biomeAt(x, y, z);
            if (sd >= 1.8 || b != DeepBiome.OBSIDIAN_TUBES) bad.add(String.format("%d %d %d: shell depth %.2f, %s", x, y, z, sd, b));
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

    static long strip(TubeField f, int[] s, int W) {
        long h = 1;
        for (int i = 0; i < 64; i++) {
            int x = s[0] + (s[2] == 1 ? i : 0), z = s[1] + (s[2] == 1 ? 0 : i);
            h = h * 31 + Double.doubleToLongBits(f.massif(x, z));
            h = h * 31 + Double.doubleToLongBits(f.apronAt(x + 0.5, z + 0.5));
            h = h * 31 + Double.doubleToLongBits(f.apronFloorAt(x + 0.5, z + 0.5));
            h = h * 31 + Double.doubleToLongBits(f.region(x, z));
            h = h * 31 + f.spanLo(x, z) * 7L + f.spanHi(x, z);
            h = h * 31 + f.floorCell(x, z);
            h = h * 31 + Double.doubleToLongBits(f.ribShare(x, z));
            for (int y = W + 30; y <= W + 110; y += 4) {
                h = h * 31 + Double.doubleToLongBits(f.tube(x, y, z));
                h = h * 31 + Double.doubleToLongBits(f.shellDepth(x, y, z));
                h = h * 31 + (f.pool(x, y, z) ? 1 : 0) + (f.inLining(x, y, z) ? 2 : 0);
            }
        }
        return h;
    }

    /**
     * The tubes cost little: the tube part of the per-block pass (the span, the veto, the tube's value) takes at most 60 %
     * of the Galleries' own per-block pass in chunks with a tube (8 x 8 chunks round a standing country's spot), and at
     * most 12 % over 96 chunks drawn at random from 16 tube cells (spec 10: about 0.33 against 1.3-1.7 ms a chunk in tube
     * chunks, 0.058 on average). One thread, best of three rounds after a warm-up.
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
                for (int y = Math.max(lo, sl); y <= Math.min(hi, sh); y++) sink += tf.tube(x, y, z);
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
