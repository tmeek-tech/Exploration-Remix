package com.explorationremix.world.terrain;

import com.explorationremix.block.ChoirBrazierBlock;
import com.explorationremix.world.feature.ChoirArena;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the Hollow Choir's arena promises ({@link ChoirSites}, 0.21.9): one in about three Blue Reaches cells has one,
 * every one stands well inside its own cell on level deep dark floor with room over it, and none of them touches
 * anything else the cell planned. The arena's own shape ({@link ChoirArena}) is checked here too, since it is pure
 * geometry: the bowl has to be walkable in and out, and its fittings have to land on the stone they stand on.
 */
class ChoirSitesTest {
    private static final long[] SEEDS = {8675309L, 12345L, -42L};
    /** Cells looked at per seed: enough for the share of cells with an arena to mean something. */
    private static final int SPAN = 4;
    private static final Map<Long, Terrain> TERRAINS = new ConcurrentHashMap<>();

    private static Terrain terrain(long seed) {
        return TERRAINS.computeIfAbsent(seed, s -> new Terrain(TerrainSettings.defaults(), -64, s));
    }

    @AfterAll
    static synchronized void release() { TERRAINS.clear(); }

    private record Found(long seed, int cx, int cz, ChoirSites.Site site) {}

    /** Every cell of the span, and the arena of each (null where it has none). */
    private static List<Found> sweep() {
        List<Found> out = new ArrayList<>();
        for (long seed : SEEDS) {
            Terrain t = terrain(seed);
            for (int cx = -SPAN; cx <= SPAN; cx++)
                for (int cz = -SPAN; cz <= SPAN; cz++)
                    out.add(new Found(seed, cx, cz, t.network.choirSite(cx, cz)));
        }
        return out;
    }

    @Test
    void aboutOneCellInThreeHasAnArena() {
        List<Found> all = sweep();
        long with = all.stream().filter(f -> f.site() != null).count();
        double share = with / (double) all.size();
        // the hash turns away one cell in four, and the deep dark about half of the rest (nowhere level and roomy
        // enough that far inside the cell), which lands at about a third
        assertTrue(share > 0.2 && share < 0.45,
                "arenas in " + with + " of " + all.size() + " cells (" + Math.round(share * 100) + "%), wanted about one in three");
    }

    /**
     * 0.21.7 opened noise rifts all through the Blue Reaches, the deep dark included. A rift is far deeper than the
     * ten blocks the arena feature plugs under itself, so one under the bowl would leave the floor a plate over a
     * chasm and the apron standing on air. {@code NetRifts.keep} eases them shut round an arena; this is what says
     * the band is wide enough.
     */
    @Test
    void noNoiseRiftOpensUnderAnArena() {
        int arenas = 0, columns = 0;
        for (Found f : sweep()) {
            ChoirSites.Site s = f.site();
            if (s == null) continue;
            arenas++;
            Network w = terrain(f.seed()).network;
            int r = (int) Math.ceil(ChoirSites.RIM_R) + 4;
            for (int x = (int) s.x() - r; x <= s.x() + r; x += 2)
                for (int z = (int) s.z() - r; z <= s.z() + r; z += 2) {
                    if (Math.hypot(x - s.x(), z - s.z()) > r) continue;
                    columns++;
                    assertFalse(w.scratch(x, z, Terrain.BLUE).nOn, "seed " + f.seed() + ": a noise rift opens in the"
                            + " arena of cell " + s.cellX() + ", " + s.cellZ() + " at " + x + ", " + z);
                }
        }
        assertTrue(arenas > 10, "only " + arenas + " arenas to look at");
        assertTrue(columns > 10000, "only " + columns + " columns looked at");
    }

