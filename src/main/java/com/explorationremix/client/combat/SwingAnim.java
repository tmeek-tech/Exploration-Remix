package com.explorationremix.client.combat;

import com.explorationremix.combat.swing.SwingKind;
import com.explorationremix.combat.swing.WeaponProfile;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;

/**
 * The swings' motion, as poses in time, with no drawing in it. A swing is a wind-up (drawn back quickly, then holding),
 * the cut (fast, crossing the middle of the view the moment it lands), a hold at the end of it (until the chain's next
 * swing could start, so a chain flows on from there) and the way back to rest, dipping out of view and up again as
 * vanilla re-equips a hand. The chain: a slash from the right shoulder over and down to the left, a backhand rising from
 * the left hip to high on the right, an overhead chop from there down through the middle. A strong attack draws the
 * blade back low on the right, point to the crosshair, trembling as it fills, and drives it out.
 *
 * <p>First person, a pose moves the hand (blocks: x right, y up, z back towards the eye) and turns it about the grip
 * (degrees, turned as the pose stack does: yaw to the left, then pitch tip up, then roll top to the left), for the right
 * hand; the left hand is its mirror. Poses blend by the shortest turn between them, and a cut passes through its middle
 * pose on a smooth curve. Third person, a pose sets the swinging arm's turns and the body's twist and lean (radians, the
 * model's own).
 */
public final class SwingAnim {
    private SwingAnim() {}

    // ------------------------------------------------------------------------------------------------ poses

    /** A first-person pose of the hand: its move, and its turn as a unit quaternion. */
    public record Hand(float x, float y, float z, float qx, float qy, float qz, float qw) {
        public static final Hand REST = of(0, 0, 0, 0, 0, 0);

        /** A pose from its move and its turn in degrees. */
        static Hand of(float x, float y, float z, float yaw, float pitch, float roll) {
            Quaternionf q = new Quaternionf().rotationYXZ(yaw * Mth.DEG_TO_RAD, pitch * Mth.DEG_TO_RAD, roll * Mth.DEG_TO_RAD);
            return new Hand(x, y, z, q.x, q.y, q.z, q.w);
        }

        /** The turn, for the right hand. */
        Quaternionf turn() { return new Quaternionf(qx, qy, qz, qw); }

        /** The turn mirrored for the left hand (yaw and roll the other way). */
        Quaternionf mirroredTurn() { return new Quaternionf(qx, -qy, -qz, qw); }

        Hand lerp(Hand o, float k) {
            Quaternionf q = turn().slerp(o.turn(), k);
            return new Hand(Mth.lerp(k, x, o.x), Mth.lerp(k, y, o.y), Mth.lerp(k, z, o.z), q.x, q.y, q.z, q.w);
        }

        /** Lower by {@code dy}. */
        Hand dropped(float dy) { return new Hand(x, y - dy, z, qx, qy, qz, qw); }

        /** Nudged by a little move and turn (a tremble). */
        Hand shaken(float dx, float dy, float dyaw, float dpitch) {
            Quaternionf q = new Quaternionf().rotationYXZ(dyaw * Mth.DEG_TO_RAD, dpitch * Mth.DEG_TO_RAD, 0.0F).mul(turn());
            return new Hand(x + dx, y + dy, z, q.x, q.y, q.z, q.w);
        }

        private float dot(Hand o) { return qx * o.qx + qy * o.qy + qz * o.qz + qw * o.qw; }

        /** On a smooth curve from {@code a} through {@code m} (reached at k = 0.5) to {@code b}; straight if no {@code m}. */
        static Hand curve(Hand a, Hand m, Hand b, float k) {
            if (m == null) return a.lerp(b, k);
            // the turns taken on m's side, then a quadratic Bezier whose middle control makes it pass through m
            float sa = a.dot(m) < 0.0F ? -1.0F : 1.0F, sb = b.dot(m) < 0.0F ? -1.0F : 1.0F;
            float w0 = (1.0F - k) * (1.0F - k), w1 = 2.0F * k * (1.0F - k), w2 = k * k;
            float x = bez(a.x, m.x, b.x, w0, w1, w2), y = bez(a.y, m.y, b.y, w0, w1, w2), z = bez(a.z, m.z, b.z, w0, w1, w2);
            float qx = bez(sa * a.qx, m.qx, sb * b.qx, w0, w1, w2), qy = bez(sa * a.qy, m.qy, sb * b.qy, w0, w1, w2);
            float qz = bez(sa * a.qz, m.qz, sb * b.qz, w0, w1, w2), qw = bez(sa * a.qw, m.qw, sb * b.qw, w0, w1, w2);
            float n = Mth.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
            if (n < 1.0E-4F) return k < 0.5F ? a.lerp(m, 2.0F * k) : m.lerp(b, 2.0F * k - 1.0F);
            return new Hand(x, y, z, qx / n, qy / n, qz / n, qw / n);
        }

