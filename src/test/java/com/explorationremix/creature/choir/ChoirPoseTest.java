package com.explorationremix.creature.choir;

import com.explorationremix.world.feature.ChoirArena;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The line a tendril of the Hollow Choir lies along ({@link ChoirPose}, 0.22.3), held to what the fight needs of it.
 * A tendril is struck by its box, which stands on its root and never moves, and it strikes a mark on the floor: so the
 * cord must be in that box where a sword can reach, however it stands or bends, and its head must come down exactly on
 * the mark. 0.22.0 and 0.22.2 each drew the cord somewhere its box was not.
 */
class ChoirPoseTest {
    /**
     * The root, eight out from the dais's middle, which is the origin; the floor is height 0, and the cord leaves it
     * half a block up, at the middle of its foot. The ceiling is as the test arena's.
     */
    private static final double RX = ChoirArena.ROOT_R, RY = 0.5, RZ = 0, TOP = 46;
    /** The crown as it hangs, measured the same way: its lowest and its highest in its slow bob, and on its way down. */
    private static final double[] CROWNS = {4.5, 5.0, 5.35, 12, 23};
    /** As high over the floor as a sword reaches, and the half width of the cord. */
    private static final double REACH = 3.5, CORD = 0.375;
    /** The crown's own body, bells and all, in heights over the floor. */
    private static final double CROWN_LOW = 4.2, CROWN_HIGH = 7.4;

    private static double[][] line(int n, double crown, double reach, double tx, double ty, double tz) {
        double[][] out = new double[n + 1][3];
        ChoirPose.curve(out, n, RX, RY, RZ, TOP, crown, -ChoirPose.LEAN, 0, reach, tx, ty, tz, ChoirPose.open(RX, RZ, tx, tz));
        return out;
    }

    /** Where a reared head is: hanging over a mark on the floor, or down on it (its middle a little over the floor). */
    private static double headY(double tx, double tz, boolean down) {
        return 0.7 + (down ? 0 : ChoirPose.hover(ChoirPose.open(RX, RZ, tx, tz)));
    }

    @Test
    void aStandingCordPassesThroughItsOwnBox() {
        double half = HollowChoirEntity.TENDRIL_W / 2;
        for (double crown : CROWNS) {
            double[][] at = line(400, crown, 0, 0, 0, 0);
            assertEquals(RX, at[0][0], 1e-9, "the root is where the box stands");
            assertEquals(RZ, at[0][2], 1e-9);
            for (double[] p : at) {
                double off = Math.hypot(p[0] - RX, p[2] - RZ), up = p[1];
                if (up <= REACH) assertTrue(off < 0.1, "crown at " + crown + ": " + off + " off its root " + up + " up, where a sword swings");
                if (up <= HollowChoirEntity.TENDRIL_H) assertTrue(off + CORD < half + 0.2, "crown at " + crown + ": " + off + " off its root " + up + " up");
                assertTrue(off <= ChoirPose.LEAN + 1e-9);
            }
            // and it does bow in over the crown, by all of the lean
            assertEquals(1, ChoirPose.bow(crown + ChoirPose.LEAN_OVER, crown), 1e-9);
            assertEquals(TOP, at[400][1], 1e-9);
        }
    }

    @Test
    void aRearedCordStillLeavesItsRootThroughItsBox() {
        double half = HollowChoirEntity.TENDRIL_W / 2;
        for (double far = 0.5; far <= ChoirActs.SLAM_REACH; far += 0.5)
            for (double a = 0; a < Math.PI * 2; a += Math.PI / 7)
                for (int down = 0; down <= 1; down++) {
                    double tx = RX + far * Math.cos(a), tz = RZ + far * Math.sin(a);
                    String mark = "mark " + far + " off at " + Math.toDegrees(a);
                    double[][] at = line(300, 5, 1, tx, headY(tx, tz, down == 1), tz);
                    // up the cord from its root for as far as a sword swings: it is on its root's own line
                    for (int i = 0; i <= 300 && at[i][1] <= 3.0 && (i == 0 || at[i][1] > at[i - 1][1]); i++) {
                        double off = Math.hypot(at[i][0] - RX, at[i][2] - RZ);
                        assertTrue(off < 1e-9, mark + ": the cord is " + off + " off its root " + at[i][1] + " up");
                    }
                    // and on its way over and back it never leaves the box there
                    for (double reach = 0.05; reach < 1; reach += 0.05) {
                        double[][] part = line(60, 5, reach, tx, headY(tx, tz, down == 1), tz);
                        for (int i = 0; i <= 60 && part[i][1] <= 3.0 && (i == 0 || part[i][1] > part[i - 1][1]); i++) {
                            double off = Math.hypot(part[i][0] - RX, part[i][2] - RZ);
                            assertTrue(off + CORD < half + 0.2, mark + ", reach " + reach + ": the cord is " + off
                                    + " off its root " + part[i][1] + " up");
                        }
                    }
                }
    }

