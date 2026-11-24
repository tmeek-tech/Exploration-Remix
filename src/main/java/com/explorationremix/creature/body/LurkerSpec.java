package com.explorationremix.creature.body;

import java.util.ArrayList;
import java.util.List;

/**
 * The vent urchin measured (0.21.3, Taran: "make the vent spiders more sea urchin-like than spider-like and have them
 * straddle the vents that they live in"): its test (the round shell), its sixteen walking spines (each a short base
 * from its socket to a knee close to the test, and a long needle from there to its point: Taran's card, "Needles with
 * one knee") and its coat of short spines, read from its shape (geometry/chimney_lurker.json, written by
 * tools/chimney_lurker.py), for the body ({@link LurkerBody}) and the pose ({@link LurkerPose}).
 *
 * <p>Places on the body are given in the test's own frame: blocks forward, up and to the left of its middle (the
 * body's centre). It has no front: "forward" only names one way round it. Lengths are in blocks. The walking spines
 * are numbered round it from forward toward its left.
 */
public final class LurkerSpec {
    /** Its walking spines. */
    public static final int LEGS = 16;
    /** Its teeth, in a ring under its middle. */
    public static final int TEETH = 5;
    /** The game's urchin: its test about a block and a half across, its spines spanning about six blocks. */
    public static final double GAME_SCALE = 1.0;

    public final double scale;

    // ---- the test
    /** Its half width and half height (it is a little flattened). */
    public final double halfWidth, halfHeight;

    // ---- the walking spines
    /** Each spine's base (from its socket to its knee) and its needle (from its knee to its point). */
    public final double[] upper = new double[LEGS], length = new double[LEGS];
    /** How far from its socket each spine's point rests, standing at ease. */
    public final double[] rest = new double[LEGS];
    /** How far its point may come in toward its socket, and go out from it (shares of its rest). */
    public final double near, far;
    /** At ease, each base rises from its socket this far over level (radians), out along its way. */
    public static final double KNEE_UP = Math.toRadians(40);
    /** Each spine's socket on the test: forward, up, left. */
    public final double[][] socket = new double[LEGS][3];
    /** The way round each spine points (radians from forward toward its left). */
    public final double[] restAngle = new double[LEGS];
    /** How far a planted spine's point may stray round from its own way before it steps (radians): they never cross. */
    public final double sector;

    // ---- the coat
    /** The short spines all over the test: how many, each one's socket (forward, up, left), its way out at rest (unit) and its length. */
    public final int coat;
    public final double[][] coatSocket, coatDir;
    public final double[] coatLength;

    // ---- the mouth
    /** Each tooth's root under the test (forward, up, left), and the teeth's length. */
    public final double[][] toothRoot = new double[TEETH][3];
    public final double toothLength;

    // ---- stances
    /** Standing, its middle rides this far out from the rock its spines stand on. */
    public final double ride;
    /** Crouched to leap, this far. */
    public final double crouchRide;
    /** Sat in its vent's mouth, its middle this far over the rim's top (its lower half down in the flue). */
    public final double seat;

    public LurkerSpec(CreatureShape shape, double scale) {
        this.scale = scale;
        double k = scale / 16.0;
        // the test: its half sizes from its boxes
        double w = 0, h = 0;
        for (CreatureShape.Box b : shape.part("test").boxes) {
            w = Math.max(w, Math.max(-b.x(), b.x() + b.w()));
            w = Math.max(w, Math.max(-b.z(), b.z() + b.d()));
            h = Math.max(h, Math.max(-b.y(), b.y() + b.h()));
        }
        halfWidth = w * k;
        halfHeight = h * k;
        double nr = 0, fr = 0;
        for (int i = 0; i < LEGS; i++) {
            CreatureShape.Part b = shape.part("base" + i), p = shape.part("spine" + i);
            upper[i] = rodLength(b) * k;
            length[i] = rodLength(p) * k;
            frame(b, socket[i], k);
            restAngle[i] = Math.atan2(socket[i][2], socket[i][0]);
            rest[i] = 0.84 * (upper[i] + length[i]);
            // folded no nearer than a little off its needle's own length less its base's; reaching to most of its length
            nr = Math.max(nr, (length[i] - upper[i] + 0.15 * scale) / rest[i]);
            fr = Math.max(fr, 0.97 * (upper[i] + length[i]) / rest[i]);
        }
        near = nr;
        far = fr;
        sector = Math.PI / LEGS * 0.92;
        List<CreatureShape.Part> coats = new ArrayList<>();
        for (int j = 0; shape.has("coat" + j); j++) coats.add(shape.part("coat" + j));
        coat = coats.size();
        coatSocket = new double[coat][3];
        coatDir = new double[coat][3];
        coatLength = new double[coat];
        for (int j = 0; j < coat; j++) {
            CreatureShape.Part p = coats.get(j);
            frame(p, coatSocket[j], k);
            coatLength[j] = rodLength(p) * k;
            // its way out at rest: the test's own way out where it sits (an ellipsoid's), a little toward the crown
            double[] s = coatSocket[j];
            double nx = s[0] / (halfWidth * halfWidth), ny = s[1] / (halfHeight * halfHeight), nz = s[2] / (halfWidth * halfWidth);
            double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
            coatDir[j][0] = nx / nl; coatDir[j][1] = ny / nl + 0.12; coatDir[j][2] = nz / nl;
            double dl = Math.sqrt(coatDir[j][0] * coatDir[j][0] + coatDir[j][1] * coatDir[j][1] + coatDir[j][2] * coatDir[j][2]);
            for (int c = 0; c < 3; c++) coatDir[j][c] /= dl;
        }
        double tl = 0;
        for (int t = 0; t < TEETH; t++) {
            CreatureShape.Part p = shape.part("tooth" + t);
            frame(p, toothRoot[t], k);
            tl = 0;
            for (CreatureShape.Box b : p.boxes) tl = Math.max(tl, b.y() + b.h());
        }
        toothLength = tl * k;
        ride = 1.25 * scale;
        crouchRide = 0.78 * scale;
        seat = 0.05 * scale;
    }

    /** A rod part's length along its own y: from its root to its far end. */
    private static double rodLength(CreatureShape.Part p) {
        double best = 0;
        for (CreatureShape.Box b : p.boxes) if (b.inflate() == 0) best = Math.max(best, b.y() + b.h());
        if (best <= 0) throw new IllegalStateException(p.name + " has no rod");
        return best;
    }

    /** A part's pivot (model pixels: x left, y down, z back) in the test's frame (forward, up, left), in blocks. */
    private static void frame(CreatureShape.Part p, double[] out, double k) {
        out[0] = -p.pz * k;
        out[1] = -p.py * k;
        out[2] = p.px * k;
    }

    /** How far a walking spine reaches from its socket, straightened. */
    public double reach(int i) { return upper[i] + length[i]; }

    /** The farthest a walking spine reaches from the test's middle (its socket out plus its reach). */
    public double span() {
        double best = 0;
        for (int i = 0; i < LEGS; i++) best = Math.max(best, Math.hypot(socket[i][0], socket[i][2]) + reach(i));
        return best;
    }
}
