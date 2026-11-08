package com.explorationremix.world.quake;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static com.explorationremix.world.quake.Support.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The quakes' support rule (0.9.0, spec 3.3 and 4.3): what the change log makes of a block, which placed blocks stand,
 * how those that do not come down, and how a build a player stands on is lowered under them. Blocks are plain traits
 * here, as {@link BlockKinds} reads them from the world.
 */
class SupportTest {
    // blocks as BlockKinds reads them: their traits
    private static final int AIR = T_AIRLIKE | T_NO_COLLISION, GLOW = T_AIRLIKE | T_NO_COLLISION, FIRE = T_AIRLIKE | T_NO_COLLISION;
    private static final int LAVA = T_LIQUID | T_SOURCE | T_NO_COLLISION, FLOWING_LAVA = T_LIQUID | T_NO_COLLISION;
    private static final int WATER = T_LIQUID | T_SOURCE | T_NO_COLLISION, FLOWING_WATER = T_LIQUID | T_NO_COLLISION;
    private static final int TORCH = T_NO_COLLISION, STONE = 0, SLAB = 0, BASALT = 0;
    // a ladder and scaffolding have collision for the empty context; vines of every kind and powder snow have none
    private static final int LADDER = T_CLIMB, SCAFFOLDING = T_CLIMB, VINE = T_CLIMB | T_NO_COLLISION, TWISTING = T_CLIMB | T_NO_COLLISION,
            CAVE_VINES = T_CLIMB | T_NO_COLLISION, POWDER_SNOW = T_CLIMB | T_NO_COLLISION;

    // ================================================================================================ kinds

    @Test
    void theLogTellsWhatABlockIs() {
        // open whatever the log says
        for (int open : new int[]{AIR, GLOW, FIRE, LAVA, FLOWING_LAVA, WATER, FLOWING_WATER, TORCH}) {
            assertEquals(OPEN, kind(open, false, 0, false));
            assertEquals(OPEN, kind(open, true, STONE, false), "mined, then opened again");
        }
        assertEquals(OPEN, kind(TORCH, true, AIR, false), "a torch a player put up");
        // the terrain's own blocks, and blocks put where the terrain was solid
        assertEquals(ROCK, kind(STONE, false, 0, false));
        assertEquals(ROCK, kind(SLAB, false, 0, false), "a generated slab is the terrain's");
        assertEquals(ROCK, kind(STONE, true, BASALT, false), "a mined block filled again counts as rock");
        assertEquals(ROCK, kind(LADDER, true, STONE, false), "a ladder in a mined block's place");
        // placed: where the terrain was air, the glow, fire or a fluid
        assertEquals(PLACED, kind(STONE, true, AIR, false));
        assertEquals(PLACED, kind(STONE, true, GLOW, false), "a block put into a glow spot");
        assertEquals(PLACED, kind(STONE, true, LAVA, true), "a bridge laid into the calm lake");
        assertEquals(PLACED, kind(STONE, true, FLOWING_LAVA, false), "a block put into a fall's lava");
        assertEquals(PLACED, kind(STONE, true, FIRE, false));
        assertEquals(PLACED, kind(BASALT, true, AIR, false), "ceiling debris that landed");
        // climbing blocks, held with an open first block, whatever their collision
        for (int climb : new int[]{LADDER, SCAFFOLDING, VINE, TWISTING, CAVE_VINES, POWDER_SNOW}) {
            assertEquals(CLIMB, kind(climb, true, AIR, false));
            assertEquals(CLIMB, kind(climb, true, GLOW, true));
        }
        assertEquals(OPEN, kind(VINE, false, 0, false), "a vine nobody placed");
        assertEquals(OPEN, kind(CAVE_VINES, false, 0, false));
        assertEquals(OPEN, kind(POWDER_SNOW, false, 0, false));
        // a source poured where the ground was dry; never in the sea, never in a fluid's own place; a flow is open
        assertEquals(FLUID, kind(WATER, true, AIR, false), "a water ladder");
        assertEquals(FLUID, kind(LAVA, true, GLOW, false));
        assertEquals(FLUID, kind(WATER, true, FIRE, false));
        assertEquals(OPEN, kind(FLOWING_WATER, true, AIR, false));
        assertEquals(OPEN, kind(WATER, true, AIR, true), "under the sea top it is the sea's");
        assertEquals(OPEN, kind(LAVA, true, LAVA, false), "a pool's lava put back by a bucket");
        assertEquals(OPEN, kind(WATER, true, STONE, false), "water poured into a mined block: open, as the terrain under it was not");
    }

