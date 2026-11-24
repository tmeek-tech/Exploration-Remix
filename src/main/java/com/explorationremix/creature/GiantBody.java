package com.explorationremix.creature;

import com.explorationremix.creature.body.RockClearance;
import com.explorationremix.creature.body.Terrain;

/**
 * The giant Skein's body (0.18.8): the Skein's own trail and spine ({@link SkeinTrail}, {@link SkeinSpine}) worked in a
 * frame {@code scale} times smaller than the world, so a body four times as long bends, cuts corners, loops and settles
 * exactly as the Skein's does, only four times as wide in its turns. Everything in and out is in world blocks: the
 * head's place goes in divided by the scale, and every sample comes out multiplied back. Rock normals are directions
 * and pass through as they are; a scale the same on every axis keeps the rock's planes planes and its corners corners.
 *
 * <p>Plain numbers, no game classes, so it can be tested on its own.
 */
public final class GiantBody {
    private static final int STRIDE = 6;

    private final double scale;
    private final SkeinTrail trail;
    private final SkeinSpine spine;

    /**
     * A body {@code scale} times the Skein's, whose trail keeps at least {@code keep} blocks behind the head and whose
     * spine has {@code joints} joints (a Skein's own count lays it the Skein's length times the scale).
     */
    public GiantBody(double scale, double keep, int joints) {
        this.scale = scale;
        this.trail = new SkeinTrail(keep / scale);
        this.spine = new SkeinSpine(joints, DRIFT / scale);
    }

    /**
     * The furthest its body strays from the way its head went (blocks; 0.21.5): the Skein's own reach, four times over,
     * let its body cut a bend seven blocks wide, through the rock on the inside of a winding tunnel's bends.
     */
    public static final double DRIFT = 1.5;

    public double scale() { return scale; }
    /** Whether the trail has been laid (set down, read back, or walked). */
    public boolean laid() { return trail.laid(); }
    /** A number that changes whenever the trail is laid afresh. */
    public int lays() { return trail.lays(); }
    /** How far (blocks) the head went at the last record. */
    public double lastStep() { return trail.lastStep() * scale; }
    /** The length of the spine (blocks). */
    public double length() { return spine.length() * scale; }
    /** For the dev trace: how the spine moved this tick (in the scaled frame's blocks). */
    public String moves() { return spine.moves(); }

    /** Lays the trail straight back from the head at (x, y, z) along (bx, by, bz), on rock facing (ux, uy, uz). */
    public void lay(double x, double y, double z, double bx, double by, double bz, double ux, double uy, double uz) {
        trail.lay(x / scale, y / scale, z / scale, bx, by, bz, ux, uy, uz);
    }

    /** The head is now at (x, y, z), on rock facing (ux, uy, uz) (see {@link SkeinTrail#record}). */
    public void record(double x, double y, double z, double ux, double uy, double uz) {
        trail.record(x / scale, y / scale, z / scale, ux, uy, uz);
    }

    /** A tick: the body comes on after the head, which points along (fx, fy, fz) (see {@link SkeinSpine#follow}). */
    public void follow(double fx, double fy, double fz) {
        spine.follow(trail, fx, fy, fz);
    }

    /** Lays the body along the trail at once (as it is first set down or seen). */
    public void layBody() {
        spine.lay(trail);
    }

    /** Whether the body lies along the trail as that was last laid (else it is laid afresh at the next follow). */
    public boolean onTrail() {
        return spine.on(trail);
    }

    /**
     * The point {@code s} blocks along the body from the head, {@code pt} of the way from a tick ago to now: position
     * (0..2) and the way its back faces (3..5) at o[off..off+5] (see {@link SkeinSpine#sample}).
     */
    public void sample(double s, double pt, double[] o, int off) {
        spine.sample(s / scale, pt, o, off);
        o[off] *= scale;
        o[off + 1] *= scale;
        o[off + 2] *= scale;
    }

