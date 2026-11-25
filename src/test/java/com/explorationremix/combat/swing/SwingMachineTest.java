package com.explorationremix.combat.swing;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The timed swings as the player's game runs them (Taran's picks, 2026-11-11): a tap's light swing lands a quarter of a
 * second after the press; held, it turns strong and lands at half a second, then lunges; three light swings or a strong
 * attack start the 1.2 s cooldown, in which only a strong attack (and only after three light swings) can be made. And
 * every landing the machine makes is one the server's rules accept.
 */
class SwingMachineTest {
    private static final WeaponProfile SWORD = WeaponProfile.SWORD;

    /** What the machine said, tick by tick. */
    static final class Log implements SwingMachine.Out {
        final List<String> events = new ArrayList<>();
        int tick;
        final List<int[]> lands = new ArrayList<>();   // tick, seq, kind, combo

        @Override public void start(int seq, SwingKind kind, int combo) { events.add(tick + " start " + kind + " " + combo); }
        @Override public void upgrade(int seq, SwingKind kind) { events.add(tick + " upgrade" + (kind == SwingKind.STRONG ? "" : " " + kind)); }
        @Override public void land(int seq, SwingKind kind, int combo) {
            events.add(tick + " land " + kind + " " + combo);
            lands.add(new int[]{tick, seq, kind.ordinal(), combo});
        }
        @Override public void lunge(int seq, int t, double speed) { events.add(tick + " lunge " + t); }
        @Override public void cancel(int seq) { events.add(tick + " cancel"); }
        @Override public void refused() { events.add(tick + " refused"); }

        boolean has(String e) { return events.contains(e); }
    }

    /** Runs the machine {@code ticks} ticks: a press on the ticks {@code presses} says, the button held while {@code held} says. */
    static Log run(SwingMachine m, int ticks, IntPredicate presses, IntPredicate held) {
        return run(m, ticks, presses, held, k -> SwingMachine.Footing.GROUND);
    }

    /** The same, the player's footing on each tick as {@code footing} says. */
    static Log run(SwingMachine m, int ticks, IntPredicate presses, IntPredicate held, java.util.function.IntFunction<SwingMachine.Footing> footing) {
        Log log = new Log();
        for (int k = 0; k < ticks; k++) {
            log.tick = k;
            if (presses.test(k)) m.press(log);
            m.tick(held.test(k), footing.apply(k), log);
        }
        return log;
    }

    /** In the air on ticks {@code from} to {@code to} (a jump), on their feet otherwise. */
    static java.util.function.IntFunction<SwingMachine.Footing> jump(int from, int to) {
        return k -> k >= from && k <= to ? SwingMachine.Footing.AIR : SwingMachine.Footing.GROUND;
    }

    @Test
    void aTapLandsALightSwingAQuarterSecondLater() {
        Log log = run(new SwingMachine(SWORD), 30, k -> k == 0, k -> k == 0);
        assertTrue(log.has("0 start LIGHT 0"), log.events.toString());
        // pressed on tick 0, it lands on the fifth tick after: 0.25 s
        assertTrue(log.has("4 land LIGHT 0"), log.events.toString());
        assertFalse(log.events.stream().anyMatch(e -> e.contains("upgrade")), "a tap never turns strong");
    }

    @Test
    void holdingTurnsItStrongItLandsAtHalfASecondAndLunges() {
        SwingMachine m = new SwingMachine(SWORD);
        Log log = run(m, 40, k -> k == 0, k -> k < 20);
        assertTrue(log.has("3 upgrade"), log.events.toString());
        assertTrue(log.has("9 land STRONG 0"), log.events.toString());
        assertTrue(log.has("10 lunge 0") && log.has("11 lunge 1") && log.has("12 lunge 2"), log.events.toString());
        assertEquals(0, m.chain());
    }