    @Test
    void climbingAndPouredBlocksHoldUpLittle() {
        assertEquals(CLIMB, supportFor(CLIMB, CLIMB), "a ladder holds up a ladder");
        assertEquals(OPEN, supportFor(PLACED, CLIMB), "nothing rests on a vine or a ladder");
        assertEquals(OPEN, supportFor(FLUID, CLIMB));
        for (int above : new int[]{PLACED, CLIMB, FLUID}) {
            assertEquals(OPEN, supportFor(above, FLUID), "a poured source holds up nothing");
            assertEquals(PLACED, supportFor(above, PLACED));
            assertEquals(ROCK, supportFor(above, ROCK));
            assertEquals(OPEN, supportFor(above, OPEN));
        }
    }

    // ================================================================================================ the rule on columns

    /** A column of blocks from y 0 up: traits, whether the log holds it and its first block's traits; rock under 0. */
    private static final class Column {
        final int[] cur = new int[64], first = new int[64];
        final boolean[] held = new boolean[64];
        int seaTop = Integer.MIN_VALUE;

        Column() { java.util.Arrays.fill(cur, AIR); }

        /** The terrain's own block. */
        Column natural(int y, int traits) { cur[y] = traits; held[y] = false; return this; }

        /** A block put where the terrain held {@code was}. */
        Column put(int y, int traits, int was) { cur[y] = traits; held[y] = true; first[y] = was; return this; }

        Column put(int y, int traits) { return put(y, traits, AIR); }

        int kindAt(int y) { return y < 0 ? ROCK : y >= cur.length ? OPEN : Support.kind(cur[y], held[y], first[y], y < seaTop); }

        boolean falls(int y, int depth) { return Support.falls(kindAt(y), j -> kindAt(y - j), depth); }
    }

    /** A column of natural rock up to {@code rockTop} with a pillar of placed stone {@code n} high on it. */
    private static Column pillar(int rockTop, int n) {
        Column c = new Column();
        for (int y = 0; y <= rockTop; y++) c.natural(y, STONE);
        for (int k = 1; k <= n; k++) c.put(rockTop + k, STONE);
        return c;
    }

    @Test
    void stepsAndLowWallsStandAndPillarsFall() {
        Column c = pillar(3, 10);
        for (int k = 1; k <= 10; k++) assertEquals(k > 2, c.falls(3 + k, 2), "block " + k + " over rock");
        assertFalse(c.falls(3, 2), "rock never falls");
        assertFalse(c.falls(20, 2), "nor does open space");
        // supportDepth 1 and 3
        for (int k = 1; k <= 10; k++) assertEquals(k > 1, c.falls(3 + k, 1), "depth 1, block " + k);
        for (int k = 1; k <= 10; k++) assertEquals(k > 3, c.falls(3 + k, 3), "depth 3, block " + k);
        // landed ceiling debris is a placed block: one more on it still stands at 2
        Column d = new Column().natural(0, STONE).put(1, BASALT).put(2, STONE).put(3, STONE);
        assertFalse(d.falls(1, 2));
        assertFalse(d.falls(2, 2));
        assertTrue(d.falls(3, 2));
    }

    @Test
    void aBlockOnAnythingOpenFalls() {
        for (int under : new int[]{LAVA, GLOW, TORCH, WATER, AIR, FIRE, FLOWING_LAVA}) {
            Column c = new Column().natural(0, STONE).natural(1, under).put(2, STONE);
            if (under == TORCH) c.put(1, TORCH);
            assertTrue(c.falls(2, 2), "on traits " + under);
        }
        // a block whose second block down is open falls too: a 2-thick bridge out from a wall
        Column bridge = new Column().natural(0, STONE).put(5, STONE).put(6, STONE);
        assertTrue(bridge.falls(5, 2));
        assertTrue(bridge.falls(6, 2));
        // the lower block falls straight down, the upper one off its stump to a side
        assertEquals(DOWN, fallWay(true, bridge.kindAt(4), false, true));
        assertEquals(SIDE, fallWay(true, bridge.kindAt(5), false, true));
        // a block hanging from the ceiling falls
        Column hanging = new Column().natural(10, STONE).put(9, STONE);
        assertTrue(hanging.falls(9, 2));
    }

