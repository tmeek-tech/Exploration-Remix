package com.explorationremix.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The clouds of a crash and a blast (Billow, 0.9.0 eruption effects round 2) are sized to their cause: a ceiling
 * block's rock dust is a low billow about 3 to 5 blocks across that settles and is gone within 3 seconds, a bomb's ash a
 * cloud about 4 to 6 blocks across over the crater, gone within 4; both are many small puffs, and both thin away
 * smoothly rather than vanishing.
 */
class BillowTest {
    @Test
    void aCrashsDustIsALowBillowGoneWithinThreeSeconds() {
        Billow.Kind k = Billow.ROCK_DUST;
        double wide = Billow.width(k), narrow = Billow.narrowest(k);
        assertTrue(wide <= 5.0 && wide >= 4.0, "at most about 5 blocks across: " + wide);
        assertTrue(narrow >= 2.5, "never a wisp: " + narrow);
        assertTrue(k.life1() <= 60, "gone within 3 seconds");
        assertTrue(k.life0() >= 40, "there for 2 seconds");
        // it settles, never climbs: the fastest upward throw rises under a block, and then it sinks
        assertTrue(Billow.settle(k, k.up1(), 20) < 0.4, "rises " + Billow.settle(k, k.up1(), 20));
        assertTrue(Billow.settle(k, k.up1(), k.life1()) < Billow.settle(k, k.up1(), 20), "and sinks back");
        assertTrue(k.size1() * k.swell() <= 1, "small puffs: at most 2 blocks across");
        assertTrue(k.puffs() >= 20, "many of them");
    }

    @Test
    void aBlastsAshIsACloudOverTheCraterGoneWithinFourSeconds() {
        Billow.Kind k = Billow.BLAST_ASH;
        double wide = Billow.width(k), narrow = Billow.narrowest(k);
        assertTrue(wide <= 6.0 && wide >= 5.0, "at most about 6 blocks across: " + wide);
        assertTrue(narrow >= 3.5, "at least about 4: " + narrow);
        assertTrue(k.life1() <= 80 && k.life0() >= 60, "3 to 4 seconds");
        double up = Billow.settle(k, k.up1(), k.life1()), low = Billow.settle(k, k.up0(), k.life0());
        assertTrue(low > 1 && up < 3, "it rises 1 to 3 blocks out of the crater: " + low + " to " + up);
        assertTrue(k.size1() * k.swell() <= 1.2, "small puffs");
        assertTrue(k.puffs() >= 24, "many of them");
        assertTrue(k.grey() < 0.25 && Billow.ROCK_DUST.grey() > 0.5, "the ash dark, the dust pale");
    }

    @Test
    void puffsSwellAndThinSmoothly() {
        for (Billow.Kind k : new Billow.Kind[]{Billow.ROCK_DUST, Billow.BLAST_ASH}) {
            assertEquals(k.size0(), Billow.size(k, k.size0(), 0), 1e-6);
            assertEquals(k.size0() * k.swell(), Billow.size(k, k.size0(), 1), 1e-5);
            assertEquals(0, Billow.alpha(k, 0, 50), 0, "born clear");
            assertEquals(k.peak(), Billow.alpha(k, 5, 50), 1e-6, "thick at once");
            assertEquals(0, Billow.alpha(k, 50, 50), 1e-6, "gone at its end");
            float prev = Billow.alpha(k, 2, 50);
            for (int age = 3; age <= 50; age++) {
                float a = Billow.alpha(k, age, 50);
                assertTrue(a <= prev + 1e-6 && prev - a < 0.06, "thins smoothly at " + age);
                prev = a;
            }
            assertEquals(0.16 / 0.15, Billow.drift(0.16, 0.85), 1e-9);
        }
    }
}
