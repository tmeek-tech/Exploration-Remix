package com.explorationremix.creature;

import com.explorationremix.block.Neon;
import com.explorationremix.world.terrain.GardensField;
import com.explorationremix.world.terrain.Terrain;
import com.explorationremix.world.terrain.TerrainSettings;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The stilt mantis herds' homes (0.20.0) as the vent field's plan places them: a herd stands on a glow mat with room
 * over it, holds 2 to 4, is lime only within reach of a live deep vent and otherwise its grove's mat colour, a cell's
 * two herds stand apart, a home is found again by its id, and the plan is the same however often it is asked.
 */
class MantisHerdsTest {
    static final long SEED = 8675309L;
    private static Terrain shared;

    static synchronized Terrain terrain() {
        if (shared == null) shared = new Terrain(TerrainSettings.defaults(), -64, SEED);
        return shared;
    }

    @Test
    void homesStandOnMatsWithRoomAndFollowTheColourRule() {
        Terrain t = terrain();
        GardensField g = t.gardens;
        assertNotNull(g);
        int bottom = t.shape.bottom(Terrain.GARDENS);
        int herds = 0;
        for (int cx = -3; cx <= 0; cx++)
            for (int cz = -3; cz <= 0; cz++) {
                List<MantisHerds.Home> hs = MantisHerds.homes(t, cx, cz);
                assertTrue(hs.size() <= 2);
                assertEquals(hs, MantisHerds.plan(t, cx, cz), "the same plan asked again");
                for (MantisHerds.Home h : hs) {
                    herds++;
                    assertEquals(cx, MantisHerds.cell(h.x()));
                    assertEquals(cz, MantisHerds.cell(h.z()));
                    int row = h.y() - bottom - 1;
                    int code = g.packed(h.x(), row, h.z()) & 31;
                    assertTrue(code == GardensField.C_MAT || code == GardensField.C_MATE, "a herd stands on a mat");
                    for (int up = 1; up <= MantisHerds.HEADROOM; up++)
                        assertEquals(GardensField.C_AIR, g.packed(h.x(), row + up, h.z()) & 31, "room over the home");
                    assertTrue(h.size() >= 2 && h.size() <= 4, "a herd of 2 to 4");
                    assertTrue(h.mats() >= (h.colour() == Neon.LIME ? MantisHerds.LIME_GROVE : MantisHerds.BIG_GROVE), "in a big grove (a lime vent's: a smaller one)");
                    boolean lime = g.limeVentNear(h.x() + 0.5, row + 1, h.z() + 0.5, MantisHerds.LIME_REACH);
                    assertEquals(lime, h.colour() == Neon.LIME, "lime exactly by a live lime vent");
                    assertSame(h, MantisHerds.byId(t, h.id()), "found again by its id");
                }
                if (hs.size() == 2) {
                    MantisHerds.Home a = hs.get(0), b = hs.get(1);
                    double across = Math.hypot(a.x() - b.x(), a.z() - b.z());
                    assertTrue(across >= MantisHerds.APART || Math.abs(a.y() - b.y()) >= MantisHerds.APART_UP, "a cell's two herds stand apart");
                }
            }
        System.out.println("herds in 16 cells: " + herds);
    }

    @Test
    void idsRoundTripForNegativeCells() {
        // the id packs the cell and the herd's index; byId reads the cell back, negative cells too
        for (int cx : new int[]{-70000, -1, 0, 5, 33000})
            for (int cz : new int[]{-123456, -1, 0, 7, 2_000_000}) {
                long id = ((long) cx << 33) | ((cz & 0xFFFFFFFFL) << 1) | 1;
                assertEquals(cx, (int) (id >> 33));
                assertEquals(cz, (int) ((id >> 1) & 0xFFFFFFFFL));
            }
    }

    /** How many herds a stretch of the Gardens holds, of which colours, in groves how big (a calibration, slow). */
    @Test
    @Tag("slow")
    void herdCensus() {
        Terrain t = terrain();
        int r = Integer.getInteger("mantis.cells", 8);
        int cells = 0, herds = 0, lime = 0, members = 0, twoInCell = 0, emptyCells = 0;
        int[] colours = new int[4], sizes = new int[5];
        long t0 = System.nanoTime();
        for (int cx = -r; cx < r; cx++)
            for (int cz = -r; cz < r; cz++) {
                cells++;
                List<MantisHerds.Home> hs = MantisHerds.homes(t, cx, cz);
                if (hs.isEmpty()) emptyCells++;
                if (hs.size() == 2) twoInCell++;
                for (MantisHerds.Home h : hs) {
                    herds++;
                    colours[h.colour().ordinal()]++;
                    sizes[h.size()]++;
                    members += h.size();
                    if (h.colour() == Neon.LIME) lime++;
                }
            }
        double secs = (System.nanoTime() - t0) / 1e9;
        // the lime vents of the stretch, and how many keep a lime herd within reach
        int vents = 0, kept = 0;
        for (double[] v : t.gardens.limeVents(-r * MantisHerds.CELL, -r * MantisHerds.CELL, r * MantisHerds.CELL - 1e-9, r * MantisHerds.CELL - 1e-9)) {
            vents++;
            boolean has = false;
            for (int cx = MantisHerds.cell((int) Math.floor(v[0])) - 1; cx <= MantisHerds.cell((int) Math.floor(v[0])) + 1; cx++)
                for (int cz = MantisHerds.cell((int) Math.floor(v[1])) - 1; cz <= MantisHerds.cell((int) Math.floor(v[1])) + 1; cz++)
                    for (MantisHerds.Home h : MantisHerds.homes(t, cx, cz))
                        if (h.colour() == Neon.LIME && GardensField.inReach(v, h.x() + 0.5, h.y() - t.shape.bottom(Terrain.GARDENS), h.z() + 0.5, MantisHerds.LIME_REACH)) has = true;
            if (has) kept++;
            System.out.printf(Locale.ROOT, "  lime vent at %.0f %.0f: %s%n", v[0], v[1], has ? "a lime herd by it" : "no herd");
        }
        System.out.printf(Locale.ROOT, "  %d lime vents, %d with a lime herd%n", vents, kept);
        double km2 = cells * (double) (MantisHerds.CELL * MantisHerds.CELL) / 1_000_000.0;
        System.out.printf(Locale.ROOT, "census: %d cells (%.2f km^2) in %.1f s: %d herds (%.1f per km^2, %.2f per cell), %d cells empty, %d with two;%n",
                cells, km2, secs, herds, herds / km2, herds / (double) cells, emptyCells, twoInCell);
        System.out.printf(Locale.ROOT, "  colours magenta %d, violet %d, lime %d, light blue %d; sizes 2: %d, 3: %d, 4: %d; %d mantises%n",
                colours[0], colours[1], colours[2], colours[3], sizes[2], sizes[3], sizes[4], members);
        assertTrue(herds > 0);
    }
}