    @Test
    void everyArenaIsTheSameOnEveryAsking() {
        for (long seed : SEEDS) {
            Terrain a = new Terrain(TerrainSettings.defaults(), -64, seed);
            Terrain b = new Terrain(TerrainSettings.defaults(), -64, seed);
            for (int cx = -1; cx <= 1; cx++)
                for (int cz = -1; cz <= 1; cz++) {
                    ChoirSites.Site s1 = a.network.choirSite(cx, cz), s2 = b.network.choirSite(cx, cz);
                    assertEquals(s1, s2, "seed " + seed + " cell " + cx + "," + cz + ": two plans disagree");
                }
        }
    }

    @Test
    void everyArenaStandsWellInsideItsOwnCell() {
        for (Found f : sweep()) {
            if (f.site() == null) continue;
            Terrain t = terrain(f.seed());
            double cell = t.plans.cellSize, x0 = f.cx() * cell, z0 = f.cz() * cell;
            ChoirSites.Site s = f.site();
            String at = "seed " + f.seed() + " cell " + f.cx() + "," + f.cz();
            assertTrue(s.x() - x0 >= ChoirSites.INSIDE && x0 + cell - s.x() >= ChoirSites.INSIDE, at + ": too near the cell's x edge");
            assertTrue(s.z() - z0 >= ChoirSites.INSIDE && z0 + cell - s.z() >= ChoirSites.INSIDE, at + ": too near the cell's z edge");
            // which is what lets a chunk ask only about the cell its own middle is in
            double edge = ChoirSites.RIM_R + ChoirArena.APRON;
            assertTrue(t.plans.cellOf(s.x() - edge) == f.cx() && t.plans.cellOf(s.x() + edge) == f.cx(), at + ": the bowl crosses a cell edge");
            assertTrue(t.plans.cellOf(s.z() - edge) == f.cz() && t.plans.cellOf(s.z() + edge) == f.cz(), at + ": the bowl crosses a cell edge");
            assertEquals(f.cx(), s.cellX(), at);
            assertEquals(f.cz(), s.cellZ(), at);
        }
    }

    @Test
    void everyArenaHasLevelFloorAndRoomOverIt() {
        for (Found f : sweep()) {
            if (f.site() == null) continue;
            String at = "seed " + f.seed() + " cell " + f.cx() + "," + f.cz();
            ChoirSites.Site s = f.site();
            assertTrue(s.ceil() - s.rimY() >= ChoirSites.HEAD, at + ": only " + (s.ceil() - s.rimY()) + " blocks over the rim");
            assertTrue(s.tier() == 0 || s.tier() == 1, at + ": tier " + s.tier());
            // the bowl is cut below the cavern's floor, so the whole of it has rock to sit in
            assertTrue(s.floorY() < s.rimY(), at);
            assertEquals(ChoirSites.DEPTH, s.rimY() - s.floorY(), 1e-9, at);
        }
    }

    @Test
    void noArenaTouchesAnythingElseItsCellPlanned() {
        for (Found f : sweep()) {
            if (f.site() == null) continue;
            Terrain t = terrain(f.seed());
            Net n = t.network.network(Terrain.BLUE, f.cx(), f.cz());
            ChoirSites.Site s = f.site();
            String at = "seed " + f.seed() + " cell " + f.cx() + "," + f.cz();
            double keep = ChoirSites.RIM_R;
            for (Net.Chamber c : n.ch) {
                if (!c.keep) continue;
                double room = c.kind == Net.Kind.CITY ? Network.CITY_RESERVE : c.R();
                assertTrue(Math.hypot(s.x() - c.x, s.z() - c.z) >= room + keep,
                        at + ": the bowl meets a " + c.kind + " chamber");
            }
            for (Net.Way wy : n.ways)
                for (int i = 0; i < wy.n; i++)
                    assertTrue(Math.hypot(s.x() - wy.x[i], s.z() - wy.z[i]) >= wy.w[i] + keep, at + ": the bowl meets a way");
            for (Net.Shaft sh : n.shafts)
                assertTrue(Math.hypot(s.x() - sh.x, s.z() - sh.z) >= sh.r + keep, at + ": the bowl meets a climb");
            for (Plans.Gate g : t.plans.denGates(f.cx(), f.cz())) {
                assertTrue(Math.hypot(s.x() - g.ux, s.z() - g.uz) >= g.landR + keep, at + ": the bowl meets a den gate's landing");
                assertTrue(Math.hypot(s.x() - g.lx, s.z() - g.lz) >= g.landR + keep, at + ": the bowl meets a den gate's landing");
            }
        }
    }