    @Test
    void ladderAndVineColumnsKeepTwo() {
        for (int climb : new int[]{LADDER, VINE, TWISTING, CAVE_VINES, SCAFFOLDING, POWDER_SNOW}) {
            Column c = new Column().natural(0, STONE);
            for (int y = 1; y <= 5; y++) c.put(y, climb);
            for (int y = 1; y <= 5; y++) assertEquals(y > 2, c.falls(y, 2), "traits " + climb + ", rung " + y);
            // a ladder on a placed block on rock is two over rock and stands
            Column on = new Column().natural(0, STONE).put(1, STONE).put(2, climb).put(3, climb);
            assertFalse(on.falls(2, 2));
            assertTrue(on.falls(3, 2));
        }
        // a block on a vine or a ladder falls: they hold up only their own kind
        Column c = new Column().natural(0, STONE).put(1, VINE).put(2, STONE);
        assertTrue(c.falls(2, 2));
        Column l = new Column().natural(0, STONE).put(1, LADDER).put(2, STONE);
        assertTrue(l.falls(2, 2));
        // a vine nobody placed holds up nothing either, and never falls itself
        Column wild = new Column().natural(0, STONE).natural(1, VINE).put(2, VINE);
        assertTrue(wild.falls(2, 2));
        assertFalse(wild.falls(1, 2));
    }

    @Test
    void aPouredSourceOverTwoIsTakenAndHoldsUpNothing() {
        Column c = new Column().natural(0, STONE).put(1, STONE).put(2, STONE).put(3, WATER);
        assertEquals(FLUID, c.kindAt(3));
        assertTrue(c.falls(3, 2), "a source 3 over rock");
        Column low = new Column().natural(0, STONE).put(1, STONE).put(2, WATER).put(3, STONE);
        assertFalse(low.falls(2, 2), "a source 2 over rock stands");
        assertTrue(low.falls(3, 2), "a block on a source falls");
        // under the sea top it is the sea's, not a poured source
        low.seaTop = 10;
        assertEquals(OPEN, low.kindAt(2));
    }

    // ================================================================================================ ways

    @Test
    void underTheLavaBlocksGoBackIntoIt() {
        for (boolean damage : new boolean[]{true, false}) {
            for (boolean old : new boolean[]{true, false}) {
                // under the sea top: the lake's rows at every phase (sea level 0 too), a pool, the risen sea
                assertEquals(GIVE_BACK, way(PLACED, true, false, damage, old));
                assertEquals(GIVE_BACK, way(CLIMB, true, false, damage, old));
                assertEquals(GIVE_BACK, way(PLACED, true, true, damage, old), "a block in the lake's lava");
                // a block put into a pool's cell over the sea
                assertEquals(GIVE_BACK, way(PLACED, false, true, damage, old));
                // a poured source is drained the way a bucket takes it, so its flow recedes
                assertEquals(DRAIN, way(FLUID, false, false, damage, old));
            }
        }
        // an entry the reset is about to take anyway is given back, but only while the reset runs
        assertEquals(GIVE_BACK, way(PLACED, false, false, true, true));
        assertEquals(FALL, way(PLACED, false, false, false, true), "the reset off: it topples whatever its generation");
        assertEquals(FALL, way(PLACED, false, false, true, false));
        assertEquals(FALL, way(CLIMB, false, false, false, false));
        // a source is drained with block updates (flags 3), not quietly
        assertEquals(3, writeFlags(false));
        // the sea top is the first row over the lake and the sea: the lake's top row is under it at sea level 0
        Column lake = new Column().natural(0, STONE).natural(1, LAVA).natural(2, LAVA).put(3, STONE, LAVA);
        lake.seaTop = 4;
        assertEquals(PLACED, lake.kindAt(3));
        assertTrue(lake.falls(3, 2));
    }

