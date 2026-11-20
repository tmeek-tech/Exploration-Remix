package com.explorationremix.creature.body;

/**
 * The stilt mantis measured (0.20.0): its parts' lengths from its shape (geometry/stilt_mantis.json, written by
 * tools/stilt_mantis.py) and where its limbs hang from its body, for the body ({@link MantisBody}) and the pose
 * ({@link MantisPose}).
 *
 * <p>Places on the body are given in the thorax's own frame: blocks forward, up and to the left of the thorax's middle.
 * Lengths are in blocks. {@link #scale} grows or shrinks the whole creature (its texture keeps its texels).
 */
public final class MantisSpec {
    /** The walking legs in the order the body keeps their feet: middle left, middle right, hind left, hind right. */
    public static final String[] LEGS = {"mid_left", "mid_right", "hind_left", "hind_right"};
    public static final String[] ARMS = {"arm_left", "arm_right"};
    /** The game's mantis (Taran's size card: about three blocks to the top of its head). */
    public static final double GAME_SCALE = 1.0;

    /** How big it is drawn and walks, against the shape as painted (1: about three blocks to the top of its head). */
    public final double scale;

    // ---- walking legs (blocks)
    public final double femur, tibia, tarsus;
    /** Where each leg's hip hangs on the thorax: forward, up, left (+ for the left legs). */
    public final double[][] hip = new double[4][3];
    /** Where each foot rests when it stands: forward of the thorax's middle and out to its side, on the ground. */
    public final double[][] rest = new double[4][2];
    /** The tarsus stands from the foot this far up and this far back in toward the body (the ankle over the foot). */
    public final double ankleUp, ankleIn;

    // ---- the body (blocks)
    /** The thorax's middle rides this high over the ground its feet stand on. */
    public final double ride;
    /** The prothorax: where it rises from the thorax (forward, up), its length, and its rest pitch above level (radians). */
    public final double proForward, proUp, prothorax, proPitch;
    /** The abdomen hangs from the thorax this far back; each segment's length (front to tip). */
    public final double abdomenBack;
    public final double[] abdomen;
    /** The arms: how far along the prothorax their shoulders sit (blocks from its root), and out to each side. */
    public final double shoulderAlong, shoulderSide;
    public final double armCoxa, armFemur, armTibia;
    public final double antenna;

    // ---- gait
    /** A stride's length (blocks the body moves in one cycle of the feet) walking and trotting. */
    public final double walkStride, trotStride;
    /** How high a foot lifts walking and trotting. */
    public final double walkLift, trotLift;

    public MantisSpec(CreatureShape shape, double scale) {
        this.scale = scale;
        double k = scale / 16.0;
        femur = rod(shape, "mid_left_femur") * k;
        tibia = rod(shape, "mid_left_tibia") * k;
        tarsus = rod(shape, "mid_left_tarsus") * k;
        prothorax = depth(shape, "prothorax") * k;
        abdomen = new double[]{depth(shape, "abdomen1") * k, depth(shape, "abdomen2") * k, depth(shape, "abdomen3") * k,
                depth(shape, "abdomen4") * k};
        armCoxa = rod(shape, "arm_left_coxa") * k;
        armFemur = rod(shape, "arm_left_femur") * k;
        armTibia = rod(shape, "arm_left_tibia") * k;
        antenna = rod(shape, "antenna_left") * k;
        // the thorax is 8 px long: the middle legs hang from its front half, the hind legs from its back half, under its sides
        for (int i = 0; i < 4; i++) {
            boolean mid = i < 2;
            int side = i % 2 == 0 ? 1 : -1;
            hip[i][0] = (mid ? 2.2 : -2.6) * k;
            hip[i][1] = -1.0 * k;
            hip[i][2] = side * 1.9 * k;
            // feet far out to the sides, the middle pair ahead of the body and the hind pair behind it
            rest[i][0] = (mid ? 15.0 : -20.0) * k;
            rest[i][1] = side * 23.0 * k;
        }
        ankleUp = tarsus * 0.9;
        ankleIn = tarsus * 0.4;
        ride = 30.0 * k;
        proForward = 3.6 * k;
        proUp = 1.0 * k;
        proPitch = Math.toRadians(58);
        abdomenBack = 3.8 * k;
        shoulderAlong = prothorax * 0.84;
        shoulderSide = 1.6 * k;
        walkStride = 1.9 * scale;
        trotStride = 3.2 * scale;
        walkLift = 0.42 * scale;
        trotLift = 0.34 * scale;
    }

    /** The whole reach of a walking leg, hip to ankle. */
    public double reach() { return femur + tibia; }

    public static boolean left(int leg) { return leg % 2 == 0; }
    public static boolean mid(int leg) { return leg < 2; }

    private static double rod(CreatureShape shape, String part) {
        for (CreatureShape.Box b : shape.part(part).boxes) if (b.w() <= 2 && b.d() <= 2 && b.h() > 2) return b.h();
        throw new IllegalStateException(part + " has no rod");
    }

    private static double depth(CreatureShape shape, String part) {
        double d = 0;
        for (CreatureShape.Box b : shape.part(part).boxes) d = Math.max(d, b.d());
        return d;
    }
}