    @Test
    void aStrikeEndsExactlyOnItsMarkAndTheRootNeverMoves() {
        for (double far = 0; far <= ChoirActs.SLAM_REACH; far += 0.75)
            for (double a = 0; a < Math.PI * 2; a += Math.PI / 5) {
                double tx = RX + far * Math.cos(a), tz = RZ + far * Math.sin(a), ty = 0.7;
                for (int n : new int[] {4, 17, 31, ChoirPose.SEGS_MAX}) {
                    double[][] at = line(n, 5, 1, tx, ty, tz);
                    assertEquals(tx, at[n][0], 0.0);
                    assertEquals(ty, at[n][1], 0.0);
                    assertEquals(tz, at[n][2], 0.0);
                    // and it comes down on it from straight above, where its pieces are short enough to show it
                    if (n >= 17) {
                        assertEquals(tx, at[n - 1][0], 1e-6);
                        assertEquals(tz, at[n - 1][2], 1e-6);
                    }
                    assertTrue(at[n - 1][1] > ty);
                    for (double reach : new double[] {0, 0.2, 0.5, 0.8, 1}) {
                        double[][] part = line(n, 5, reach, tx, ty, tz);
                        assertEquals(RX, part[0][0], 1e-9, "reach " + reach);
                        assertEquals(RY, part[0][1], 1e-9);
                        assertEquals(RZ, part[0][2], 1e-9);
                    }
                }
            }
    }

