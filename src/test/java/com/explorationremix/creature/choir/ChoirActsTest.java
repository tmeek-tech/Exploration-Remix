package com.explorationremix.creature.choir;

import com.explorationremix.world.feature.ChoirArena;
import com.explorationremix.world.terrain.ChoirSites;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the Hollow Choir's blows promise a player ({@link ChoirActs}, 0.22.3). The mod has no dodge roll, no parry and
 * no stamina, so each blow has to be one that walking, jumping and a pillar are enough against: these hold the clocks
 * to that, with the game's own walking speed and its own jump.
 */
class ChoirActsTest {
    /** The game's walk and sprint, in blocks a tick. */
    private static final double WALK = 4.317 / 20, SPRINT = 5.612 / 20;
    /** How long a player takes to see a warning and begin to act on it: a third of a second. */
    private static final int REACT = 7;

    /** How high a player's feet are at each tick of the game's jump, from the tick after the key is pressed. */
    private static List<Double> jump() {
        List<Double> up = new ArrayList<>();
        double y = 0, v = 0.42;
        while (true) {
            y += v;
            v = (v - 0.08) * 0.98;
            if (y <= 0) return up;
            up.add(y);
        }
    }

    /**
     * The game's own walk of a player who is thrown over flat floor, tick by tick from the tick after the throw:
     * {how far they have been carried, how high their feet are}, to the tick they stop. A throw moves them its whole
     * self in the first tick. If they stood, the floor then takes its drag out of what is left at once; the air takes
     * a tenth a tick; and the floor takes the rest when they land. With {@code jump}, they jump the tick they land.
     */
    private static List<double[]> thrown(double speed, double lift, boolean standing, boolean jump) {
        List<double[]> at = new ArrayList<>();
        double x = 0, y = 0, vy = lift;
        boolean ground = standing, jumps = false;
        while (at.size() < 400) {
            if (jumps) { vy = 0.42; jumps = false; }
            double drag = ground ? 0.6 * 0.91 : 0.91;
            x += speed;
            y += vy;
            boolean was = ground;
            ground = y <= 0 && vy <= 0;
            if (ground) { y = 0; vy = 0; }
            if (ground && !was && jump) { jumps = true; jump = false; }
            at.add(new double[] {x, y});
            vy = (vy - 0.08) * 0.98;
            speed *= drag;
            if (speed < 0.003) speed = 0;
            if (ground && speed == 0 && !jumps) break;
        }
        return at;
    }

    private static List<double[]> thrown(double speed, double lift, boolean standing) {
        return thrown(speed, lift, standing, false);
    }

    private static double far(List<double[]> at) { return at.get(at.size() - 1)[0]; }

    private static double high(List<double[]> at) { return at.stream().mapToDouble(a -> a[1]).max().orElse(0); }

    /** The tick of a throw at which a player is down again, counted from the tick after the throw as 1. */
    private static int lands(List<double[]> at) {
        for (int i = 1; i < at.size(); i++) if (at.get(i)[1] <= 0) return i + 1;
        return at.size();
    }

    // ------------------------------------------------------------------------------------------------------ the slam

    @Test
    void aSlamCanBeWalkedOutOfAtFirstAndRunFromAtTheLast() {
        // from the very middle of the circle, which is where the mark is laid
        int walk = (int) Math.ceil(ChoirActs.SLAM_R / WALK), run = (int) Math.ceil(ChoirActs.SLAM_R / SPRINT);
        assertTrue(REACT + walk <= ChoirActs.windUp(1) + ChoirActs.SLAM_AT,
                "phase 1 gives " + (ChoirActs.windUp(1) + ChoirActs.SLAM_AT) + " ticks; seeing it and walking out takes " + (REACT + walk));
        for (int phase = 1; phase <= 3; phase++)
            assertTrue(REACT + run <= ChoirActs.windUp(phase) + ChoirActs.SLAM_AT,
                    "phase " + phase + " gives " + (ChoirActs.windUp(phase) + ChoirActs.SLAM_AT) + " ticks; seeing it and running out takes " + (REACT + run));
        assertTrue(ChoirActs.windUp(1) > ChoirActs.windUp(2) && ChoirActs.windUp(2) > ChoirActs.windUp(3), "each phase is quicker");
    }

