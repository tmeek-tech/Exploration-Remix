package com.explorationremix.creature.choir;

/**
 * The line a tendril of the Hollow Choir lies along (0.22.3): standing from its root to the cavern's ceiling, reared
 * over a mark on the floor, or anywhere between the two. Plain sums, so the renderer draws from them and a test can
 * hold them to what the fight needs: the standing cord passes through its own box, a reared one still leaves its
 * root through it, a tendril that strikes ends exactly on its mark, and none of it passes through the crown.
 *
 * <p>Two lines and a bend between. <em>Standing</em>, the cord runs straight up from its root and bows in towards
 * the crown over the crown's head, as a thing the crown hangs from would. <em>Reared</em>, it has let go of the
 * ceiling and is a crook: it climbs straight out of the same root, turns over, runs to above its mark and comes
 * straight down on its head, as a snake's does. Both are cut into the same number of pieces by length along the cord.
 *
 * <p>A tendril part-way between is not each point part-way between: a cord going up and one coming down cancel
 * half-way, and the cord would fold to a point there. It is each <em>piece</em> part-way between, in the way it
 * points and in how long it is, laid end to end from the root. So the cord curls over as one thing, as a fern's
 * head unrolls, no piece of it ever shrinks to nothing, and its root never moves.
 */
public final class ChoirPose {
    private ChoirPose() {}

    /**
     * How far a standing tendril bows in towards the crown, how far over the crown's own height the bow is deepest,
     * and how far above and below that it is taken up over. The bow is all above a player's reach: 0.22.2 had it
     * deepest at the crown's own height, which put the cord most of a block inside its root at head height, half out
     * of the box a sword finds it by.
     */
    public static final double LEAN = 1.6, LEAN_OVER = 3.5, LEAN_SPAN = 5.0;
    /** A standing tendril runs this far up past the ceiling the arena knows of, so its end is always in the rock. */
    public static final double BURY = 3.0;
    /** A tendril is cut into pieces about this long, and never more than this many of them. */
    public static final double SEG_STEP = 1.5;
    public static final int SEGS_MAX = 48;

    /**
     * The crook, out on the open floor: how far over its root a reared tendril climbs before it turns, how tight it
     * may turn at most, how wide a turn it makes at the least, and how high over its mark its head hangs while it
     * waits. A mark nearer the root than a least turn is wide is reached by leaning back first, as a snake does.
     */
    public static final double RISE = 7.0, HOOK = 5.0, HOOK_MIN = 1.5, HOVER = 5.0;
    /**
     * The same under the crown, which hangs over the dais: a head that waited five blocks up there would wait inside
     * it, so the tendril comes in low and flat under the crown's rim instead. And how far from the dais's middle the
     * one gives way to the other.
     */
    public static final double RISE_UNDER = 2.5, HOOK_UNDER = 0.8, HOVER_UNDER = 1.8, UNDER_R0 = 3.0, UNDER_R1 = 5.5;
    /** A reared tendril climbs straight this far out of its root before it leans, and comes straight down at least this far on its head. */
    public static final double CLEAR = 3.0, FALL_MIN = 0.5;
    /** How far ahead of its root a tendril's head is in bending over, as a share of the whole rearing ({@link #bent}). */
    public static final double HEAD_FIRST = 0.6;
    /** How finely each stretch of the crook is measured before the whole is cut into pieces of even length. */
    private static final int CLIMB = 12, CORNER = 10, RUN = 8, FALL = 8, FINE = 1 + CLIMB + CORNER + RUN + CORNER + FALL;

    /** How many pieces a tendril standing {@code height} tall is drawn from. */
    public static int segments(double height) {
        return Math.max(4, Math.min(SEGS_MAX, (int) Math.round(height / SEG_STEP)));
    }

