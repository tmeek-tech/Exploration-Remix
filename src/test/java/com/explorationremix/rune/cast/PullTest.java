package com.explorationremix.rune.cast;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reel's pull to a wall, steered by the server and moved as the game moves a player in the air: it gets there and
 * stops there, on a quick connection and on slow ones.
 */
class PullTest {
    /** How a pull went: ticks until it arrived (-1: never), how far off they came to rest, how far past the point they ever got. */
    record Run(int ticks, double off, double past, double fastest) {}

    /**
     * Pulls a player from the origin to {@code tx, ty, tz} as the game does, with {@code extra} ticks of delay each way
     * on top of the game's own (0: one machine). Each tick the client moves by the last speed it was sent, then falls
     * and slows; the server hears where the client was {@code extra + 1} ticks ago, moves that on by its own copy of the
     * last speed it set (its own move of the player), adds the speeds still on their way when {@code lead} is on, and
     * sets the next speed. When it decides they have arrived it sends a stop, and the client runs on until that lands.
     */
    static Run pull(double tx, double ty, double tz, int extra, boolean lead) {
        double len = Math.sqrt(tx * tx + ty * ty + tz * tz);
        double ux = tx / len, uy = ty / len, uz = tz / len;
        List<double[]> client = new ArrayList<>();
        client.add(new double[]{0, 0, 0});
        List<double[]> sent = new ArrayList<>();
        double[] v = {0, 0, 0};
        int ahead = lead ? Pull.lead(100 * extra) : 0;
        double past = Double.NEGATIVE_INFINITY, fastest = 0;
        int limit = Pull.ticks(len, Pull.SPEED), stoppedAt = -1;
        for (int n = 0; n < limit + extra + 2; n++) {
            // the client's tick: the speed set extra + 1 ticks ago lands, it moves, then falls and slows
            int k = n - 1 - extra;
            if (k >= 0 && k < sent.size()) v = sent.get(k).clone();
            double[] c = client.get(client.size() - 1);
            double[] next = {c[0] + v[0], c[1] + v[1], c[2] + v[2]};
            client.add(next);
            v[1] = (v[1] - 0.08) * 0.98;
            v[0] *= 0.91;
            v[2] *= 0.91;
            past = Math.max(past, (next[0] - tx) * ux + (next[1] - ty) * uy + (next[2] - tz) * uz);
            if (stoppedAt >= 0) {
                if (n > stoppedAt + extra + 1) break;
                continue;
            }
            if (n >= limit) return new Run(-1, Double.NaN, past, fastest);
            // the server's tick, at its end
            double[] heard = client.get(Math.max(0, n - extra));
            double[] last = sent.isEmpty() ? new double[]{0, 0, 0} : sent.get(sent.size() - 1);
            double px = heard[0] + last[0], py = heard[1] + last[1], pz = heard[2] + last[2];
            for (int j = 2; j <= 1 + ahead && j <= sent.size(); j++) {
                double[] w = sent.get(sent.size() - j);
                px += w[0];
                py += w[1];
                pz += w[2];
            }
            double dx = tx - px, dy = ty - py, dz = tz - pz;
            if (Math.sqrt(dx * dx + dy * dy + dz * dz) < Pull.ARRIVE) {
                sent.add(new double[]{0, 0, 0});
                stoppedAt = n;
                continue;
            }
            double[] s = Pull.step(dx, dy, dz, Pull.SPEED, n);
            fastest = Math.max(fastest, Math.sqrt(s[0] * s[0] + s[1] * s[1] + s[2] * s[2]));
            sent.add(s);
        }
        double[] c = client.get(client.size() - 1);
        double ox = tx - c[0], oy = ty - c[1], oz = tz - c[2];
        return new Run(stoppedAt, Math.sqrt(ox * ox + oy * oy + oz * oz), past, fastest);
    }

    static final double[][] POINTS = {{6, 0, 0}, {0, 0, -9}, {5, 4, 3}, {3, 8, 0}, {20, 6, 5}, {4, -3, 4}, {0, 12, 1}, {-17, 2, 17}, {2, 0, 0}, {0, 3, 0}};

    @Test
    void onOneMachineItGetsThereAndStopsThere() {
        for (double[] d : POINTS) {
            Run r = pull(d[0], d[1], d[2], 0, true);
            String where = "to " + d[0] + ", " + d[1] + ", " + d[2];
            assertTrue(r.ticks() > 0, where + ": never got there");
            assertTrue(r.off() < Pull.ARRIVE + 0.15, where + ": came to rest " + r.off() + " off");
            assertTrue(r.past() < 0.2, where + ": went " + r.past() + " past");
        }
    }

    @Test
    void onASlowConnectionTheSpeedsOnTheirWayKeepItFromShootingPast() {
        for (int extra = 1; extra <= 2; extra++) {
            for (double[] d : POINTS) {
                Run r = pull(d[0], d[1], d[2], extra, true);
                String where = extra * 100 + " ms to " + d[0] + ", " + d[1] + ", " + d[2];
                assertTrue(r.ticks() > 0, where + ": never got there");
                assertTrue(r.past() < 0.5, where + ": went " + r.past() + " past");
            }
        }
        // without them it would fly well past
        assertTrue(pull(20, 6, 5, 1, false).past() > 1.0);
    }

    @Test
    void theLeadFollowsTheRoundTrip() {
        assertEquals(0, Pull.lead(0));
        assertEquals(0, Pull.lead(20));
        assertEquals(2, Pull.lead(100));
        assertEquals(4, Pull.lead(210));
        assertEquals(Pull.MAX_LEAD, Pull.lead(5000));
        assertEquals(0, Pull.lead(-1));
    }

    @Test
    void neverFasterThanFullPullAndItBuildsUp() {
        assertTrue(pull(30, 10, -12, 0, true).fastest() <= Pull.SPEED + 1.0E-9);
        assertEquals(Pull.START, Pull.speed(Pull.SPEED, 0, 50), 1.0E-9);
        assertTrue(Pull.speed(Pull.SPEED, 1, 50) > Pull.speed(Pull.SPEED, 0, 50));
        assertEquals(Pull.SPEED, Pull.speed(Pull.SPEED, 10, 50), 1.0E-9);
        // easing off near the point
        assertTrue(Pull.speed(Pull.SPEED, 10, 0.5) < 0.6);
        double[] v = Pull.step(0, 0, 0, Pull.SPEED, 5);
        assertEquals(0.0, v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    @Test
    void theLongestReelHasTimeToArrive() {
        double far = Instants.GRAPPLE_RANGE + 3.0;
        for (int extra = 0; extra <= 2; extra++) {
            assertTrue(pull(far * 0.6, far * 0.5, far * 0.62, extra, true).ticks() > 0, extra * 100 + " ms");
            assertTrue(pull(0, -far, 0.5, extra, true).ticks() > 0, extra * 100 + " ms, straight down");
        }
    }

    @Test
    void somethingInTheWayStopsThePull() {
        Pull.Progress p = new Pull.Progress();
        assertTrue(p.update(10.0));
        assertTrue(p.update(9.0));
        // stuck against a block: no nearer for a few ticks, then it gives up
        int ticks = 0;
        while (p.update(9.0) && ticks < 100) ticks++;
        assertEquals(Pull.STALL_TICKS, ticks);
        // a new point starts afresh
        p.reset();
        assertTrue(p.update(9.0));
        assertTrue(p.update(8.9));
        assertTrue(p.update(8.0));
    }
}