    @Test
    void theMarkIsFullTheTickTheBlowLandsAndNotBefore() {
        for (int phase = 1; phase <= 3; phase++) {
            assertEquals(0F, ChoirActs.slamFill(phase, false, 0));
            float last = 0;
            for (int t = 1; t <= ChoirActs.windUp(phase); t++) {
                float f = ChoirActs.slamFill(phase, false, t);
                assertTrue(f > last && f < 1, "phase " + phase + " wind-up tick " + t + ": " + f);
                last = f;
            }
            // the stroke carries straight on from where the wind-up left it
            assertEquals(last, ChoirActs.slamFill(phase, true, 0), 1e-6);
            for (int t = 1; t < ChoirActs.SLAM_AT; t++) {
                float f = ChoirActs.slamFill(phase, true, t);
                assertTrue(f > last && f < 1, "phase " + phase + " stroke tick " + t + ": " + f);
                last = f;
            }
            assertEquals(1F, ChoirActs.slamFill(phase, true, ChoirActs.SLAM_AT));
            // the tendril is right over the mark with wind-up to spare, and on it exactly as the mark fills
            assertEquals(1F, ChoirActs.rear(phase, ChoirActs.windUp(phase) - 4));
            assertEquals(0F, ChoirActs.rear(phase, 0));
        }
        assertEquals(0F, ChoirActs.plunge(0));
        assertEquals(1F, ChoirActs.plunge(ChoirActs.SLAM_AT));
        assertTrue(ChoirActs.plunge(ChoirActs.SLAM_AT - 1) < 1);
        assertTrue(ChoirActs.SLAM_END - ChoirActs.SLAM_AT >= 16, "the head lies where it struck long enough to be hit");
    }

    @Test
    void aSlamStrikesItsCircleAndNothingElse() {
        assertTrue(ChoirActs.slamStrikes(0, 0));
        assertTrue(ChoirActs.slamStrikes(ChoirActs.SLAM_R, 0));
        assertFalse(ChoirActs.slamStrikes(ChoirActs.SLAM_R + 0.01, 0), "a step outside the circle is safe");
        assertTrue(ChoirActs.slamStrikes(1, 1.25), "a jump does not clear a slam");
        assertTrue(ChoirActs.slamStrikes(1, -ChoirActs.SLAM_TALL));
        assertFalse(ChoirActs.slamStrikes(1, ChoirActs.SLAM_TALL + 0.1));
        // the head lying on its mark is in reach of a sword from just outside the circle
        assertTrue(ChoirActs.SLAM_R - ChoirActs.HEAD_W / 2 < 3.0);
    }

    @Test
    void aSlamThrowsAPlayerToTheEdgeOfItsCircleAndNoHigherThanAJump() {
        List<double[]> slam = thrown(ChoirActs.SLAM_THROW, ChoirActs.SLAM_LIFT, true);
        double jump = jump().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        // a fall hurts past three blocks, and this is a hop: the blow is the whole of the hurt
        assertTrue(high(slam) <= jump, "thrown " + high(slam) + " high");
        // from the middle of the circle, where the mark is laid, to its edge and not far past it
        assertTrue(far(slam) > ChoirActs.SLAM_R - 0.5 && far(slam) < ChoirActs.SLAM_R + 1, "thrown " + far(slam));
        // and from there the head it leaves lying is a step and a sword's reach away, for as long as it lies
        double toHead = far(slam) - ChoirActs.HEAD_W / 2 - 3.0;
        int back = lands(slam) + REACT + (int) Math.ceil(Math.max(0, toHead) / WALK);
        assertTrue(back < ChoirActs.SLAM_END - ChoirActs.SLAM_AT, "thrown too far to answer: " + back + " ticks");
        // a player caught in the air lands where one who stood would have
        List<double[]> inAir = thrown(ChoirActs.SLAM_THROW * ChoirActs.AIR_SHARE, ChoirActs.SLAM_LIFT, false);
        assertEquals(far(slam), far(inAir), 0.2);
        // and the way they are thrown turns about nowhere in the circle but next to its very middle
        assertTrue(ChoirActs.SLAM_ALONG > 0 && ChoirActs.SLAM_ALONG < 1);
    }

