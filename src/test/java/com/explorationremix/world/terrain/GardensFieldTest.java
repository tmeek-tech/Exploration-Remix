package com.explorationremix.world.terrain;

import com.explorationremix.block.TubeWormSignals;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Deep Gardens' vent field (round 2, 0.19.1) as the game builds it: the throats' exit search on the preview's patch
 * (the preview's two exits at its vents' size), the layer is dry and stands in its three bands, the per-block pass is
 * the field's answer, each deep vent's throat winds down through band 3 and comes out in a Wasteland tunnel, the gates
 * open into the caves, no vent stands in the Seep Beds, a tile is the same from any window, and every answer is the
 * same on any thread. The
 * field's blocks were checked against the preview block for block over its patch while it was ported (the preview's
 * data is not kept in the repository); these are the game's own promises.
 */
class GardensFieldTest {
    static final long SEED = 8675309L;
    /**
     * A 200 x 200 area round the seed's first standing deep vent from the origin ({@code throatsNear(0, 0, 1500, 1)}, as
     * /remix tp deepvent finds it), at (-387.0, -254.8) since the vents grew half again (GardensSites.GIRTH): the one at
     * (-247.7, 61.4) found no exit for its wider throat and stands plain.
     */
    static final int AX = -487, AZ = -355, AS = 200;
    static final double VX = -387.0, VZ = -254.8;
    private static Terrain shared;

    static synchronized Terrain terrain() {
        if (shared == null) shared = new Terrain(TerrainSettings.defaults(), -64, SEED);
        return shared;
    }

    /**
     * The vent field's block tiles hold 192 rows (round 3; 240 before), so a Deep Gardens layer of any other height, the
     * old 240 among them, is refused when the terrain is made (0.19.4; before, a taller layer read past the tiles and
     * crashed generation). The world config fixes it at 192 ({@code ConfigKeysTest.theDeepGardensHeightIsFixed}).
     */
    @Test
    void aGardensLayerOfAnotherHeightIsRefused() {
        TerrainSettings d = TerrainSettings.defaults();
        for (int h : new int[]{176, 208, 240, 512}) {
            List<LayerSettings> ls = new ArrayList<>(d.layers());
            LayerSettings g = ls.get(Terrain.GARDENS);
            ls.set(Terrain.GARDENS, new LayerSettings(g.id(), h, g.fluidDepth(), g.lava(), g.heightMin(), g.heightMax(), g.widthMin(), g.widthMax()));
            TerrainSettings s = new TerrainSettings(d.bandThickness(), d.gateSpacing(), d.passageRadius(), d.landingRadius(), List.copyOf(ls));
            assertThrows(IllegalArgumentException.class, () -> new Terrain(s, -64, SEED), "a Deep Gardens layer of " + h + " rows");
        }
        assertEquals(192, terrain().shape.height(Terrain.GARDENS));
    }

    /**
     * The preview's patch (448 x 448 from 4096, -2560 at seed 8675309, round 1's gates, their floors at rows SY - 18 and
     * 18) and its two deep vents, searched against the game's own Wasteland. At the preview's size (GardensSites.GIRTH
     * 1) the first came out off its last turn at row -41 and the second by a 36-block S at row -50 (Y -521 and -530
     * since round 3 raised the layer's bottom; -569 and -578 before), exactly as the preview's exit search found them,
     * and round 3's storeys and bigger domes left both so. Pinned again since the vents grew half again, their rings
     * with them (Rh 16.1 and 16.0, from 11.5 and 11.4): the first's wider ring meets tunnels from row -36 down but on no
     * floor its level run can walk, and at row -47 its descent breaks into one, so it finds no exit (in the game it
     * would stand plain); the second still comes out at row -50, by a 21-block outlet off a level run of 58.
     */
    @Test
    void thePreviewsThroatsComeOutWhereThePreviewPutThem() {
        int x0 = 4096, z0 = -2560, s = 448;
        GardensPlan plan = new GardensPlan(GardensPlan.foldSeed(SEED), GardensGates.fixed(List.of(
                new GardensGates.Gate(x0 + 96.0, z0 + 112.0, GardensPlan.SY - 18.0, true, null),
                new GardensGates.Gate(x0 + 336.0, z0 + 332.0, 18.0, false, null))), true, x0, z0, s, s,
                GardensPlan.HALLS, GardensPlan.BEDS, new boolean[]{true, true, true, true, false, false});
        GardensStoreys a = new GardensStoreys(plan, x0, z0, s, s, null, 1e4f, false);
        List<GardensPlan.DeepSite> deeps = plan.deepSitesNear(x0, z0, x0 + s, z0 + s, 0);
        GardensSites sa = new GardensSites(plan, GardensSites.of(a));
        Set<List<Integer>> held = new HashSet<>();
        for (GardensSites.Site v : sa.resolve(sa.candidates(x0, z0, x0 + s, z0 + s), deeps, v -> true))
            if (v.family == 1) held.add(List.of(v.k, v.i, v.kk));
        GardensStoreys b = new GardensStoreys(plan, x0, z0, s, s, v -> held.contains(List.of(v.k(), v.i(), v.kk())), 1e4f, false);
        GardensSites sb = new GardensSites(plan, GardensSites.of(b));
        GardensVents gv = new GardensVents(plan);
        GardensExits ex = new GardensExits(gv, terrain()::wasteView);
        List<String> got = new ArrayList<>();
        for (GardensPlan.DeepSite d : deeps) {
            GardensSites.Site v = sb.deepVent(d);
            if (v == null || v.form != 3) continue;
            GardensVents.Vent vt = gv.table(List.of(v), (x, y, z) -> 0).get(0);
            got.add(String.format(Locale.ROOT, "%.1f %.1f: %s", d.x() - x0, d.z() - z0, ex.find(d, vt)));
        }
        assertEquals(List.of("212.0 106.2: null",
                "223.6 195.6: Exit[g=-50, levelLen=58.0, outletDep=249.0, outletLen=21.0]"), got);
    }