        private static float bez(float a, float m, float b, float w0, float w1, float w2) {
            return w0 * a + w1 * (2.0F * m - (a + b) * 0.5F) + w2 * b;
        }
    }

    /** A third-person pose: the swinging arm's turns, its pivot pushed forward or back, the body's twist and lean. */
    public record Body(float armX, float armY, float armZ, float armForward, float twist, float lean) {
        public static final Body REST = new Body(0, 0, 0, 0, 0, 0);

        Body lerp(Body o, float k) {
            return new Body(Mth.lerp(k, armX, o.armX), Mth.lerp(k, armY, o.armY), Mth.lerp(k, armZ, o.armZ),
                    Mth.lerp(k, armForward, o.armForward), Mth.lerp(k, twist, o.twist), Mth.lerp(k, lean, o.lean));
        }
    }

    /**
     * One swing's poses: drawn back, the middle of the cut (or none: straight), where the cut ends, and how far the hand
     * dips out of view on its way back to rest; third person, drawn back and where the cut ends.
     */
    record Keys(Hand windup, Hand mid, Hand follow, float dip, Body bodyWindup, Body bodyFollow) {}

    // The chain: a slash right to left, a rising backhand left to right, an overhead chop. Then the strong attack's
    // thrust. The hands were laid out on screen in a preview (the item's grip and tip at chosen points of the view).
    static final Keys SLASH = new Keys(
            Hand.of(-0.206F, 0.37F, 0.142F, 70.8F, 26.8F, -32.4F),
            Hand.of(-0.438F, 0.466F, 0.142F, -112.1F, -71.6F, 178.4F),
            Hand.of(-0.967F, 0.274F, 0.162F, -100.7F, -47.7F, -166.3F),
            0.55F,
            new Body(-1.45F, 0.95F, -0.25F, 0.0F, 0.45F, 0.0F),
            new Body(-1.35F, -1.05F, 0.2F, -1.0F, -0.4F, 0.08F));
    static final Keys BACKHAND = new Keys(
            Hand.of(-0.939F, 0.292F, 0.142F, -169.4F, -59.9F, -110.5F),
            Hand.of(-0.669F, 0.392F, 0.142F, 92.2F, 46.9F, -26.9F),
            Hand.of(-0.206F, 0.492F, 0.142F, 66.4F, 11.7F, -14.0F),
            0.55F,
            new Body(-1.3F, -1.1F, 0.25F, 0.0F, -0.45F, 0.0F),
            new Body(-1.7F, 0.95F, -0.2F, -0.5F, 0.4F, 0.05F));
    static final Keys CHOP = new Keys(
            Hand.of(-0.344F, 0.541F, 0.122F, 91.3F, -7.8F, -21.3F),
            Hand.of(-0.507F, 0.587F, 0.142F, 178.6F, -54.1F, -140.5F),
            Hand.of(-0.631F, 0.232F, 0.162F, -151.4F, -9.3F, 169.1F),
            0.45F,
            new Body(-2.95F, 0.1F, 0.0F, 0.0F, 0.1F, -0.12F),
            new Body(-0.55F, -0.15F, 0.0F, -1.5F, -0.05F, 0.28F));
    // the thrust's arm turns against the body's twist, so its point stays on the line of sight
    static final Keys THRUST = new Keys(
            Hand.of(-0.247F, 0.44F, 0.322F, 142.0F, -62.8F, -118.9F),
            null,
            Hand.of(-0.38F, 0.419F, -0.078F, 121.9F, -39.1F, -90.1F),
            0.35F,
            new Body(0.45F, -0.55F, 0.12F, 2.0F, 0.55F, -0.05F),
            new Body(-1.55F, 0.35F, 0.0F, -3.0F, -0.35F, 0.16F));