    @Test
    void threeLightSwingsStartTheCooldownInWhichATapDoesNothing() {
        SwingMachine m = new SwingMachine(SWORD);
        // taps every few ticks: each waits for the swing before it (buffered)
        Log log = run(m, 30, k -> k % 3 == 0 && k < 25, k -> k % 3 == 0 && k < 25);
        long lights = log.lands.stream().filter(l -> l[2] == SwingKind.LIGHT.ordinal()).count();
        assertEquals(3, lights, log.events.toString());
        assertEquals(List.of(0, 1, 2), log.lands.stream().map(l -> l[3]).toList(), "slash, backhand, chop");
        // a click that comes just as a wind-up would turn strong is a new tap, not a hold
        assertFalse(log.events.stream().anyMatch(e -> e.contains("upgrade")), log.events.toString());
        assertTrue(m.cooldown() > 0 && !m.cooldownFromStrong(), "the lights' cooldown");
        // the chop landed on 22; taps after it start a strong attack that is dropped when let go
        assertTrue(log.events.stream().anyMatch(e -> e.endsWith("start STRONG 0")), log.events.toString());
        assertTrue(log.events.stream().anyMatch(e -> e.endsWith("cancel")), log.events.toString());
    }

    @Test
    void aStrongAttackHeldOutOfTheLightsCooldownLandsAndStartsItsOwn() {
        SwingMachine m = new SwingMachine(SWORD);
        // three taps, then the button pressed and held from tick 27
        Log log = run(m, 60, k -> k == 0 || k == 9 || k == 18 || k == 27, k -> k == 0 || k == 9 || k == 18 || (k >= 27 && k < 45));
        int[] last = log.lands.get(log.lands.size() - 1);
        assertEquals(SwingKind.STRONG.ordinal(), last[2], log.events.toString());
        assertEquals(4, log.lands.size());
        assertTrue(m.cooldownFromStrong() || m.cooldown() == 0);
    }

    @Test
    void aPressInAStrongAttacksCooldownIsRefusedButOneNearItsEndWaits() {
        SwingMachine m = new SwingMachine(SWORD);
        // a strong attack (held from 0), landing on 9: its cooldown runs 2 s (40 ticks) from there
        Log log = run(m, 80, k -> k == 0 || k == 20 || k == 40 || k == 46, k -> k < 12);
        assertTrue(log.has("20 refused") && log.has("40 refused"), log.events.toString());
        // 46 is within the last ticks of the cooldown: it waits and starts as soon as the cooldown is over, on 49
        assertFalse(log.has("46 refused"), log.events.toString());
        assertTrue(log.has("49 start LIGHT 0"), log.events.toString());
    }

    @Test
    void heldInMidAirASwordMakesAJumpAttackThatComesDownAsThePlayerTouchesDown() {
        SwingMachine m = new SwingMachine(SWORD);
        // a jump from tick 0 to 11; the button pressed on 1 and held (and the run stopped inside the cooldown)
        Log log = run(m, 30, k -> k == 1, k -> k >= 1 && k < 30, jump(0, 11));
        assertTrue(log.has("1 start LIGHT 0") && log.has("4 upgrade JUMP"), log.events.toString());
        assertTrue(log.has("12 land JUMP 0"), log.events.toString());
        assertTrue(m.cooldownFromStrong() && m.cooldownTotal() == SWORD.heavyCooldown(), "the heavy cooldown follows");
        assertFalse(log.events.stream().anyMatch(e -> e.contains("lunge")), "no lunge");
    }

    @Test
    void aJumpAttackComesDownNoSoonerThanItsWindUpAfterThePress() {
        SwingMachine m = new SwingMachine(SWORD);
        // pressed late in the jump: upgraded on 8, on the ground from 11, but down only as its wind-up allows (on 12)
        Log log = run(m, 60, k -> k == 5, k -> k >= 5 && k < 30, jump(0, 10));
        assertTrue(log.has("8 upgrade JUMP"), log.events.toString());
        assertTrue(log.has("12 land JUMP 0"), log.events.toString());
    }

    @Test
    void aJumpAttackIsDroppedInWaterOrInALongFall() {
        SwingMachine m = new SwingMachine(SWORD);
        Log log = run(m, 40, k -> k == 1, k -> k >= 1 && k < 30,
                k -> k < 8 ? SwingMachine.Footing.AIR : SwingMachine.Footing.OTHER);
        assertTrue(log.has("4 upgrade JUMP") && log.has("8 cancel"), log.events.toString());
        assertTrue(log.lands.isEmpty(), log.events.toString());
        assertEquals(0, m.cooldown(), "a dropped swing starts no cooldown");
        SwingMachine m2 = new SwingMachine(SWORD);
        Log log2 = run(m2, 80, k -> k == 1, k -> k >= 1, jump(0, 70));
        assertTrue(log2.has((SwingMachine.JUMP_MAX_AIR + 1) + " cancel"), log2.events.toString());
        assertTrue(log2.lands.isEmpty(), log2.events.toString());
    }