    // ------------------------------------------------------------------------------------------------------ the toll

    @Test
    void aRingRunsFromTheDaisToTheSeatsAndIsThenSpent() {
        for (int phase = 1; phase <= 3; phase++)
            for (int ring = 0; ring < ChoirActs.tollRings(phase); ring++) {
                int lands = ChoirActs.TOLL_TELL + ring * ChoirActs.TOLL_GAP;
                assertTrue(Double.isNaN(ChoirActs.tollRadius(phase, ring, lands - 1)), "not down yet");
                assertEquals(ChoirActs.TOLL_R0, ChoirActs.tollRadius(phase, ring, lands), 1e-9);
                double last = ChoirActs.TOLL_R0;
                int t = lands + 1;
                for (; !Double.isNaN(ChoirActs.tollRadius(phase, ring, t)); t++) {
                    double r = ChoirActs.tollRadius(phase, ring, t);
                    assertTrue(r > last && r <= ChoirActs.TOLL_R1);
                    last = r;
                }
                assertTrue(last > ChoirActs.TOLL_R1 - ChoirActs.tollSpeed(phase), "it reaches the foot of the seats");
                assertTrue(t <= ChoirActs.tollEnd(phase), "ring " + ring + " of phase " + phase + " outlives its toll");
                // while it drops it is not yet on the floor, and it is never both
                for (double at = ring * ChoirActs.TOLL_GAP; at < ChoirActs.tollEnd(phase); at += 0.25) {
                    boolean dropping = !Double.isNaN(ChoirActs.tollDrop(ring, at));
                    boolean running = !Double.isNaN(ChoirActs.tollRadius(phase, ring, at));
                    assertFalse(dropping && running, "tick " + at);
                    assertEquals(at < lands, dropping, "tick " + at);
                }
                assertEquals(0, ChoirActs.tollDrop(ring, ring * ChoirActs.TOLL_GAP), 1e-9);
            }
        assertEquals(1, ChoirActs.tollRings(1));
        assertEquals(2, ChoirActs.tollRings(3));
        assertTrue(ChoirActs.TOLL_R0 > ChoirArena.DAIS_R + 1, "it lands outside the dais ring's braziers, which would shelter half the floor");
    }

    /** A player's distance from the dais's middle, or their height over the floor, tick by tick. */
    private interface Path { double at(int tick); }

    /** The tick on which ring 0 of a phase strikes a player, or -1 if it never does. */
    private static int struckAt(int phase, Path dist, Path up) {
        for (int t = 0; t <= ChoirActs.tollEnd(phase); t++)
            if (ChoirActs.tollStrikes(phase, ChoirActs.tollRadius(phase, 0, t), dist.at(t), up.at(t), Float.MAX_VALUE)) return t;
        return -1;
    }

    /** True when ring 0 of a phase passes a player on the floor from one tick to the next without striking them. */
    private static boolean steppedOver(int phase, Path dist) {
        for (int t = 0; t < ChoirActs.tollEnd(phase); t++) {
            double r0 = ChoirActs.tollRadius(phase, 0, t), r1 = ChoirActs.tollRadius(phase, 0, t + 1);
            if (Double.isNaN(r0) || Double.isNaN(r1)) continue;
            boolean outside = dist.at(t) > r0, inside = dist.at(t + 1) < r1;
            if (outside && inside && !ChoirActs.tollStrikes(phase, r0, dist.at(t), 0, Float.MAX_VALUE)
                    && !ChoirActs.tollStrikes(phase, r1, dist.at(t + 1), 0, Float.MAX_VALUE)) return true;
        }
        return false;
    }

