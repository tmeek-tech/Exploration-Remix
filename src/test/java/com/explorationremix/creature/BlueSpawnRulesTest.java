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
    void aSkeinMovesInSlowlyOnlyWhereNoneIsLeft() {
        assertTrue(wantSkein(0, false, true, false, 0.0), "none left within the refill radius: now and then");
        assertFalse(wantSkein(0, false, true, false, REFILL_CHANCE), "but not at every look");
        assertTrue(wantSkein(0, false, true, true, REFILL_CHANCE), "once due, at every look until a spot is found");
        assertFalse(wantSkein(1, false, true, false, 0.0), "one is still about: none comes");
        assertFalse(wantSkein(1, false, true, true, 0.0), "not even when one was due");
        assertFalse(wantSkein(3, false, true, false, 0.0));
        assertFalse(wantSkein(0, true, true, true, 0.0), "never in Peaceful");
        assertFalse(wantSkein(0, false, false, true, 0.0), "doMobSpawning off");
        // about one every two minutes of looks (every EVERY ticks) round a player whose caves have none
        double minutes = BlueReachesSpawner.EVERY / REFILL_CHANCE / 20.0 / 60.0;
        assertTrue(minutes > 1.0 && minutes < 4.0, minutes + " minutes");
        // the refill counts further out than it brings one out, so the Skeins it brings stop it at once
        assertTrue(REFILL_RADIUS > SKEIN_RADIUS && SKEIN_FAR < SKEIN_RADIUS);
    }

    @Test
    void aSwarmComesBackSlowlyOnlyWhereTheCavesAreShortOfThem() {
        // twice the old spawner's three to five round a player in the rift and the geodes, none in the Deep Dark (0.19.8)
        assertEquals(6, swarmsKept(Place.RIFT));
        assertEquals(10, swarmsMax(Place.RIFT));
        assertEquals(6, swarmsKept(Place.GEODES));
        assertEquals(10, swarmsMax(Place.GEODES));
        assertEquals(0, swarmsKept(Place.DEEP_DARK));
        assertEquals(0, swarmsMax(Place.DEEP_DARK));
        assertEquals(0, swarmsMax(Place.OTHER));
        assertTrue(wantSwarm(Place.RIFT, 5, true, false, 0.0), "short of swarms: now and then");
        assertFalse(wantSwarm(Place.RIFT, 5, true, false, SWARM_REFILL_CHANCE), "but not at every look");
        assertTrue(wantSwarm(Place.RIFT, 0, true, true, 0.99), "once due, at every look until a hidden spot is found");
        assertFalse(wantSwarm(Place.RIFT, 6, true, false, 0.0), "the caves have theirs: none comes");
        assertFalse(wantSwarm(Place.RIFT, 6, true, true, 0.0), "not even when one was due");
        assertTrue(wantSwarm(Place.GEODES, 0, true, false, 0.0));
        assertFalse(wantSwarm(Place.DEEP_DARK, 0, true, false, 0.0), "none kept round a player in the Deep Dark");
        assertFalse(wantSwarm(Place.DEEP_DARK, 0, true, true, 0.0), "not even when one was due");
        assertFalse(wantSwarm(Place.OTHER, 0, true, true, 0.0));
        assertFalse(wantSwarm(Place.RIFT, 0, false, true, 0.0), "doMobSpawning off");
        // about one a minute of looks (every EVERY ticks) round a player whose caves are short of them
        double minutes = BlueReachesSpawner.EVERY / SWARM_REFILL_CHANCE / 20.0 / 60.0;
        assertTrue(minutes >= 0.5 && minutes <= 2.0, minutes + " minutes");
    }

    @Test
    void theRefugeHasFirefliesButNoSkeins() {
        assertFalse(skeinsLive(Place.GEODES));
        assertTrue(skeinsLive(Place.RIFT));
        assertTrue(swarmsLive(Place.GEODES));
        assertTrue(swarmsLive(Place.RIFT));
        assertFalse(skeinSpot(Place.GEODES, -150, TOP, BOTTOM, 30, 30, 0));
        assertTrue(swarmSpot(Place.GEODES, -150, TOP, BOTTOM, 30, 30));
    }

    @Test
    void theDeepDarkHasNeither() {
        // 0.19.8, Taran's ask: no Skein and no swarm comes out in the Deep Dark, wherever the player is
        assertFalse(skeinsLive(Place.DEEP_DARK));
        assertFalse(swarmsLive(Place.DEEP_DARK));
        assertFalse(skeinSpot(Place.DEEP_DARK, -150, TOP, BOTTOM, 30, 30, 0));
        assertFalse(swarmSpot(Place.DEEP_DARK, -150, TOP, BOTTOM, 30, 30));
        // a player standing in it still has the rift's Skeins looked after round them (they come out on the rift's rock)
        assertTrue(playerCounts(false, false, Place.DEEP_DARK, -200, TOP, BOTTOM));
        assertTrue(skeinSpot(Place.RIFT, -200, TOP, BOTTOM, 30, 30, 0));
        // and nothing lives outside the Blue Reaches' own places
        assertFalse(skeinsLive(Place.OTHER));
        assertFalse(swarmsLive(Place.OTHER));
    }

    @Test
    void spotsAreFarFromEveryPlayerAndNotCrowded() {
        assertTrue(skeinSpot(Place.RIFT, -150, TOP, BOTTOM, 25, 25, 2));
        assertFalse(skeinSpot(Place.RIFT, -150, TOP, BOTTOM, 19.9, 19.9, 0), "too near a player");
        assertFalse(skeinSpot(Place.RIFT, -150, TOP, BOTTOM, 25, 25, 3), "three there already");
        assertFalse(skeinSpot(Place.RIFT, -300, TOP, BOTTOM, 25, 25, 0), "below the Blue Reaches");
        assertFalse(swarmSpot(Place.RIFT, -150, TOP, BOTTOM, 10, 10), "too near a player");
        assertTrue(swarmSpot(Place.RIFT, -150, TOP, BOTTOM, 25, 25));
        assertFalse(swarmSpot(Place.RIFT, -300, TOP, BOTTOM, 25, 25), "below the Blue Reaches");
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
        // the ring is flat, but a spot is tried up to HEIGHT_SPREAD above or below the player and a Skein's rock is
        // looked for SKEIN_SCAN further up or down (its node two blocks across): the furthest corner is outside the
        // sphere it is counted in, so the spot's own distance is what keeps it in
        double skeinWorst = Math.hypot(SKEIN_FAR + 2.0, BlueReachesSpawner.HEIGHT_SPREAD + BlueReachesSpawner.SKEIN_SCAN + 1.0);
        double swarmWorst = Math.hypot(SWARM_FAR + 1.0, BlueReachesSpawner.HEIGHT_SPREAD + 1.0);
        assertTrue(skeinWorst > SKEIN_RADIUS && swarmWorst > SWARM_RADIUS);
        for (double d = NEAR; d <= skeinWorst; d += 0.25)
            assertEquals(d <= SKEIN_RADIUS, skeinSpot(Place.RIFT, -150, TOP, BOTTOM, NEAR, d, 0), "a Skein " + d + " away");
        for (double d = NEAR; d <= swarmWorst; d += 0.25)
            assertEquals(d <= SWARM_RADIUS, swarmSpot(Place.RIFT, -150, TOP, BOTTOM, NEAR, d), "a swarm " + d + " away");
    }

    @Test
    void swarmsOfThirtyToSixtyAndNowAndThenAHugeOne() {
        int ordinary = 0, huge = 0, samples = 0;
        int lo = Integer.MAX_VALUE, hi = 0, hugeLo = Integer.MAX_VALUE, hugeHi = 0;
        int[] seen = new int[HUGE_MAX_COUNT + 1];
        for (double r = 0; r < 1.0; r += 0.0001) {
            int n = swarmSize(r);
            samples++;
            seen[n]++;
            if (n >= HUGE_MIN_COUNT) { huge++; hugeLo = Math.min(hugeLo, n); hugeHi = Math.max(hugeHi, n); }
            else { ordinary++; lo = Math.min(lo, n); hi = Math.max(hi, n); }
        }
        assertEquals(30, lo);
        assertEquals(60, hi);
        assertEquals(120, hugeLo);
        assertEquals(160, hugeHi);
        assertEquals(HUGE_SWARMS, huge / (double) samples, 0.002, "about one in eight is huge");
        assertEquals(samples, ordinary + huge, "nothing between the two ranges");
        for (int n = 61; n < 120; n++) assertEquals(0, seen[n], "no swarm of " + n);
        // every size of a range about as likely as the next
        for (int n = 31; n < 60; n++) assertEquals(seen[30], seen[n], seen[30] * 0.05 + 2, "size " + n);
        assertEquals(60, swarmSize(0.99999));
        assertEquals(120, swarmSize(0.0));
        assertEquals(60, swarmSize(1.5), "a roll past 1 counts as 1");
    }

    @Test
    void theRiftsGreatChasmKeepsFewerRoundAPlayerThanItsTunnels() {
        // 0.20.8: the chasm keeps fewer swarms, and the spawner keeps fewer round a player standing in it
        assertTrue(CHASM_KEPT >= 1 && CHASM_KEPT <= CHASM_MAX && CHASM_KEPT < swarmsKept(Place.RIFT) && CHASM_MAX < swarmsMax(Place.RIFT));
        assertEquals(CHASM_KEPT, swarmsKept(Place.RIFT, true));
        assertEquals(CHASM_MAX, swarmsMax(Place.RIFT, true));
        assertEquals(swarmsKept(Place.RIFT), swarmsKept(Place.RIFT, false), "the tunnels keep what they kept");
        assertEquals(swarmsMax(Place.RIFT), swarmsMax(Place.RIFT, false));
        assertEquals(swarmsKept(Place.GEODES), swarmsKept(Place.GEODES, true), "only the rift has the chasm");
        assertEquals(0, swarmsKept(Place.DEEP_DARK, true));
        assertTrue(wantSwarm(Place.RIFT, true, CHASM_KEPT - 1, true, false, 0.0), "short of its few: now and then");
        assertFalse(wantSwarm(Place.RIFT, true, CHASM_KEPT, true, true, 0.0), "the chasm has its few: none comes");
        assertTrue(wantSwarm(Place.RIFT, false, CHASM_KEPT, true, false, 0.0), "a player in the tunnels with as many is short");
        assertEquals(wantSwarm(Place.RIFT, 5, true, false, 0.0), wantSwarm(Place.RIFT, false, 5, true, false, 0.0));
        // a spot in the chasm only for a player standing in it
        assertTrue(swarmSpot(Place.RIFT, true, true, -150, TOP, BOTTOM, 25, 25));
        assertFalse(swarmSpot(Place.RIFT, true, false, -150, TOP, BOTTOM, 25, 25), "none brought out in the chasm for a player in the tunnels");
        assertTrue(swarmSpot(Place.RIFT, false, true, -150, TOP, BOTTOM, 25, 25), "one in a tunnel by the chasm for a player in it");
        assertTrue(swarmSpot(Place.RIFT, false, false, -150, TOP, BOTTOM, 25, 25));
        assertFalse(swarmSpot(Place.RIFT, true, true, -150, TOP, BOTTOM, 10, 10), "too near a player");
        assertFalse(swarmSpot(Place.DEEP_DARK, false, true, -150, TOP, BOTTOM, 25, 25));
    }
}
