package com.explorationremix.creature.body;

import java.util.ArrayList;
import java.util.List;

/**
 * The ember urchin measured (0.23.7, Taran: "more of a ball than a disk, be black with orange, ember-like details, and
 * straddle the cave walls"): its ball, its thirty long walking spines (each a short shaft and a long needle, one rod
 * that bows like a spring where it presses on the rock: the cards' "Springy needles that bow") and its coat of short
 * spines, read from its shape (geometry/ember_urchin.json, written by tools/ember_urchin.py), for the body
 * ({@link EmberBody}) and the pose ({@link EmberPose}).
 *
 * <p>It never turns (the cards' "Crawls on its spines": it glides level and has no front), so every place on it is given
 * in the world's own axes, in blocks from the ball's middle: the model's pixels (x left, y down, z back) turned half
 * round its z axis, as vanilla's renderer turns a model ((x, y, z) to (-x, -y, z)), so nothing is mirrored.
 */
public final class EmberSpec {
    /** The game's urchin: its ball a block and a quarter across, its spines spanning about seven blocks. */
    public static final double GAME_SCALE = 1.0;

    public final double scale;
    /** The ball's radius. */
    public final double radius;

    // ---- the walking spines
    public final int legs;
    /** Each spine's way out at rest (unit), its socket (the root of its rod, a little under the ball's skin). */
    public final double[][] way, socket;
    /** Each spine's length along it (its shaft and needle), and its shaft's alone (the stout part from its root). */
    public final double[] length, shaft;
    /** For each spine, the others within {@link #NEIGHBOUR} of it (they never step together). */
    public final int[][] neighbours;
    /** Two spines nearer than this (radians between their ways) are neighbours. */
    static final double NEIGHBOUR = Math.toRadians(44);

    // ---- the coat
    public final int coat;
    public final double[][] coatSocket, coatWay;
    public final double[] coatLength;

    // ---- stances
    /** Its middle stands at most this far over the floor under it (walking a cave's floor on its lower spines). */
    public final double stand;
    /** Under a low roof its middle keeps at least this far under the rock over it, and over the floor. */
    public final double clearance;

    public EmberSpec(CreatureShape shape, double scale) {
        this.scale = scale;
        double k = scale / 16.0;
        CreatureShape.Box ball = shape.part("ball").boxes.get(0);
        radius = ball.w() * 0.5 * k;
        List<CreatureShape.Part> spines = new ArrayList<>();
        for (int i = 0; shape.has("spine" + i); i++) spines.add(shape.part("spine" + i));
        legs = spines.size();
        way = new double[legs][3];
        socket = new double[legs][3];
        length = new double[legs];
        shaft = new double[legs];
        for (int i = 0; i < legs; i++) {
            CreatureShape.Part p = spines.get(i);
            frame(p, socket[i], k);
            unit(socket[i], way[i]);
            double total = 0, first = -1;
            for (CreatureShape.Box b : p.boxes) {
                if (b.inflate() != 0) continue;
                total = Math.max(total, b.y() + b.h());
                if (first < 0) first = b.y() + b.h();
            }
            length[i] = total * k;
            shaft[i] = Math.max(0, first) * k;
        }
        neighbours = new int[legs][];
        for (int i = 0; i < legs; i++) {
            List<Integer> n = new ArrayList<>();
            for (int j = 0; j < legs; j++) if (j != i && Math.acos(clamp(dot(way[i], way[j]))) < NEIGHBOUR) n.add(j);
            neighbours[i] = n.stream().mapToInt(Integer::intValue).toArray();
        }
        List<CreatureShape.Part> coats = new ArrayList<>();
        for (int j = 0; shape.has("coat" + j); j++) coats.add(shape.part("coat" + j));
        coat = coats.size();
        coatSocket = new double[coat][3];
        coatWay = new double[coat][3];
        coatLength = new double[coat];
        for (int j = 0; j < coat; j++) {
            CreatureShape.Part p = coats.get(j);
            frame(p, coatSocket[j], k);
            unit(coatSocket[j], coatWay[j]);
            double l = 0;
            for (CreatureShape.Box b : p.boxes) if (b.inflate() == 0) l = Math.max(l, b.y() + b.h());
            coatLength[j] = l * k;
        }
        stand = 2.6 * scale;
        clearance = radius + 0.3 * scale;
    }

    /** A part's pivot (model pixels) in the world's axes from the ball's middle, in blocks: (x, y, z) to (-x, -y, z). */
    private static void frame(CreatureShape.Part p, double[] out, double k) {
        out[0] = -p.px * k;
        out[1] = -p.py * k;
        out[2] = p.pz * k;
    }

    private static void unit(double[] v, double[] out) {
        double l = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        out[0] = v[0] / l; out[1] = v[1] / l; out[2] = v[2] / l;
    }

    static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }

    private static double clamp(double c) { return Math.max(-1.0, Math.min(1.0, c)); }

    /** How far a walking spine's point reaches from the ball's middle, straight out. */
    public double reach(int i) {
        return Math.sqrt(dot(socket[i], socket[i])) + length[i];
    }

    /** The farthest any spine reaches from the ball's middle. */
    public double span() {
        double best = 0;
        for (int i = 0; i < legs; i++) best = Math.max(best, reach(i));
        return best;
    }
}
