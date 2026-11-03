package com.explorationremix.creature;

import com.explorationremix.creature.BlueSpawnRules.Place;
import org.junit.jupiter.api.Test;

import static com.explorationremix.creature.BlueSpawnRules.*;
import static org.junit.jupiter.api.Assertions.*;

/** The Blue Reaches' spawner: which players it works round, how many it keeps near them, and where each may live. */
class BlueSpawnRulesTest {

    @Test
    void placesByBiome() {
        assertEquals(Place.RIFT, place("explorationremix:deep_blue_rift"));
        assertEquals(Place.GEODES, place("explorationremix:asurine_geodes"));
        assertEquals(Place.DEEP_DARK, place("minecraft:deep_dark"));
        assertEquals(Place.OTHER, place("minecraft:lush_caves"));
        assertEquals(Place.OTHER, place("explorationremix:lantern_lake"));
    }

    @Test
    void onlyPlayersInTheBlueReachesInSurvivalOrAdventure() {
        assertTrue(playerCounts(false, false, Place.RIFT, -150, TOP, BOTTOM));
        assertTrue(playerCounts(false, false, Place.GEODES, -100, TOP, BOTTOM), "a player in the refuge still has the caves round it");
        assertFalse(playerCounts(true, false, Place.RIFT, -150, TOP, BOTTOM), "spectators");
        assertFalse(playerCounts(false, true, Place.RIFT, -150, TOP, BOTTOM), "creative");
        assertFalse(playerCounts(false, false, Place.OTHER, -150, TOP, BOTTOM));
        // the Deep Dark of the pack's own caves, above the first band, is not the Blue Reaches
        assertFalse(playerCounts(false, false, Place.DEEP_DARK, -40, TOP, BOTTOM));
        assertTrue(playerCounts(false, false, Place.DEEP_DARK, -200, TOP, BOTTOM));
        assertFalse(playerCounts(false, false, Place.RIFT, BOTTOM - 1, TOP, BOTTOM));
    }

    @Test
    void oneOrTwoSkeinsNeverThree() {
        assertTrue(wantSkein(0, false, true, 0.99), "always one");
        assertTrue(wantSkein(1, false, true, 0.0), "a second now and then");
        assertFalse(wantSkein(1, false, true, 0.99));
        assertFalse(wantSkein(2, false, true, 0.0));
        assertFalse(wantSkein(3, false, true, 0.0));
        assertFalse(wantSkein(0, true, true, 0.0), "never in Peaceful");
        assertFalse(wantSkein(0, false, false, 0.0), "doMobSpawning off");
    }

    @Test
    void threeToFiveSwarmsAndAFewInTheDeepDark() {
        assertTrue(wantSwarm(Place.RIFT, 2, true, 0.99));
        assertTrue(wantSwarm(Place.RIFT, 3, true, 0.0));
        assertFalse(wantSwarm(Place.RIFT, 3, true, 0.99));
        assertFalse(wantSwarm(Place.RIFT, 5, true, 0.0));
        assertTrue(wantSwarm(Place.GEODES, 0, true, 0.99));
        assertTrue(wantSwarm(Place.DEEP_DARK, 0, true, 0.99));
        assertFalse(wantSwarm(Place.DEEP_DARK, 2, true, 0.0));
        assertFalse(wantSwarm(Place.RIFT, 0, false, 0.0), "doMobSpawning off");
        assertEquals(0, swarmsMax(Place.OTHER));
    }

    @Test
    void theRefugeHasFirefliesButNoSkeins() {
        assertFalse(skeinsLive(Place.GEODES));
        assertTrue(skeinsLive(Place.RIFT));
        assertTrue(skeinsLive(Place.DEEP_DARK));
        assertTrue(swarmsLive(Place.GEODES));
        assertFalse(skeinSpot(Place.GEODES, -150, TOP, BOTTOM, 30, 0));
        assertTrue(swarmSpot(Place.GEODES, -150, TOP, BOTTOM, 30, 0.9));
    }

    @Test
    void spotsAreFarFromEveryPlayerAndNotCrowded() {
        assertTrue(skeinSpot(Place.RIFT, -150, TOP, BOTTOM, 25, 2));
        assertFalse(skeinSpot(Place.RIFT, -150, TOP, BOTTOM, 19.9, 0), "too near a player");
        assertFalse(skeinSpot(Place.RIFT, -150, TOP, BOTTOM, 25, 3), "three there already");
        assertFalse(skeinSpot(Place.RIFT, -300, TOP, BOTTOM, 25, 0), "below the Blue Reaches");
        assertFalse(swarmSpot(Place.RIFT, -150, TOP, BOTTOM, 10, 0.0));
        assertTrue(swarmSpot(Place.DEEP_DARK, -150, TOP, BOTTOM, 25, 0.1));
        assertFalse(swarmSpot(Place.DEEP_DARK, -150, TOP, BOTTOM, 25, 0.9), "few in the Deep Dark");
    }

    @Test
    void theyComeOutWhereTheyAreCounted() {
        for (double r = 0; r <= 1.0; r += 0.01) {
            double s = ringDistance(r, SKEIN_FAR), w = ringDistance(r, SWARM_FAR);
            assertTrue(s >= NEAR && s <= SKEIN_FAR && SKEIN_FAR < SKEIN_RADIUS);
            assertTrue(w >= NEAR && w <= SWARM_FAR && SWARM_FAR < SWARM_RADIUS);
        }
        assertEquals(NEAR, ringDistance(-1, SKEIN_FAR));
        assertEquals(SKEIN_FAR, ringDistance(2, SKEIN_FAR));
    }

    @Test
    void swarmsOfEightToTwentyFour() {
        int lo = 100, hi = 0;
        for (double r = 0; r < 1.0; r += 0.001) {
            int n = swarmSize(r);
            lo = Math.min(lo, n);
            hi = Math.max(hi, n);
        }
        assertEquals(8, lo);
        assertEquals(24, hi);
        assertEquals(24, swarmSize(0.99999));
    }
}