    @Test
    void anAxeHeldInMidAirMakesAStrongAttackAndATapInMidAirIsALightSwing() {
        Log axe = run(new SwingMachine(WeaponProfile.AXE), 40, k -> k == 1, k -> k >= 1 && k < 30, jump(0, 11));
        assertTrue(axe.has("4 upgrade"), axe.events.toString());
        assertTrue(axe.lands.stream().allMatch(l -> l[2] == SwingKind.STRONG.ordinal()), axe.events.toString());
        Log tap = run(new SwingMachine(SWORD), 40, k -> k == 1, k -> k == 1, jump(0, 11));
        assertTrue(tap.has("5 land LIGHT 0"), tap.events.toString());
    }

    @Test
    void afterTheChainAJumpAttackCanStillBeMadeAndThenNothingFor2Seconds() {
        SwingMachine m = new SwingMachine(SWORD);
        // three taps (the chop lands on 22), then a press held through a jump from 26
        Log log = run(m, 60, k -> k == 0 || k == 9 || k == 18 || k == 27, k -> k == 0 || k == 9 || k == 18 || (k >= 27 && k < 50),
                jump(26, 38));
        assertTrue(log.has("27 start STRONG 0") && log.has("30 upgrade JUMP"), log.events.toString());
        assertTrue(log.has("39 land JUMP 0"), log.events.toString());
        assertTrue(m.cooldownFromStrong() && m.cooldownTotal() == SWORD.heavyCooldown(), "the jump attack's cooldown");
    }

    @Test
    void theChainStartsAgainAfterAPause() {
        SwingMachine m = new SwingMachine(SWORD);
        Log log = run(m, 60, k -> k == 0 || k == 40, k -> k == 0 || k == 40);
        assertEquals(List.of(0, 0), log.lands.stream().map(l -> l[3]).toList(), "both slashes: the pause reset the chain");
    }

    @Test
    void axesSwingSlowerInAChainOfTwo() {
        SwingMachine m = new SwingMachine(WeaponProfile.AXE);
        Log log = run(m, 40, k -> k % 2 == 0 && k < 30, k -> k % 2 == 0 && k < 30);
        assertTrue(log.has("7 land LIGHT 0"), log.events.toString());
        assertEquals(2, log.lands.stream().filter(l -> l[2] == SwingKind.LIGHT.ordinal()).count(), log.events.toString());
        assertTrue(m.cooldown() > 0);
    }

    /** The server never refuses a landing the player's own game made, however the button is pressed and held, jumping or not. */
    @Test
    void everyLandingTheMachineMakesTheRulesAccept() {
        for (WeaponProfile w : new WeaponProfile[]{WeaponProfile.SWORD, WeaponProfile.AXE}) {
            for (int seed = 0; seed < 300; seed++) {
                Random r = new Random(seed);
                boolean[] press = new boolean[600], held = new boolean[600], air = new boolean[600];
                boolean down = false;
                int airLeft = 0;
                for (int k = 0; k < 600; k++) {
                    if (!down && r.nextInt(seed % 3 == 0 ? 3 : 8) == 0) { down = true; press[k] = true; }
                    else if (down && r.nextInt(seed % 2 == 0 ? 2 : 9) == 0) down = false;
                    held[k] = down;
                    // jumps (and now and then a long drop) for half the seeds
                    if (airLeft == 0 && seed % 2 == 1 && r.nextInt(14) == 0) airLeft = r.nextInt(10) == 0 ? 20 + r.nextInt(50) : 9 + r.nextInt(5);
                    air[k] = airLeft > 0;
                    if (airLeft > 0) airLeft--;
                }
                SwingMachine m = new SwingMachine(w);
                Log log = run(m, 600, k -> press[k], k -> held[k], k -> air[k] ? SwingMachine.Footing.AIR : SwingMachine.Footing.GROUND);
                SwingRules.State s = new SwingRules.State();
                for (int[] l : log.lands) {
                    assertTrue(SwingRules.land(s, w, SwingKind.values()[l[2]], l[1], 1000L + l[0]),
                            w.name() + " seed " + seed + ": the server refused a landing on tick " + l[0] + "\n" + log.events);
                }
            }
        }
    }
}
