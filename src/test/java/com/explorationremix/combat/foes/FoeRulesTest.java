package com.explorationremix.combat.foes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The numbers of vanilla's hostile mobs' fights (0.23.8): leaps that land, shots that lead, blows with an opening. */
class FoeRulesTest {
    /** Most blocks' friction (stone, dirt, deepslate): what the ground holds of a leap the tick it sets off. */
    private static final double STONE = 0.6;

    /**
     * Where a leap set off at {@code along} blocks a tick along and {@code lift} up comes down on ground {@code dy}
     * above, flown as the game moves a creature each tick: it moves by its speed, then the ground's friction (while it
     * was standing) or the air's drag slows it along, and gravity and drag act up and down.
     */
    private static double landsAt(double along, double lift, double dy, double friction) {
        double x = 0.0, y = 0.0, vx = along, vy = lift;
        boolean onGround = true;
        for (int t = 0; t < 200; t++) {
            double keep = onGround ? friction * 0.91 : 0.91;
            x += vx;
            double ny = y + vy;
            if (vy < 0.0 && ny <= dy) return x;
            y = ny;
            onGround = false;
            vx *= keep;
            vy = (vy - 0.08) * 0.98;
        }
        return Double.NaN;
    }

    @Test
    void aSpidersPounceComesDownOnItsQuarry() {
        for (double d = FoeRules.POUNCE_NEAR; d <= FoeRules.POUNCE_FAR + 1.0E-9; d += 0.4) {
            for (double dy : new double[]{-1.0, 0.0, 1.0}) {
                double lift = FoeRules.pounceLift(d);
                double v = FoeRules.leapSpeed(d, dy, lift, FoeRules.firstDrag(true, STONE));
                assertEquals(d, landsAt(v, lift, dy, STONE), 0.05, "a pounce of " + d + " blocks onto ground " + dy + " up");
            }
        }
    }

    @Test
    void theGroundsHoldOnTheFirstTickIsMadeUpFor() {
        double lift = FoeRules.pounceLift(4.0);
        double onStone = FoeRules.leapSpeed(4.0, 0.0, lift, FoeRules.firstDrag(true, STONE));
        double inAir = FoeRules.leapSpeed(4.0, 0.0, lift, FoeRules.firstDrag(false, STONE));
        assertTrue(onStone > inAir * 1.3, "set off from stone it needs more speed: " + onStone + " against " + inAir);
        // on ice the ground hardly holds it
        double onIce = FoeRules.leapSpeed(4.0, 0.0, lift, FoeRules.firstDrag(true, 0.98));
        assertEquals(4.0, landsAt(onIce, lift, 0.0, 0.98), 0.05);
        // the speed worked out for the air, set off from stone, falls well short: the reason for the first tick's drag
        assertTrue(landsAt(inAir, lift, 0.0, STONE) < 3.0);
    }

    @Test
    void aDropOntoAQuarryBelowLandsOnIt() {
        double d = FoeRules.DROP_NEAR, dy = -FoeRules.DROP_ABOVE - 1.0;
        double v = FoeRules.leapSpeed(d, dy, 0.12, FoeRules.firstDrag(true, STONE));
        assertEquals(d, landsAt(v, 0.12, dy, STONE), 0.05);
        assertEquals(0.0, FoeRules.leapSpeed(0.0, dy, 0.12, 0.5), 0.0, "straight down");
    }

    @Test
    void aSlimesSlamComesDownOnItsQuarryWhateverItsSize() {
        for (int size = 1; size <= 4; size++) {
            double lift = FoeRules.slamLift(size);
            for (double d = 1.0; d <= FoeRules.SLAM_FROM; d += 1.0) {
                double v = FoeRules.leapSpeed(d, 0.0, lift, FoeRules.firstDrag(true, STONE));
                assertEquals(d, landsAt(v, lift, 0.0, STONE), 0.05, "size " + size + ", " + d + " blocks");
            }
            assertTrue(FoeRules.slamReach(size, 0.52 * size) > 0.52 * size * 0.5, "it reaches past its own edge");
        }
        assertTrue(FoeRules.slamPush(4) > FoeRules.slamPush(1), "a big one throws further");
        assertTrue(FoeRules.slamLift(4) > FoeRules.slamLift(1), "and leaps higher");
    }

    @Test
    void aLungeCrossesTheGapToWithinReachAndNoFurther() {
        assertEquals(0.0, FoeRules.lungeSpeed(0.0, 2.0, 4), 0.0, "already within reach: no lunge");
        assertEquals(0.0, FoeRules.lungeSpeed(-1.0, 2.0, 4), 0.0);
        assertEquals((1.0 + 0.3) / 4, FoeRules.lungeSpeed(1.0, 2.0, 4), 1.0E-9, "a little past the gap");
        assertEquals(2.0 / 4, FoeRules.lungeSpeed(5.0, 2.0, 4), 1.0E-9, "never more than the move's own lunge");
        assertEquals(0.0, FoeRules.lungeSpeed(1.0, 2.0, 0), 0.0);
    }

