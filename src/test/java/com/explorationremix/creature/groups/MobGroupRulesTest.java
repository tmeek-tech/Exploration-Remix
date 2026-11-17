package com.explorationremix.creature.groups;

import com.explorationremix.creature.groups.MobGroupRules.Home;
import com.explorationremix.creature.groups.MobGroupRules.Kind;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static com.explorationremix.creature.groups.MobGroupRules.*;
import static org.junit.jupiter.api.Assertions.*;

/** Vanilla's hostile mobs in groups: where each kind lives, how big a group is, how many round a player, how it holds together. */
class MobGroupRulesTest {

    @Test
    void eachKindLivesInItsOwnHome() {
        assertEquals(Home.WOODS, Kind.UNDEAD.home);
        assertEquals(Home.DARK_OAK, Kind.SPIDERS.home);
        assertEquals(Home.DARK_OAK, Kind.WITCHES.home);
        assertEquals(Home.GREEN_CAVES, Kind.CREEPERS.home);
        assertEquals(Home.GREEN_CAVES, Kind.SLIMES.home);
        assertEquals(Home.DESERT, Kind.HUSKS.home);
        assertEquals(Home.SNOW, Kind.STRAYS.home);
        assertEquals(Home.WATER, Kind.DROWNED.home);
        // every home has a kind, and every kind it gets is its own
        for (Home h : Home.values())
            for (double roll = 0.0; roll < 1.0; roll += 0.05) assertEquals(h, kindFor(h, roll).home, h + " at " + roll);
    }

    @Test
    void surfaceHomes() {
        assertEquals(Home.DARK_OAK, surfaceHome(true, false, true, 0.9, WOODS_CANOPY, true), "a dark oak forest first, strays or not");
        assertEquals(Home.DESERT, surfaceHome(false, true, false, 0.0, WOODS_CANOPY, false));
        assertEquals(Home.WOODS, surfaceHome(false, false, true, 0.2, WOODS_CANOPY, false));
        assertNull(surfaceHome(false, false, true, 0.0, WOODS_CANOPY, false), "an open patch of a forest biome is no woods");
        assertEquals(Home.SNOW, surfaceHome(false, false, true, 0.0, WOODS_CANOPY, true), "an open patch of a snowy forest has strays");
        assertEquals(Home.WOODS, surfaceHome(false, false, true, 0.5, WOODS_CANOPY, true), "under the trees of a snowy forest: the woods");
        assertEquals(Home.SNOW, surfaceHome(false, false, false, 0.0, WOODS_CANOPY, true));
        assertNull(surfaceHome(false, false, false, 1.0, WOODS_CANOPY, false), "plains, meadows and peaks: nothing, trees or not");
        assertEquals(Home.WOODS, surfaceHome(false, false, true, 0.0, 0.0, false), "a canopy of 0 makes the whole biome the woods");
    }

    @Test
    void caveAndWaterHomes() {
        assertEquals(Home.GREEN_CAVES, caveHome(true));
        assertNull(caveHome(false), "ordinary caves, the caves under a forest, the Blue Reaches: nothing");
        assertEquals(Home.WATER, waterHome(true, 2));
        assertEquals(Home.WATER, waterHome(true, 24));
        assertNull(waterHome(true, 1), "a puddle");
        assertNull(waterHome(false, 10), "a lake in plains has no drowned");
    }

    @Test
    void twoGroupsKeptAThirdNowAndThen() {
        assertTrue(wantGroup(0, KEPT, MOST, 0.0));
        assertTrue(wantGroup(1, KEPT, MOST, FILL_CHANCE - 1e-9));
        assertFalse(wantGroup(1, KEPT, MOST, FILL_CHANCE), "only some looks fill");
        assertTrue(wantGroup(KEPT, KEPT, MOST, TOP_UP_CHANCE - 1e-9), "the third comes now and then");
        assertFalse(wantGroup(KEPT, KEPT, MOST, TOP_UP_CHANCE));
        for (double roll = 0.0; roll < 1.0; roll += 0.01) {
            assertFalse(wantGroup(MOST, KEPT, MOST, roll), "never a fourth");
            assertFalse(wantGroup(MOST + 2, KEPT, MOST, roll));
        }
        assertTrue(TOP_UP_CHANCE < FILL_CHANCE, "a look whose roll misses the fill brings nothing (the spawner skips it)");
        assertEquals(2, KEPT);
        assertEquals(3, MOST);
    }

