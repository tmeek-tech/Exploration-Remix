package com.explorationremix.client.combat;

import com.explorationremix.client.combat.SwingAnim.Hand;
import com.explorationremix.combat.swing.SwingKind;
import com.explorationremix.combat.swing.WeaponProfile;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The first-person swing poses: their turns as the pose stack makes them, the cut's curve, and no jumps anywhere. */
class SwingAnimTest {
    /** The blade's line in the item's own space (its grip to its tip, as vanilla's handheld display turns it). */
    private static final Vector3f BLADE = new Vector3f(0.0F, 0.9397F, 0.3420F);

    @Test
    void aPoseTurnsAsYawThenPitchThenRoll() {
        Hand h = Hand.of(0, 0, 0, 30.0F, -20.0F, 10.0F);
        Quaternionf expected = new Quaternionf().rotateY((float) Math.toRadians(30)).rotateX((float) Math.toRadians(-20))
                .rotateZ((float) Math.toRadians(10));
        Vector3f a = h.turn().transform(new Vector3f(BLADE)), b = expected.transform(new Vector3f(BLADE));
        assertTrue(a.distance(b) < 1.0E-5F, a + " vs " + b);
        // the left hand's mirror: x flipped
        Vector3f m = h.mirroredTurn().transform(new Vector3f(-BLADE.x, BLADE.y, BLADE.z));
        assertTrue(Math.abs(m.x + a.x) < 1.0E-5F && Math.abs(m.y - a.y) < 1.0E-5F && Math.abs(m.z - a.z) < 1.0E-5F, m + " vs " + a);
    }

    @Test
    void aCutPassesThroughItsMiddlePose() {
        for (SwingAnim.Keys k : new SwingAnim.Keys[]{SwingAnim.SLASH, SwingAnim.BACKHAND, SwingAnim.CHOP}) {
            Hand h = Hand.curve(k.windup(), k.mid(), k.follow(), 0.5F);
            assertEquals(k.mid().x(), h.x(), 1.0E-5F);
            assertEquals(k.mid().y(), h.y(), 1.0E-5F);
            Vector3f a = h.turn().transform(new Vector3f(BLADE)), b = k.mid().turn().transform(new Vector3f(BLADE));
            assertTrue(a.distance(b) < 1.0E-4F, a + " vs " + b);
        }
    }

    /** Every swing, alone and chained, moves the blade smoothly: no frame where it jumps, from its start back to rest. */
    @Test
    void theBladeNeverJumps() {
        for (WeaponProfile w : new WeaponProfile[]{WeaponProfile.SWORD, WeaponProfile.AXE}) {
            Hand from = Hand.REST;
            for (int combo = 0; combo < w.chain(); combo++) {
                // each chained swing starts where the one before is when its recovery ends
                from = run(w, SwingKind.LIGHT, combo, from, w.lightWindup() + w.lightRecover(), -1.0F, -1.0F);
            }
            run(w, SwingKind.STRONG, 0, Hand.REST, SwingAnim.length(shape(w, SwingKind.STRONG, 0, w.holdAt() - 1.0F, w.strongWindup())),
                    w.holdAt() - 1.0F, -1.0F);
            if (w.jumps()) {
                // held overhead a while in the air, then brought down as the player lands
                float land = 14.0F;
                Hand end = run(w, SwingKind.JUMP, 0, Hand.REST, SwingAnim.length(shape(w, SwingKind.JUMP, 0, w.holdAt() - 1.0F, land)),
                        w.holdAt() - 1.0F, land);
                assertTrue(dist(end, Hand.REST) < 1.0E-4F, "a jump attack's blade back at rest");
            }
            Hand end = run(w, SwingKind.LIGHT, 0, Hand.REST, SwingAnim.length(shape(w, SwingKind.LIGHT, 0, -1.0F, w.lightWindup())), -1.0F, -1.0F);
            assertTrue(dist(end, Hand.REST) < 1.0E-4F, "back at rest");
        }
    }

    /** A jump attack waits overhead for as long as the player is in the air: never over, and still until it lands. */
    @Test
    void aJumpAttackWaitsOverheadUntilItLands() {
        WeaponProfile w = WeaponProfile.SWORD;
        SwingAnim.Shape waiting = shape(w, SwingKind.JUMP, 0, w.holdAt() - 1.0F, SwingAnim.NOT_YET);
        assertEquals(Float.MAX_VALUE, SwingAnim.length(waiting));
        assertEquals(0.0F, SwingAnim.cutting(waiting, 30.0F));
        Hand a = SwingAnim.hand(waiting, Hand.REST, 20.0F, 0.0F), b = SwingAnim.hand(waiting, Hand.REST, 40.0F, 0.0F);
        assertTrue(dist(a, b) < 0.02F, "it holds overhead: " + dist(a, b));
        assertTrue(dist(a, Hand.of(-0.352F, 0.795F, 0.202F, 76.9F, -29.1F, -13.3F)) < 0.05F, "overhead");
    }

    /** One swing's shape: {@code kind} (a light one until {@code upgradeAt}, if heavy), landing at {@code land}. */
    private static SwingAnim.Shape shape(WeaponProfile w, SwingKind kind, int combo, float upgradeAt, float land) {
        SwingAnim.Keys light = SwingAnim.keys(SwingKind.LIGHT, combo, w);
        float hold = switch (kind) {
            case STRONG -> w.lungeTicks();
            case JUMP -> w.jumpRecover();
            default -> w.lightRecover();
        };
        return new SwingAnim.Shape(kind, SwingAnim.keys(kind, combo, w), SwingKind.LIGHT, light, w.lightWindup(), upgradeAt, land, hold);
    }

    /**
     * A swing as the renderer draws it: a heavy one is a light one until it turns at {@code upgradeAt}; a jump attack
     * comes down at {@code landAt}.
     */
    private static Hand run(WeaponProfile w, SwingKind kind, int combo, Hand from, float until, float upgradeAt, float landAt) {
        float step = 0.02F;
        Hand last = from;
        // the fastest the blade's tip moves is in the middle of a cut, about 3 blocks a tick; anything past twice that in
        // one step is a jump
        float most = 6.0F * step;
        int steps = Math.round(until / step);
        for (int i = 0; i <= steps; i++) {
            float t = i == steps ? until : i * step;
            boolean heavy = kind.heavy() && t >= upgradeAt;
            SwingKind now = heavy ? kind : SwingKind.LIGHT;
            float land = now == SwingKind.JUMP ? (t >= landAt ? landAt : SwingAnim.NOT_YET) : w.windup(now);
            SwingAnim.Shape sh = shape(w, now, combo, heavy ? upgradeAt : -1.0F, land);
            Hand h = SwingAnim.hand(sh, from, t, 0.0F);
            float d = dist(last, h);
            assertTrue(d < most, w.name() + " " + kind + " " + combo + ": the blade jumps " + d + " at " + t);
            last = h;
        }
        return last;
    }

    /** How far apart the blade's tip is in two poses (blocks), the grip turned about where it sits. */
    private static float dist(Hand a, Hand b) {
        Vector3f ta = a.turn().transform(new Vector3f(BLADE)).mul(0.78F).add(a.x(), a.y(), a.z());
        Vector3f tb = b.turn().transform(new Vector3f(BLADE)).mul(0.78F).add(b.x(), b.y(), b.z());
        return ta.distance(tb);
    }
}
