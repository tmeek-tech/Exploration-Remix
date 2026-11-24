package com.explorationremix.creature.body;

/**
 * The chimney lurker measured (0.21.0): its parts' lengths from its shape (geometry/chimney_lurker.json, written by
 * tools/chimney_lurker.py) and where its twelve legs hang from its prosoma and where their feet rest, for the body
 * ({@link LurkerBody}) and the pose ({@link LurkerPose}).
 *
 * <p>Places on the body are given in the prosoma's own frame: blocks forward, up and to the left of its middle (the
 * body's centre). Lengths are in blocks. Legs are numbered pair by pair from the front, left before right: leg
 * {@code 2 * pair + side}, side 0 left and 1 right.
 */
public final class LurkerSpec {
    public static final int PAIRS = 6, LEGS = 12;
    /** The game's lurker (Taran's size card: legs spanning about six blocks). */
    public static final double GAME_SCALE = 1.0;

    public final double scale;

    // ---- the legs (blocks), per leg
    public final double[] coxa = new double[LEGS], femur = new double[LEGS], tibia = new double[LEGS], meta = new double[LEGS];
    /** Where each leg's coxa hangs on the prosoma's rim: forward, up, left (+ for the left legs). */
    public final double[][] hip = new double[LEGS][3];
    /** Each foot's rest: its way round from straight ahead (radians, toward its own side) and its distance out from the centre. */
    public final double[] restAngle = new double[LEGS], restRadius = new double[LEGS];
    /** How far a foot may stray round from its rest way before it steps (radians): the legs never cross. */
    public final double sector;

    // ---- the body (blocks)
    /** The prosoma's middle rides this far out from the rock its feet stand on. */
    public final double ride;
    /** The prosoma's half length (forward of its middle to the front of its head) and half width. */
    public final double front, halfWidth;
    /** The waist: this far behind the prosoma's middle and this far up; the abdomen's length behind it. */
    public final double waistBack, waistUp, abdomen;
    /** The fangs' bases hang this far forward of the middle and down, this far to each side; their lengths. */
    public final double cheliceraForward, cheliceraDown, cheliceraSide, chelicera, fang;
    /** The feelers: their roots (forward, down, side) and their two segments' lengths. */
    public final double palpForward, palpDown, palpSide, palpFemur, palpTibia;

    // ---- gait
    /** A stride's length (blocks the body moves in one cycle of the feet) walking and running; how high a foot lifts. */
    public final double walkStride, runStride, walkLift, runLift;

    public LurkerSpec(CreatureShape shape, double scale) {
        this.scale = scale;
        double k = scale / 16.0;
        for (int i = 0; i < LEGS; i++) {
            String n = legName(i);
            coxa[i] = rod(shape, n + "_coxa") * k;
            femur[i] = rod(shape, n + "_femur") * k;
            tibia[i] = rod(shape, n + "_tibia") * k;
            meta[i] = rod(shape, n + "_meta") * k;
        }
        chelicera = rod(shape, "chelicera_l") * k;
        fang = rod(shape, "fang_l") * k;
        palpFemur = rod(shape, "palp_l_femur") * k;
        palpTibia = rod(shape, "palp_l_tibia") * k;
        // the coxae hang round the prosoma's rim, under its edge: six a side from just behind the fangs to the waist
        double[] along = {4.6, 2.9, 1.0, -1.0, -2.9, -4.6};
        double[] out = {4.6, 5.4, 5.8, 5.8, 5.4, 4.6};
        // the feet rest in a wide ring: the front pair far ahead, the back pair far behind, the middle ones out to the side
        double[] ang = {21, 44, 71, 103, 132, 157};
        double[] rad = {50, 46, 43, 43, 46, 48};
        for (int i = 0; i < LEGS; i++) {
            int pair = i / 2;
            double side = left(i) ? 1 : -1;
            hip[i][0] = along[pair] * k;
            hip[i][1] = -0.8 * k;
            hip[i][2] = side * out[pair] * k;
            restAngle[i] = Math.toRadians(ang[pair]);
            restRadius[i] = rad[pair] * k;
        }
        sector = Math.toRadians(17);
        ride = 12.0 * k;
        front = 9.0 * k;
        halfWidth = 7.0 * k;
        waistBack = 8.0 * k;
        waistUp = 0.5 * k;
        abdomen = depth(shape, "abdomen") * k;
        cheliceraForward = 7.6 * k;
        cheliceraDown = 1.6 * k;
        cheliceraSide = 1.8 * k;
        palpForward = 7.0 * k;
        palpDown = 1.2 * k;
        palpSide = 3.6 * k;
        walkStride = 1.5 * scale;
        runStride = 2.5 * scale;
        walkLift = 0.38 * scale;
        runLift = 0.46 * scale;
    }

    public static String legName(int i) {
        return "leg" + (i / 2) + (left(i) ? "l" : "r");
    }

    public static boolean left(int leg) { return leg % 2 == 0; }
    public static int pair(int leg) { return leg / 2; }

    /** A leg's reach from its hip (the coxa's tip) to its ankle. */
    public double reach(int leg) { return femur[leg] + tibia[leg]; }

    /** The longest box of a limb part: its bone's length (pixels). */
    private static double rod(CreatureShape shape, String part) {
        double best = 0;
        for (CreatureShape.Box b : shape.part(part).boxes) if (b.inflate() == 0 && b.h() > best && b.w() <= 3 && b.d() <= 3) best = b.h();
        if (best <= 0) throw new IllegalStateException(part + " has no rod");
        return best;
    }

    /** A body part's length along z: from its frontmost box to its hindmost. */
    private static double depth(CreatureShape shape, String part) {
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (CreatureShape.Box b : shape.part(part).boxes) { lo = Math.min(lo, b.z()); hi = Math.max(hi, b.z() + b.d()); }
        return hi - lo;
    }
}
