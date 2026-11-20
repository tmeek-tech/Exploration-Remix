package com.explorationremix.creature;

import com.explorationremix.creature.BlueSpawnRules.Place;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static com.explorationremix.creature.FireflyHomes.*;
import static org.junit.jupiter.api.Assertions.*;

/** Where the swarms live from the start: by water and glowbells most, none in the Deep Dark, fixed by the seed. */
class FireflyHomesTest {
    private static final long SEED = 8675309L;

    @Test
    void drawnToWaterAndGlowbells() {
        for (Place p : new Place[]{Place.RIFT, Place.GEODES}) {
            double bare = chance(p, false), drawn = chance(p, true);
            assertTrue(bare > 0.0, p + " has swarms over bare rock too (fewer, but not none)");
            assertEquals(DRAW, drawn / bare, 1e-9, p + ": by water or glowbells is the likelier home");
            assertTrue(drawn <= 1.0);
            assertTrue(drawn <= maxChance());
        }
        assertEquals(0.0, chance(Place.OTHER, true), "no swarm outside the Blue Reaches' places");
        assertTrue(chance(Place.GEODES, false) < chance(Place.RIFT, false),
                "the geodes' wide halls give many tries room, so each comes up less often than the rift's");
        assertEquals(1.0, chance(Place.RIFT, true), 0.02, "by the rift's water nearly every try with room is a home");
    }

    @Test
    void noneInTheDeepDark() {
        // 0.19.8, Taran's ask: not over bare rock, not by water or glowbells
        assertEquals(0.0, chance(Place.DEEP_DARK, false));
        assertEquals(0.0, chance(Place.DEEP_DARK, true));
        for (Place p : Place.values())
            assertEquals(BlueSpawnRules.swarmsLive(p), chance(p, false) > 0.0, p + ": a home only where swarms live");
    }

    @Test
    void aWorldMadeAgainHasItsSwarmsInTheSamePlaces() {
        Set<Long> seeds = new HashSet<>();
        for (int x = -15; x < 15; x++)
            for (int z = -15; z < 15; z++) {
                assertEquals(chunkSeed(SEED, x, z), chunkSeed(SEED, x, z));
                seeds.add(chunkSeed(SEED, x, z));
            }
        assertEquals(900, seeds.size(), "each chunk's tries are its own");
        assertNotEquals(chunkSeed(SEED, 3, 4), SkeinHomes.chunkSeed(SEED, 3, 4), "not the Skeins' tries");
        assertNotEquals(chunkSeed(SEED, 3, 4), chunkSeed(SEED + 1, 3, 4), "another seed, other homes");
    }

    @Test
    void homesInAChunkKeepApartAndInside() {
        assertTrue(apart(0, 0, 0, APART, 0, 0));
        assertFalse(apart(0, 0, 0, APART - 0.1, 0, 0));
        assertFalse(apart(0, 0, 0, 4, 4, 4), "height counts too");
        assertTrue(apart(0, 0, 0, 0, APART, 0));
        assertTrue(PERCH_APART <= APART && PERCH_APART >= 2 * 3.0, "two swarms' resting patches (about 3 blocks round) never overlap much");
        // a try's perch search and its water scan reach only into the neighbouring chunks' near edges
        assertTrue(EDGE >= 1 && 16 - 2 * EDGE > 0);
        assertTrue(PERCH_REACH - EDGE + 2 <= 16 && DRAW_REACH - EDGE <= 16);
        // a chunk can hold its most swarms apart from each other within its tries' room
        assertTrue(MAX_PER_CHUNK >= 1 && MAX_PER_CHUNK <= 8);
    }
}
