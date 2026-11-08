package com.explorationremix.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An eruption as the client plays it (Fountain): the fountain throws in full for the burst's first second, then gusts
 * between 0.6 and 1 the same way on every client and every frame of a held moment, and dies away over its last 30
 * ticks; its spatter climbs 10 to 27 blocks under its own physics (15 to 40 for a woken eruption); the roar swells and
 * dies with it; the boom and the roar keep a floor of loudness to the eruption's range; far particles thin with the
 * Particles option and the distance and the spatter grows; the vent's rumble starts only in time, a short warning's too;
 * a bomb's jolt and whistle follow their rules; and (the pack's look fixes) the start's flash dies within 16 ticks, the
 * jet of gobs fans out but mostly stays tight, a gob lives until it has rained back down past the crater floor, and the
 * plume's ash is thrown up out of the mouth toward the vault and still spreads under rock; and (effects round 2) the
 * warning shows steadily, its pops on a cadence that quickens toward the burst and its glow and fire growing, and the
 * burst's start blazes for its first three ticks.
 */
class FountainTest {
    private static final long[] SEEDS = {0, 1, 7, -42, 8675309, Long.MIN_VALUE, Long.MAX_VALUE};

    @Test
    void theFountainThrowsInFullThenGustsAndDiesAway() {
        int burst = 200;
        for (long seed : SEEDS) {
            for (int t = 0; t < Fountain.FULL_TICKS; t++) assertEquals(1, Fountain.intensity(t, burst, seed), 0, "full at first");
            double prev = 1, lo = 1;
            for (double t = Fountain.FULL_TICKS; t < burst - Fountain.FADE_TICKS; t += 0.5) {
                double k = Fountain.intensity(t, burst, seed);
                assertTrue(k >= Fountain.GUST_LOW - 1e-9 && k <= 1 + 1e-9, "gusts between 0.6 and 1: " + k);
                assertTrue(Math.abs(k - prev) < 0.05, "no jump in a half tick");
                prev = k;
                lo = Math.min(lo, k);
            }
            assertTrue(lo < 0.85, "it does gust, seed " + seed);
            assertEquals(0, Fountain.intensity(burst, burst, seed), 0);
            assertEquals(0, Fountain.intensity(-1, burst, seed), 0);
            double before = Fountain.intensity(burst - Fountain.FADE_TICKS - 0.01, burst, seed);
            assertTrue(Fountain.intensity(burst - Fountain.FADE_TICKS / 2.0, burst, seed) <= 0.5 + 1e-9, "half gone half way through its fade");
            assertTrue(Fountain.intensity(burst - 1, burst, seed) < before / 10, "nearly gone at its end");
            // every client, and every frame of a held moment, see the same
            assertEquals(Fountain.intensity(87.25, burst, seed), Fountain.intensity(87.25, burst, seed), 0);
        }
    }

    @Test
    void theSpatterClimbsTenToTwentySevenBlocksAndHalfAgainWoken() {
        double low = Fountain.apex(Fountain.speed(0, 1)), high = Fountain.apex(Fountain.speed(1, 1));
        assertTrue(low >= 9.5 && low <= 11, "lowest " + low);
        assertTrue(high >= 25 && high <= 27.5, "highest " + high);
        double wokenLow = Fountain.apex(Fountain.speed(0, 1.5)), wokenHigh = Fountain.apex(Fountain.speed(1, 1.5));
        assertTrue(wokenLow >= 13.5 && wokenLow <= 16, "woken lowest " + wokenLow);
        assertTrue(wokenHigh >= 34 && wokenHigh <= 40, "woken highest " + wokenHigh);
        // the apex comes within the spatter's shortest life (30 ticks) for nearly all of them, so the column reaches its top
        double v = Fountain.speed(1, 1), y = 0;
        for (int t = 0; t < 30; t++) {
            v -= 0.04 * Fountain.SPATTER_GRAVITY;
            y += v;
            v *= Fountain.SPATTER_FRICTION;
        }
        assertTrue(y > 0.95 * high, "at 30 ticks the fastest is at " + y + " of " + high);
    }