    // A sword's heavy jump attack (0.22.7): the blade raised high over the right shoulder, point back, waiting for the
    // ground; then brought straight down through the middle, ending driven into the ground just ahead.
    static final Keys SMASH = new Keys(
            Hand.of(-0.352F, 0.795F, 0.202F, 76.9F, -29.1F, -13.3F),
            Hand.of(-0.511F, 0.61F, 0.162F, 132.2F, 10.7F, -45.9F),
            Hand.of(-0.475F, 0.409F, 0.282F, 118.6F, 24.4F, -81.1F),
            0.3F,
            new Body(-2.95F, 0.15F, 0.0F, 0.0F, 0.1F, -0.15F),
            new Body(-0.45F, -0.1F, 0.0F, -1.8F, -0.05F, 0.38F));

    static Keys keys(SwingKind kind, int combo, WeaponProfile w) {
        if (kind == SwingKind.JUMP) return SMASH;
        if (kind == SwingKind.STRONG) return THRUST;
        if (combo >= w.chain() - 1) return CHOP;
        return combo % 2 == 0 ? SLASH : BACKHAND;
    }

    // ------------------------------------------------------------------------------------------------ the guard

    /**
     * A sword held up in guard (0.22.7): across the view low on the right, its point high on the left, the middle of the
     * view left clear; a blow taken on it knocks it down and back a little; a parry flicks it up and out.
     */
    static final Hand GUARD = Hand.of(-0.343F, 0.361F, 0.212F, -171.4F, -57.2F, -71.3F);
    static final Hand BLOCKED = Hand.of(-0.357F, 0.351F, 0.262F, -169.8F, -45.5F, -74.6F);
    static final Hand PARRIED = Hand.of(-0.4F, 0.403F, 0.182F, 142.7F, -66.3F, -37.5F);
    /** Third person: the arm across the body, as a shield is held, the blade's point up by the other shoulder. */
    static final Body GUARD_BODY = new Body(-0.95F, -0.55F, 0.1F, 0.0F, -0.15F, 0.0F);

    /** The guard comes up in this long, and down again in this long (ticks). */
    static final float GUARD_UP = 3.0F, GUARD_DOWN = 3.0F;
    /** A blow taken on it: the jolt's length; a parry: the flick out, and the way back into the guard. */
    static final float JOLT = 5.0F, FLICK_OUT = 1.5F, FLICK_BACK = 5.0F;

    /**
     * The hand in guard: {@code up} 0 to 1 how far it is raised (from {@code from}, where the hand was), {@code jolt} ticks
     * since a blow struck it (or below 0), {@code parry} whether that was a parry; {@code time} for a slight sway.
     */
    static Hand guard(Hand from, float up, float jolt, boolean parry, float time) {
        Hand h = GUARD.shaken(0.0F, 0.004F * Mth.sin(time * 0.21F), 0.0F, 0.6F * Mth.sin(time * 0.17F + 1.0F));
        if (jolt >= 0.0F) {
            if (parry) {
                float k = jolt < FLICK_OUT ? easeOut(jolt / FLICK_OUT) : 1.0F - smooth(Mth.clamp((jolt - FLICK_OUT) / FLICK_BACK, 0.0F, 1.0F));
                h = h.lerp(PARRIED, k);
            } else if (jolt < JOLT) {
                // struck: knocked back at once, then eased up again
                float k = jolt < 1.0F ? jolt : 1.0F - smooth((jolt - 1.0F) / (JOLT - 1.0F));
                h = h.lerp(BLOCKED, k);
            }
        }
        return from.lerp(h, smooth(Mth.clamp(up, 0.0F, 1.0F)));
    }

    // ------------------------------------------------------------------------------------------------ timing

    /** The cut starts this long before the swing lands, and ends this long after (ticks): it crosses the middle as it lands. */
    static final float CUT_LEAD = 1.0F, CUT_TAIL = 1.1F;
    /** The thrust is quicker off the mark, and most of the way out as it lands. */
    static final float THRUST_LEAD = 0.5F, THRUST_TAIL = 0.8F;
    /**
     * A jump attack's blade is raised overhead in this long once it has become one; it comes down the moment the player
     * touches down (no sooner: when that will be isn't known), all the way in this long.
     */
    static final float JUMP_RISE = 3.0F, JUMP_TAIL = 1.6F;
    /** After its hold, the hand is back at rest in this many ticks. */
    static final float BACK = 4.0F;
    /** A jump attack's landing, until it lands. */
    static final float NOT_YET = -1.0F;