    /** The points at each of {@code arcs} (blocks along the body), six numbers each in o. */
    public void sampleAll(double[] arcs, double pt, double[] o) {
        for (int k = 0; k < arcs.length; k++) sample(arcs[k], pt, o, STRIDE * k);
    }

    /** The point {@code s} blocks back along the trail (the way the head went, not the body's curve), into o[0..5]. */
    public void trailSample(double s, double[] o) {
        trail.sample(s / scale, o);
        o[0] *= scale;
        o[1] *= scale;
        o[2] *= scale;
    }

    /**
     * The body as it lies now at {@code arcs} (blocks along it, the first 0), six floats each: its position less (ox,
     * oy, oz), in blocks, and the way its back faces. Read back ({@link #read}), a body lies again as this one does.
     */
    public float[] write(double[] arcs, double ox, double oy, double oz) {
        double[] a = new double[arcs.length];
        for (int k = 0; k < arcs.length; k++) a[k] = arcs[k] / scale;
        float[] out = spine.write(a, ox / scale, oy / scale, oz / scale);
        for (int o = 0; o < out.length; o += STRIDE) {
            out[o] *= (float) scale;
            out[o + 1] *= (float) scale;
            out[o + 2] *= (float) scale;
        }
        return out;
    }

    /**
     * Lays the trail through samples {@link #write} gave (relative to (ox, oy, oz), in blocks). False, and the trail left
     * as it was, when they are no trail.
     */
    public boolean read(float[] data, double ox, double oy, double oz) {
        float[] d = data.clone();
        for (int o = 0; o + 2 < d.length; o += STRIDE) {
            d[o] /= (float) scale;
            d[o + 1] /= (float) scale;
            d[o + 2] /= (float) scale;
        }
        return trail.read(d, ox / scale, oy / scale, oz / scale);
    }

    /**
     * The box round the stretch of body between samples a and b (offsets into {@code at}, as {@link #sampleAll} lays
     * them out), {@code wide} across the rock and {@code thick} off it: {minX, minY, minZ, maxX, maxY, maxZ}.
     */
    public static void stretchBox(double[] at, int a, int b, double wide, double thick, double[] out) {
        SkeinTrail.stretchBox(at, a, b, wide, thick, out);
    }

    // ------------------------------------------------------------------------------------------------ kept out of rock

    private final RockClearance clearance = new RockClearance();
    private final double[] cleared = new double[6];
    private double lastPushed, lastPushedMost;
    private int lastPushedJoints;

    /**
     * Keeps the body as it lies this tick clear of the rock (0.21.5): the line {@code lift} out along each joint's back
     * is moved out of any rock it would pass through, and the joint with it, its back tipped a little the way it was
     * pushed (see RockClearance#clear). Where its head went, the body lies on the rock; only where its curve cuts a
     * corner over rock the trail went round (a knob on a wall, the inside of a bend on rough rock) is it pushed, so it
     * rides over the knob rather than through it. Done after {@link #follow} on both sides, so what is drawn and what can
     * be hit never lie in rock. Returns the joints moved.
     */
    public int keepOut(Terrain t, double lift) {
        return keepOut(t, lift, 0.0, 0.0, 0.0);
    }