    @Test
    void aRingCannotStepOverAnyone() {
        for (int phase = 1; phase <= 3; phase++) {
            int p = phase;
            assertTrue(2 * ChoirActs.tollHalf(p) >= ChoirActs.tollSpeed(p) + SPRINT, "the band is narrower than a tick of ring and sprint");
            // standing anywhere on the floor it is struck, not passed
            for (double r = ChoirActs.TOLL_R0 + 0.01; r < ChoirActs.TOLL_R1 - 0.6; r += 0.037) {
                double at = r;
                assertTrue(struckAt(p, t -> at, t -> 0) >= 0, "phase " + p + ": a player standing " + r + " out is stepped over");
            }
            // and sprinting in through it, from every start: wherever ring and runner cross, it strikes
            for (double from = ChoirActs.TOLL_R0 + 1; from < ChoirActs.TOLL_R1 + 16; from += 0.043) {
                double start = from;
                assertFalse(steppedOver(p, t -> start - SPRINT * t), "phase " + p + ": a player sprinting in from " + from + " is stepped over");
            }
        }
    }

    @Test
    void aJumpClearsARingAndAHopDoesNot() {
        List<Double> jump = jump();
        assertTrue(jump.get(0) > ChoirActs.TOLL_OVER, "the first tick of a jump is already clear");
        for (int phase = 1; phase <= 3; phase++) {
            int p = phase;
            for (double r = ChoirActs.TOLL_R0 + 0.5; r < ChoirActs.TOLL_R1 - 0.5; r += 0.61) {
                double at = r;
                int hit = struckAt(p, t -> at, t -> 0);
                assertTrue(hit >= 0);
                // every tick the key could be pressed on, counted back from the tick the ring arrives
                int clear = 0;
                for (int press = hit - jump.size(); press <= hit; press++) {
                    int pressed = press;
                    if (struckAt(p, t -> at, t -> t > pressed && t - pressed - 1 < jump.size() ? jump.get(t - pressed - 1) : 0) < 0) clear++;
                }
                assertTrue(clear >= 5, "phase " + p + ", " + r + " out: only " + clear + " ticks on which a jump clears the ring");
            }
        }
        // a step up clears it; a hop that barely leaves the floor does not; nor does standing on the floor
        assertFalse(ChoirActs.tollStrikes(1, 10, 10, 0.5, Float.MAX_VALUE));
        assertTrue(ChoirActs.tollStrikes(1, 10, 10, 0.2, Float.MAX_VALUE));
        assertTrue(ChoirActs.tollStrikes(1, 10, 10, 0, Float.MAX_VALUE));
        assertFalse(ChoirActs.tollStrikes(1, Double.NaN, 10, 0, Float.MAX_VALUE));
    }

    @Test
    void aRingThatStrikesThrowsAPlayerOutAndLeavesThemTheNext() {
        List<double[]> toll = thrown(ChoirActs.TOLL_THROW, ChoirActs.TOLL_LIFT, true);
        assertTrue(high(toll) < jump().stream().mapToDouble(Double::doubleValue).max().orElse(0));
        assertTrue(far(toll) > 1.5 && far(toll) < far(thrown(ChoirActs.SLAM_THROW, ChoirActs.SLAM_LIFT, true)) - 0.5,
                "a toll throws " + far(toll));
        // struck by the first of two rings, a player who jumps as they land is over the second: the first never
        // costs them the second as well. Wherever on the floor it caught them, and with the seats' foot in the way.
        List<double[]> jumped = thrown(ChoirActs.TOLL_THROW, ChoirActs.TOLL_LIFT, true, true);
        for (int phase = 2; phase <= 3; phase++)
            for (double d = ChoirActs.TOLL_R0; d < ChoirActs.TOLL_R1; d += 0.125) {
                int hit = -1;
                for (int t = 0; t <= ChoirActs.tollEnd(phase) && hit < 0; t++)
                    if (ChoirActs.tollStrikes(phase, ChoirActs.tollRadius(phase, 0, t), d, 0, 99)) hit = t;
                assertTrue(hit >= 0, "the first ring misses a player " + d + " out");
                for (int t = hit + 1; t <= ChoirActs.tollEnd(phase); t++) {
                    double[] now = jumped.get(Math.min(jumped.size(), t - hit) - 1);
                    double dist = Math.min(d + now[0], ChoirSites.SEAT_R0 - 0.3);
                    assertFalse(ChoirActs.tollStrikes(phase, ChoirActs.tollRadius(phase, 1, t), dist, now[1], 99),
                            "phase " + phase + ", struck " + d + " out: the second ring takes them too at tick " + t);
                }
            }
    }