    /** No pillar is left standing through a bowl: they would hang from the ceiling once the arena is cut. */
    @Test
    void noPillarStandsThroughABowl() {
        for (Found f : sweep()) {
            if (f.site() == null) continue;
            Terrain t = terrain(f.seed());
            Net n = t.network.network(Terrain.BLUE, f.cx(), f.cz());
            ChoirSites.Site s = f.site();
            String at = "seed " + f.seed() + " cell " + f.cx() + "," + f.cz();
            double[] pc = new double[3];
            for (int tier = 0; tier < 2; tier++) {
                double grid = NetDark.PILLAR_GRID[tier];
                for (int gx = (int) Math.floor((s.x() - ChoirSites.RIM_R) / grid) - 1;
                     gx * grid <= s.x() + ChoirSites.RIM_R + grid; gx++)
                    for (int gz = (int) Math.floor((s.z() - ChoirSites.RIM_R) / grid) - 1;
                         gz * grid <= s.z() + ChoirSites.RIM_R + grid; gz++) {
                        if (!t.network.dark.gridPillar(tier, gx, gz, pc)) continue;
                        if (!ChoirSites.inBowl(s, pc[0], pc[1], pc[2] * NetDark.PILLAR_REACH)) continue;
                        assertTrue(n.dark.dropped(tier, gx, gz),
                                at + ": a pillar still stands at " + Math.round(pc[0]) + "," + Math.round(pc[1]));
                    }
            }
        }
    }

    // --------------------------------------------------------------------------------------------- the bowl's shape

    private static ChoirSites.Site anySite() {
        for (Found f : sweep()) if (f.site() != null) return f.site();
        throw new AssertionError("no arena at all in the swept cells");
    }

    @Test
    void theBowlIsADishOfTiersFromTheFloorUpToTheRim() {
        ChoirSites.Site s = anySite();
        assertEquals((int) s.floorY(), ChoirArena.topY(s, s.x(), s.z()), "the middle is the fighting floor");
        assertEquals((int) s.rimY(), ChoirArena.topY(s, s.x() + ChoirSites.RIM_R - 0.5, s.z()), "the rim walk is the cavern's floor");
        assertEquals(ChoirArena.NONE, ChoirArena.topY(s, s.x() + ChoirSites.RIM_R + ChoirArena.APRON + 1, s.z()),
                "nothing is built past the apron");
        // out from the middle the top never falls: a dish, never a lip you could be trapped under
        int last = Integer.MIN_VALUE;
        for (double r = 0; r <= ChoirSites.RIM_R; r += 0.25) {
            int y = ChoirArena.topY(s, s.x() + r, s.z() + 0.0001);
            if (last != Integer.MIN_VALUE) assertTrue(y >= last, "the top falls again at radius " + r);
            last = y;
        }
    }

    @Test
    void bothRampsWalkFromTheRimDownToTheFloor() {
        ChoirSites.Site s = anySite();
        for (int i = 0; i < 2; i++) {
            double a = ChoirArena.rampAngle(s, i);
            int last = Integer.MIN_VALUE;
            boolean reached = false;
            for (double r = ChoirSites.RIM_R; r >= 0; r -= 0.25) {
                double x = s.x() + r * Math.cos(a), z = s.z() + r * Math.sin(a);
                int y = ChoirArena.topY(s, x, z);
                assertNotEquals(ChoirArena.NONE, y, "a hole in ramp " + i + " at radius " + r);
                // a step of one block is walked up; more is a wall
                if (last != Integer.MIN_VALUE) assertTrue(Math.abs(y - last) <= 1,
                        "ramp " + i + " steps " + Math.abs(y - last) + " blocks at radius " + r);
                last = y;
                if (y == (int) s.floorY()) reached = true;
            }
            assertTrue(reached, "ramp " + i + " never reaches the fighting floor");
        }
    }