    /**
     * Over the area (every 5th column, every row): the layer is dry (no water table, Taran's pick; the seep pools are
     * the blocks' crude oil), the per-block pass is the field's answer whatever the interpolation and the corners are
     * rock (so no corner opens a block the field shut), the open blocks' biomes lie in their bands' order (Vent Domes at
     * the bottom, Chimney Halls, Seep Beds on top), about a seventh of the layer is open (14.36% when pinned; 9.98% in
     * round 2's 240 rows with its smaller domes and vents) and its rooms stand about a storey tall (39 at most when
     * pinned, 32 in round 2).
     */
    @Test
    void theLayerIsDryAndStandsInItsBands() {
        Terrain t = terrain();
        int bot = t.shape.bottom(Terrain.GARDENS), top = t.shape.top(Terrain.GARDENS);
        List<String> fails = new ArrayList<>();
        long open = 0, total = 0;
        int tallest = 0;
        double[] sum = new double[3];
        long[] n = new long[3];
        for (int x = AX; x < AX + AS; x += 5)
            for (int z = AZ; z < AZ + AS; z += 5) {
                int run = 0;
                for (int y = bot; y <= top; y++) {
                    total++;
                    boolean o = t.gardens.open(x, y - bot, z);
                    if (t.fluidAt(x, y, z) != Terrain.AIR && fails.size() < 20) fails.add("fluid at " + x + " " + y + " " + z);
                    if ((t.fine(x, y, z, -1) < 0) != o || (t.fine(x, y, z, 1) < 0) != o) if (fails.size() < 20) fails.add("the per-block pass is not the field's at " + x + " " + y + " " + z);
                    if (y % 8 == 0 && t.rawDensity(x, y, z) <= 0 && fails.size() < 20) fails.add("an open corner at " + x + " " + y + " " + z);
                    if (!o) {
                        run = 0;
                        continue;
                    }
                    open++;
                    tallest = Math.max(tallest, ++run);
                    DeepBiome bi = t.biomeAt(x, y, z);
                    int band = bi == DeepBiome.VENT_DOMES ? 0 : bi == DeepBiome.CHIMNEY_HALLS ? 1 : bi == DeepBiome.SEEP_BEDS ? 2 : -1;
                    if (band < 0) {
                        if (fails.size() < 20) fails.add("biome " + bi + " at " + x + " " + y + " " + z);
                        continue;
                    }
                    sum[band] += y;
                    n[band]++;
                }
            }
        double share = open / (double) total;
        System.out.printf(Locale.ROOT, "theLayerIsDryAndStandsInItsBands: open %.2f%%, tallest run %d, mean y of the bands' open blocks %.1f %.1f %.1f (%d %d %d)%n",
                100 * share, tallest, sum[0] / n[0], sum[1] / n[1], sum[2] / n[2], n[0], n[1], n[2]);
        assertTrue(fails.isEmpty(), String.join("\n", fails));
        assertTrue(n[0] > 0 && n[1] > 0 && n[2] > 0, "a band with no open block");
        assertTrue(sum[0] / n[0] < sum[1] / n[1] && sum[1] / n[1] < sum[2] / n[2], "the bands out of order");
        assertTrue(share > 0.05 && share < 0.2, "open share " + share);
        assertTrue(tallest >= 20, "the tallest open run is " + tallest);
    }