    @Test
    void aRingStopsAtAnythingSolid() {
        assertFalse(ChoirActs.tollStrikes(2, 12, 12, 0, 10.5F), "behind a pillar");
        assertTrue(ChoirActs.tollStrikes(2, 9, 9, 0, 10.5F), "in front of one");
        assertTrue(ChoirActs.tollStrikes(2, 12, 12, 0, 12.2F), "against its face");
        // the slices go once round, and each lies where its own angle says
        for (int s = 0; s < ChoirActs.TOLL_SLICES; s++) {
            double a = ChoirActs.sliceAngle(s);
            assertEquals(s, ChoirActs.tollSlice(9 * Math.cos(a), 9 * Math.sin(a)));
        }
        assertEquals(0, ChoirActs.tollSlice(5, 0));
        assertEquals(ChoirActs.TOLL_SLICES - 1, ChoirActs.tollSlice(5, -1e-9));
    }

    // ---------------------------------------------------------------------------------------------- grab and bloom

    @Test
    void theGrabsRipplesAreAllInBeforeItPulls() {
        for (int k = 0; k < ChoirActs.GRAB_RIPPLES; k++) {
            int from = k * ChoirActs.GRAB_RIPPLE_GAP, to = from + ChoirActs.GRAB_RIPPLE_RUN;
            assertTrue(to <= ChoirActs.GRAB_TELL, "ripple " + k + " is still running at the pull");
            assertEquals(ChoirSites.RIM_R, ChoirActs.grabRipple(k, from), 1e-9);
            assertEquals(ChoirActs.GRAB_TO, ChoirActs.grabRipple(k, to), 1e-9);
            assertTrue(Double.isNaN(ChoirActs.grabRipple(k, from - 0.5)) && Double.isNaN(ChoirActs.grabRipple(k, to + 0.5)));
            for (double t = from; t < to; t += 0.5)
                assertTrue(ChoirActs.grabRipple(k, t + 0.5) < ChoirActs.grabRipple(k, t), "it runs in");
        }
        assertEquals(ChoirActs.GRAB_TELL, (ChoirActs.GRAB_RIPPLES - 1) * ChoirActs.GRAB_RIPPLE_GAP + ChoirActs.GRAB_RIPPLE_RUN,
                "the last ripple reaches the dais as it pulls");
    }

    @Test
    void theGrabDrawsHarderTheFurtherOutAndNobodyThroughTheDais() {
        double last = 0;
        for (double d = 0; d <= HollowChoirEntity.KEEP; d += 0.25) {
            double pull = ChoirActs.grabPull(d);
            assertTrue(pull >= last - 1e-9, d + " out");
            // drawn in, and never past where the ripples end: standing, or caught in the air
            double in = far(thrown(pull, ChoirActs.GRAB_LIFT, true));
            double inAir = far(thrown(pull * ChoirActs.AIR_SHARE, ChoirActs.GRAB_LIFT, false));
            assertTrue(Math.max(in, inAir) <= Math.max(0, d - ChoirActs.GRAB_TO) + 1e-9, d + " out: drawn past the dais");
            if (d <= ChoirSites.FLOOR_R) assertEquals(in, inAir, 0.3, d + " out");
            last = pull;
        }
        // from the foot of the seats it draws a player a good five blocks in, on to the open floor
        double fromFoot = far(thrown(ChoirActs.grabPull(ChoirSites.FLOOR_R), ChoirActs.GRAB_LIFT, true));
        assertTrue(fromFoot > 4.5 && fromFoot < 6.5, "drawn " + fromFoot + " from the foot of the seats");
        // and it lifts them over a brazier, which stands seven sixteenths of a block, for the body of the flight
        List<double[]> flight = thrown(1, ChoirActs.GRAB_LIFT, true);
        int over = 0;
        for (double[] a : flight) if (a[1] > 7 / 16.0) over++;
        assertTrue(over >= lands(flight) - 4, "over a brazier for " + over + " ticks of " + lands(flight));
        assertTrue(high(flight) < 1, "and no higher than a block");
        assertEquals(0, ChoirActs.grabPull(ChoirActs.GRAB_TO));
        assertTrue(ChoirActs.grabPull(ChoirSites.RIM_R) > ChoirActs.grabPull(ChoirSites.FLOOR_R), "the rim is no place to wait it out");
        assertTrue(HollowChoirEntity.KEEP > ChoirSites.RIM_R + ChoirArena.APRON, "the rim walk is still in the fight");
    }

