package com.explorationremix.world.feature;

import com.explorationremix.world.terrain.Plans;
import com.explorationremix.world.terrain.Route;
import com.explorationremix.world.terrain.Terrain;
import com.explorationremix.world.terrain.TerrainSettings;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The den gates' lairs ({@link DenLair}): where the den may go on the landing ring, the scraps round it and the bone
 * blocks in the floor, all from the plan alone, and the den's floor search against a made-up floor. Only the helper:
 * DenLairFeature itself is a Feature, which needs the game's registries.
 */
class DenLairTest {
    private static final long[] SEEDS = {8675309L, 12345L, -42L};

    /** Every den gate of the cells round the origin, for a few seeds, with the seed each belongs to. */
    private static List<Object[]> gates() {
        List<Object[]> out = new ArrayList<>();
        for (long seed : SEEDS) {
            Terrain t = new Terrain(TerrainSettings.defaults(), -64, seed);
            for (int cx = -2; cx <= 2; cx++)
                for (int cz = -2; cz <= 2; cz++)
                    for (Plans.Gate g : t.plans.denGates(cx, cz)) out.add(new Object[]{seed, g, t.plans.ramp(g)});
        }
        return out;
    }

    @Test
    void theDensSpotsKeepToTheRingClearOfTheRampAndTheMouth() {
        int spots = 0, lairs = 0, minSpots = Integer.MAX_VALUE, few = 0;
        for (Object[] e : gates()) {
            long seed = (Long) e[0];
            Plans.Gate g = (Plans.Gate) e[1];
            Route.Link ramp = (Route.Link) e[2];
            DenLair l = DenLair.of(seed, g, ramp);
            lairs++;
            assertFalse(l.spots.isEmpty(), "a den gate with nowhere for its den");
            minSpots = Math.min(minSpots, l.spots.size());
            if (l.spots.size() < 8) few++;
            spots += l.spots.size();
            double rampA = Math.atan2(ramp.z2() - g.uz, ramp.x2() - g.ux);
            assertEquals(rampA, l.rampAngle, 1e-9, "the ramp's direction, from the landing centre to its far end");
            Set<Long> seen = new HashSet<>();
            double prev = -1;
            for (int i = 0; i < l.spots.size(); i++) {
                int[] s = l.spots.get(i);
                assertTrue(seen.add(((long) s[0] << 32) ^ (s[1] & 0xFFFFFFFFL)), "spots are distinct");
                assertEquals(l.chunkX, s[0] >> 4, "every spot in the den's chunk (x)");
                assertEquals(l.chunkZ, s[1] >> 4, "every spot in the den's chunk (z)");
                double dx = s[0] + 0.5 - g.ux, dz = s[1] + 0.5 - g.uz, d = Math.hypot(dx, dz);
                assertTrue(d >= DenLair.RING_MIN - 1e-9 && d <= DenLair.RING_MAX + 1e-9, "on the ring: " + d);
                assertTrue(DenLair.angleOff(Math.atan2(dz, dx), rampA) >= Math.toRadians(60) - 1e-9, "at least 60 degrees off the ramp");
                assertFalse(DenLair.mouth2x2(g, s[0], s[1], l.floorY), "the lizard's 2 x 2 clear of the passage's mouth");
                // the lizard's 2 x 2 never touches the mouth's hole at the landing floor, by the plain passage numbers too
                for (int ox = 0; ox < 2; ox++)
                    for (int oz = 0; oz < 2; oz++)
                        for (int y = l.floorY - 1; y <= l.floorY; y++) {
                            double t = g.tAt(y);
                            double pd = Math.hypot(s[0] + ox + 0.5 - g.px(t), s[1] + oz + 0.5 - g.pz(t));
                            assertTrue(pd > g.radiusAt(t) + 1.1, "clear of the passage's edge and its noise");
                        }
                // the first is the lair's centre, the rest nearest it first
                double fromCentre = Math.hypot(s[0] + 1 - l.centreX, s[1] + 1 - l.centreZ);
                if (i == 0) assertEquals(0, fromCentre, 1e-9);
                assertTrue(fromCentre >= prev - 1e-9, "nearest the centre first");
                prev = fromCentre;
            }
            // what MossbackDen's tile asks of a gate den: near the landing centre and its floor
            assertTrue(Math.hypot(l.centreX - g.ux, l.centreZ - g.uz) < 24 - 2);
            assertTrue(Math.abs(l.floorY - g.uFloor) + DenLair.DEN_UP < 10);
        }
        System.out.printf("den lairs: %d, %.1f den spots each (min %d, %d with fewer than 8)%n", lairs, spots / (double) lairs, minSpots, few);
        assertTrue(minSpots >= 4, "a den has a few spots to try");
    }