    @Test
    void takingOut() {
        assertTrue(canFallAsEntity(false, false, true));
        assertFalse(canFallAsEntity(true, false, true), "a block entity");
        assertFalse(canFallAsEntity(false, true, true), "a door, a bed, a tall plant");
        assertFalse(canFallAsEntity(false, false, false), "a top slab, a fence, a pane, a ladder");
        // a natural stone kind gives nothing while the reset is on (it regrows at its source), its item without it
        assertEquals(OUT, fallWay(true, PLACED, false, false), "a stump with no side free");
        assertFalse(drops(true, true));
        assertTrue(drops(false, true));
        assertTrue(drops(true, false), "a player's other blocks drop as vanilla's");
        assertTrue(drops(false, false));
        // a container waits while lava is under it, so nothing it holds falls into the lava
        assertTrue(waitsForLava(true, true));
        assertFalse(waitsForLava(true, false));
        assertFalse(waitsForLava(false, true));
        // a plug next to a fluid goes quietly, so the fluid gets no tick
        assertEquals(2 | 16, writeFlags(true));
        assertEquals(OUT, fallWay(false, OPEN, false, true), "what cannot fall as an entity is taken out");
        // nothing falls straight down onto a player or into lava (it would lie on the floor under the lava): taken out
        assertEquals(OUT, fallWay(true, OPEN, true, true));
        assertEquals(DOWN, fallWay(true, OPEN, false, false));
    }

    @Test
    void aWaterloggedBlockTakesItsWaterWithIt() {
        // its own leak (flowing water) is no bar to its tumbling aside; a water source, lava or a dry block's water is
        assertTrue(ownLeak(true, true, false));
        assertFalse(ownLeak(true, true, true), "a source beside it is not its leak");
        assertFalse(ownLeak(true, false, false), "lava never is");
        assertFalse(ownLeak(false, true, false), "a dry block's side in water stays barred");
        // its quiet write still wakes the flowing water beside it, so the leak recedes; never next to lava
        assertTrue(wakesWater(true, false));
        assertFalse(wakesWater(true, true));
        assertFalse(wakesWater(false, false), "a dry plug stays quiet: the water it holds back gets no tick");
    }

    // ================================================================================================ order and sides

    @Test
    void columnsAreTakenTopDown() {
        Random r = new Random(5);
        long[] keys = new long[400];
        int[][] pos = new int[keys.length][];
        for (int i = 0; i < keys.length; i++) {
            int x = r.nextInt(64) - 32 - (i % 3 == 0 ? 29_000_000 : 0), y = r.nextInt(4000) - 2048, z = r.nextInt(64) - 32 + (i % 5 == 0 ? 29_000_000 : 0);
            pos[i] = new int[]{x, y, z};
            keys[i] = topDownKey(x, y, z);
            assertEquals(x, keyX(keys[i]));
            assertEquals(y, keyY(keys[i]));
            assertEquals(z, keyZ(keys[i]));
        }
        sortTopDown(keys, keys.length);
        for (int i = 1; i < keys.length; i++) {
            int x0 = keyX(keys[i - 1]), z0 = keyZ(keys[i - 1]), y0 = keyY(keys[i - 1]);
            int x1 = keyX(keys[i]), z1 = keyZ(keys[i]), y1 = keyY(keys[i]);
            assertTrue(x0 < x1 || x0 == x1 && (z0 < z1 || z0 == z1 && y0 >= y1), "order at " + i);
        }
    }

    @Test
    void theBlocksOfAPillarTumbleDifferentWays() {
        for (int start = 0; start < 4; start++) {
            Set<Integer> got = new HashSet<>();
            for (int index = 0; index < 4; index++) got.add(side(sideStart(start, index), d -> true, d -> true));
            assertEquals(4, got.size(), "four blocks of one column go four ways");
        }
        // a side next to a fluid, over lava, in a player's column, already taken by this pass, unloaded or not open is never used
        assertFalse(sideAllowed(true, true, true, false, false, false));
        assertFalse(sideAllowed(true, true, false, true, false, false), "a block never tumbles into lava, to lie under it");
        assertFalse(sideAllowed(true, true, false, false, true, false));
        assertFalse(sideAllowed(true, true, false, false, false, true));
        assertFalse(sideAllowed(false, true, false, false, false, false));
        assertFalse(sideAllowed(true, false, false, false, false, false));
        assertTrue(sideAllowed(true, true, false, false, false, false));
        // free space under the side is preferred; otherwise the first allowed; none: taken out
        assertEquals(2, side(0, d -> d != 0, d -> d == 2));
        assertEquals(1, side(0, d -> d != 0, d -> false));
        assertEquals(3, side(3, d -> true, d -> false));
        assertEquals(-1, side(1, d -> false, d -> true));
        assertEquals(OUT, fallWay(true, PLACED, false, false));
        assertEquals(SIDE, fallWay(true, CLIMB, false, true));
        // never straight down onto a player: taken out instead
        assertEquals(DOWN, fallWay(true, OPEN, false, false));
        assertEquals(OUT, fallWay(true, OPEN, true, true));
        assertEquals(DOWN, fallWay(true, FLUID, false, false));
    }