    @Test
    void theRoarSwellsAndDiesWithTheFountain() {
        int burst = 180;
        assertEquals(0, Fountain.roarEnvelope(0, burst), 0);
        assertEquals(0.5, Fountain.roarEnvelope(5, burst), 1e-12);
        assertEquals(1, Fountain.roarEnvelope(Fountain.ROAR_RISE, burst), 1e-12);
        assertEquals(1, Fountain.roarEnvelope(burst - Fountain.ROAR_FADE, burst), 1e-12);
        assertEquals(0.5, Fountain.roarEnvelope(burst - Fountain.ROAR_FADE / 2.0, burst), 1e-12);
        assertEquals(0, Fountain.roarEnvelope(burst, burst), 0);
        assertEquals(0, Fountain.roarEnvelope(-3, burst), 0);
    }

    @Test
    void theBoomAndTheRoarAreHeardToTheEruptionsRange() {
        assertEquals(1, Fountain.boomVolume(0, 256), 0);
        assertEquals(1, Fountain.boomVolume(51.2, 256), 1e-12);
        assertEquals(0.7, Fountain.boomVolume(128, 256), 1e-12);
        assertEquals(0.2, Fountain.boomVolume(256, 256), 1e-12);
        assertEquals(0.15, Fountain.boomVolume(1000, 256), 0);
        assertEquals(0.1, Fountain.roarVolume(300, 256), 0);
        assertEquals(0.7, Fountain.roarVolume(256, 512), 1e-12);
        for (double d = 0; d < 600; d += 7) {
            assertTrue(Fountain.boomVolume(d, 256) >= Fountain.roarVolume(d, 256), "the boom never quieter than the roar");
        }
    }

    @Test
    void farParticlesThinAndTheSpatterGrows() {
        assertEquals(1, Fountain.farShare(10, 0.1), 0, "near: the game thins them itself");
        assertEquals(1, Fountain.farShare(32, 0.4), 0);
        assertEquals(1, Fountain.farShare(40, 1), 0);
        assertEquals(0.4, Fountain.farShare(48, 0.4), 1e-12);
        assertEquals(0.5, Fountain.farShare(96, 1), 1e-12);
        assertEquals(0.1 * 48 / 200.0, Fountain.farShare(200, 0.1), 1e-12);
        assertEquals(1, Fountain.farSize(10), 0);
        assertEquals(1, Fountain.farSize(32), 0);
        assertEquals(4, Fountain.farSize(128), 1e-12);
        // the ash grows more gently: twice at 128 blocks, so a far column keeps its width without swamping the view
        assertEquals(1, Fountain.farAshSize(32), 0);
        assertEquals(2, Fountain.farAshSize(128), 1e-12);
        assertTrue(Fountain.farAshSize(113) > 1.8 && Fountain.farAshSize(113) < Fountain.farSize(113));
        assertEquals(5, Fountain.plumeEvery(1));
        assertEquals(3, Fountain.plumeEvery(1.5));
        assertEquals(2, Fountain.plumeEvery(3));
        assertEquals(1, Fountain.plumeEvery(100));
    }

    @Test
    void aShareOfOneShowsNowAndThen() {
        assertEquals(0, Fountain.count(0, 0));
        assertEquals(0, Fountain.count(-2, 0));
        assertEquals(3, Fountain.count(3, 0.99));
        assertEquals(4, Fountain.count(3.25, 0.2));
        assertEquals(3, Fountain.count(3.25, 0.3));
        int n = 0;
        for (int i = 0; i < 1000; i++) n += Fountain.count(0.25, (i + 0.5) / 1000);
        assertEquals(250, n, "on average the share asked for");
    }

    @Test
    void theVentRumblesWhenItsWarningIsHeardInTime() {
        // the default warning (4 s): only with 20 ticks or more of it to come
        assertTrue(Fountain.rumbleInTime(0, 80));
        assertTrue(Fountain.rumbleInTime(60, 80));
        assertTrue(!Fountain.rumbleInTime(60.5, 80), "too late with under 20 ticks left");
        assertTrue(!Fountain.rumbleInTime(80, 80), "never once the burst has begun");
        // a short warning (warnSeconds 1, 0.5, 0.1) still rumbles when it is heard from its start, with half of it to come
        for (int pre : new int[]{20, 10, 2}) {
            assertTrue(Fountain.rumbleInTime(0, pre), "a warning of " + pre + " ticks heard from its start");
            assertTrue(Fountain.rumbleInTime(1, pre) == (pre - 1 >= pre / 2.0), "a warning of " + pre + " ticks, one tick in");
            assertTrue(!Fountain.rumbleInTime(pre, pre));
        }
        assertTrue(!Fountain.rumbleInTime(0, 0), "no warning, no rumble");
    }