    @Test
    void theFittingsStandOnTheirOwnStone() {
        ChoirSites.Site s = anySite();
        int[][] sockets = ChoirArena.sockets(s);
        assertEquals(3, sockets.length);
        for (int[] c : sockets) {
            assertEquals((int) s.rimY(), ChoirArena.topY(s, c[0] + 0.5, c[1] + 0.5), "a socket is not on the rim walk");
            // and not in a ramp's cut, which would put it in the way down
            assertFalse(ChoirArena.onRamp(s, c[0] + 0.5, c[1] + 0.5), "a socket stands in a ramp");
            laid(s, c[0], c[1], "a socket");
        }
        // the three are a third of a turn apart
        for (int i = 0; i < 3; i++) {
            double[] a = {sockets[i][0] - s.x(), sockets[i][1] - s.z()};
            double[] b = {sockets[(i + 1) % 3][0] - s.x(), sockets[(i + 1) % 3][1] - s.z()};
            double ang = Math.abs(Math.atan2(a[0] * b[1] - a[1] * b[0], a[0] * b[0] + a[1] * b[1]));
            assertEquals(2 * Math.PI / 3, ang, 0.12, "sockets " + i + " and " + ((i + 1) % 3) + " are not a third of a turn apart");
        }
        int[][] braziers = ChoirArena.braziers(s);
        assertEquals(ChoirArena.BRAZIER_TREAD + ChoirArena.BRAZIER_FLOOR + ChoirArena.BRAZIER_DAIS, braziers.length,
                "the three rings do not add up");
        for (int[] b : braziers) {
            assertEquals(ChoirArena.topY(s, b[0] + 0.5, b[1] + 0.5) + 1, b[2], "a brazier floats or is buried");
            laid(s, b[0], b[1], "a brazier");
        }
        for (int i = 0; i < braziers.length; i++)
            for (int j = i + 1; j < braziers.length; j++) {
                double d = Math.hypot(braziers[i][0] - braziers[j][0], braziers[i][1] - braziers[j][1]);
                assertTrue(d >= 3, "braziers " + i + " and " + j + " stand " + Math.round(d * 10) / 10.0 + " apart");
            }
        ChoirArena.Stump[] stumps = ChoirArena.stumps(s);
        assertTrue(stumps.length >= 3, "only " + stumps.length + " broken pillars");
        for (ChoirArena.Stump st : stumps) {
            assertTrue(ChoirArena.onFloor(s, st.x() + 0.5, st.z() + 0.5), "a broken pillar is off the fighting floor");
            assertFalse(ChoirArena.onRamp(s, st.x() + 0.5, st.z() + 0.5), "a broken pillar stands in a ramp");
            assertTrue(st.height() >= ChoirArena.PILLAR_LO && st.height() <= ChoirArena.PILLAR_HI, "a broken pillar is " + st.height() + " tall");
            for (int dx = 0; dx < 2; dx++)
                for (int dz = 0; dz < 2; dz++) laid(s, st.x() + dx, st.z() + dz, "a broken pillar");
        }
    }

    /**
     * Every fitting has to stand on a column the bowl lays itself, which is the footprint inside the rim. The apron
     * outside it is dressed only where the cavern's own floor comes up about level with the rim, so a fitting out
     * there would stand on whatever that cavern left, or on nothing.
     */
    private static void laid(ChoirSites.Site s, int x, int z, String what) {
        double r = Math.hypot(x + 0.5 - s.x(), z + 0.5 - s.z());
        assertTrue(r <= ChoirSites.RIM_R, what + " stands out on the apron, at radius " + Math.round(r * 10) / 10.0);
    }