    /**
     * As {@link #keepOut(Terrain, double)}, for a body {@code half} blocks wide either side of its line at the head,
     * narrowing to {@code tail} at its tip, whose sides are kept {@code room} from the rock too (see
     * RockClearance#clearWide): it lies along a wall or in a hollow beside it without its flanks going into the wall.
     */
    public int keepOut(Terrain t, double lift, double half, double tail, double room) {
        if (!laid()) return 0;
        double[] d = spine.data();
        int moved = 0, n = spine.joints();
        double total = 0.0, most = 0.0;
        for (int j = 0; j < n; j++) {
            int o = STRIDE * j;
            double x = d[o] * scale, y = d[o + 1] * scale, z = d[o + 2] * scale;
            double m;
            if (half > 0.0) {
                // its side: square to its back and to the way its body runs here
                int a = STRIDE * Math.max(0, j - 1), b = STRIDE * Math.min(n - 1, j + 1);
                double tx = d[a] - d[b], ty = d[a + 1] - d[b + 1], tz = d[a + 2] - d[b + 2];
                double sx = ty * d[o + 5] - tz * d[o + 4], sy = tz * d[o + 3] - tx * d[o + 5], sz = tx * d[o + 4] - ty * d[o + 3];
                double w = half + (tail - half) * j / Math.max(1.0, n - 1.0);
                m = clearance.clearWide(t, x, y, z, d[o + 3], d[o + 4], d[o + 5], sx, sy, sz, lift, w, room, cleared);
            } else m = clearance.clear(t, x, y, z, d[o + 3], d[o + 4], d[o + 5], lift, cleared);
            if (j > 0 && clearance.inRock(t, cleared[0] + cleared[3] * lift, cleared[1] + cleared[4] * lift, cleared[2] + cleared[5] * lift)) {
                // too deep in rock to get out along its back: back along its body towards the joint ahead of it (clear
                // already), to the last point clear of the rock, and out from there
                int p = o - STRIDE;
                double px = d[p] * scale, py = d[p + 1] * scale, pz = d[p + 2] * scale;
                double ex = px, ey = py, ez = pz;
                for (int k = 1; k <= 10; k++) {
                    double f = k / 10.0;
                    double qx = px + (x - px) * f, qy = py + (y - py) * f, qz = pz + (z - pz) * f;
                    if (clearance.inRock(t, qx + d[p + 3] * lift, qy + d[p + 4] * lift, qz + d[p + 5] * lift)) break;
                    ex = qx; ey = qy; ez = qz;
                }
                clearance.clear(t, ex, ey, ez, d[p + 3], d[p + 4], d[p + 5], lift, cleared);
                double mx = cleared[0] - x, my = cleared[1] - y, mz = cleared[2] - z;
                m = Math.sqrt(mx * mx + my * my + mz * mz);
            }
            if (m <= 1.0E-4) continue;
            moved++;
            total += m;
            most = Math.max(most, m);
            d[o] = cleared[0] / scale; d[o + 1] = cleared[1] / scale; d[o + 2] = cleared[2] / scale;
            d[o + 3] = cleared[3]; d[o + 4] = cleared[4]; d[o + 5] = cleared[5];
        }
        lastPushed = total;
        lastPushedMost = most;
        lastPushedJoints = moved;
        return moved;
    }

    /** For the dev listing: how many joints the last {@link #keepOut} moved, and the most it moved one (blocks). */
    public int pushedJoints() { return lastPushedJoints; }
    public double pushedMost() { return lastPushedMost; }

    /** The number of joints, and joint j's place (blocks) and back into o[0..5]. */
    public int joints() { return spine.joints(); }

    public void joint(int j, double[] o) {
        double[] d = spine.data();
        int k = STRIDE * j;
        o[0] = d[k] * scale; o[1] = d[k + 1] * scale; o[2] = d[k + 2] * scale;
        o[3] = d[k + 3]; o[4] = d[k + 4]; o[5] = d[k + 5];
    }

    /** The place along the body (blocks back from the head) of joint j. */
    public double jointAt(int j) {
        return j * SkeinSpine.LINK * scale;
    }

    /**
     * Sets the body as it lies (now and a tick ago) to {@code at}: six numbers a joint, its place (blocks) and back, as
     * {@link #joint} gives them (from the giant's fall, so that when its trail is next laid afresh the body eases over
     * from where it fell rather than from where it lay before it let go).
     */
    public void pose(double[] at) {
        double[] d = spine.data(), p = spine.before();
        int n = Math.min(spine.joints(), at.length / STRIDE);
        for (int j = 0; j < n; j++) {
            int o = STRIDE * j;
            d[o] = at[o] / scale; d[o + 1] = at[o + 1] / scale; d[o + 2] = at[o + 2] / scale;
            d[o + 3] = at[o + 3]; d[o + 4] = at[o + 4]; d[o + 5] = at[o + 5];
            System.arraycopy(d, o, p, o, STRIDE);
        }
    }
}