    /**
     * How open the air is over a mark at (tx, tz) for a tendril rooted at (rx, rz), both measured from the dais's
     * middle: 1 out on the floor and 0 under the crown, easing from the one to the other between. A tendril that has
     * to pass the crown to reach its mark goes clean over it or clean under, and nothing between.
     */
    public static double open(double rx, double rz, double tx, double tz) {
        double m = Math.hypot(tx, tz);
        double dx = tx - rx, dz = tz - rz, l2 = dx * dx + dz * dz;
        // the nearest the way from root to mark comes to the middle, and whether that is before it gets there
        double t = l2 < 1e-9 ? 1 : Math.max(0, Math.min(1, -(rx * dx + rz * dz) / l2));
        if (t < 1 - 1e-6 && Math.hypot(rx + dx * t, rz + dz * t) < UNDER_R0) return m >= UNDER_R0 ? 1 : 0;
        double u = Math.max(0, Math.min(1, (m - UNDER_R0) / (UNDER_R1 - UNDER_R0)));
        return u * u * (3 - 2 * u);
    }

    /** How high over its mark a reared head hangs, by how open the air over the mark is ({@link #open}). */
    public static double hover(double open) { return HOVER_UNDER + (HOVER - HOVER_UNDER) * open; }

    /** How far in a standing tendril bows at a height, as a share of {@link #LEAN}: all of it {@link #LEAN_OVER} over the crown. */
    public static double bow(double y, double crownY) {
        double near = Math.max(0, Math.min(1, 1 - Math.abs(y - (crownY + LEAN_OVER)) / LEAN_SPAN));
        return near * near * (3 - 2 * near);
    }