    @Test
    void aStragglerIsNoGroup() {
        assertFalse(stillAGroup(0));
        assertFalse(stillAGroup(1), "the last of a group, left behind, does not hold off a new one");
        assertTrue(stillAGroup(2), "a coven of two is a group");
        assertTrue(stillAGroup(5));
    }

    @Test
    void endermenAloneAndRare() {
        assertTrue(wantEnderman(0, 0.0, ENDERMAN_CHANCE));
        assertFalse(wantEnderman(0, ENDERMAN_CHANCE, ENDERMAN_CHANCE));
        assertFalse(wantEnderman(1, 0.0, ENDERMAN_CHANCE), "at most one round a player");
        assertFalse(wantEnderman(0, 0.0, 0.0), "a chance of 0 brings none");
        assertTrue(ENDERMAN_CHANCE <= 0.05);
    }

    @Test
    void groupsOfThreeToFiveCovensOfTwo() {
        Set<Integer> seen = new java.util.TreeSet<>();
        for (Kind k : EnumSet.complementOf(EnumSet.of(Kind.WITCHES)))
            for (double roll = 0.0; roll < 1.0; roll += 0.001) {
                int n = size(k, SIZE_MIN, SIZE_MAX, roll);
                assertTrue(n >= 3 && n <= 5, k + " " + n);
                seen.add(n);
            }
        assertEquals(Set.of(3, 4, 5), seen, "every size comes up");
        assertEquals(5, size(Kind.UNDEAD, SIZE_MIN, SIZE_MAX, 0.999999));
        assertEquals(3, size(Kind.UNDEAD, SIZE_MIN, SIZE_MAX, 0.0));
        for (double roll = 0.0; roll < 1.0; roll += 0.1) assertEquals(COVEN, size(Kind.WITCHES, SIZE_MIN, SIZE_MAX, roll));
        // settings out of order or too small still make a group
        assertEquals(4, size(Kind.CREEPERS, 4, 4, 0.7));
        assertEquals(2, size(Kind.CREEPERS, 1, 1, 0.5), "never fewer than two");
        int n = size(Kind.SLIMES, 6, 3, 0.5);
        assertTrue(n >= 3 && n <= 6, "min over max: " + n);
    }

    @Test
    void aGroupComesOutWholeOrNotAtAll() {
        assertEquals(2, fewest(Kind.WITCHES, 2), "a coven is both witches");
        assertEquals(3, fewest(Kind.UNDEAD, 5));
        assertEquals(3, fewest(Kind.SPIDERS, 3));
        assertEquals(2, fewest(Kind.CREEPERS, 2), "a group of two (settings) needs both");
    }

    @Test
    void mixedGroupsHaveBothZombiesAndSkeletons() {
        for (int size = 2; size <= 8; size++)
            for (double roll = 0.0; roll <= 1.0; roll += 0.01) {
                int s = skeletons(size, roll);
                assertTrue(s >= 1 && s <= size - 1, size + " at " + roll + ": " + s);
            }
        assertEquals(1, skeletons(5, 0.0));
        assertEquals(4, skeletons(5, 1.0));
    }

    @Test
    void picksByWeight() {
        double[] w = {95, 5};
        assertEquals(0, pick(w, 0.0));
        assertEquals(0, pick(w, 0.94));
        assertEquals(1, pick(w, 0.96));
        assertEquals(1, pick(w, 1.0));
        assertEquals(-1, pick(new double[]{0, 0}, 0.5), "no weights: nothing picked");
        assertEquals(1, pick(new double[]{0, 3}, 0.0));
        assertEquals(0, pick(new double[]{3, -1}, 0.99), "negative weights count as none");
        // a dark oak forest's spiders: 50 spiders, 50 cave spiders damped to 25, so a third are cave spiders
        int cave = 0;
        for (int i = 0; i < 300; i++) if (pick(new double[]{50, 50 * CAVE_SPIDER_DAMPING}, (i + 0.5) / 300.0) == 1) cave++;
        assertEquals(100, cave);
    }