    @Test
    void theSculkComesFurtherOutEachPhaseAndNeverPastTheRim() {
        ChoirSites.Site s = anySite();
        int[] taken = new int[ChoirArena.LAST_PHASE + 1];
        int r = (int) Math.ceil(ChoirSites.RIM_R) + 4;
        for (int phase = 0; phase <= ChoirArena.LAST_PHASE; phase++)
            for (int x = (int) s.x() - r; x <= s.x() + r; x++)
                for (int z = (int) s.z() - r; z <= s.z() + r; z++) {
                    if (!ChoirArena.sculkAt(s, x, z, phase)) continue;
                    taken[phase]++;
                    assertTrue(Math.hypot(x + 0.5 - s.x(), z + 0.5 - s.z()) <= ChoirSites.RIM_R,
                            "sculk past the rim at phase " + phase);
                }
        assertTrue(taken[0] > 0, "no sculk at all round the dais at rest");
        assertEquals(taken[0], taken[1], "phase 1 should not move the sculk: the arena darkens first");
        assertTrue(taken[2] > taken[1] * 1.5, "phase 2 barely spreads the sculk (" + taken[1] + " -> " + taken[2] + ")");
        assertTrue(taken[3] > taken[2] * 1.3, "phase 3 barely spreads the sculk (" + taken[2] + " -> " + taken[3] + ")");
    }

    /**
     * The fighting floor is lit at every phase, and the dais brightest of all.
     *
     * <p>A brazier's light falls a step for every block of the path it takes, not for every block of the straight
     * line, so a floor thirty-two across cannot be lit from its edge whatever the light level: two rings left the
     * middle, where the whole fight happens, at light 2. That is what the third ring on the dais is for, and why it is
     * the one that never goes out. The Choir and its tendrils are drawn lit whatever the block light round them, so
     * the fight is readable in the dark either way; the floor still has to be, because that is what a player reads a
     * wind-up against.
     */
    @Test
    void thereIsLightToFightBy() {
        int arenas = 0;
        for (Found f : sweep()) {
            if (f.site() == null) continue;
            arenas++;
            lightToFightBy(f.site());
        }
        assertTrue(arenas >= 10, "only " + arenas + " arenas to look at");
    }

    private static void lightToFightBy(ChoirSites.Site s) {
        int[][] br = ChoirArena.braziers(s);
        for (int phase = 0; phase <= ChoirArena.LAST_PHASE; phase++) {
            int floor = 0, lit = 0, dais = 0;
            for (int x = (int) s.x() - 17; x <= s.x() + 17; x++)
                for (int z = (int) s.z() - 17; z <= s.z() + 17; z++) {
                    if (!ChoirArena.onFloor(s, x + 0.5, z + 0.5)) continue;
                    int y = ChoirArena.topY(s, x + 0.5, z + 0.5);
                    if (y == ChoirArena.NONE) continue;
                    floor++;
                    int light = 0;
                    for (int i = 0; i < br.length; i++) {
                        if (!ChoirArena.brazierLit(i, phase)) continue;
                        // the path the game's light takes: a step for each block along each axis
                        int steps = Math.abs(x - br[i][0]) + Math.abs(z - br[i][1]) + Math.abs(y + 1 - br[i][2]);
                        light = Math.max(light, ChoirBrazierBlock.LIGHT - steps);
                    }
                    if (light >= 4) lit++;
                    if (Math.hypot(x + 0.5 - s.x(), z + 0.5 - s.z()) <= ChoirArena.DAIS_R) dais = Math.max(dais, light);
                }
            String at = "at phase " + phase + " of the arena at " + (int) s.x() + ", " + (int) s.z();
            assertTrue(dais >= 8, "the dais is at light " + dais + " " + at);
            assertTrue(lit * 2 > floor, lit + " of " + floor + " floor columns reach light 4 " + at);
        }
    }
}