    @Test
    void playersAndColumns() {
        // a player 0.6 wide standing on the block at (4, 10, 4): feet at 11
        assertTrue(standsOn(4, 10, 4, 4.2, 11.0, 4.2, 4.8, 4.8));
        assertTrue(standsOn(4, 10, 4, 3.5, 11.0, 4.2, 4.1, 4.8), "half off the edge, over the grown cell");
        assertFalse(standsOn(4, 10, 4, 2.0, 11.0, 4.2, 2.6, 4.8), "beside it");
        assertFalse(standsOn(4, 10, 4, 4.2, 12.0, 4.2, 4.8, 4.8), "a block higher");
        assertFalse(standsOn(4, 10, 4, 4.2, 10.0, 4.2, 4.8, 4.8), "inside it");
        assertTrue(standsOn(4, 10, 4, 4.2, 11.9, 4.2, 4.8, 4.8), "jumping just over it");
        // the column test: within half a block horizontally, between the landing row and the block
        assertTrue(inColumn(4, 4, 2, 10, 4.2, 3.0, 4.2, 4.8, 4.8, 4.8));
        assertTrue(inColumn(4, 4, 2, 10, 5.3, 3.0, 4.2, 5.9, 4.8, 4.8), "0.3 off the column's side");
        assertFalse(inColumn(4, 4, 2, 10, 5.6, 3.0, 4.2, 6.2, 4.8, 4.8), "more than half a block off");
        assertFalse(inColumn(4, 4, 2, 10, 4.2, 11.0, 4.2, 4.8, 12.8, 4.8), "standing on top of it");
        assertFalse(inColumn(4, 4, 5, 10, 4.2, 2.0, 4.2, 4.8, 3.8, 4.8), "under its landing row");
    }

    /**
     * A player towers on a column of placed stone the way players do (jump 0.42, gravity 0.08, drag 0.98): once their
     * feet are a block over the top they place the next block under them when their held click comes round, 0 to 3 ticks
     * later. Through every tick they ride the column, though for the ticks before a placement they stand on no block of
     * it: a find that only asked who stands on its top would miss them then, and take the tower from under the blocks
     * they place next (review G2).
     */
    @Test
    void aPlayerToweringRidesTheColumnThroughEveryJump() {
        for (int late = 0; late < 4; late++) {
            Column c = pillar(0, 3);
            int top = 3;
            double feet = top + 1, v = 0;
            boolean onGround = true, alwaysStanding = true;
            int clear = -1, placed = 0;
            for (int tick = 0; tick < 400 && top < 60; tick++) {
                if (onGround) {
                    v = 0.42;
                    onGround = false;
                }
                feet += v;
                if (feet <= top + 1) {
                    feet = top + 1;
                    v = 0;
                    onGround = true;
                } else {
                    v = (v - 0.08) * 0.98;
                }
                if (!onGround && feet >= top + 2) {
                    if (clear < 0) clear = tick;
                    if (tick - clear >= late) {
                        c.put(++top, STONE);
                        placed++;
                        clear = -1;
                    }
                }
                assertTrue(ridesFrom(top, feet, !onGround, c::kindAt), "late " + late + " tick " + tick + ": feet " + feet + " over the top at " + top);
                assertTrue(ridesFrom(3, feet, !onGround, c::kindAt), "late " + late + " tick " + tick + ": the third block over rock is under them");
                if (!standsOn(0, top, 0, 0.2, feet, 0.2, 0.8, 0.8)) alwaysStanding = false;
            }
            assertTrue(placed > 30, "late " + late);
            if (late > 0) assertFalse(alwaysStanding, "late " + late + ": some ticks stand on no block");
        }
    }