    @Test
    void theSculkBrightensToItsPulse() {
        assertEquals(0F, ChoirActs.bloomLight(-1));
        float most = 0;
        double[] thirds = new double[3];
        for (int t = 0; t < ChoirActs.BLOOM_TELL; t++) {
            float l = ChoirActs.bloomLight(t);
            assertTrue(l > 0 && l < 0.8F, "tick " + t + ": " + l);
            most = Math.max(most, l);
            thirds[t * 3 / ChoirActs.BLOOM_TELL] += l;
        }
        assertTrue(thirds[0] < thirds[1] && thirds[1] < thirds[2], "it swells");
        assertEquals(1F, ChoirActs.bloomLight(ChoirActs.BLOOM_TELL), "the pulse is the brightest thing in it");
        assertTrue(most < 0.8F);
        float last = 1;
        for (int t = ChoirActs.BLOOM_TELL + 1; t <= ChoirActs.BLOOM_END; t++) {
            float l = ChoirActs.bloomLight(t);
            assertTrue(l <= last);
            last = l;
        }
        assertEquals(0F, last, "dark again by the end");
        // long enough to step off the sculk on to clean stone: three blocks at a walk
        assertTrue(ChoirActs.BLOOM_TELL >= REACT + 3 / WALK);
    }

    // --------------------------------------------------------------------------------------------------- the arena

    @Test
    void everyTendrilIsRootedBeforeTheFirstBlow() {
        for (int place = 0; place < HollowChoirEntity.PLACES; place++)
            assertTrue(ChoirActs.growStart(place) + ChoirActs.GROW_TICKS <= ChoirActs.ENTER_TICKS, "place " + place);
        assertTrue(ChoirActs.growStart(0) > 0);
    }

    @Test
    void theGroundTheSignsLieOnIsTheArenasOwn() {
        for (double turn : new double[] {0, 0.7, 2.9, 5.1}) {
            ChoirSites.Site s = new ChoirSites.Site(0, 0, 300.5, -420.5, -30, 12, 1, turn, 99L);
            for (double x = -ChoirSites.RIM_R; x <= ChoirSites.RIM_R; x += 0.5)
                for (double z = -ChoirSites.RIM_R; z <= ChoirSites.RIM_R; z += 0.5) {
                    if (Math.hypot(x, z) > ChoirSites.RIM_R) continue;
                    double built = ChoirArena.topY(s, s.x() + x, s.z() + z) - s.floorY();
                    assertEquals(built, ChoirActs.groundUp(turn, x, z), 1e-9, "turn " + turn + " at " + x + ", " + z);
                }
        }
        assertEquals(0, ChoirActs.stepUp(ChoirSites.FLOOR_R));
        assertEquals(ChoirSites.STEP_RISE, ChoirActs.stepUp(ChoirSites.SEAT_R0 + 0.1));
        assertEquals(ChoirSites.DEPTH, ChoirActs.stepUp(ChoirSites.RIM_R));
    }
}