    // ------------------------------------------------------------------------------------------------ the spectacle

    @Test
    void theStartFlashesAndDiesWithinItsSixteenTicks() {
        assertEquals(1, Fountain.flash(0), 0, "in full at the burst's first tick");
        assertEquals(0.25, Fountain.flash(Fountain.FLASH_TICKS / 2.0), 1e-12, "a quarter half way");
        assertEquals(0, Fountain.flash(Fountain.FLASH_TICKS), 0);
        assertEquals(0, Fountain.flash(-1), 0, "nothing in the warning");
        assertEquals(0, Fountain.flash(200), 0);
        for (double t = 0; t < Fountain.FLASH_TICKS - 0.5; t += 0.5)
            assertTrue(Fountain.flash(t + 0.5) < Fountain.flash(t), "it only dies away");
    }

    @Test
    void theJetFansOutButMostOfItStaysTight() {
        int n = 2000, core = 0;
        double widest = 0;
        for (int i = 0; i < n; i++) {
            double u = (i + 0.5) / n, w = ((i * 7919) % n + 0.5) / n;
            double tilt = Fountain.tilt(u, w);
            assertTrue(tilt >= 0 && tilt <= Fountain.SPRAY_CONE, "within the fan: " + tilt);
            if (tilt <= Fountain.CORE_CONE) core++;
            widest = Math.max(widest, tilt);
        }
        assertTrue(core > 0.6 * n, "most in the jet: " + core);
        assertTrue(widest > 0.9 * Fountain.SPRAY_CONE, "some fan out to " + widest);
        assertEquals(Fountain.CORE_CONE, Fountain.tilt(0.99, 1), 1e-12);
        assertEquals(Fountain.SPRAY_CONE, Fountain.tilt(0, 1), 1e-12);
        assertEquals(0, Fountain.tilt(0.5, 0), 0);
    }

    @Test
    void aGobRainsBackDownBeforeItGoesOut() {
        for (double u = 0; u <= 1; u += 0.25) {
            double vy = Fountain.speed(u, 1);
            int life = Fountain.flightTicks(vy, Fountain.GOB_DROP);
            // it lives past its apex and back down through the crater floor it left, and on down the flanks
            double y = 0, v = vy, top = 0;
            int back = -1;
            for (int t = 1; t <= life; t++) {
                v -= 0.04 * Fountain.SPATTER_GRAVITY;
                y += v;
                v *= Fountain.SPATTER_FRICTION;
                top = Math.max(top, y);
                if (back < 0 && y < 0) back = t;
            }
            assertTrue(back > 0 && back < life, "back under the floor at " + back + " of its " + life + " ticks");
            assertEquals(Fountain.apex(vy), top, 1e-9, "it reaches its full height");
            assertTrue(y <= -Fountain.GOB_DROP + 2, "and ends " + y + " under the floor");
            assertTrue(life <= Fountain.GOB_LIFE_MAX);
        }
        // a woken eruption's fastest reaches the cap rather than flying on for ever
        assertEquals(Fountain.GOB_LIFE_MAX, Fountain.flightTicks(50, 1000));
        // a pop's gobs clear the crater's rim (2 to 4 blocks over its floor) and fall back in a few seconds
        assertTrue(Fountain.apex(Fountain.POP_SPEED_MIN) > 4, "the lowest pop climbs " + Fountain.apex(Fountain.POP_SPEED_MIN));
        assertTrue(Fountain.apex(Fountain.POP_SPEED_MAX) < 12, "the highest pop climbs " + Fountain.apex(Fountain.POP_SPEED_MAX));
        assertTrue(Fountain.flightTicks(Fountain.POP_SPEED_MAX, 1) < 70);
    }