    @Test
    void ridingNeedsTheColumnUnderTheFeet() {
        Column c = pillar(0, 6);
        assertTrue(ridesFrom(6, 7.0, false, c::kindAt), "standing on its top");
        assertTrue(ridesFrom(3, 7.0, false, c::kindAt), "on the blocks stacked on it");
        assertTrue(ridesFrom(6, 8.3, true, c::kindAt), "in the air over its top");
        assertFalse(ridesFrom(6, 8.3, false, c::kindAt), "standing on something else beside it, over its top");
        assertFalse(ridesFrom(6, 8.6, true, c::kindAt), "higher than a jump");
        assertFalse(ridesFrom(6, 6.0, false, c::kindAt), "beside it, lower down");
        // a gap: a bridge or a ledge over the column is something else to stand on
        Column gap = pillar(0, 4).put(7, STONE);
        assertFalse(ridesFrom(3, 8.0, false, gap::kindAt), "standing on a block over a gap");
        assertTrue(ridesFrom(7, 8.0, false, gap::kindAt));
        // rock over the top: a player over it stands on the rock
        Column ledge = pillar(0, 4).natural(5, STONE);
        assertFalse(ridesFrom(4, 6.2, true, ledge::kindAt), "in the air over rock over the top");
        assertFalse(ridesFrom(4, 6.0, false, ledge::kindAt), "standing on rock over the top");
        // the pure test by itself
        assertTrue(rides(10, 11.0, false, false));
        assertTrue(rides(10, 12.4, true, true));
        assertFalse(rides(10, 12.4, true, false));
        assertFalse(rides(10, 12.4, false, true));
        assertFalse(rides(10, 12.5, true, true));
        assertFalse(rides(10, 10.9, true, true));
        assertTrue(overCell(4, 4, 3.5, 4.2, 4.1, 4.8));
        assertFalse(overCell(4, 4, 2.0, 4.2, 2.6, 4.8));
    }

    // ================================================================================================ lowering

    @Test
    void lowerTicksAsRead() {
        assertEquals(0, lowerTicks(0));
        for (int t = 1; t <= 5; t++) assertEquals(6, lowerTicks(t));
        assertEquals(6, lowerTicks(6));
        assertEquals(40, lowerTicks(40));
        assertEquals(10, feetBlockY(11.0));
        assertEquals(10, feetBlockY(10.5), "on a bottom slab's top");
    }

    @Test
    void aPillarUnderAPlayerSinksToTwo() {
        Column c = pillar(0, 12);
        double feet = 13.0;
        int steps = 0;
        while (true) {
            int t = feetBlockY(feet);
            boolean tFalls = c.falls(t, 2);
            if (!lowersOn(tFalls, true, steps)) break;
            assertEquals(SINKS, lowering(c.kindAt(t - 1), lowerTicks(6)));
            // the step takes the block under their feet; they drop one block onto the next
            c.natural(t, AIR);
            feet -= 1;
            steps++;
        }
        assertEquals(10, steps);
        assertEquals(3.0, feet, "standing on the second block over rock");
        assertEquals(PLACED, c.kindAt(1));
        assertEquals(PLACED, c.kindAt(2));
        assertEquals(OPEN, c.kindAt(3));
        // at most MAX_STEPS steps, and none once nobody stands on it
        assertFalse(lowersOn(true, true, MAX_STEPS));
        assertFalse(lowersOn(true, false, 0));
        // with lowerTicks 0 the tower goes at once after its creak
        assertEquals(AT_ONCE, lowering(PLACED, lowerTicks(0)));
        assertEquals(AT_ONCE, lowering(CLIMB, 0));
    }

    @Test
    void aBridgeUnderAPlayerGivesWay() {
        Column c = new Column().natural(0, STONE).put(6, STONE);
        assertTrue(c.falls(6, 2));
        assertEquals(GIVES_WAY, lowering(c.kindAt(5), lowerTicks(6)), "open space under it: taken out at once, no falling block");
        assertEquals(GIVES_WAY, lowering(FLUID, 6));
        assertEquals(GIVES_WAY, lowering(OPEN, 0));
    }

    @Test
    void theRulesArePure() throws Exception {
        String src = Files.readString(Path.of("src/main/java/com/explorationremix/world/quake/Support.java"));
        assertFalse(src.contains("import net.minecraft") || src.contains("import com.mojang") || src.contains("import net.minecraftforge"),
                "Support must stay free of game types: it is tested offline");
    }
}