    /**
     * The points of a tendril, {@code n + 1} of them into {@code out}, from its root up. The root (rx, ry, rz) is
     * where the cord leaves the floor; {@code topY} is the height the standing cord runs up to and {@code crownY} the
     * crown's; (inX, inZ) is the way from the root to the dais's middle, {@link #LEAN} long; {@code reach} is 0
     * standing and 1 reared right over; (tx, ty, tz) is where the cord's far end is when it is reared, its head,
     * hanging over the mark or down on it; and {@code open} is how open the air over the mark is ({@link #open}).
     */
    public static void curve(double[][] out, int n, double rx, double ry, double rz, double topY, double crownY,
                             double inX, double inZ, double reach, double tx, double ty, double tz, double open) {
        if (reach <= 0) {
            for (int i = 0; i <= n; i++) {
                double y = ry + (topY - ry) * i / n;
                double bow = bow(y, crownY);
                out[i][0] = rx + inX * bow;
                out[i][1] = y;
                out[i][2] = rz + inZ * bow;
            }
            return;
        }
        // the crook, in the upright plane through the root and the mark: s along the floor towards the mark, h up
        double dx = tx - rx, dz = tz - rz;
        double far = Math.hypot(dx, dz);
        double ux, uz;
        if (far > 0.05) { ux = dx / far; uz = dz / far; }
        else { double l = Math.max(1e-6, Math.hypot(inX, inZ)); ux = inX / l; uz = inZ / l; }
        double half = Math.max(HOOK_MIN, far / 2);                 // half the width of its turn over the top
        double back = far - 2 * half;                              // where the turn starts: at the root or behind it
        double turn = Math.min(half, HOOK_UNDER + (HOOK - HOOK_UNDER) * open);
        double rise = RISE_UNDER + (RISE - RISE_UNDER) * open - 1.5 * back;
        double top = Math.max(ty + FALL_MIN, ry + rise);           // where it stops climbing and where it starts down
        double run = 2 * half - 2 * turn, dome = 0.08 * run * open;
        double clear = Math.min(CLEAR, (top - ry) * 0.5);
        double[] s = new double[FINE + 1], h = new double[FINE + 1], len = new double[FINE + 1];
        int k = 0;
        // straight out of the root, as far as a sword swings
        h[k++] = ry;
        // the rest of the climb: leaning back if the mark is too near to turn over on
        for (int j = 0; j <= CLIMB; j++, k++) {
            double v = j / (double) CLIMB;
            h[k] = ry + clear + (top - ry - clear) * v;
            s[k] = back * v * v * (3 - 2 * v);
        }
        // the turn over: from straight up to level
        for (int j = 1; j <= CORNER; j++, k++) {
            double p = Math.PI / 2 * j / CORNER;
            s[k] = back + turn * (1 - Math.cos(p));
            h[k] = top + turn * Math.sin(p);
        }
        // the run to above the mark, a little arched where there is room for it
        for (int j = 1; j <= RUN; j++, k++) {
            double t = j / (double) RUN, bump = Math.sin(Math.PI * t);
            s[k] = back + turn + run * t;
            h[k] = top + turn + dome * bump * bump;
        }
        // the turn down: from level to straight down
        for (int j = 1; j <= CORNER; j++, k++) {
            double p = Math.PI / 2 * j / CORNER;
            s[k] = far - turn + turn * Math.sin(p);
            h[k] = top + turn * Math.cos(p);
        }
        // the fall, straight down on to its head
        for (int j = 1; j <= FALL; j++, k++) {
            s[k] = far;
            h[k] = top + (ty - top) * j / FALL;
        }
        for (int i = 1; i <= FINE; i++) len[i] = len[i - 1] + Math.hypot(s[i] - s[i - 1], h[i] - h[i - 1]);

        // cut into pieces of a length; and part-way reared, each piece part-way from standing to where the crook has
        // it, laid end to end from the root
        double stand = (topY - ry) / n;
        double wasAlong = 0, wasUp = ry, along = 0, up = ry, bentAlong = 0, bentUp = ry;
        double wasBow = bow(ry, crownY), bowed = wasBow;
        k = 1;
        for (int i = 0; i <= n; i++) {
            if (i > 0) {
                double want = len[FINE] * i / n;
                while (k < FINE && len[k] < want) k++;
                double span = len[k] - len[k - 1];
                double f = span < 1e-9 ? 1 : Math.max(0, Math.min(1, (want - len[k - 1]) / span));
                along = s[k - 1] + (s[k] - s[k - 1]) * f;
                up = h[k - 1] + (h[k] - h[k - 1]) * f;
            }
            if (reach >= 1) {
                out[i][0] = rx + ux * along;
                out[i][1] = up;
                out[i][2] = rz + uz * along;
                continue;
            }
            if (i > 0) {
                double ds = along - wasAlong, dh = up - wasUp, piece = Math.hypot(ds, dh);
                double points = piece < 1e-9 ? Math.PI / 2 : Math.atan2(dh, ds);
                double w = bent(reach, (i - 0.5) / n), draw = 1 - (1 - w) * (1 - w);
                // it draws in sooner than it bends
                double l = stand + (piece - stand) * draw, a = Math.PI / 2 + (points - Math.PI / 2) * w;
                bentAlong += l * Math.cos(a);
                bentUp += l * Math.sin(a);
                wasAlong = along;
                wasUp = up;
                // and what is left of the bow it stood with: each piece keeps a share of its own slant, so the bow
                // is let out along the cord and never squeezed into a wriggle as the cord draws in
                double bow = bow(ry + (topY - ry) * i / n, crownY);
                bowed += (bow - wasBow) * (1 - w) * l / stand;
                wasBow = bow;
            }
            out[i][0] = rx + ux * bentAlong + inX * bowed;
            out[i][1] = bentUp;
            out[i][2] = rz + uz * bentAlong + inZ * bowed;
        }
        // reared, the head is on its mark to the last decimal, whatever the sums above have rounded
        if (reach >= 1) { out[n][0] = tx; out[n][1] = ty; out[n][2] = tz; }
    }

    /**
     * How far a piece of the cord has bent over, 0 standing to 1 where the crook has it, by how far the tendril has
     * reared and where along the cord the piece is, 0 at the root and 1 at the head. The head goes first: it has
     * tipped over and hangs looking down while the cord under it is still coming over, so it reaches its mark from
     * above and from the root's side, never from beyond it.
     */
    public static double bent(double reach, double along) {
        double q = Math.max(0, Math.min(1, reach * (1 + HEAD_FIRST) - HEAD_FIRST * (1 - along)));
        return q * q * (3 - 2 * q);
    }

    /** How long a line of points is, first to last. */
    public static double length(double[][] at, int n) {
        double l = 0;
        for (int i = 1; i <= n; i++)
            l += Math.sqrt(sq(at[i][0] - at[i - 1][0]) + sq(at[i][1] - at[i - 1][1]) + sq(at[i][2] - at[i - 1][2]));
        return l;
    }

    private static double sq(double v) { return v * v; }
}