    @Test
    void thePlumeIsThrownToTheVault() {
        assertEquals(Fountain.ASH_THRUST_MIN, Fountain.ashThrust(0, 1), 1e-12);
        assertEquals(Fountain.ASH_THRUST_MAX, Fountain.ashThrust(1, 1), 1e-12);
        assertTrue(Fountain.ashThrust(1, 2) > Fountain.ashThrust(1, 1), "a stronger eruption throws harder");
        assertEquals(0, Fountain.ashThrust(1, 0), 0);
        // the thrust alone carries a puff 15 to 27 blocks up out of the mouth; then it rises on as the smoke does
        assertTrue(Fountain.thrustRise(Fountain.ashThrust(0, 1)) >= 14, "lowest " + Fountain.thrustRise(Fountain.ashThrust(0, 1)));
        assertTrue(Fountain.thrustRise(Fountain.ashThrust(1, 1)) <= 28);
        SmokePuff puff = new SmokePuff(0, 0, 0, 0, 60, () -> 0.5).launch(Fountain.ashThrust(0.5, 1));
        SmokePuff still = new SmokePuff(0, 0, 0, 0, 60, () -> 0.5);
        for (int t = 0; t < 100; t++) {
            puff.tick((x, y, z) -> false, () -> 0.5);
            still.tick((x, y, z) -> false, () -> 0.5);
        }
        double gained = puff.y - still.y, expected = Fountain.thrustRise(Fountain.ashThrust(0.5, 1));
        assertTrue(gained > 0.85 * expected && gained < 1.05 * expected, "the thrust carried it " + gained + " of " + expected);
        assertTrue(puff.y > 25, "five seconds out it is " + puff.y + " blocks up");
        // under a vault it stops and spreads all the same
        SmokePuff capped = new SmokePuff(0, 0, 0, 0, 60, () -> 0.5).launch(0.8);
        for (int t = 0; t < 200; t++) capped.tick((x, y, z) -> y >= 20, () -> 0.5);
        assertTrue(capped.y < 20, "held under the rock at " + capped.y);
        assertTrue(capped.spreads > 0, "and spread under it");
        // a slow puff is not slowed by a launch
        SmokePuff slow = new SmokePuff(0, 0, 0, 0, 60, () -> 0.5);
        double vy = slow.vy;
        slow.launch(0);
        assertEquals(vy, slow.vy, 0);
    }

    @Test
    void aBombsJoltAndWhistle() {
        assertEquals(0.4, Fountain.bombJolt(0), 1e-12);
        assertEquals(0.2, Fountain.bombJolt(16), 1e-12);
        assertEquals(0, Fountain.bombJolt(32), 0);
        assertEquals(0, Fountain.bombJolt(60), 0);
        assertEquals(0.95, Fountain.whistlePitch(0), 1e-12);
        assertEquals(1.10, Fountain.whistlePitch(1.6), 1e-12);
        assertEquals(0.80, Fountain.whistlePitch(-2), 1e-12);
        assertTrue(Fountain.whistlePitch(0.5) > Fountain.whistlePitch(-0.5), "higher climbing than falling");
    }

    /**
     * The warning's pops come on a cadence, not by chance: every 20 ticks as it starts, quickening steadily to every 3 as
     * the burst comes, so in the last two seconds of the default 4-second warning a pop comes at least every 8 ticks,
     * and a pop's gobs (in the air 30 ticks and more) never leave a gap; each pop flings more gobs toward the end.
     */
    @Test
    void theWarningsPopsComeOnAQuickeningCadence() {
        assertEquals(20, Fountain.popEvery(0), 1e-9);
        assertEquals(3, Fountain.popEvery(1), 1e-9);
        assertEquals(Math.sqrt(20 * 3), Fountain.popEvery(0.5), 1e-9);
        assertEquals(20, Fountain.popEvery(-1), 1e-9);
        assertEquals(3, Fountain.popEvery(2), 1e-9);
        for (double w = 0; w < 1; w += 0.01) assertTrue(Fountain.popEvery(w + 0.01) < Fountain.popEvery(w), "quickening at " + w);
        // played on a clock as EruptionEffects does: the pops of a 4-second warning, by the tick they come
        int pre = 80, pops = 0, last = -1, widest = 0, lateWidest = 0;
        double clock = 0;
        for (int t = 0; t < pre; t++) {
            clock += 1 / Fountain.popEvery(Fountain.warned(t, pre));
            if (clock < 1) continue;
            clock = Math.min(1, clock - 1);
            pops++;
            if (last >= 0) {
                widest = Math.max(widest, t - last);
                if (t >= pre - 40) lateWidest = Math.max(lateWidest, t - last);
            }
            last = t;
        }
        assertTrue(pops >= 10 && pops <= 16, pops + " pops in a 4-second warning");
        assertTrue(widest <= 20, "never more than a second apart: " + widest);
        assertTrue(lateWidest <= 8, "in its last two seconds at most 8 ticks apart: " + lateWidest);
        assertTrue(Fountain.popGobs(1) > Fountain.popGobs(0), "more gobs a pop toward the end");
        assertEquals(Fountain.POP_GOBS * 0.75, Fountain.popGobs(0), 1e-9);
        assertEquals(Fountain.POP_GOBS * 1.5, Fountain.popGobs(1), 1e-9);
        assertEquals(1, Fountain.warned(5, 0), 0, "no warning: all warned");
        assertEquals(0.5, Fountain.warned(40, 80), 1e-12);
        assertEquals(1, Fountain.warned(100, 80), 0);
    }

