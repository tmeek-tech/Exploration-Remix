package com.explorationremix.creature.body;

import org.joml.Vector3d;

/**
 * The Mossback's body measured from its shape (geometry/mossback.json, written by tools/creatures.py): the joints of its
 * spine from the hinge of its head to the tip of its tail, how high each rides over the rock it walks on, how thick its
 * tail is along its length, and its four legs. The body ({@link MossbackBody}) moves these joints; the mesh
 * ({@link MossbackRig}) hangs the drawn shape on them.
 *
 * <p>Lengths are in blocks unless they say otherwise; {@link #rest} is in the model's own units (1/16 block, y down).
 */
public final class MossbackSpec {
    /** The joints: the head's hinge, the neck's root, the shoulders, the middle, the hips, the tail's root, then the tail. */
    public static final int HEAD = 0, NECK = 1, SHOULDERS = 2, MIDDLE = 3, HIPS = 4, TAIL_BASE = 5;
    public final int head = HEAD, neck = NECK, shoulders = SHOULDERS, middle = MIDDLE, hips = HIPS, tailBase = TAIL_BASE;
    /** The tail's parts, root first; each one's pivot is a joint (tail1 at TAIL_BASE), and the last ends at the tip. */
    public static final String[] TAIL = {"tail1", "tail2", "tail3", "tail4", "tail5"};
    /** The legs, in the order the body keeps its feet: front left, front right, hind left, hind right. */
    public static final String[] LEGS = {"front_left", "front_right", "hind_left", "hind_right"};

    /** Each joint where it lies in the model at rest (model units). */
    public final Vector3d[] rest;
    /** How far along the spine each joint lies from the head's hinge. */
    public final double[] arc;
    /** How high each joint rides over flat rock. */
    public final double[] ride;
    /** How far the body reaches below each joint (its belly; the tail lies this far out from the rock it rests on). */
    public final double[] radius;
    public final Leg[] legs = new Leg[4];

    /** A stride's length walking and trotting, and the furthest a foot lands ahead of where it would rest. */
    public final double walkStride = 1.4, trotStride = 2.0, maxStep = 0.55;

    /** One leg, measured from its shoulder (or hip). */
    public static final class Leg {
        public final String name;
        /** The spine joint it hangs from (SHOULDERS or HIPS). */
        public final int joint;
        /** +1 for a left leg (the model's +x), -1 for a right one. */
        public final int side;
        public final boolean front;
        /** Its shoulder: out to the side of the spine, and down under it. */
        public final double lateral, drop;
        /** Where its foot rests when it stands: out to the side of the spine, and ahead of the shoulder (behind if less than 0). */
        public final double stance, fore;
        /** The upper leg (shoulder to elbow), the lower leg (elbow to ankle), and the sole (ankle to the rock). */
        public final double upper, lower, sole;

        Leg(String name, int joint, int side, boolean front, double lateral, double drop, double stance, double fore,
            double upper, double lower, double sole) {
            this.name = name; this.joint = joint; this.side = side; this.front = front;
            this.lateral = lateral; this.drop = drop; this.stance = stance; this.fore = fore;
            this.upper = upper; this.lower = lower; this.sole = sole;
        }

        public double reach() { return upper + lower; }
    }

    private MossbackSpec(Vector3d[] rest, double[] arc, double[] ride, double[] radius) {
        this.rest = rest;
        this.arc = arc;
        this.ride = ride;
        this.radius = radius;
    }

    /** The whole spine's length, head's hinge to tail tip. */
    public double length() { return arc[arc.length - 1]; }

    /** The length along the spine between two joints. */
    public double length(int i, int j) { return Math.abs(arc[j] - arc[i]); }

    /** Where along the spine (from the head's hinge) a point of the body's trunk lies, from its z in the model. */
    public double trunkArc(double modelZ) {
        int last = TAIL_BASE;
        if (modelZ <= rest[0].z) return arc[0] + (modelZ - rest[0].z) / 16.0;
        for (int i = 0; i < last; i++) {
            double z0 = rest[i].z, z1 = rest[i + 1].z;
            if (modelZ <= z1) return arc[i] + (arc[i + 1] - arc[i]) * (modelZ - z0) / (z1 - z0);
        }
        return arc[last] + (modelZ - rest[last].z) / 16.0;
    }

