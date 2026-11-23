package com.explorationremix.client;

import com.explorationremix.client.UpperCaveSounds.Hush;
import com.explorationremix.client.UpperCaveSounds.Hush.Change;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Upper Caves' crickets (0.20.9): the chorus plays only well underground and is softer in a tight tunnel than in a
 * big cave; a cricket near you goes quiet the moment you come within a few blocks (Taran's card pick: "Yes, it goes
 * quiet"), stays quiet while you linger near it, and sings again once you have kept away for a few seconds.
 */
class UpperCaveSoundsTest {
    /** A player walking, in blocks a tick (4.3 blocks a second). */
    private static final double WALK = 0.215;

    @Test
    void theChorusPlaysOnlyWellUnderground() {
        assertEquals(0, UpperCaveSounds.underground(15, 0), "on the surface");
        assertEquals(0, UpperCaveSounds.underground(15, 30), "under a skylight");
        assertEquals(0, UpperCaveSounds.underground(0, 4), "a few blocks under the ground");
        assertEquals(0, UpperCaveSounds.underground(9, 20), "in a cave's mouth, the daylight reaching in");
        assertEquals(1, UpperCaveSounds.underground(0, 40), 1e-6, "deep in a cave");
        assertEquals(1, UpperCaveSounds.underground(2, 14), 1e-6, "past the mouth, ground well over it");
        // between the two it fades, a little more with each block of ground and each step out of the light
        float last = 0;
        for (int depth = 6; depth <= 14; depth++) {
            float u = UpperCaveSounds.underground(0, depth);
            assertTrue(u >= last, "deeper is never quieter: " + depth);
            last = u;
        }
        last = 0;
        for (int sky = 7; sky >= 2; sky--) {
            float u = UpperCaveSounds.underground(sky, 30);
            assertTrue(u >= last, "darker is never quieter: " + sky);
            last = u;
        }
    }

    @Test
    void theChorusIsSofterInATightTunnel() {
        float tunnel = UpperCaveSounds.roomGain(0.08F), hall = UpperCaveSounds.roomGain(0.7F);
        assertEquals(1, hall, 1e-6, "full in a big cave");
        assertTrue(tunnel > 0.5F && tunnel < 0.6F, "about half in a tight tunnel: " + tunnel);
    }

    @Test
    void aCricketSingsOnWhileYouKeepYourDistance() {
        Hush h = new Hush();
        for (int t = 0; t < 600; t++) {
            assertEquals(Change.NONE, h.tick(7 + 6 * Math.abs(Math.sin(t * 0.05))), "tick " + t);
            assertFalse(h.quiet());
        }
    }

    @Test
    void itGoesQuietTheMomentYouComeClose() {
        Hush h = new Hush();
        int fell = -1;
        double d = 16;
        for (int t = 0; d > 2; t++, d -= WALK) {
            Change c = h.tick(d);
            if (c == Change.FELL_QUIET) {
                assertEquals(-1, fell, "it falls quiet once");
                fell = t;
                assertTrue(d < 6.5 && d > 6.5 - WALK - 1e-9, "on the first step within a few blocks: " + d);
            } else {
                assertEquals(Change.NONE, c);
            }
            assertEquals(d < 6.5, h.quiet(), "at " + d);
        }
        assertTrue(fell >= 0, "it never fell quiet");
    }

    @Test
    void itStaysQuietWhileYouLingerNearIt() {
        Hush h = new Hush();
        assertEquals(Change.FELL_QUIET, h.tick(5));
        // standing about, a step this way and that, just past where it fell quiet: for half a minute it keeps still
        for (int t = 0; t < 600; t++) {
            assertEquals(Change.NONE, h.tick(8 + 1.8 * Math.sin(t * 0.1)), "tick " + t);
            assertTrue(h.quiet());
        }
    }

    @Test
    void itSingsAgainOnceYouHaveKeptAwayAFewSeconds() {
        Hush h = new Hush();
        assertEquals(Change.FELL_QUIET, h.tick(4));
        // walking off: past 10 blocks it counts three seconds, then may sing
        double d = 4;
        int t = 0, past = -1;
        Change c;
        do {
            d = Math.min(14, d + WALK);
            c = h.tick(d);
            if (d > 10 && past < 0) past = t;
            t++;
        } while (c == Change.NONE && t < 1000);
        assertEquals(Change.MAY_SING, c);
        int waited = t - past;
        assertTrue(waited > Hush.AWAY_TICKS && waited <= Hush.AWAY_TICKS + 2, "about three seconds after passing 10 blocks: " + waited);
        assertFalse(h.quiet());
    }

    @Test
    void comingBackResetsItsWait() {
        Hush h = new Hush();
        h.tick(4);
        for (int t = 0; t < Hush.AWAY_TICKS - 5; t++) assertEquals(Change.NONE, h.tick(12));
        // a step back within 10 blocks, though still out of its reach: the wait starts again
        assertEquals(Change.NONE, h.tick(9.5));
        for (int t = 0; t < Hush.AWAY_TICKS; t++) assertEquals(Change.NONE, h.tick(12), "tick " + t);
        assertEquals(Change.MAY_SING, h.tick(12));
        // and once it sings, coming close again quiets it again
        assertEquals(Change.FELL_QUIET, h.tick(6));
    }
}