    /**
     * The glow at the mouth and the fire over it grow steadily through the warning, and there is always a glow: a new
     * one comes before the last is gone. The burst's blaze shows for its first three ticks, and is larger than the
     * biggest fireball of its flash.
     */
    @Test
    void theWarningGlowsSteadilyAndTheBurstBlazes() {
        for (double w = 0; w < 1; w += 0.05) {
            assertTrue(Fountain.glowSize(w + 0.05) > Fountain.glowSize(w), "the glow grows");
            assertTrue(Fountain.glowStrength(w + 0.05) > Fountain.glowStrength(w), "and brightens");
            assertTrue(Fountain.warnFire(w + 0.05) > Fountain.warnFire(w), "the fire grows");
        }
        assertEquals(Fountain.GLOW_MIN, Fountain.glowSize(0), 1e-12);
        assertEquals(Fountain.GLOW_MAX, Fountain.glowSize(1), 1e-12);
        assertEquals(1, Fountain.glowStrength(1), 1e-12);
        assertEquals(Fountain.WARN_FIRE, Fountain.warnFire(0), 1e-12);
        assertTrue(Fountain.glowSize(0.5) * 2 >= 6, "halfway through, the glow is six blocks across");
        assertTrue(Fountain.GLOW_LIFE > Fountain.GLOW_EVERY, "a new glow comes before the last one is gone");
        for (int tb = -1; tb <= 4; tb++) assertEquals(tb >= 0 && tb < 3, Fountain.blazing(tb), "the blaze at " + tb);
        // a flash fireball is at most 1.2 blocks in half-width, drawn 2.2 times that, swelling to 1.9 times
        double fireball = 1.2 * EruptionEffects.FLASH_SIZE * 1.9;
        assertTrue(Fountain.BLAZE_SIZE > fireball, "the blaze (" + Fountain.BLAZE_SIZE + ") outgrows the fireballs (" + fireball + ")");
    }

    @Test
    void aFlashIsDrawnTowardTheCameraAtTheSameApparentSize() {
        // from the rim (40 blocks) the blaze comes the whole 8 blocks forward, in front of the ash round the mouth
        assertEquals(8.0 / 40, Fountain.toward(Fountain.BLAZE_FORWARD, 40), 1e-12);
        // close by, never more than halfway to the camera; at the camera, not at all
        assertEquals(0.5, Fountain.toward(Fountain.BLAZE_FORWARD, 6), 1e-12);
        assertEquals(0, Fountain.toward(Fountain.BLAZE_FORWARD, 0), 1e-12);
        assertEquals(0, Fountain.toward(-1, 20), 1e-12);
        for (double dist = 0.5; dist < 200; dist *= 1.7) {
            double k = Fountain.toward(Fountain.GLOW_FORWARD, dist);
            assertTrue(k >= 0 && k <= 0.5, "within halfway: " + k);
            // drawn at (1 - k) of its half-width, (1 - k) of the distance off: the same angle in the view
            double half = 3, angle = half / dist, drawn = half * (1 - k) / (dist * (1 - k));
            assertEquals(angle, drawn, 1e-12);
        }
        assertTrue(Fountain.BLAST_FORWARD < Fountain.GLOW_FORWARD && Fountain.GLOW_FORWARD < Fountain.BLAZE_FORWARD);
    }
}
