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
    static final Keys THRUST = new Keys(
            Hand.of(-0.247F, 0.44F, 0.322F, -140.0F, -74.3F, 156.3F),
            null,
            Hand.of(-0.38F, 0.419F, -0.078F, -96.3F, -67.2F, 106.1F),
            0.35F,
            new Body(0.45F, 0.0F, 0.12F, 2.0F, 0.55F, -0.05F),
            new Body(-1.55F, -0.1F, 0.0F, -3.0F, -0.35F, 0.16F));

    static Keys keys(SwingKind kind, int combo, WeaponProfile w) {
        if (kind == SwingKind.STRONG) return THRUST;
        if (combo >= w.chain() - 1) return CHOP;
        return combo % 2 == 0 ? SLASH : BACKHAND;
    }

    // ------------------------------------------------------------------------------------------------ timing

    /** The cut starts this long before the swing lands, and ends this long after (ticks): it crosses the middle as it lands. */
    static final float CUT_LEAD = 1.0F, CUT_TAIL = 1.1F;
    /** The thrust is quicker off the mark, and most of the way out as it lands. */
    static final float THRUST_LEAD = 0.5F, THRUST_TAIL = 0.8F;
    /** After its hold, the hand is back at rest in this many ticks. */
    static final float BACK = 4.0F;

    /** Where a swing is: 0 to 1 through its wind-up, its cut, and its way back, from the swing's own times. */
    record Phase(float windup, float cut, float back) {}

    /**
     * The phases of a swing {@code tau} ticks after it started, landing at {@code land} ticks (its wind-up), its hand
     * held at the end of the cut until {@code hold} ticks after it lands.
     */
    static Phase phase(float tau, float land, float hold, boolean strong) {
        float lead = strong ? THRUST_LEAD : CUT_LEAD, tail = strong ? THRUST_TAIL : CUT_TAIL;
        float cutFrom = land - lead, cutTo = land + tail;
        float w = Mth.clamp(tau / Math.max(0.5F, cutFrom), 0.0F, 1.0F);
        float c = Mth.clamp((tau - cutFrom) / (cutTo - cutFrom), 0.0F, 1.0F);
        float b = Mth.clamp((tau - Math.max(cutTo, land + hold)) / BACK, 0.0F, 1.0F);
        return new Phase(w, c, b);
    }

    /** From a swing's start until its hand is back at rest (ticks). */
    static float length(float land, float hold, boolean strong) {
        return Math.max(land + (strong ? THRUST_TAIL : CUT_TAIL), land + hold) + BACK;
    }

    /** Quick, then holding at the end: a wind-up. */
    static float easeOut(float k) { float a = 1.0F - k; return 1.0F - a * a * a; }

    /** Fast through the middle: the cut. */
    static float easeInOut(float k) { return k < 0.5F ? 4.0F * k * k * k : 1.0F - (float) Math.pow(-2.0F * k + 2.0F, 3) / 2.0F; }

    static float smooth(float k) { return k * k * (3.0F - 2.0F * k); }

    // ------------------------------------------------------------------------------------------------ evaluation

    /**
     * The hand's pose for a swing: {@code from} where the hand was when it started, {@code tau} ticks in. A light
     * wind-up that became a strong one at {@code upgradeAt} ticks (or -1) leaves its own wind-up from where it had got
     * to by then ({@code lightLand} the light swing's own wind-up); {@code time} drives a strong wind-up's tremble.
     */
    static Hand hand(Keys k, Keys light, Hand from, float tau, float land, float lightLand, float hold, boolean strong, float upgradeAt,
                     float time) {
        Phase ph = phase(tau, land, hold, strong);
        Hand wound;
        if (strong && upgradeAt >= 0.0F && tau >= upgradeAt) {
            float lightWound = Mth.clamp(upgradeAt / Math.max(0.5F, lightLand - CUT_LEAD), 0.0F, 1.0F);
            Hand mid = from.lerp(light.windup, easeOut(lightWound));
            float k2 = Mth.clamp((tau - upgradeAt) / Math.max(0.5F, land - THRUST_LEAD - upgradeAt), 0.0F, 1.0F);
            wound = mid.lerp(k.windup, easeOut(k2));
        } else {
            wound = from.lerp(k.windup, easeOut(ph.windup));
        }
        if (strong && ph.cut <= 0.0F) {
            // the thrust fills: a tremble that grows to the end of the wind-up
            float s = Mth.clamp((tau - land * 0.45F) / (land * 0.55F), 0.0F, 1.0F);
            s *= s;
            wound = wound.shaken(0.006F * s * Mth.sin(time * 5.1F), 0.006F * s * Mth.sin(time * 6.3F + 1.0F),
                    1.2F * s * Mth.sin(time * 4.7F), 1.2F * s * Mth.sin(time * 5.9F + 2.0F));
        }
        if (ph.cut <= 0.0F) return wound;
        Hand cut = Hand.curve(wound, k.mid, k.follow, strong ? easeOut(ph.cut) : easeInOut(ph.cut));
        if (ph.back <= 0.0F) return cut;
        float b = smooth(ph.back);
        return cut.lerp(Hand.REST, b).dropped(k.dip * Mth.sin(b * Mth.PI));
    }

    /** The same swing's third-person pose, and how much of it holds (1 through the swing, easing to 0 at rest). */
    static Body body(Keys k, float tau, float land, float hold, boolean strong) {
        Phase ph = phase(tau, land, hold, strong);
        Body b = Body.REST.lerp(k.bodyWindup, easeOut(ph.windup));
        if (ph.cut > 0.0F) b = b.lerp(k.bodyFollow, easeInOut(ph.cut));
        return b;
    }

    /** How much a third-person pose holds: all of it until the way back, then less and less. */
    static float bodyWeight(float tau, float land, float hold, boolean strong) {
        return 1.0F - smooth(phase(tau, land, hold, strong).back);
    }

    /** Whether the blade is in its cut now (for its trail): 0 outside, up to 1 in the middle of it. */
    static float cutting(float tau, float land, float hold, boolean strong) {
        float c = phase(tau, land, hold, strong).cut;
        return c <= 0.0F || c >= 1.0F ? 0.0F : Mth.sin(c * Mth.PI);
    }
}