    @Test
    void scrapsAndBonesStayNearTheDenAndOutOfTheMouth() {
        int[] looks = new int[4];
        int[] turns = new int[4];
        double inner = 0, outer = 0;
        int lairs = 0;
        for (Object[] e : gates()) {
            long seed = (Long) e[0];
            Plans.Gate g = (Plans.Gate) e[1];
            DenLair l = DenLair.of(seed, g, (Route.Link) e[2]);
            lairs++;
            assertTrue(l.scraps.size() >= DenLair.SCRAPS_MIN - 2 && l.scraps.size() <= DenLair.SCRAPS_MAX, "scraps: " + l.scraps.size());
            Set<Long> seen = new HashSet<>();
            for (DenLair.Point p : l.scraps) {
                assertTrue(seen.add(((long) p.x() << 32) ^ (p.z() & 0xFFFFFFFFL)), "one scrap to a column");
                double d = Math.hypot(p.x() + 0.5 - l.centreX, p.z() + 0.5 - l.centreZ);
                assertTrue(d <= DenLair.REACH + 1, "within reach of the den: " + d);
                assertTrue(DenLair.mayReach(g, p.x(), p.z(), p.x(), p.z()), "the chunk holding it decorates this lair");
                looks[p.look()]++;
                turns[p.turn()]++;
                if (d < 10) inner++;
                else if (d >= 20) outer++;
            }
            assertTrue(l.bones.size() >= DenLair.BONES_MIN && l.bones.size() <= DenLair.BONES_MAX, "bones: " + l.bones.size());
            for (DenLair.Point b : l.bones) {
                double d = Math.hypot(b.x() + 0.5 - g.ux, b.z() + 0.5 - g.uz);
                assertTrue(d >= DenLair.BONE_RING_MIN - 1 && d <= DenLair.BONE_RING_MAX + 1, "on the landing ring: " + d);
                assertFalse(l.mouth(b.x(), l.floorY, b.z()), "not in the mouth");
            }
            for (int[] s : l.spots) assertTrue(DenLair.mayReach(g, s[0], s[1], s[0], s[1]));
            // the same lair every time it is asked for
            DenLair again = DenLair.of(seed, g, (Route.Link) e[2]);
            assertEquals(l.scraps, again.scraps);
            assertEquals(l.bones, again.bones);
            assertEquals(l.spots.size(), again.spots.size());
            for (int i = 0; i < l.spots.size(); i++) assertArrayEquals(l.spots.get(i), again.spots.get(i));
        }
        for (int k = 0; k < 4; k++) {
            assertTrue(looks[k] > 0, "look " + k + " is used");
            assertTrue(turns[k] > 0, "turn " + k + " is used");
        }
        // denser near the den: per block of floor plan, the disc within 10 holds more than the ring from 20 to 30
        double innerDensity = inner / (Math.PI * 100), outerDensity = outer / (Math.PI * (900 - 400));
        System.out.printf("scraps per 100 blocks: %.2f within 10 of the den, %.2f from 20 to 30 (%d lairs)%n",
                innerDensity * 100 / lairs, outerDensity * 100 / lairs, lairs);
        assertTrue(innerDensity > outerDensity * 2, "denser near the den");
    }

    @Test
    void theMouthIsTheHoleThePassageCutsInTheLandingFloor() {
        for (Object[] e : gates()) {
            Plans.Gate g = (Plans.Gate) e[1];
            DenLair l = DenLair.of((Long) e[0], g, (Route.Link) e[2]);
            double t = g.tAt(l.floorY - 1);
            int x = (int) Math.floor(g.px(t)), z = (int) Math.floor(g.pz(t));
            assertTrue(l.mouth(x, l.floorY, z), "on the passage's centre line at the floor");
            assertTrue(l.mouth(x, l.floorY - 3, z), "down in it");
            assertFalse(l.mouth(x, l.floorY + 6, z), "the landing's air and the ramp above it are not the mouth");
            int[] s = l.spots.get(0);
            assertFalse(l.mouth(s[0], l.floorY, s[1]), "the den's spot is not the mouth");
        }
    }

    @Test
    void theDenTakesTheFirstSpotWhoseFloorFits() {
        Object[] e = gates().get(3);
        Plans.Gate g = (Plans.Gate) e[1];
        DenLair l = DenLair.of((Long) e[0], g, (Route.Link) e[2]);
        assertTrue(l.spots.size() >= 3);
        int[] first = l.spots.get(0), second = l.spots.get(1), third = l.spots.get(2);
        int floor = l.floorY;
        // a flat floor everywhere: the first spot, at the highest height that fits
        assertArrayEquals(new int[]{first[0], floor + 1, first[1]}, l.den((x, y, z) -> y <= floor + 1, null));
        // the first spot has no room, the second has it a block lower
        DenLair.Floor strict = (x, y, z) -> (x == second[0] && z == second[1] && y == floor - 1) || (x == third[0] && z == third[1] && y == floor);
        assertArrayEquals(new int[]{second[0], floor - 1, second[1]}, l.den(strict, null));
        // no room for the lizard anywhere: the den alone where it fits, still in order
        DenLair.Floor loose = (x, y, z) -> x == third[0] && z == third[1] && y == floor;
        assertArrayEquals(new int[]{third[0], floor, third[1]}, l.den((x, y, z) -> false, loose));
        // heights outside the scan are never asked about, and no floor at all gives no den
        assertNull(l.den((x, y, z) -> y > floor + DenLair.DEN_UP || y < floor - DenLair.DEN_DOWN, null));
        assertNull(l.den((x, y, z) -> false, (x, y, z) -> false));
    }
}