    static float lead(SwingKind kind) {
        return switch (kind) {
            case LIGHT -> CUT_LEAD;
            case STRONG -> THRUST_LEAD;
            case JUMP -> 0.0F;
        };
    }

    static float tail(SwingKind kind) {
        return switch (kind) {
            case LIGHT -> CUT_TAIL;
            case STRONG -> THRUST_TAIL;
            case JUMP -> JUMP_TAIL;
        };
    }

    /**
     * One swing as its poses need it: its kind and poses now; what it was before it turned heavy ({@code was}, the same if
     * it never did), with that wind-up's poses and length, and when it turned ({@code upgradeAt} ticks in, or -1); when
     * it lands ({@code land} ticks in; a jump attack's is {@link #NOT_YET} until it comes down) and how long the hand
     * holds at the end of the cut after that ({@code hold}).
     */
    record Shape(SwingKind kind, Keys keys, SwingKind was, Keys wasKeys, float wasLand, float upgradeAt, float land, float hold) {
        boolean waiting() { return kind == SwingKind.JUMP && land < 0.0F; }
    }

    /** Where a swing is: 0 to 1 through its wind-up, its cut, and its way back, from the swing's own times. */
    record Phase(float windup, float cut, float back) {}

    /** The phases of swing {@code s}, {@code tau} ticks after it started. */
    static Phase phase(Shape s, float tau) {
        if (s.kind == SwingKind.JUMP) {
            float w = Mth.clamp(tau / JUMP_RISE, 0.0F, 1.0F);
            if (s.waiting()) return new Phase(w, 0.0F, 0.0F);
            float c = Mth.clamp((tau - s.land) / JUMP_TAIL, 0.0F, 1.0F);
            float b = Mth.clamp((tau - s.land - Math.max(JUMP_TAIL, s.hold)) / BACK, 0.0F, 1.0F);
            return new Phase(w, c, b);
        }
        float cutFrom = s.land - lead(s.kind), cutTo = s.land + tail(s.kind);
        float w = Mth.clamp(tau / Math.max(0.5F, cutFrom), 0.0F, 1.0F);
        float c = Mth.clamp((tau - cutFrom) / (cutTo - cutFrom), 0.0F, 1.0F);
        float b = Mth.clamp((tau - Math.max(cutTo, s.land + s.hold)) / BACK, 0.0F, 1.0F);
        return new Phase(w, c, b);
    }

    /** From a swing's start until its hand is back at rest (ticks); a jump attack still waiting to come down: never. */
    static float length(Shape s) {
        if (s.waiting()) return Float.MAX_VALUE;
        return Math.max(s.land + tail(s.kind), s.land + s.hold) + BACK;
    }

    /** Quick, then holding at the end: a wind-up. */
    static float easeOut(float k) { float a = 1.0F - k; return 1.0F - a * a * a; }

    /** Fast through the middle: the cut. */
    static float easeInOut(float k) { return k < 0.5F ? 4.0F * k * k * k : 1.0F - (float) Math.pow(-2.0F * k + 2.0F, 3) / 2.0F; }

    static float smooth(float k) { return k * k * (3.0F - 2.0F * k); }

    // ------------------------------------------------------------------------------------------------ evaluation

