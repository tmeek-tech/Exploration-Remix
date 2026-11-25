package com.explorationremix.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The combat layer's numbers: how many hits break a creature's poise, momentum, slams, and poise coming back. */
class PoiseTest {
    /** Hits of {@code each} poise it takes to break {@code max}. */
    static int hitsToBreak(float max, float each) {
        int n = 0;
        for (float left = max; left > 0.0F; left -= each) n++;
        return n;
    }

    @Test
    void aZombieBreaksInThreeSwordHitsOrTwoAxeHits() {
        float zombie = Poise.max(20);
        assertEquals(16.0F, zombie, 1.0E-4);
        // an iron sword hits for 6, an iron axe for 9
        assertEquals(3, hitsToBreak(zombie, Poise.melee(6, Poise.Weapon.SWORD, 1.0F, false, 0.0F)));
        assertEquals(2, hitsToBreak(zombie, Poise.melee(9, Poise.Weapon.AXE, 1.0F, false, 0.0F)));
        // spamming wears little: a swing at a fifth of its charge deals a fifth and wears a third of that
        assertTrue(hitsToBreak(zombie, Poise.melee(1.2F, Poise.Weapon.SWORD, 0.2F, false, 0.0F)) > 30);
    }

    @Test
    void criticalAndMomentumHitsWearMore() {
        float plain = Poise.melee(6, Poise.Weapon.SWORD, 1.0F, false, 0.0F);
        assertEquals(1.5F * plain, Poise.melee(6, Poise.Weapon.SWORD, 1.0F, true, 0.0F), 1.0E-4);
        assertEquals(2.5F * plain, Poise.melee(6, Poise.Weapon.SWORD, 1.0F, false, 1.0F), 1.0E-4);
        // a full Dash's hit breaks a zombie at once
        assertEquals(1, hitsToBreak(Poise.max(20), Poise.melee(6 * Poise.momentumDamage(1.0F), Poise.Weapon.SWORD, 1.0F, false, 1.0F)));
    }

    @Test
    void walkingCarriesNoMomentumADashAll() {
        assertEquals(0.0F, Poise.momentum(0.0), "standing");
        assertEquals(0.0F, Poise.momentum(0.2), "walking (0.22 a tick at most) carries next to nothing");
        float sprint = Poise.momentum(0.28);
        assertTrue(sprint > 0.0F && sprint < 0.2F, "a sprint a little: " + sprint);
        float leap = Poise.momentum(0.45);
        assertTrue(leap > 0.35F && leap < 0.5F, "a sprinting leap more: " + leap);
        assertEquals(1.0F, Poise.momentum(1.2), "a Dash all");
        assertEquals(0.0F, Poise.momentum(-1.0), "backing away");
        assertEquals(1.5F, Poise.momentumDamage(1.0F), 1.0E-4);
        assertEquals(0.0F, Poise.momentumPush(0.0F));
        assertEquals(0.8F, Poise.momentumPush(1.0F), 1.0E-4);
    }

    @Test
    void onlyAHardKnockIntoAWallSlams() {
        assertEquals(0.0F, Poise.slamDamage(0.4), "a plain hit's knockback only bumps");
        assertEquals(1.0F, Poise.slamDamage(Poise.SLAM_FROM), 1.0E-4);
        float sprintHit = Poise.slamDamage(0.7);
        assertTrue(sprintHit > 2.0F && sprintHit < 3.0F, "a sprinting hit against a wall: " + sprintHit);
        assertEquals(Poise.SLAM_MAX, Poise.slamDamage(5.0), "never more than the most");
        assertEquals(2.0F * sprintHit, Poise.slam(sprintHit), 1.0E-4);
        assertEquals(0.0F, Poise.slamShare(Poise.SLAM_FROM), 1.0E-4);
        assertEquals(1.0F, Poise.slamShare(3.0), 1.0E-4);
    }

    @Test
    void poiseComesBackAfterAQuietSpell() {
        float max = Poise.max(20);
        // hit at tick 100 down to 4: nothing back until the delay is up, then a fifth a second
        assertEquals(4.0F, Poise.at(4.0F, max, 100, 100, 100 + Poise.RECOVER_DELAY), 1.0E-4);
        assertEquals(4.0F + max * Poise.RECOVER_PER_TICK * 20, Poise.at(4.0F, max, 100, 100, 100 + Poise.RECOVER_DELAY + 20), 1.0E-4);
        assertEquals(max, Poise.at(4.0F, max, 100, 100, 100_000), "never past the most");
    }

    /** Taran 2026-11-25: a jump attack lands much harder on the one creature in front, up to double from about 8 blocks. */
    @Test
    void aJumpAttackLandsHarderTheFurtherThePlayerFell() {
        assertEquals(1.0F, Poise.plunge(0.0), 1.0E-6);
        assertEquals(1.0F, Poise.plunge(1.25), 1.0E-6, "a jump on the flat");
        assertEquals(2.0F, Poise.plunge(8.0), 1.0E-6);
        assertEquals(2.0F, Poise.plunge(30.0), 1.0E-6, "never past double");
        assertTrue(Poise.plunge(4.0) > 1.3F && Poise.plunge(4.0) < 1.5F);
        // an iron sword's jump attack off a hop does more than a strong attack, and breaks a zombie's poise at once
        assertTrue(Poise.jumpDamage(1.25) > Poise.swingDamage(true, false));
        float zombie = Poise.max(20);
        assertEquals(1, hitsToBreak(zombie, Poise.melee(6 * Poise.jumpDamage(1.25), Poise.Weapon.SWORD, 1.0F, false, 0.0F) * Poise.JUMP_POISE));
    }
}