    public static MossbackSpec of(CreatureShape shape) {
        int n = TAIL_BASE + TAIL.length + 1;
        Vector3d[] rest = new Vector3d[n];
        CreatureShape.Part chest = shape.part("chest"), neck = shape.part("neck"), head = shape.part("head");
        CreatureShape.Part frontLeg = shape.part("front_left_upper"), hindLeg = shape.part("hind_left_upper");
        rest[HEAD] = new Vector3d(head.restPivot);
        rest[NECK] = new Vector3d(neck.restPivot);
        rest[MIDDLE] = new Vector3d(chest.restPivot);
        for (int i = 0; i < TAIL.length; i++) rest[TAIL_BASE + i] = new Vector3d(shape.part(TAIL[i]).restPivot);
        // the tip: the last part's end, its centre line carried on the way the tail's pivots step down
        CreatureShape.Part last = shape.part(TAIL[TAIL.length - 1]);
        double depth = 0;
        for (CreatureShape.Box b : last.boxes) depth = Math.max(depth, b.z() + b.d());
        Vector3d tip = new Vector3d(0, 0.5, depth);
        last.restTurn.transform(tip);
        rest[n - 1] = tip.add(last.restPivot);
        // shoulders and hips: on the line from the neck's root to the middle and from the middle to the tail's root, where the legs hang
        rest[SHOULDERS] = between(rest[NECK], rest[MIDDLE], frontLeg.restPivot.z);
        rest[HIPS] = between(rest[MIDDLE], rest[TAIL_BASE], hindLeg.restPivot.z);

        double[] arc = new double[n], ride = new double[n], radius = new double[n];
        for (int i = 1; i < n; i++) arc[i] = arc[i - 1] + rest[i].distance(rest[i - 1]) / 16.0;
        for (int i = 0; i < n; i++) ride[i] = (24.0 - rest[i].y) / 16.0;
        // the trunk's belly under each joint (chest, hips, neck boxes), the tail's from each tail part
        radius[HEAD] = belly(head, 0.0);
        radius[NECK] = belly(neck, 0.0) + (neck.restPivot.y - rest[NECK].y) / 16.0;
        radius[SHOULDERS] = belly(chest, 0.0) + (chest.restPivot.y - rest[SHOULDERS].y) / 16.0;
        radius[MIDDLE] = belly(chest, 0.0);
        radius[HIPS] = belly(shape.part("hips"), 0.0) + (chest.restPivot.y - rest[HIPS].y) / 16.0;
        for (int i = 0; i < TAIL.length; i++) radius[TAIL_BASE + i] = belly(shape.part(TAIL[i]), 0.0);
        radius[n - 1] = Math.max(0.06, belly(last, 0.5));

        MossbackSpec spec = new MossbackSpec(rest, arc, ride, radius);
        for (int k = 0; k < 4; k++) {
            String name = LEGS[k];
            boolean front = k < 2;
            int side = (k % 2 == 0) ? 1 : -1;
            int joint = front ? SHOULDERS : HIPS;
            CreatureShape.Part up = shape.part(name + "_upper"), lo = shape.part(name + "_lower"), foot = shape.part(name + "_foot");
            double lateral = Math.abs(up.restPivot.x) / 16.0;
            double drop = (up.restPivot.y - rest[joint].y) / 16.0;
            double upper = len(lo) / 16.0, lower = len(foot) / 16.0;
            double sole = 0;
            for (CreatureShape.Box b : foot.boxes) sole = Math.max(sole, b.y() + b.h());
            sole /= 16.0;
            // where each foot rests: a little narrower than the model's own stance (so it reaches further forward and
            // back), the front feet a touch ahead of the shoulders, the hind feet under the hips
            double stance = front ? 0.78 : 0.76;
            double fore = front ? 0.06 : -0.02;
            spec.legs[k] = new Leg(name, joint, side, front, lateral, drop, stance, fore, upper, lower, sole);
        }
        return spec;
    }

    /** How far a part's boxes reach below a line {@code above} model units under its pivot, in blocks. */
    private static double belly(CreatureShape.Part p, double above) {
        double low = 0;
        for (CreatureShape.Box b : p.boxes) low = Math.max(low, b.y() + b.h() + b.inflate());
        return Math.max(0.0, low - above) / 16.0;
    }

    private static double len(CreatureShape.Part p) {
        return Math.sqrt(p.px * p.px + p.py * p.py + p.pz * p.pz);
    }

    private static Vector3d between(Vector3d a, Vector3d b, double z) {
        double t = (z - a.z) / (b.z - a.z);
        return new Vector3d(a).lerp(b, t);
    }
}
