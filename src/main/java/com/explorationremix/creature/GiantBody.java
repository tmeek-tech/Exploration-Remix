package com.explorationremix.creature;

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
        this.spine = new SkeinSpine(joints);
    }

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
}
