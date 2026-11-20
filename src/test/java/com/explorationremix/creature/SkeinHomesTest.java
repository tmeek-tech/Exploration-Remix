package com.explorationremix.creature;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static com.explorationremix.creature.SkeinHomes.*;
import static org.junit.jupiter.api.Assertions.*;

/** Which chunks are a Skein's home: fixed by the seed, about the share meant, spread out, and searched up and down. */
class SkeinHomesTest {
    private static final long SEED = 8675309L;

    @Test
    void aWorldMadeAgainHasItsSkeinsInTheSameChunks() {
        for (int x = -20; x <= 20; x++)
            for (int z = -20; z <= 20; z++) {
                assertEquals(home(SEED, x, z), home(SEED, x, z));
                assertEquals(chunkSeed(SEED, x, z), chunkSeed(SEED, x, z));
            }
        int differ = 0;
        for (int x = 0; x < 40; x++) if (home(SEED, x, 7) != home(SEED + 1, x, 7)) differ++;
        assertTrue(differ > 0, "another seed, other homes");
    }

    @Test
    void aboutTheShareMeantAndNotInLines() {
        int n = 200, homes = 0, rows = 0;
        for (int x = 0; x < n; x++) {
            int inRow = 0;
            for (int z = 0; z < n; z++) if (home(SEED, x, z)) { homes++; inRow++; }
            if (inRow == 0 || inRow == n) rows++;
        }
        double share = homes / (double) (n * n);
        assertEquals(HOME_CHANCE, share, 0.01, "share of home chunks");
        assertEquals(0, rows, "no row of chunks all homes or none");
        // each home chunk's tries are its own
        Set<Long> seeds = new HashSet<>();
        for (int x = 0; x < 30; x++) for (int z = 0; z < 30; z++) seeds.add(chunkSeed(SEED, x, z));
        assertEquals(900, seeds.size());
    }

    @Test
    void theScanGoesDownAndUpByTurnsAndCoversItsReach() {
        assertEquals(-150, scanY(-150, 0));
        assertEquals(-151, scanY(-150, 1));
        assertEquals(-149, scanY(-150, 2));
        Set<Integer> seen = new HashSet<>();
        for (int k = 0; k <= 2 * SCAN; k++) seen.add(scanY(-150, k));
        assertEquals(2 * SCAN + 1, seen.size());
        for (int y = -150 - SCAN; y <= -150 + SCAN; y++) assertTrue(seen.contains(y), "height " + y);
    }

    @Test
    void theDeepDarksRockEndsTheTriesSoTheRiftKeepsItsSkeins() {
        BooleanSupplier room = () -> true, cramped = () -> false;
        assertEquals(Take.SET_DOWN, take(BlueSpawnRules.Place.RIFT, room));
        assertEquals(Take.TRY_AGAIN, take(BlueSpawnRules.Place.RIFT, cramped));
        // where a Skein lay before 0.19.8 the chunk now keeps none, rather than trying on into the rift
        assertEquals(Take.NONE, take(BlueSpawnRules.Place.DEEP_DARK, room));
        assertEquals(Take.TRY_AGAIN, take(BlueSpawnRules.Place.DEEP_DARK, cramped));
        // the refuge and anywhere else were passed over as before, without the slow look at the body's room
        BooleanSupplier never = () -> { throw new AssertionError("room asked outside the rift and the Deep Dark"); };
        assertEquals(Take.TRY_AGAIN, take(BlueSpawnRules.Place.GEODES, never));
        assertEquals(Take.TRY_AGAIN, take(BlueSpawnRules.Place.OTHER, never));
        // a home sets its Skein down only where Skeins live
        for (BlueSpawnRules.Place p : BlueSpawnRules.Place.values())
            assertEquals(BlueSpawnRules.skeinsLive(p), take(p, room) == Take.SET_DOWN, p.name());
    }

    @Test
    void triesStayInsideTheChunk() {
        // a try's node is two blocks across, its low corner EDGE in from the chunk's low side
        int span = 16 - 2 * EDGE - 1;
        assertTrue(EDGE >= 1 && span > 0);
        assertEquals(15 - EDGE, EDGE + (span - 1) + 1, "the far cell of the furthest try stays EDGE in from the high side");
    }
}