    /**
     * Where the wind-up has got to {@code tau} ticks in, before the cut: a light wind-up that became a heavy one at
     * {@code upgradeAt} leaves its own wind-up from where it had got to by then; a strong one trembles as it fills, a
     * jump attack's sways a little overhead as it waits ({@code time} drives both).
     */
    private static Hand wound(Shape s, Hand from, float tau, float time) {
        Phase ph = phase(s, tau);
        Hand wound;
        boolean heavy = s.kind.heavy();
        if (heavy && s.upgradeAt >= 0.0F && tau >= s.upgradeAt) {
            float wasFrom = s.wasLand - lead(s.was);
            float before = Mth.clamp(s.upgradeAt / Math.max(0.5F, wasFrom), 0.0F, 1.0F);
            Hand mid = from.lerp(s.wasKeys.windup, easeOut(before));
            float riseEnd = s.kind == SwingKind.JUMP ? s.upgradeAt + JUMP_RISE : s.land - THRUST_LEAD;
            float k2 = Mth.clamp((tau - s.upgradeAt) / Math.max(0.5F, riseEnd - s.upgradeAt), 0.0F, 1.0F);
            wound = mid.lerp(s.keys.windup, easeOut(k2));
        } else {
            wound = from.lerp(s.keys.windup, easeOut(ph.windup));
        }
        if (ph.cut > 0.0F) return wound;
        if (s.kind == SwingKind.STRONG) {
            // the thrust fills: a tremble that grows to the end of the wind-up
            float k = Mth.clamp((tau - s.land * 0.45F) / (s.land * 0.55F), 0.0F, 1.0F);
            k *= k;
            wound = wound.shaken(0.006F * k * Mth.sin(time * 5.1F), 0.006F * k * Mth.sin(time * 6.3F + 1.0F),
                    1.2F * k * Mth.sin(time * 4.7F), 1.2F * k * Mth.sin(time * 5.9F + 2.0F));
        } else if (s.kind == SwingKind.JUMP) {
            // held high, the blade sways as the player hangs in the air
            float k = Mth.clamp((tau - (s.upgradeAt < 0.0F ? 0.0F : s.upgradeAt) - JUMP_RISE) / 2.0F, 0.0F, 1.0F);
            wound = wound.shaken(0.004F * k * Mth.sin(time * 0.9F), 0.008F * k * Mth.sin(time * 1.3F + 0.5F),
                    0.8F * k * Mth.sin(time * 1.1F + 1.0F), 1.5F * k * Mth.sin(time * 0.8F));
        }
        return wound;
    }

    /** The hand's pose for swing {@code s}, {@code tau} ticks in: {@code from} where the hand was when it started. */
    static Hand hand(Shape s, Hand from, float tau, float time) {
        Phase ph = phase(s, tau);
        Hand wound = wound(s, from, ph.cut > 0.0F ? Math.min(tau, cutStart(s)) : tau, time);
        if (ph.cut <= 0.0F) return wound;
        float k = switch (s.kind) {
            case LIGHT -> easeInOut(ph.cut);
            case STRONG -> easeOut(ph.cut);
            // a smash is fast from the first moment, and slams to a stop
            case JUMP -> 1.0F - (1.0F - ph.cut) * (1.0F - ph.cut);
        };
        Hand cut = Hand.curve(wound, s.keys.mid, s.keys.follow, k);
        if (ph.back <= 0.0F) return cut;
        float b = smooth(ph.back);
        return cut.lerp(Hand.REST, b).dropped(s.keys.dip * Mth.sin(b * Mth.PI));
    }

    /** When the cut begins (ticks in): the wind-up is held as it was then. */
    private static float cutStart(Shape s) { return s.land - lead(s.kind); }

    /** The same swing's third-person pose. A light wind-up that turned heavy leaves from where it had got to. */
    static Body body(Shape s, float tau) {
        Phase ph = phase(s, tau);
        Body b;
        if (s.kind.heavy() && s.upgradeAt >= 0.0F) {
            Shape was = new Shape(s.was, s.wasKeys, s.was, s.wasKeys, s.wasLand, -1.0F, s.wasLand, 0.0F);
            Body mid = Body.REST.lerp(s.wasKeys.bodyWindup, easeOut(phase(was, Math.min(tau, s.upgradeAt)).windup));
            float riseEnd = s.kind == SwingKind.JUMP ? s.upgradeAt + JUMP_RISE : s.land - THRUST_LEAD;
            float k2 = Mth.clamp((tau - s.upgradeAt) / Math.max(0.5F, riseEnd - s.upgradeAt), 0.0F, 1.0F);
            b = mid.lerp(s.keys.bodyWindup, easeOut(k2));
        } else {
            b = Body.REST.lerp(s.keys.bodyWindup, easeOut(ph.windup));
        }
        if (ph.cut > 0.0F) b = b.lerp(s.keys.bodyFollow, easeInOut(ph.cut));
        return b;
    }

    /** How much a third-person pose holds: all of it until the way back, then less and less. */
    static float bodyWeight(Shape s, float tau) { return 1.0F - smooth(phase(s, tau).back); }

    /** Whether the blade is in its cut now (for its trail): 0 outside, up to 1 in the middle of it. */
    static float cutting(Shape s, float tau) {
        float c = phase(s, tau).cut;
        return c <= 0.0F || c >= 1.0F ? 0.0F : Mth.sin(c * Mth.PI);
    }
}