    /**
     * The deep vent of the area (its throat down to row -40, an outlet of 28.25 off its last turn): the throat opens at
     * least one block in every row from the layer's floor down to its exit, the band 3 rows it opens are open in the
     * game's per-block pass, and at its exit it meets a Wasteland tunnel: a throat block at the exit's rows has an open
     * Wasteland block beside or under it that the throat did not carve.
     */
    @Test
    void theDeepVentsThroatWindsDownIntoTheWasteland() {
        Terrain t = terrain();
        GardensField g = t.gardens;
        int bot = t.shape.bottom(Terrain.GARDENS);
        GardensSites.Site vent = null;
        for (GardensSites.Site s : g.ventsNear(VX - 1, VZ - 1, VX + 1, VZ + 1))
            if (s.form == 3 && Math.hypot(s.x - VX, s.z - VZ) < 1) vent = s;
        assertNotNull(vent, "no deep vent at " + VX + " " + VZ);
        assertFalse(Double.isNaN(vent.throatLevel), "the deep vent has no exit");
        int gs = vent.gStand, R = (int) GardensField.UNDER_REACH;
        assertTrue(gs < -16 && gs >= GardensField.UNDER_LO, "the exit row " + gs);
        int[] perRow = new int[-GardensField.UNDER_LO];
        int meets = 0;
        List<String> fails = new ArrayList<>();
        GardensExits.Waste.View w = t.wasteView();
        byte[] col = new byte[GardensExits.SLAB], nb = new byte[GardensExits.SLAB];
        int[][] side = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int x = (int) VX - R; x <= (int) VX + R; x++)
            for (int z = (int) VZ - R; z <= (int) VZ + R; z++) {
                boolean any = false;
                for (int y = GardensField.UNDER_LO; y <= -1; y++) {
                    if (!g.throatUnder(x, y, z)) continue;
                    any = true;
                    perRow[y - GardensField.UNDER_LO]++;
                    if (y >= -16 && t.fine(x, y + bot, z, 1) >= 0 && fails.size() < 20) fails.add("band 3 shut at a throat block " + x + " " + (y + bot) + " " + z);
                }
                if (!any) continue;
                // the exit: a throat block in its rows with Wasteland air beside or under it, outside the throat
                w.column(x, z, col);
                for (int y = gs - 1; y <= Math.min(-17, gs + 4); y++) {
                    if (!g.throatUnder(x, y, z)) continue;
                    int e = y - GardensField.UNDER_LO;
                    if (e > 0 && col[e - 1] == 0 && !g.throatUnder(x, y - 1, z)) meets++;
                    for (int[] d : side) {
                        if (g.throatUnder(x + d[0], y, z + d[1])) continue;
                        w.column(x + d[0], z + d[1], nb);
                        if (nb[e] == 0) meets++;
                    }
                }
            }
        StringBuilder rows = new StringBuilder();
        for (int y = -1; y >= gs; y--) {
            int c = perRow[y - GardensField.UNDER_LO];
            if (c == 0) fails.add("no throat block in row " + y);
            if (y % 5 == 0) rows.append(' ').append(y).append(':').append(c);
        }
        System.out.printf(Locale.ROOT, "theDeepVentsThroatWindsDownIntoTheWasteland: exit row %d, level %.2f, outlet %.2f %.2f; throat blocks per row%s; %d open Wasteland faces at the exit%n",
                gs, vent.throatLevel, vent.outletDep, vent.outletLen, rows, meets);
        assertTrue(fails.isEmpty(), String.join("\n", fails));
        assertTrue(meets > 0, "the throat meets no Wasteland tunnel at its exit");
    }

    /**
     * The gates into and out of the layer: each passage's centre line is open through the layer's rows (no spire,
     * dripstone or rubble grows in a gate's landing or passage, 0.19.1) and each landing opens into the field's caves (a
     * walk from its floor gets 45 blocks out). Three gates of the seed: the entry of cell 0,-1, where a dead spire grew
     * in the landing, the exit of cell -1,0, where rubble ran into the passage, and the entry of cell 1,-1, which no way
     * left until a lifeline ({@link GardensStoreys#lifeline}) joined it to the caves outside its ring (1 of 32 gates
     * over 16 cells needed one).
     */
    @Test
    void theGatesOpenIntoTheField() throws Exception {
        Terrain t = terrain();
        int[][] gates = {{0, -1, 2}, {-1, 0, 3}, {1, -1, 2}};
        ExecutorService ex = Executors.newFixedThreadPool(gates.length);
        List<String> fails = new ArrayList<>();
        try {
            List<Future<String>> fs = new ArrayList<>();
            for (int[] g : gates) fs.add(ex.submit(() -> gateFails(t, g[0], g[1], g[2])));
            for (Future<String> f : fs) {
                String r = f.get();
                if (r != null) fails.add(r);
            }
        } finally {
            ex.shutdownNow();
        }
        assertTrue(fails.isEmpty(), String.join("\n", fails));
    }

    /** What is wrong with gate b of a cell, or null. */
    static String gateFails(Terrain t, int cx, int cz, int b) {
        int bot = t.shape.bottom(Terrain.GARDENS), top = t.shape.top(Terrain.GARDENS);
        Plans.Gate g = t.plans.gate(b, cx, cz);
        String tg = "gate " + b + " of cell " + cx + "," + cz;
        for (int i = 0; i <= 400; i++) {
            double tt = i / 400.0;
            int x = (int) Math.floor(g.px(tt)), y = (int) Math.floor(g.py(tt)), z = (int) Math.floor(g.pz(tt));
            if (y < bot || y > top) continue;
            if (!t.gardens.open(x, y - bot, z)) return tg + ": its passage is shut at " + x + " " + y + " " + z + " (code " + (t.gardens.packed(x, y - bot, z) & 31) + ")";
        }
        double lx = b == 2 ? g.lx : g.ux, lz = b == 2 ? g.lz : g.uz, fl = b == 2 ? g.lFloor : g.uFloor;
        double r = reach(t, lx, lz, fl, 70);
        System.out.printf(Locale.ROOT, "theGatesOpenIntoTheField: %s, landing at %.0f %.0f: a walk gets %.0f out%n", tg, lx, lz, r);
        return r >= 45 ? null : tg + ": a walk from its landing gets only " + r + " blocks out";
    }

    /** How far from a landing's centre the open blocks joined to it (6 ways) reach, in a box of r round it. */
    static double reach(Terrain t, double lx, double lz, double fl, int r) {
        int bot = t.shape.bottom(Terrain.GARDENS), top = t.shape.top(Terrain.GARDENS);
        int x0 = (int) Math.floor(lx) - r, z0 = (int) Math.floor(lz) - r, y0 = Math.max(bot, (int) Math.floor(fl) - 40),
                y1 = Math.min(top, (int) Math.floor(fl) + 40), w = 2 * r + 1, ny = y1 - y0 + 1;
        boolean[] open = new boolean[w * w * ny], seen = new boolean[open.length];
        for (int i = 0; i < w; i++)
            for (int k = 0; k < w; k++)
                for (int y = 0; y < ny; y++) open[(i * w + k) * ny + y] = t.gardens.open(x0 + i, y0 + y - bot, z0 + k);
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
        int ci = (int) Math.floor(lx) - x0, ck = (int) Math.floor(lz) - z0;
        for (int y = (int) Math.floor(fl) - y0 - 1; y <= (int) Math.floor(fl) - y0 + 3; y++) {
            if (y < 0 || y >= ny) continue;
            int id = (ci * w + ck) * ny + y;
            if (open[id] && !seen[id]) {
                seen[id] = true;
                q.add(id);
            }
        }
        double far = 0;
        int[][] d = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!q.isEmpty()) {
            int id = q.poll(), y = id % ny, ik = id / ny, k = ik % w, i = ik / w;
            far = Math.max(far, Math.hypot(i - ci, k - ck));
            for (int[] dd : d) {
                int ii = i + dd[0], kk = k + dd[1], yy = y + dd[2];
                if (ii < 0 || kk < 0 || yy < 0 || ii >= w || kk >= w || yy >= ny) continue;
                int o = (ii * w + kk) * ny + yy;
                if (open[o] && !seen[o]) {
                    seen[o] = true;
                    q.add(o);
                }
            }
        }
        return far;
    }

    /**
     * No vent stands in the Seep Beds (Taran, 2026-11-19: "No vents should be in the seeps"): over every column and row
     * of the area no block of a vent's walls, root or glow (its blackstone, crown, the vent beads down its sides and
     * inside it, the blackstone dithered round its foot; the fissure, vent glow and throat vein codes, 15 to 17, are no
     * longer written since round 4) lies where the band is the Seep Beds,
     * and no standing vent's rim spire does (its blocks are spire codes, so each vent's own shape is asked). The area
     * holds both, and the beds.
     */
    @Test
    void noVentStandsInTheSeepBeds() {
        GardensField g = terrain().gardens;
        List<String> fails = new ArrayList<>();
        long vent = 0, beds = 0;
        for (int x = AX; x < AX + AS; x++)
            for (int z = AZ; z < AZ + AS; z++)
                for (int y = 0; y < GardensPlan.SY; y++) {
                    int c = g.packed(x, y, z) & 31;
                    boolean b = g.band(x, y, z) == 2;
                    if (b) beds++;
                    if ((c < GardensField.C_VDEAD || c > GardensField.C_TVEIN) && c != GardensField.C_STREAK && c != GardensField.C_VBASE) continue;
                    vent++;
                    if (b && fails.size() < 20) fails.add("vent block (code " + c + ") in the Seep Beds at " + x + " " + y + " " + z);
                }
        List<GardensSites.Site> sites = g.ventsNear(AX, AZ, AX + AS, AZ + AS);
        int rims = 0;
        GardensVents.Geom geo = new GardensVents.Geom();
        for (GardensVents.Vent v : g.gv.table(sites, (x, y, z) -> 0)) {
            if (v.site.band == 2 && fails.size() < 20) fails.add("a vent of the Seep Beds at " + v.cx + " " + v.cz);
            int r = (int) v.t[47] + 1, icx = (int) Math.floor(v.cx), icz = (int) Math.floor(v.cz);
            for (int j = 0; j < (int) v.t[28]; j++)
                for (int y = (int) Math.floor(v.rim[j][2]); y < v.rim[j][2] + v.rim[j][1]; y++)
                    for (int x = icx - r; x <= icx + r; x++)
                        for (int z = icz - r; z <= icz + r; z++) {
                            if (x < AX || x >= AX + AS || z < AZ || z >= AZ + AS) continue;
                            if (g.gv.part(v, x, y, z, -999f, geo) != GardensVents.VP_RIM) continue;
                            rims++;
                            if (g.band(x, y, z) == 2 && fails.size() < 20) fails.add("a rim spire in the Seep Beds at " + x + " " + y + " " + z);
                        }
        }
        System.out.printf(Locale.ROOT, "noVentStandsInTheSeepBeds: %d vent blocks, %d rim spire blocks of %d vents, %d blocks of the Seep Beds%n", vent, rims, sites.size(), beds);
        assertTrue(vent > 0 && beds > 0, "the area has no vent or no Seep Beds");
        assertTrue(fails.isEmpty(), String.join("\n", fails));
    }

    /**
     * The crude oil lies in flat, sunken pools (2026-11-19): over the area every oil block has rock or oil on its four
     * sides and rock under it, and no oil over it, so no pool's surface steps and none spills; every pool has a way out
     * one block up (a side whose block has air over it, or oil leading to one). At least 1000 oil blocks lie in the area
     * for the check to mean anything, some in each band.
     */
    @Test
    void theOilLiesFlatAndHeld() {
        GardensField g = terrain().gardens;
        List<String> fails = new ArrayList<>();
        int[] byBand = new int[3];
        int oil = 0, shore = 0;
        for (int x = AX + 1; x < AX + AS - 1; x++)
            for (int z = AZ + 1; z < AZ + AS - 1; z++)
                for (int y = 1; y < GardensPlan.SY - 2; y++) {
                    if ((g.packed(x, y, z) & 31) != GardensField.C_OIL) continue;
                    oil++;
                    byBand[g.band(x, y, z)]++;
                    String at = x + " " + y + " " + z;
                    int under = g.packed(x, y - 1, z) & 31, over = g.packed(x, y + 1, z) & 31;
                    if ((under == GardensField.C_AIR || under == GardensField.C_OIL) && fails.size() < 20) fails.add("no rock under the oil at " + at);
                    if (over != GardensField.C_AIR && fails.size() < 20) fails.add("oil not open over it (code " + over + ") at " + at);
                    int[][] side = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                    for (int[] d : side) {
                        int c = g.packed(x + d[0], y, z + d[1]) & 31, up = g.packed(x + d[0], y + 1, z + d[1]) & 31;
                        if (c == GardensField.C_AIR && fails.size() < 20) fails.add("oil open at its side at " + at);
                        if (c != GardensField.C_AIR && c != GardensField.C_OIL && GardensField.airy(up)) shore++;
                    }
                }
        System.out.printf(Locale.ROOT, "theOilLiesFlatAndHeld: %d oil blocks (domes %d, halls %d, beds %d), %d shore sides one block up%n",
                oil, byBand[0], byBand[1], byBand[2], shore);
        assertTrue(fails.isEmpty(), String.join("\n", fails));
        assertTrue(oil >= 1000 && byBand[0] > 0 && byBand[1] > 0 && byBand[2] > 0, "too little oil to check: " + oil);
        assertTrue(shore > oil / 4, "too few ways out of the pools: " + shore + " for " + oil + " oil blocks");
    }

    /**
     * The plant life (0.20.2) stands where its blocks can live, over the area: every plant but a thread on whole rock
     * (or on its own lower half), the tube worms on rock or a live vent's ledge, every thread hanging from rock or a
     * thread and keeping its room over the floor; tall grass and tall reeds come in exactly two halves; grass on a glow
     * mat takes the mat's colour, and grass is lime only within reach of a live deep vent (and always well within it).
     * Some of every plant grows in the area, the grass most of the floors' and more than the threads.
     */
    @Test
    void thePlantsStandOnTheirFloors() {
        GardensField g = terrain().gardens;
        List<String> fails = new ArrayList<>();
        int[] n = new int[32];
        int grassOnMat = 0, lime = 0;
        Set<Integer> floorCodes = Set.of(GardensField.C_ROCK, GardensField.C_TALUS, GardensField.C_MAT, GardensField.C_MATE, GardensField.C_SKIN,
                GardensField.C_SEEP, GardensField.C_VBASE);
        Set<Integer> ceilCodes = Set.of(GardensField.C_ROCK, GardensField.C_SKIN, GardensField.C_DRIP, GardensField.C_TALUS, GardensField.C_SEALROCK);
        for (int x = AX; x < AX + AS; x++)
            for (int z = AZ; z < AZ + AS; z++)
                for (int y = 1; y < GardensPlan.SY - 1; y++) {
                    int p = g.packed(x, y, z), c = p & 31;
                    if (!GardensField.plant(c)) continue;
                    n[c]++;
                    String at = x + " " + y + " " + z;
                    int under = g.packed(x, y - 1, z) & 31, over = g.packed(x, y + 1, z) & 31;
                    boolean floor = floorCodes.contains(under);
                    switch (c) {
                        case GardensField.C_THREAD -> {
                            int top = y;
                            while ((g.packed(x, top + 1, z) & 31) == GardensField.C_THREAD) top++;
                            int bottom = y;
                            while ((g.packed(x, bottom - 1, z) & 31) == GardensField.C_THREAD) bottom--;
                            if (!ceilCodes.contains(g.packed(x, top + 1, z) & 31)) fails.add("a thread hangs from nothing at " + at);
                            for (int k = 1; k <= GardensDress.THREAD_ROOM; k++)
                                if (!GardensField.airy(g.packed(x, bottom - k, z) & 31)) { fails.add("a thread too near the floor at " + at); break; }
                        }
                        case GardensField.C_TALLG, GardensField.C_REED -> {
                            boolean upper = under == c;
                            if (upper && (g.packed(x, y - 2, z) & 31) == c) fails.add("a plant three high at " + at);
                            if (!upper && !floor) fails.add("code " + c + " on code " + under + " at " + at);
                            if (c == GardensField.C_TALLG && !upper && over != c) fails.add("tall grass with no upper half at " + at);
                        }
                        case GardensField.C_WORM -> {
                            // 0.20.6: standing on whole rock, or on a wall beside them, with room for the plumes over them
                            boolean holds = GardensField.holds(under);
                            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) holds |= GardensField.holds(g.packed(x + d[0], y, z + d[1]) & 31);
                            if (!holds) fails.add("tube worms holding to nothing at " + at);
                            if (GardensField.holds(over) || over == GardensField.C_WORM) fails.add("tube worms under code " + over + " at " + at);
                        }
                        default -> {
                            if (!floor) fails.add("code " + c + " on code " + under + " at " + at);
                        }
                    }
                    if (c == GardensField.C_GRASS || (c == GardensField.C_TALLG && under != c)) {
                        int colour = (p >> 5) & 3, below = g.packed(x, y - 1, z);
                        boolean nearLime = g.limeVentNear(x + 0.5, y, z + 0.5, GardensDress.LIME_REACH - 4.0);
                        boolean inLime = g.limeVentNear(x + 0.5, y, z + 0.5, GardensDress.LIME_REACH + 4.0);
                        if (colour == 2) lime++;
                        if (colour == 2 && !inLime) fails.add("lime grass far from a lime vent at " + at);
                        if (nearLime && colour != 2) fails.add("grass by a lime vent not lime at " + at);
                        if (!inLime && (under == GardensField.C_MAT || under == GardensField.C_MATE)) {
                            grassOnMat++;
                            if (colour != ((below >> 5) & 3)) fails.add("grass not the mat's colour at " + at);
                        }
                    }
                    if (fails.size() > 20) break;
                }
        System.out.printf(Locale.ROOT, "thePlantsStandOnTheirFloors: grass %d, tall grass %d, tube worms %d, glowworm threads %d, seep reeds %d, small mushrooms %d"
                        + " (%d grass on mats, %d lime)%n", n[GardensField.C_GRASS], n[GardensField.C_TALLG], n[GardensField.C_WORM], n[GardensField.C_THREAD],
                n[GardensField.C_REED], n[GardensField.C_SHROOM], grassOnMat, lime);
        assertTrue(fails.isEmpty(), String.join("\n", fails));
        for (int c = GardensField.C_GRASS; c <= GardensField.C_SHROOM; c++) assertTrue(n[c] > 0, "no plants of code " + c + " in the area");
        assertTrue(n[GardensField.C_GRASS] + n[GardensField.C_TALLG] > 2 * (n[GardensField.C_WORM] + n[GardensField.C_SHROOM]), "the grass the floors' most common plant");
        assertTrue(n[GardensField.C_THREAD] < n[GardensField.C_GRASS] + n[GardensField.C_TALLG], "fewer threads than grass");
    }

    /**
     * The tube worms (0.20.6; Taran: "They should also be in clusters and mostly on the lower half of vents"): over the
     * area, most worm blocks are in clumps (three or more touching), the clumps many; the worms on a vent's own rock are
     * nearly all on its lower half and none near its top; some hold to the vents' steep walls; a clump's thick middle is
     * a good share of the worms but not most. And their warning ("send a propagating signal to nearby tube worms to
     * pull in", block.TubeWormSignals) crosses a colony: passed from worm to worm no further than its hop, it reaches
     * nearly all the worms near where it began (with a hop of 3.3 blocks it reached 59% and often died in the first
     * clump).
     */
    @Test
    void theTubeWormsGrowInClumpsLowOnTheVents() {
        GardensField g = terrain().gardens;
        Set<Integer> ventRock = Set.of(GardensField.C_LIVE, GardensField.C_VLIVE, GardensField.C_CROWN, GardensField.C_TIP, GardensField.C_STREAK);
        List<int[]> worms = new ArrayList<>();
        Map<Long, Integer> at = new HashMap<>();
        int wall = 0, thick = 0;
        for (int x = AX; x < AX + AS; x++)
            for (int z = AZ; z < AZ + AS; z++)
                for (int y = 1; y < GardensPlan.SY - 1; y++) {
                    int p = g.packed(x, y, z);
                    if ((p & 31) != GardensField.C_WORM) continue;
                    int under = g.packed(x, y - 1, z) & 31;
                    boolean onWall = !GardensField.holds(under);
                    boolean onVent = ventRock.contains(under);
                    for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}})
                        if (onWall && ventRock.contains(g.packed(x + d[0], y, z + d[1]) & 31)) onVent = true;
                    if (onWall) wall++;
                    if ((p & GardensField.PLANT_BIT) != 0) thick++;
                    at.put(BlockPosKey.of(x, y, z), worms.size());
                    worms.add(new int[]{x, y, z, onVent ? 1 : 0});
                }
        int n = worms.size();
        assertTrue(n > 200, "too few tube worms to check: " + n);
        // clumps: worms touching (26 ways) join
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (int i = 0; i < n; i++) {
            int[] w = worms.get(i);
            for (int a = -1; a <= 1; a++)
                for (int b = -1; b <= 1; b++)
                    for (int c = -1; c <= 1; c++) {
                        Integer o = at.get(BlockPosKey.of(w[0] + a, w[1] + b, w[2] + c));
                        if (o != null) parent[find(parent, i)] = find(parent, o);
                    }
        }
        Map<Integer, Integer> size = new HashMap<>();
        for (int i = 0; i < n; i++) size.merge(find(parent, i), 1, Integer::sum);
        int inClumps = 0;
        for (int i = 0; i < n; i++) if (size.get(find(parent, i)) >= 3) inClumps++;
        // up the vents: each worm on a vent's own rock, by the nearest live vent's height over its sill
        int onVents = 0, low = 0, high = 0;
        for (int[] w : worms) {
            if (w[3] == 0) continue;
            GardensSites.Site best = null;
            double bd = 1e9;
            for (GardensSites.Site s : g.ventsNear(w[0] - 24, w[2] - 24, w[0] + 24, w[2] + 24)) {
                if (!s.live) continue;
                double d = Math.hypot(s.x + g.gv.ox - w[0], s.z + g.gv.oz - w[2]);
                if (d < bd) { bd = d; best = s; }
            }
            if (best == null) continue;
            onVents++;
            double hr = (w[1] - best.ys) / Math.max(1.0, best.H);
            if (hr < 0.5) low++;
            if (hr > 0.75) high++;
        }
        // the scare (block.TubeWormSignals) passed from worm to worm, each no further than its hop: of the worms within
        // its sure reach of a start (past it the scare thins out), how many it gets to, from every third worm
        int r = (int) Math.ceil(TubeWormSignals.HOP);
        double hop2 = TubeWormSignals.HOP * TubeWormSignals.HOP, sure2 = TubeWormSignals.THIN_FROM * TubeWormSignals.THIN_FROM,
                reach2 = TubeWormSignals.REACH * TubeWormSignals.REACH;
        int[][] next = new int[n][];
        for (int i = 0; i < n; i++) {
            int[] w = worms.get(i);
            List<Integer> l = new ArrayList<>();
            for (int a = -r; a <= r; a++)
                for (int b = -TubeWormSignals.HOP_UP; b <= TubeWormSignals.HOP_UP; b++)
                    for (int c = -r; c <= r; c++) {
                        if ((a == 0 && b == 0 && c == 0) || a * a + b * b + c * c > hop2) continue;
                        Integer o = at.get(BlockPosKey.of(w[0] + a, w[1] + b, w[2] + c));
                        if (o != null) l.add(o);
                    }
            next[i] = l.stream().mapToInt(Integer::intValue).toArray();
        }
        double coverSum = 0;
        int starts = 0, whole = 0;
        for (int s0 = 0; s0 < n; s0 += 3) {
            int[] st = worms.get(s0);
            boolean[] seen = new boolean[n];
            java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
            seen[s0] = true;
            queue.add(s0);
            int got = 0, within = 0;
            while (!queue.isEmpty()) {
                int i = queue.poll();
                if (dist2(worms.get(i), st) <= sure2) got++;
                for (int o : next[i])
                    if (!seen[o] && dist2(worms.get(o), st) <= reach2) {
                        seen[o] = true;
                        queue.add(o);
                    }
            }
            for (int[] w : worms) if (dist2(w, st) <= sure2) within++;
            coverSum += (double) got / within;
            starts++;
            if (got == within) whole++;
        }
        double cover = coverSum / starts;
        System.out.printf(Locale.ROOT, "theTubeWormsGrowInClumpsLowOnTheVents: %d worm blocks, %d clumps, %d in clumps of 3+, %d on walls, %d thick;"
                + " %d on vents' rock, %d on their lower half, %d high; a scare reaches %.0f%% of the worms within %.0f of its start, all of them from %d of %d starts%n",
                n, size.size(), inClumps, wall, thick, onVents, low, high, 100 * cover, TubeWormSignals.THIN_FROM, whole, starts);
        assertTrue(inClumps >= 0.75 * n, "worms not in clumps: " + inClumps + " of " + n + " in clumps of three or more");
        assertTrue(size.size() >= 20, "too few clumps: " + size.size());
        assertTrue(onVents > 50 && low >= 0.85 * onVents, "worms not on the vents' lower halves: " + low + " of " + onVents);
        assertTrue(high <= 0.01 * onVents, "worms near the vents' tops: " + high + " of " + onVents);
        assertTrue(wall >= 0.05 * n, "too few worms on the vents' walls: " + wall + " of " + n);
        assertTrue(thick >= 0.15 * n && thick <= 0.7 * n, "the clumps' thick middles: " + thick + " of " + n);
        assertTrue(cover >= 0.85, "a scare dies out in its colony: it reaches " + Math.round(100 * cover) + "% of the worms near its start");
        assertTrue(whole >= 0.5 * starts, "a scare reaches all the worms near its start from only " + whole + " of " + starts + " starts");
    }

    private static int dist2(int[] a, int[] b) {
        int dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return dx * dx + dy * dy + dz * dz;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) i = parent[i] = parent[parent[i]];
        return i;
    }

    /** A block's place as one long key. */
    private static final class BlockPosKey {
        static long of(int x, int y, int z) { return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFF); }
    }

    /**
     * A block tile's blocks never depend on the window they are made in, so tiles agree at their seams: the tile by the
     * deep vent (whose foot crosses the tile's seam at z -256) made over the game's window (40 round it) and over one
     * reaching 64 columns farther on its low sides (the vents, the rubble, the settling and the dressing all see more
     * round it) agree block for block, with its floors and bands, the oil and the seep glow along it included (since
     * the sunken pools, 2026-11-19: a pool reads only ground within GardensStoreys.OIL_R of it; before them the pools
     * spilled at the window's edges, and 782 blocks of oil or seep and 16 of dressing on them differed).
     */
    @Test
    void aTileIsTheSameFromAnyWindow() {
        GardensField g = terrain().gardens;
        int TB = GardensField.TB, BM = GardensField.BM, cx0 = Math.floorDiv((int) Math.floor(VX), TB) * TB, cz0 = Math.floorDiv((int) Math.floor(VZ), TB) * TB;
        GardensField.Tile a = g.makeTile(cx0, cz0, cx0 - BM, cz0 - BM, TB + 2 * BM, TB + 2 * BM);
        GardensField.Tile b = g.makeTile(cx0, cz0, cx0 - BM - 64, cz0 - BM - 64, TB + 2 * BM + 64, TB + 2 * BM + 64);
        int differ = 0, oil = 0, onOil = 0;
        String first = null;
        // the columns whose oil or seep glow differ, and the ones beside them
        boolean[] oilCol = new boolean[TB * TB], byOil = new boolean[TB * TB];
        for (int i = 0; i < TB; i++)
            for (int j = 0; j < TB; j++)
                for (int y = 0; y < GardensPlan.SY; y++) {
                    int q = (i * TB + j) * GardensPlan.SY + y, ca = a.blocks[q] & 31, cb = b.blocks[q] & 31;
                    if (a.blocks[q] != b.blocks[q] && (ca == GardensField.C_OIL || cb == GardensField.C_OIL || ca == GardensField.C_SEEP || cb == GardensField.C_SEEP))
                        oilCol[i * TB + j] = true;
                }
        for (int i = 0; i < TB; i++)
            for (int j = 0; j < TB; j++)
                for (int di = -1; di <= 1; di++)
                    for (int dj = -1; dj <= 1; dj++) {
                        int ii = i + di, jj = j + dj;
                        if (ii >= 0 && jj >= 0 && ii < TB && jj < TB && oilCol[ii * TB + jj]) byOil[i * TB + j] = true;
                    }
        for (int i = 0; i < TB; i++)
            for (int j = 0; j < TB; j++)
                for (int y = 0; y < GardensPlan.SY; y++) {
                    int q = (i * TB + j) * GardensPlan.SY + y, ca = a.blocks[q] & 31, cb = b.blocks[q] & 31;
                    if (a.blocks[q] == b.blocks[q]) continue;
                    if (ca == GardensField.C_OIL || cb == GardensField.C_OIL || ca == GardensField.C_SEEP || cb == GardensField.C_SEEP) {
                        oil++;
                        continue;
                    }
                    if (byOil[i * TB + j]) {
                        onOil++;
                        continue;
                    }
                    if (differ++ == 0) first = (cx0 + i) + " " + y + " " + (cz0 + j) + ": " + (a.blocks[q] & 0xFF) + " against " + (b.blocks[q] & 0xFF);
                }
        System.out.printf(Locale.ROOT, "aTileIsTheSameFromAnyWindow: tile %d,%d, %d and %d vents in the windows, %d and %d ms, %d oil or seep blocks differ, %d dressing blocks on them%n",
                cx0, cz0, a.vents, b.vents, a.ms, b.ms, oil, onOil);
        assertEquals(0, differ, "blocks that differ between the windows, first at " + first);
        assertEquals(0, oil, "oil or seep blocks that differ between the windows");
        assertEquals(0, onOil, "dressing blocks beside differing oil");
        assertArrayEquals(a.floors, b.floors, "the floors");
        assertArrayEquals(a.dom, b.dom, "the bands");
    }

    /**
     * The field is a pure function of the seed: the per-block pass, fluid, biome and the blocks' codes and colours
     * answer the same on four threads in shuffled order, from a fresh instance whose caches fill while they ask, as on
     * one (the Deep Gardens' rows over the area, and the rows under the layer the deep vent's throat opens).
     */
    @Test
    void theFieldIsPureOnAnyThread() throws Exception {
        Terrain one = terrain();
        int bot = one.shape.bottom(Terrain.GARDENS), top = one.shape.top(Terrain.GARDENS);
        Random r = new Random(17);
        int n = 20000;
        int[][] pts = new int[n][];
        for (int k = 0; k < n; k++) {
            if (k % 4 == 3) {
                // under the layer, round the deep vent
                int x = (int) VX + r.nextInt(121) - 60, z = (int) VZ + r.nextInt(121) - 60;
                pts[k] = new int[]{x, bot + GardensField.UNDER_LO + r.nextInt(-GardensField.UNDER_LO), z};
            } else pts[k] = new int[]{AX + r.nextInt(AS), bot + r.nextInt(top - bot + 1), AZ + r.nextInt(AS)};
        }
        long[] alone = new long[n];
        for (int k = 0; k < n; k++) alone[k] = probe(one, pts[k]);
        Terrain fresh = new Terrain(TerrainSettings.defaults(), -64, SEED);
        List<Integer> order = new ArrayList<>();
        for (int k = 0; k < n; k++) order.add(k);
        Collections.shuffle(order, new Random(19));
        long[] many = new long[n];
        ExecutorService ex = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int w = 0; w < 4; w++) {
                final int W = w;
                fs.add(ex.submit(() -> {
                    for (int i = W; i < n; i += 4) {
                        int k = order.get(i);
                        many[k] = probe(fresh, pts[k]);
                    }
                }));
            }
            for (Future<?> f : fs) f.get();
        } finally {
            ex.shutdownNow();
        }
        int differ = 0;
        String first = null;
        for (int k = 0; k < n; k++)
            if (alone[k] != many[k]) {
                differ++;
                if (first == null) first = pts[k][0] + " " + pts[k][1] + " " + pts[k][2];
            }
        assertEquals(0, differ, "points that answer differently on four threads, first at " + first);
    }

    /** What the game asks of a block of the Deep Gardens or of the rows under them, hashed. */
    static long probe(Terrain t, int[] p) {
        int x = p[0], y = p[1], z = p[2], row = y - t.shape.bottom(Terrain.GARDENS);
        long h = Double.doubleToLongBits(t.fine(x, y, z, 0.25));
        h = h * 31 + t.fluidAt(x, y, z);
        DeepBiome b = t.biomeAt(x, y, z);
        h = h * 31 + (b == null ? -1 : b.ordinal());
        if (row >= 0) {
            h = h * 31 + t.gardens.packed(x, row, z);
        } else h = h * 31 + (t.gardens.throatUnder(x, row, z) ? 1 : 0);
        return h;
    }
}