    @Test
    void anArrowIsAimedWhereARunningPlayerWillBe() {
        // standing still: straight at it
        double[] still = FoeRules.lead(0, 64, 0, 12, 64, 0, 0, 0, FoeRules.ARROW_SPEED);
        assertEquals(12.0, still[0], 1.0E-9);
        assertEquals(0.0, still[2], 1.0E-9);
        // sprinting across its line at 0.28 blocks a tick, 12 blocks off
        double vz = 0.28;
        double[] aim = FoeRules.lead(0, 64, 0, 12, 64, 0, 0, vz, FoeRules.ARROW_SPEED);
        double d = Math.sqrt(aim[0] * aim[0] + aim[2] * aim[2]);
        double flight = flown(d, FoeRules.ARROW_SPEED);
        double there = vz * flight;
        assertEquals(there * FoeRules.LEAD, aim[2], 0.15, "led most of the way to where it will be");
        assertTrue(there - aim[2] < 0.6, "so it falls a little behind a runner who keeps on: " + (there - aim[2]));
        assertEquals(64.0, aim[1], 0.0, "jumping isn't led");
    }

    /** Ticks an arrow at {@code speed} takes to fly {@code d} blocks, flown with the game's drag (0.99 a tick). */
    private static double flown(double d, double speed) {
        double x = 0.0, v = speed;
        for (int t = 1; t <= 200; t++) {
            if (x + v >= d) return t - 1 + (d - x) / v;
            x += v;
            v *= 0.99;
        }
        return Double.NaN;
    }

    @Test
    void anArrowsFlightIsWorkedOutWithItsDrag() {
        for (double d = 2.0; d <= 30.0; d += 4.0) {
            assertEquals(flown(d, FoeRules.ARROW_SPEED), FoeRules.flightTicks(d, FoeRules.ARROW_SPEED), 1.0, d + " blocks");
        }
        assertEquals(60.0, FoeRules.flightTicks(500.0, FoeRules.ARROW_SPEED), 0.0, "out of reach: counted as the most");
        assertTrue(FoeRules.flightTicks(10.0, FoeRules.ARROW_SPEED) > FoeRules.flightTicks(5.0, FoeRules.ARROW_SPEED));
    }

    @Test
    void theFirstToWaitStandsBehindItsQuarryThenItsFlanks() {
        assertEquals(180.0, FoeRules.slotDegrees(0), 0.0);
        assertEquals(135.0, FoeRules.slotDegrees(1), 0.0);
        assertEquals(225.0, FoeRules.slotDegrees(2), 0.0);
        assertEquals(0.0, FoeRules.slotDegrees(7), 0.0, "its front last");
        for (int r = 8; r < 24; r++) {
            double a = FoeRules.slotDegrees(r);
            assertTrue(a >= 0.0 && a < 360.0, "rank " + r + ": " + a);
        }
        // facing +z (the game's yaw 0): behind is -z
        double[] behind = FoeRules.slotPoint(10.0, 10.0, 0.0F, 180.0, 3.0);
        assertEquals(10.0, behind[0], 1.0E-9);
        assertEquals(7.0, behind[1], 1.0E-9);
        // facing -x (yaw 90): behind is +x
        double[] behindWest = FoeRules.slotPoint(10.0, 10.0, 90.0F, 180.0, 3.0);
        assertEquals(13.0, behindWest[0], 1.0E-9);
        assertEquals(10.0, behindWest[1], 1.0E-9);
    }

    @Test
    void everyBlowWindsUpAndAMissLeavesTheLongerOpening() {
        for (FoeRules.Melee m : new FoeRules.Melee[]{FoeRules.ZOMBIE, FoeRules.SKELETON, FoeRules.VINDICATOR, FoeRules.ENDERMAN,
                FoeRules.KICK, FoeRules.BITE}) {
            assertTrue(m.windup() >= 8, m.move() + ": a wind-up you can see (" + m.windup() + " ticks)");
            assertTrue(m.whiff() > m.recover(), m.move() + ": a miss leaves it open longer");
            assertTrue(m.coolMax() >= m.coolMin(), m.move().toString());
            assertEquals(m.coolMin(), m.cooldown(0.0), m.move().toString());
            assertEquals(m.coolMax(), m.cooldown(0.9999), m.move().toString());
            assertTrue(m.cooldown(0.5) >= m.coolMin() && m.cooldown(0.5) <= m.coolMax());
        }
        assertTrue(FoeRules.VINDICATOR.windup() > FoeRules.ZOMBIE.windup(), "the axe's chop is the slowest to come");
        // a cave spider's bite and pounce come quicker
        assertTrue(FoeRules.QUICK < 1.0);
    }

    @Test
    void aWindupScaledDownStillShows() {
        assertEquals(10, FoeRules.scaled(10, 1.0));
        assertEquals(15, FoeRules.scaled(10, 1.5));
        assertEquals(3, FoeRules.scaled(10, 0.1), "never under three ticks");
        assertEquals(5, FoeRules.ZOMBIE.windup(0.5));
    }

    @Test
    void theirTellComesBeforeTheShotAndInsideTheDraw() {
        assertTrue(FoeRules.SHOT_TELL < FoeRules.DRAW);
        assertTrue(FoeRules.SHOT_TELL >= 6, "time to raise a guard: " + FoeRules.SHOT_TELL + " ticks");
        assertTrue(FoeRules.BACK_OFF < FoeRules.BOW_RANGE);
        assertTrue(FoeRules.KICK.start() < FoeRules.BACK_OFF, "it kicks only those already too near to shoot");
    }
}