    @Test
    void spotsKeepVanillasDistances() {
        assertTrue(spotOk(30, 30, 500));
        assertFalse(spotOk(NEAR - 0.1, 60, 500), "too near some player");
        assertFalse(spotOk(30, DESPAWN + 1, 500), "so far from its player that vanilla would remove it at once");
        assertFalse(spotOk(30, 30, NEAR - 1), "too near the world's spawn");
        assertTrue(spotOk(NEAR, DESPAWN, NEAR));
    }

    @Test
    void holdingTogether() {
        assertFalse(strayed(LEASH));
        assertTrue(strayed(LEASH + 0.1));
        assertTrue(caughtUp(CLOSE));
        assertFalse(caughtUp(CLOSE + 0.1));
        assertTrue(CLOSE < LEASH, "a member walking back stops well inside the leash, so it does not start again at once");
        assertTrue(LEASH < ALERT, "the whole group hears a fight");
        assertTrue(SPREAD <= LEASH, "a new group comes out within its leash");
    }

    @Test
    void aGroupNeverFallsOutAmongItself() {
        assertTrue(spared(true, true, false), "a skeleton's arrow does not hurt a zombie of its group, or of the next group");
        assertFalse(spared(true, true, true), "a creeper's blast hurts the groups round it too");
        assertFalse(spared(true, false, false), "a grouped skeleton's arrow hurts a monster in no group as in vanilla");
        assertFalse(spared(false, true, false), "and a monster in no group hurts a grouped one");
    }

    @Test
    void despawningTogether() {
        assertTrue(heldByLeader(false, 10, true), "a member with its group waits for its leader");
        assertTrue(heldByLeader(false, KEEP, true));
        assertFalse(heldByLeader(false, KEEP + 1, true), "a member lost from its group despawns on its own");
        assertFalse(heldByLeader(true, 0, true), "the leader despawns as vanilla has it");
        assertFalse(heldByLeader(false, 5, false), "a leader that never despawns holds nobody back");
    }

    @Test
    void canopy() {
        assertEquals(0.0, MobGroupRules.canopy(0, 0));
        assertEquals(0.0, MobGroupRules.canopy(0, 25));
        assertEquals(0.08, MobGroupRules.canopy(2, 25), 1e-9, "two columns in 25: a tree or two near");
        assertTrue(MobGroupRules.canopy(2, 25) >= WOODS_CANOPY);
        assertFalse(MobGroupRules.canopy(1, 25) >= WOODS_CANOPY, "one leafy column is not the woods");
    }

    @Test
    void onlyVanillasOwnMonstersFromTheBiomesOwnList() {
        assertTrue(takenOff("minecraft", true, true));
        assertFalse(takenOff("minecraft", true, false), "a witch hut's witches, a monument's guardians, an outpost's pillagers stay");
        assertFalse(takenOff("minecraft", false, true), "the pack's jungles list ocelots among their monsters: they stay");
        assertFalse(takenOff("othermod", true, true), "other mods' monsters stay");
    }

    @Test
    void distancesFitVanillas() {
        assertTrue(NEAR < SURFACE_FAR && SURFACE_FAR + SPREAD < DESPAWN, "a surface group comes out where vanilla would keep it");
        assertTrue(CAVE_NEAR < CAVE_FAR && Math.hypot(CAVE_FAR, CAVE_HEIGHT) + SPREAD < DESPAWN);
        assertTrue(COUNT_RADIUS >= SURFACE_FAR, "a group counts round the player it came out for");
        assertTrue(KEEP < DESPAWN);
    }
}