    @Test
    void aRearingCordStaysOverTheFloorAndBendsWithoutAKink() {
        int n = ChoirPose.segments(TOP - RY);
        // a standing piece's length, where the cord bows the most
        double[][] stood = line(n, 5, 0, 0, 0, 0);
        double stand = 0;
        for (int i = 1; i <= n; i++) stand = Math.max(stand, dist(stood[i - 1], stood[i]));
        for (double far = 0.5; far <= ChoirActs.SLAM_REACH; far += 0.75)
            for (double a = 0; a < Math.PI * 2; a += Math.PI / 6)
                for (int down = 0; down <= 1; down++) {
                    double tx = RX + far * Math.cos(a), tz = RZ + far * Math.sin(a), ty = headY(tx, tz, down == 1);
                    double[][] reared = line(n, 5, 1, tx, ty, tz);
                    double least = Double.MAX_VALUE;
                    for (int i = 1; i <= n; i++) least = Math.min(least, dist(reared[i - 1], reared[i]));
                    for (double reach = 0.05; reach <= 1.0001; reach += 0.05) {
                        String what = "mark " + far + " off at " + Math.toDegrees(a) + ", reach " + reach + (down == 1 ? " down" : "");
                        double[][] at = line(n, 5, Math.min(1, reach), tx, ty, tz);
                        double shortest = Double.MAX_VALUE, longest = 0;
                        for (int i = 0; i <= n; i++) {
                            assertTrue(at[i][1] >= RY - 1e-9, what + ": under the floor at piece " + i);
                            if (i == 0) continue;
                            double len = dist(at[i - 1], at[i]);
                            shortest = Math.min(shortest, len);
                            longest = Math.max(longest, len);
                            if (i < 2) continue;
                            // the turn from one piece to the next
                            double dot = 0;
                            for (int c = 0; c < 3; c++) dot += (at[i][c] - at[i - 1][c]) * (at[i - 1][c] - at[i - 2][c]);
                            double turn = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot / (len * dist(at[i - 2], at[i - 1]))))));
                            assertTrue(turn < 40, what + ": a kink of " + turn + " degrees at piece " + i);
                        }
                        // no piece of it ever shrinks to nothing on the way: each is between what it was and will be
                        assertTrue(shortest > least * 0.98, what + ": a piece " + shortest + " long, and " + least + " reared");
                        assertTrue(longest < stand * 1.02, what + ": a piece " + longest + " long, and " + stand + " standing");
                        if (reach >= 1) assertTrue(longest / shortest < 1.3, what + ": its pieces are not of a length, "
                                + shortest + " to " + longest);
                        // and the head comes to its mark from above
                        assertTrue(at[n][1] >= ty - 1e-9, what + ": its head is at " + at[n][1] + ", under where it will hang");
                        assertEquals(ChoirPose.length(at, n), sum(at, n), 1e-9);
                    }
                }
    }

    @Test
    void theHeadBendsOverFirstAndNeverReachesPastItsMark() {
        // the head has tipped right over while the root has yet to start, and all of it is over at the end
        assertEquals(1, ChoirPose.bent(0.7, 1), 1e-9);
        assertEquals(0, ChoirPose.bent(0.3, 0), 1e-9);
        for (double along = 0; along <= 1; along += 0.1) {
            assertEquals(0, ChoirPose.bent(0, along), 1e-9);
            assertEquals(1, ChoirPose.bent(1, along), 1e-9);
            double last = 0;
            for (double reach = 0; reach <= 1; reach += 0.02) {
                double bent = ChoirPose.bent(reach, along);
                assertTrue(bent >= last && bent >= ChoirPose.bent(reach, Math.max(0, along - 0.1)) - 1e-12);
                last = bent;
            }
        }
        // rearing over a mark, the cord never swings far past it: high up, where its head tips over, by a few
        // blocks more than down where players and the crown are
        int n = ChoirPose.segments(TOP - RY);
        for (double far = 0.5; far <= ChoirActs.SLAM_REACH; far += 0.5)
            for (double a = 0; a < Math.PI * 2; a += Math.PI / 6) {
                double tx = RX + far * Math.cos(a), tz = RZ + far * Math.sin(a);
                for (double reach = 0.05; reach < 1; reach += 0.05) {
                    double[][] at = line(n, 5, reach, tx, headY(tx, tz, false), tz);
                    for (double[] p : at) {
                        double past = (p[0] - RX) * Math.cos(a) + (p[2] - RZ) * Math.sin(a) - far;
                        assertTrue(past < (p[1] < 12 ? 3.5 : 10.5), "mark " + far + " off at " + Math.toDegrees(a) + ", reach "
                                + reach + ": the cord is " + past + " past its mark, " + p[1] + " up");
                    }
                }
            }
    }

    @Test
    void aRearedCordNeverPassesThroughTheCrown() {
        // every mark a slam can be laid on: anywhere within a tendril's reach of the root
        for (double mx = -ChoirArena.ROOT_R; mx <= 24; mx += 0.5)
            for (double mz = -17; mz <= 17; mz += 0.5) {
                if (Math.hypot(mx - RX, mz - RZ) > ChoirActs.SLAM_REACH) continue;
                for (int down = 0; down <= 1; down++) {
                    String what = "a mark at " + mx + ", " + mz + (down == 1 ? " (down)" : " (hanging)");
                    assertOutOfTheCrown(line(300, 5, 1, mx, headY(mx, mz, down == 1), mz), what);
                }
                // and on its way over, and back, for every mark that is not under the crown itself: under it there
                // is no way in but through
                if (Math.hypot(mx, mz) < ChoirPose.UNDER_R1) continue;
                for (double reach = 0.05; reach < 1; reach += 0.05)
                    assertOutOfTheCrown(line(60, 5, reach, mx, headY(mx, mz, false), mz),
                            "a mark at " + mx + ", " + mz + ", reach " + reach);
            }
    }

    private static void assertOutOfTheCrown(double[][] at, String what) {
        double wide = HollowChoirEntity.CROWN_R + CORD;
        for (double[] p : at)
            assertFalse(Math.hypot(p[0], p[2]) < wide && p[1] > CROWN_LOW - CORD && p[1] < CROWN_HIGH + CORD,
                    what + ": the cord is in the crown at " + p[0] + ", " + p[1] + ", " + p[2]);
    }

    @Test
    void theHeadHangsLowUnderTheCrown() {
        assertEquals(ChoirPose.HOVER_UNDER, ChoirPose.hover(0), 1e-9);
        assertEquals(ChoirPose.HOVER, ChoirPose.hover(1), 1e-9);
        // from this root: out on the floor it is all open, under the crown it is not at all, and it eases between
        assertEquals(1, ChoirPose.open(RX, RZ, 12, 3), 1e-9);
        assertEquals(1, ChoirPose.open(RX, RZ, ChoirPose.UNDER_R1, 0), 1e-9);
        assertEquals(0, ChoirPose.open(RX, RZ, 1, 0.5), 1e-9);
        double last = 0;
        for (double r = 0; r < 12; r += 0.1) {
            double open = ChoirPose.open(RX, RZ, r, 0);
            assertTrue(open >= last, "at " + r);
            last = open;
        }
        // across the dais it is clean over or clean under, never between
        assertEquals(1, ChoirPose.open(RX, RZ, -4, 0), 1e-9);
        assertEquals(0, ChoirPose.open(RX, RZ, -2, 0), 1e-9);
        assertEquals(1, ChoirPose.open(RX, RZ, -3.5, 1), 1e-9);
        // under the crown the head waits below it
        assertTrue(0.7 + ChoirPose.HOVER_UNDER + CORD < CROWN_LOW);
        assertTrue(ChoirPose.UNDER_R0 > HollowChoirEntity.CROWN_R + CORD);
    }

    @Test
    void aTendrilIsCutIntoPiecesOfAboutABlockAndAHalf() {
        assertEquals(4, ChoirPose.segments(1));
        assertEquals(31, ChoirPose.segments(46.5));
        assertEquals(ChoirPose.SEGS_MAX, ChoirPose.segments(1000));
    }

    private static double dist(double[] a, double[] b) {
        return Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]));
    }

    private static double sum(double[][] at, int n) {
        double l = 0;
        for (int i = 1; i <= n; i++) l += dist(at[i - 1], at[i]);
        return l;
    }
}
